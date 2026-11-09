package com.lsmom.core.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lsmom.core.trace.TraceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 写入领域事件与 Outbox。必须在业务事务内调用（MANDATORY）：业务回滚时事件与 Outbox 一并回滚。
 */
@Service
public class EventPublisher {

    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private final JdbcTemplate jdbc;
    private final List<OutboxDestination> destinations;
    private final Clock clock;

    public EventPublisher(JdbcTemplate jdbc, List<OutboxDestination> destinations, Clock clock) {
        this.jdbc = jdbc;
        this.destinations = destinations;
        this.clock = clock;
    }

    public record NewEvent(String type, String subjectConcept, String subjectId, Long orgUnitId, Long actorParty, Long onBehalfOf,
                           String idempotencyKey, Map<String, Object> payload) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DomainEvent publish(NewEvent e) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(clock);
        String trace = TraceContext.current();
        String key = e.idempotencyKey() != null ? e.idempotencyKey() : id.toString();
        jdbc.update("""
            insert into core_domain_event (event_id, type, occurred_at, actor_party, on_behalf_of, subject_concept, subject_id,
              org_unit_id, trace_id, idempotency_key, payload) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""",
            id, e.type(), now, e.actorParty(), e.onBehalfOf(), e.subjectConcept(), e.subjectId(), e.orgUnitId(), trace, key, toJson(e.payload()));
        DomainEvent event = new DomainEvent(id, e.type(), 1, now, e.actorParty(), e.onBehalfOf(), e.subjectConcept(), e.subjectId(),
            e.orgUnitId(), trace, key, e.payload() == null ? Map.of() : e.payload());
        for (OutboxDestination d : destinations) {
            if (d.accepts(e.type())) {
                jdbc.update("insert into core_outbox (event_id, destination, next_attempt_at) values (?, ?, ?)", id, d.name(), now);
                d.enqueued(event);
            }
        }
        return event;
    }

    public List<DomainEvent> eventsOf(String subjectConcept, String subjectId) {
        return jdbc.query("select * from core_domain_event where subject_concept = ? and subject_id = ? order by occurred_at, event_id",
            OutboxWorker.EVENT_MAPPER, subjectConcept, subjectId);
    }

    static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o == null ? Map.of() : o);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("事件载荷无法序列化", ex);
        }
    }
}
