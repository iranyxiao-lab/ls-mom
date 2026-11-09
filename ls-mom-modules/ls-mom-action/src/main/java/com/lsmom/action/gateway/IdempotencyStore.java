package com.lsmom.action.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.action.api.OperationResult;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 幂等记录：同一键在窗口内重复到达返回首次结果；键相同但操作或输入不同返回冲突。
 * 记录与业务写入同事务提交，业务回滚则不留记录（允许重试）。
 */
@Component
public class IdempotencyStore {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    public static final Duration WINDOW = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdempotencyStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 查找有效记录；并发的同键请求在此串行（行锁/咨询锁），后到者等待首个事务结束。 */
    public Optional<OperationResult> lookup(String key, String opCode, String inputHash) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?))", "op-idem:" + key);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "select op_code, input_hash, response::text as response from op_idempotency where idempotency_key = ? and expires_at > ?",
            key, LocalDateTime.now(clock));
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> r = rows.get(0);
        if (!opCode.equals(r.get("op_code")) || !inputHash.equals(r.get("input_hash"))) {
            throw new LsBizException("IDEMPOTENCY_CONFLICT", "幂等键已用于不同的请求，请使用新的幂等键",
                Map.of("idempotencyKey", key, "operation", r.get("op_code")));
        }
        try {
            return Optional.of(JSON.readValue((String) r.get("response"), OperationResult.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void save(String key, String opCode, Long actorParty, String inputHash, OperationResult result) {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            jdbc.update("""
                insert into op_idempotency (idempotency_key, op_code, actor_party, input_hash, response, created_at, expires_at)
                values (?, ?, ?, ?, ?::jsonb, ?, ?)
                on conflict (idempotency_key) do update set op_code = excluded.op_code, actor_party = excluded.actor_party,
                  input_hash = excluded.input_hash, response = excluded.response, created_at = excluded.created_at,
                  expires_at = excluded.expires_at
                where op_idempotency.expires_at <= excluded.created_at""",
                key, opCode, actorParty, inputHash, JSON.writeValueAsString(result), now, now.plus(WINDOW));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public int purgeExpired() {
        return jdbc.update("delete from op_idempotency where expires_at <= ?", LocalDateTime.now(clock));
    }
}
