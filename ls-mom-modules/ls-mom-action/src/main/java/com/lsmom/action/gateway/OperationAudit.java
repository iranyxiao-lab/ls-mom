package com.lsmom.action.gateway;

import com.lsmom.action.api.ActorContext;
import com.lsmom.core.trace.TraceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Operation 审计：成功记录随业务事务提交；失败记录在独立事务中写入，业务回滚后仍保留。 */
@Component
public class OperationAudit {

    public record Entry(String opCode, ActorContext actor, String targetConcept, String targetId, Integer dataVersion,
                        String result, String errorCode, String errorMessage, String inputDigest, String inputSummary,
                        long durationMs, String idempotencyKey) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate newTx;

    public OperationAudit(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void write(Entry e) {
        com.lsmom.action.api.AgentCall call = com.lsmom.action.api.AgentCall.current();
        jdbc.update("""
            insert into op_audit (op_code, actor_type, actor_user, actor_party, on_behalf_of, target_concept, target_id, data_version,
              result, error_code, error_message, input_digest, input_summary, duration_ms, trace_id, idempotency_key, batch_id, approval_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            e.opCode(), e.actor().type().name(), e.actor().userId(), e.actor().actorParty(), e.actor().onBehalfOf(),
            e.targetConcept(), e.targetId(), e.dataVersion(), e.result(), e.errorCode(), truncate(e.errorMessage(), 1000),
            e.inputDigest(), truncate(e.inputSummary(), 2000), (int) Math.min(e.durationMs(), Integer.MAX_VALUE),
            TraceContext.current(), e.idempotencyKey(), call == null ? null : call.batchId(), call == null ? null : call.approvalId());
    }

    public void writeIndependently(Entry e) {
        if (com.lsmom.core.sandbox.Sandbox.active()) write(e);
        else newTx.executeWithoutResult(s -> write(e));
    }

    private static String truncate(String s, int n) {
        return s == null ? null : s.length() > n ? s.substring(0, n) : s;
    }
}
