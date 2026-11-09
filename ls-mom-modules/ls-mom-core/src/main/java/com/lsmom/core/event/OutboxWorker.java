package com.lsmom.core.event;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Outbox 投递 Worker：{@code FOR UPDATE SKIP LOCKED} 领取到期记录，多实例并行不重复领取；
 * 失败按指数退避重试，超过上限进入 DEAD（人工补偿列表）。
 */
@Slf4j
@Component
public class OutboxWorker {

    static final RowMapper<DomainEvent> EVENT_MAPPER = (rs, i) -> new DomainEvent(rs.getObject("event_id", UUID.class),
        rs.getString("type"), rs.getInt("version"), rs.getObject("occurred_at", LocalDateTime.class), (Long) rs.getObject("actor_party"),
        (Long) rs.getObject("on_behalf_of"), rs.getString("subject_concept"), rs.getString("subject_id"),
        (Long) rs.getObject("org_unit_id"), rs.getString("trace_id"), rs.getString("idempotency_key"), readPayload(rs.getString("payload")));

    private final JdbcTemplate jdbc;
    private final Map<String, OutboxDestination> destinations;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration baseBackoff;

    @org.springframework.beans.factory.annotation.Autowired
    public OutboxWorker(JdbcTemplate jdbc, List<OutboxDestination> destinations, PlatformTransactionManager txm, Clock clock) {
        this(jdbc, destinations, txm, clock, Duration.ofSeconds(10));
    }

    public OutboxWorker(JdbcTemplate jdbc, List<OutboxDestination> destinations, PlatformTransactionManager txm, Clock clock,
                        Duration baseBackoff) {
        this.jdbc = jdbc;
        this.destinations = destinations.stream().collect(Collectors.toMap(OutboxDestination::name, Function.identity()));
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
        this.baseBackoff = baseBackoff;
    }

    public record OutboxEntry(long id, UUID eventId, String eventType, String destination, String status, int attempts,
                              LocalDateTime nextAttemptAt, String lastError, String subjectConcept, String subjectId) {
    }

    @Scheduled(fixedDelayString = "${lsmom.outbox.poll-ms:2000}")
    public void poll() {
        int n;
        do {
            n = deliverBatch(50);
        } while (n == 50);
    }

    /** 领取并投递一批到期记录，返回处理条数。每条在独立事务中处理，互不影响。 */
    public int deliverBatch(int limit) {
        int processed = 0;
        for (int i = 0; i < limit; i++) {
            Boolean got = tx.execute(s -> deliverOne());
            if (!Boolean.TRUE.equals(got)) break;
            processed++;
        }
        return processed;
    }

    private boolean deliverOne() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select id, event_id, destination, attempts from core_outbox
            where status = 'PENDING' and next_attempt_at <= ? order by next_attempt_at, id
            limit 1 for update skip locked""", LocalDateTime.now(clock));
        if (rows.isEmpty()) return false;
        Map<String, Object> row = rows.get(0);
        long id = ((Number) row.get("id")).longValue();
        String destName = (String) row.get("destination");
        int attempts = ((Number) row.get("attempts")).intValue() + 1;
        OutboxDestination dest = destinations.get(destName);
        try {
            if (dest == null) throw new IllegalStateException("订阅方未注册：" + destName);
            DomainEvent event = jdbc.queryForObject("select * from core_domain_event where event_id = ?", EVENT_MAPPER, row.get("event_id"));
            dest.deliver(event);
            jdbc.update("update core_outbox set status = 'DELIVERED', attempts = ?, delivered_at = ?, last_error = null where id = ?",
                attempts, LocalDateTime.now(clock), id);
        } catch (Exception e) {
            int max = dest == null ? 1 : dest.maxAttempts();
            String err = (e.getClass().getSimpleName() + ": " + e.getMessage());
            if (attempts >= max) {
                jdbc.update("update core_outbox set status = 'DEAD', attempts = ?, last_error = ? where id = ?", attempts, truncate(err), id);
                log.warn("[LS-MOM] Outbox {} 投递到 {} 失败 {} 次，已进入补偿列表：{}", id, destName, attempts, err);
            } else {
                long factor = 1L << Math.min(attempts - 1, 10);
                Duration delay = baseBackoff.multipliedBy(factor).compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : baseBackoff.multipliedBy(factor);
                jdbc.update("update core_outbox set attempts = ?, next_attempt_at = ?, last_error = ? where id = ?",
                    attempts, LocalDateTime.now(clock).plus(delay), truncate(err), id);
            }
        }
        return true;
    }

    /** 人工补偿列表（投递失败达上限的记录）。 */
    public List<OutboxEntry> deadLetters(String destination) {
        return jdbc.query("""
            select o.*, e.type, e.subject_concept, e.subject_id from core_outbox o join core_domain_event e on e.event_id = o.event_id
            where o.status = 'DEAD' and (?::varchar is null or o.destination = ?::varchar) order by o.id""", this::entry, destination, destination);
    }

    public List<OutboxEntry> entriesOf(UUID eventId) {
        return jdbc.query("""
            select o.*, e.type, e.subject_concept, e.subject_id from core_outbox o join core_domain_event e on e.event_id = o.event_id
            where o.event_id = ? order by o.id""", this::entry, eventId);
    }

    /** 手工重投：重置为待投递并清零尝试次数。 */
    public void redeliver(long outboxId) {
        int n = jdbc.update("update core_outbox set status = 'PENDING', attempts = 0, next_attempt_at = ?, last_error = null where id = ? and status = 'DEAD'",
            LocalDateTime.now(clock), outboxId);
        if (n == 0) throw new com.lsmom.core.error.LsBizException("OUTBOX_NOT_DEAD", "只有补偿列表中的记录可以重投");
    }

    /** 归档已投递超过保留期的记录。业务表与事件表不受影响。 */
    public int archiveDelivered(Duration retention) {
        LocalDateTime before = LocalDateTime.now(clock).minus(retention);
        Integer n = tx.execute(st -> {
            jdbc.update("insert into core_outbox_archive select * from core_outbox where status = 'DELIVERED' and delivered_at < ?", before);
            return jdbc.update("delete from core_outbox where status = 'DELIVERED' and delivered_at < ?", before);
        });
        return n == null ? 0 : n;
    }

    private OutboxEntry entry(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new OutboxEntry(rs.getLong("id"), rs.getObject("event_id", UUID.class), rs.getString("type"), rs.getString("destination"),
            rs.getString("status"), rs.getInt("attempts"), rs.getObject("next_attempt_at", LocalDateTime.class), rs.getString("last_error"),
            rs.getString("subject_concept"), rs.getString("subject_id"));
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    private static Map<String, Object> readPayload(String json) {
        try {
            return json == null ? Map.of() : EventPublisher.JSON.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
