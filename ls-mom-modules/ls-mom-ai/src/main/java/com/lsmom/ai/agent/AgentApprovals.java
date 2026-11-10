package com.lsmom.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.Exposure;
import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.action.registry.OperationDefinition;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.trace.TraceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * APPROVAL_REQUIRED 级调用的一次性审批单：Agent 申请（绑定 Operation、输入摘要、目标与业务版本）→ 真人批准（有效期）→
 * Agent 执行时核对并一次性消费。输入或版本变化、过期、重复使用都被拒绝。
 */
@Service
public class AgentApprovals {

    public record Approval(String id, long agentId, long onBehalfOf, String operation, String targetId, Integer targetVersion, JsonNode input,
                           String inputDigest, String reason, String status, Long approvedBy, LocalDateTime approvedAt, LocalDateTime expiresAt,
                           LocalDateTime usedAt, String note, LocalDateTime createdAt) {
    }

    static final int DEFAULT_TTL_MINUTES = 30;

    private final JdbcTemplate jdbc;
    private final OperationRegistry operations;
    private final Clock clock;

    public AgentApprovals(JdbcTemplate jdbc, OperationRegistry operations, Clock clock) {
        this.jdbc = jdbc;
        this.operations = operations;
        this.clock = clock;
    }

    /** Agent 申请。 */
    public Approval request(AgentRegistry.Identity agent, ActorContext actor, String code, JsonNode input, String targetId, Integer version,
                            String reason) {
        OperationDefinition op = operations.require(code);
        if (op.exposure() != Exposure.APPROVAL_REQUIRED) {
            throw new LsBizException("AGENT_APPROVAL_INVALID", "「" + op.name() + "」不需要审批（暴露等级 " + op.exposure() + "）");
        }
        if (!actor.hasPermission(op.permission())) {
            throw new LsBizException("ACCESS_DENIED", "没有执行「" + op.name() + "」的权限", Map.of("permission", op.permission()));
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            insert into agent_approval (id, agent_id, on_behalf_of, op_code, target_id, target_version, input, input_digest, reason, trace_id)
            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)""", id, agent.id(), actor.onBehalfOf(), code, targetId, version,
            input == null ? "{}" : input.toString(), OperationGateway.inputDigest(input), reason, TraceContext.current());
        return get(id);
    }

    /** 真人批准或驳回：须为真人，且自身有该 Operation 的权限。 */
    public Approval decide(String id, boolean approve, Integer ttlMinutes, String note, ActorContext human) {
        if (human.type() != ActorContext.ActorType.HUMAN) throw new LsBizException("AGENT_APPROVAL_INVALID", "只有真人可以审批 Agent 的操作");
        Approval a = get(id);
        if (!"PENDING".equals(a.status())) throw new LsBizException("AGENT_APPROVAL_INVALID", "审批单状态为 " + a.status() + "，不能再审批");
        OperationDefinition op = operations.require(a.operation());
        if (!human.hasPermission(op.permission())) {
            throw new LsBizException("ACCESS_DENIED", "没有「" + op.name() + "」的权限，不能批准", Map.of("permission", op.permission()));
        }
        int ttl = ttlMinutes == null ? DEFAULT_TTL_MINUTES : ttlMinutes;
        if (ttl < 1 || ttl > 24 * 60) throw LsBizException.validation(Map.of("ttlMinutes", "有效期 1～1440 分钟"));
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("update agent_approval set status = ?, approved_by = ?, approved_at = ?, expires_at = ?, note = ? where id = ? and status = 'PENDING'",
            approve ? "APPROVED" : "REJECTED", human.actorParty(), now, approve ? now.plusMinutes(ttl) : null, note, id);
        return get(id);
    }

    /**
     * 核对并一次性消费（独立于业务执行：消费后即使执行失败也不能重用，须重新申请）。
     */
    public Approval consume(String id, AgentRegistry.Identity agent, ActorContext actor, String code, JsonNode input, String targetId, Integer version) {
        if (id == null || id.isBlank()) throw new LsBizException("AGENT_APPROVAL_REQUIRED", "该操作须先申请并经真人批准");
        Approval a = jdbc.query("select * from agent_approval where id = ?", (rs, i) -> map(rs), id).stream().findFirst()
            .orElseThrow(() -> new LsBizException("AGENT_APPROVAL_INVALID", "审批单不存在"));
        if ("USED".equals(a.status())) throw new LsBizException("AGENT_APPROVAL_USED", "审批单已使用，不能重复执行", Map.of("approvalId", id));
        if (!"APPROVED".equals(a.status())) throw new LsBizException("AGENT_APPROVAL_INVALID", "审批单未批准（" + a.status() + "）");
        if (a.agentId() != agent.id() || !Objects.equals(a.onBehalfOf(), actor.onBehalfOf())) {
            throw new LsBizException("AGENT_APPROVAL_MISMATCH", "审批单不属于当前 Agent 或代理人");
        }
        if (!a.expiresAt().isAfter(LocalDateTime.now(clock))) throw new LsBizException("AGENT_APPROVAL_EXPIRED", "审批已过期，请重新申请");
        List<String> diff = new java.util.ArrayList<>();
        if (!a.operation().equals(code)) diff.add("operation");
        if (!a.inputDigest().equals(OperationGateway.inputDigest(input))) diff.add("input");
        if (!Objects.equals(a.targetId(), targetId)) diff.add("targetId");
        if (!Objects.equals(a.targetVersion(), version)) diff.add("version");
        if (!diff.isEmpty()) {
            throw new LsBizException("AGENT_APPROVAL_MISMATCH", "执行内容与批准内容不一致（" + String.join("、", diff) + "），须重新批准",
                Map.of("changed", diff));
        }
        int n = jdbc.update("update agent_approval set status = 'USED', used_at = now() where id = ? and status = 'APPROVED'", id);
        if (n == 0) throw new LsBizException("AGENT_APPROVAL_USED", "审批单已使用，不能重复执行", Map.of("approvalId", id));
        return get(id);
    }

    public Approval get(String id) {
        return jdbc.query("select * from agent_approval where id = ?", (rs, i) -> map(rs), id).stream().findFirst()
            .orElseThrow(() -> LsBizException.notFound("审批单", id));
    }

    /** 待审批（审批人有对应 Operation 权限的才列出）。 */
    public List<Approval> pending(ActorContext human) {
        return jdbc.query("select * from agent_approval where status = 'PENDING' order by created_at desc limit 200", (rs, i) -> map(rs)).stream()
            .filter(a -> operations.find(a.operation()).map(op -> human.hasPermission(op.permission())).orElse(false)).toList();
    }

    public List<Approval> byAgent(long agentId) {
        return jdbc.query("select * from agent_approval where agent_id = ? order by created_at desc limit 200", (rs, i) -> map(rs), agentId);
    }

    private static Approval map(java.sql.ResultSet rs) throws java.sql.SQLException {
        JsonNode input;
        try {
            input = AgentRegistry.JSON.readTree(rs.getString("input"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new Approval(rs.getString("id"), rs.getLong("agent_id"), rs.getLong("on_behalf_of"), rs.getString("op_code"), rs.getString("target_id"),
            (Integer) rs.getObject("target_version"), input, rs.getString("input_digest"), rs.getString("reason"), rs.getString("status"),
            (Long) rs.getObject("approved_by"), rs.getObject("approved_at", LocalDateTime.class), rs.getObject("expires_at", LocalDateTime.class),
            rs.getObject("used_at", LocalDateTime.class), rs.getString("note"), rs.getObject("created_at", LocalDateTime.class));
    }
}
