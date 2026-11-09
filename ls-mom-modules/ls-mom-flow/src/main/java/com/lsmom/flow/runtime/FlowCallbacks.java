package com.lsmom.flow.runtime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.action.state.StateStores;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 流程结果 → 命名 Operation 回调。登记与引擎同事务（幂等键唯一，重复登记被忽略），事务提交后在独立事务中执行；
 * 执行带同一幂等键调用网关，重复投递只迁移一次。失败记为 PENDING_RETRY 按指数退避重试，超过上限记 FAILED 进补偿。
 */
public class FlowCallbacks {

    public static final int MAX_ATTEMPTS = 8;

    public record Callback(long id, String key, String procInstId, String taskId, String event, String operation, String bizType, String bizId,
                           Long onBehalfOf, String comment, String status, int attempts, String lastError) {
    }

    private static final java.util.List<String> COMMENT_FIELDS = java.util.List.of("conclusion", "reason", "comment");

    private final JdbcTemplate jdbc;
    private final OperationGateway gateway;
    private final StateStores stores;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    public FlowCallbacks(JdbcTemplate jdbc, OperationGateway gateway, StateStores stores, PlatformTransactionManager txm, Clock clock) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.stores = stores;
        this.requiresNew = new TransactionTemplate(txm);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public static String key(String procInstId, String taskId, String event) {
        return procInstId + ":" + (taskId == null ? "-" : taskId) + ":" + event;
    }

    /** 在当前事务中登记；已登记过同一幂等键则返回 false。 */
    public boolean enqueue(String key, String procInstId, String taskId, String event, String operation, String bizType, String bizId) {
        return enqueue(key, procInstId, taskId, event, operation, bizType, bizId, null, null);
    }

    public boolean enqueue(String key, String procInstId, String taskId, String event, String operation, String bizType, String bizId,
                           Long onBehalfOf, String comment) {
        return jdbc.update("""
            insert into biz_flow_callback_log (idempotency_key, proc_inst_id, task_id, event, operation, biz_type, biz_id, on_behalf_of, comment, status)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING') on conflict (idempotency_key) do nothing""",
            key, procInstId, taskId, event, operation, bizType, bizId, onBehalfOf, FlowInstances.trim(comment)) > 0;
    }

    /** 执行一条回调（独立事务）。返回是否成功。 */
    public boolean dispatch(String key) {
        Callback c = find(key);
        if (c == null || "DONE".equals(c.status()) || "FAILED".equals(c.status())) return c != null && "DONE".equals(c.status());
        try {
            requiresNew.executeWithoutResult(st -> {
                int version = stores.has(c.bizType()) ? stores.of(c.bizType()).load(c.bizId()).version() : 0;
                gateway.execute(c.operation(), new OperationRequest(input(c), c.bizId(), version, c.key(), false), actor(c.onBehalfOf()));
                jdbc.update("update biz_flow_callback_log set status = 'DONE', attempts = attempts + 1, done_at = now(), last_error = null where id = ?", c.id());
            });
            return true;
        } catch (RuntimeException e) {
            int attempts = c.attempts() + 1;
            boolean giveUp = attempts >= MAX_ATTEMPTS;
            String msg = e instanceof LsBizException b ? b.getCode() + "：" + b.getMessage() : e.getClass().getSimpleName() + "：" + e.getMessage();
            requiresNew.executeWithoutResult(st -> jdbc.update("""
                update biz_flow_callback_log set status = ?, attempts = ?, last_error = ?, next_retry_at = ? where id = ?""",
                giveUp ? "FAILED" : "PENDING_RETRY", attempts, FlowInstances.trim(msg),
                LocalDateTime.now(clock).plusSeconds(30L * (1L << Math.min(attempts, 10))), c.id()));
            return false;
        }
    }

    /** 重试到期的回调（Worker 调用）。 */
    public int retryDue() {
        List<String> keys = jdbc.queryForList("""
            select idempotency_key from biz_flow_callback_log where status in ('PENDING', 'PENDING_RETRY')
              and (next_retry_at is null or next_retry_at <= ?) order by id limit 100""", String.class, LocalDateTime.now(clock));
        int ok = 0;
        for (String k : keys) if (dispatch(k)) ok++;
        return ok;
    }

    /** 补偿：把 FAILED 回调重新置为待重试。 */
    public void requeue(long id) {
        jdbc.update("update biz_flow_callback_log set status = 'PENDING_RETRY', next_retry_at = null where id = ? and status = 'FAILED'", id);
    }

    public Callback find(String key) {
        return jdbc.query("select * from biz_flow_callback_log where idempotency_key = ?", (rs, i) -> map(rs), key).stream().findFirst().orElse(null);
    }

    public List<Callback> ofProcInst(String procInstId) {
        return jdbc.query("select * from biz_flow_callback_log where proc_inst_id = ? order by id", (rs, i) -> map(rs), procInstId);
    }

    public List<Callback> pending() {
        return jdbc.query("select * from biz_flow_callback_log where status <> 'DONE' order by id desc limit 200", (rs, i) -> map(rs));
    }

    static ActorContext actor() {
        return actor(null);
    }

    /** 回调身份：流程引擎（系统），代表最后办理人执行，业务记录与时间轴归属到该办理人。 */
    static ActorContext actor(Long onBehalfOf) {
        return new ActorContext(ActorContext.ActorType.SYSTEM, null, null, onBehalfOf, true, java.util.Set.of(),
            com.lsmom.core.org.DataScope.unrestricted(), "流程引擎");
    }

    /** 回调输入只含审批意见：Operation 输入声明了 conclusion / reason / comment 时，以最后办理意见填入。 */
    private com.fasterxml.jackson.databind.node.ObjectNode input(Callback c) {
        com.fasterxml.jackson.databind.node.ObjectNode in = JsonNodeFactory.instance.objectNode();
        if (c.comment() == null || c.comment().isBlank()) return in;
        com.fasterxml.jackson.databind.JsonNode props = gateway.operationSchema(c.operation()).path("properties");
        for (String f : COMMENT_FIELDS) {
            if (props.has(f)) {
                in.put(f, c.comment());
                break;
            }
        }
        return in;
    }

    private static Callback map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Callback(rs.getLong("id"), rs.getString("idempotency_key"), rs.getString("proc_inst_id"), rs.getString("task_id"),
            rs.getString("event"), rs.getString("operation"), rs.getString("biz_type"), rs.getString("biz_id"),
            (Long) rs.getObject("on_behalf_of"), rs.getString("comment"), rs.getString("status"),
            rs.getInt("attempts"), rs.getString("last_error"));
    }

}
