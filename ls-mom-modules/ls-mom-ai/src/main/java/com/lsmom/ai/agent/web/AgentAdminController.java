package com.lsmom.ai.agent.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.ai.agent.AgentApprovals;
import com.lsmom.ai.agent.AgentRegistry;
import com.lsmom.core.error.LsBizException;
import org.dromara.common.core.domain.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Agent 服务身份与 API Key 管理、OBO 审计查询，以及真人处理 Agent 的审批申请。 */
@RestController
@RequestMapping("/api")
public class AgentAdminController {

    public record CreateBody(String code, String name, Set<String> permissions, Integer rateLimitPerMinute, String description) {
    }

    public record PermBody(Set<String> permissions, Integer rateLimitPerMinute) {
    }

    public record KeyBody(Integer expiresInDays, Integer graceHours) {
    }

    public record RevokeBody(String reason) {
    }

    public record DecideBody(boolean approve, Integer ttlMinutes, String note) {
    }

    private final AgentRegistry registry;
    private final AgentApprovals approvals;
    private final ActorResolver actors;
    private final JdbcTemplate jdbc;

    public AgentAdminController(AgentRegistry registry, AgentApprovals approvals, ActorResolver actors, JdbcTemplate jdbc) {
        this.registry = registry;
        this.approvals = approvals;
        this.actors = actors;
        this.jdbc = jdbc;
    }

    @SaCheckPermission("lsmom:agent:manage")
    @GetMapping("/agents")
    public R<List<Map<String, Object>>> list() {
        return R.ok(registry.list().stream().map(this::view).toList());
    }

    @SaCheckPermission("lsmom:agent:manage")
    @GetMapping("/agents/{id}")
    public R<Map<String, Object>> get(@PathVariable long id) {
        Map<String, Object> m = view(registry.get(id));
        m.put("events", registry.events(id, 100));
        m.put("approvals", approvals.byAgent(id));
        return R.ok(m);
    }

    @SaCheckPermission("lsmom:agent:manage")
    @PostMapping("/agents")
    public R<Map<String, Object>> create(@RequestBody CreateBody b) {
        return R.ok(view(registry.create(b.code(), b.name(), b.permissions(), b.rateLimitPerMinute(), b.description(), by())));
    }

    @SaCheckPermission("lsmom:agent:manage")
    @PutMapping("/agents/{id}/permissions")
    public R<Map<String, Object>> permissions(@PathVariable long id, @RequestBody PermBody b) {
        return R.ok(view(registry.updatePermissions(id, b.permissions(), b.rateLimitPerMinute())));
    }

    @SaCheckPermission("lsmom:agent:manage")
    @PostMapping("/agents/{id}/disable")
    public R<Map<String, Object>> disable(@PathVariable long id) {
        return R.ok(view(registry.setStatus(id, false)));
    }

    @SaCheckPermission("lsmom:agent:manage")
    @PostMapping("/agents/{id}/enable")
    public R<Map<String, Object>> enable(@PathVariable long id) {
        return R.ok(view(registry.setStatus(id, true)));
    }

    /** 签发 Key：明文只返回这一次。 */
    @SaCheckPermission("lsmom:agent:manage")
    @PostMapping("/agents/{id}/keys")
    public R<AgentRegistry.Issued> issue(@PathVariable long id, @RequestBody(required = false) KeyBody b) {
        return R.ok(registry.issue(id, b == null ? null : b.expiresInDays(), by()));
    }

    @SaCheckPermission("lsmom:agent:manage")
    @PostMapping("/agent-keys/{keyId}/rotate")
    public R<AgentRegistry.Issued> rotate(@PathVariable long keyId, @RequestBody(required = false) KeyBody b) {
        return R.ok(registry.rotate(keyId, b == null ? null : b.graceHours(), b == null ? null : b.expiresInDays(), by()));
    }

    @SaCheckPermission("lsmom:agent:manage")
    @PostMapping("/agent-keys/{keyId}/revoke")
    public R<AgentRegistry.KeyInfo> revoke(@PathVariable long keyId, @RequestBody(required = false) RevokeBody b) {
        return R.ok(registry.revoke(keyId, b == null ? null : b.reason(), by()));
    }

    /** OBO 双主体审计：Agent 主体、代理的真人、trace_id、批次、审批单。 */
    @SaCheckPermission("lsmom:agent:manage")
    @GetMapping("/agents/{id}/audit")
    public R<List<Map<String, Object>>> audit(@PathVariable long id, @RequestParam(required = false) String batchId) {
        AgentRegistry.Identity a = registry.get(id);
        return R.ok(jdbc.queryForList("""
            select a.id, a.occurred_at, a.op_code, a.actor_type, a.actor_party, ap.name as agent_name, a.on_behalf_of, hp.name as on_behalf_of_name,
                   a.target_concept, a.target_id, a.result, a.error_code, a.error_message, a.trace_id, a.batch_id, a.approval_id
              from op_audit a left join core_party ap on ap.id = a.actor_party left join core_party hp on hp.id = a.on_behalf_of
             where a.actor_party = ? and (cast(? as varchar) is null or a.batch_id = ?)
             order by a.id desc limit 200""", a.partyId(), batchId, batchId));
    }

    // —— 真人审批 Agent 的申请 ——

    @GetMapping("/agent-approvals")
    public R<List<AgentApprovals.Approval>> pending() {
        return R.ok(approvals.pending(actors.current()));
    }

    @PostMapping("/agent-approvals/{id}/decide")
    public R<AgentApprovals.Approval> decide(@PathVariable String id, @RequestBody DecideBody b) {
        return R.ok(approvals.decide(id, b.approve(), b.ttlMinutes(), b.note(), actors.current()));
    }

    private Long by() {
        ActorContext a = actors.current();
        if (a.type() != ActorContext.ActorType.HUMAN) throw new LsBizException("ACCESS_DENIED", "只有真人可以管理 Agent");
        return a.actorParty();
    }

    private Map<String, Object> view(AgentRegistry.Identity a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("code", a.code());
        m.put("name", a.name());
        m.put("partyId", a.partyId());
        m.put("permissions", a.permissions());
        m.put("rateLimitPerMinute", a.rateLimitPerMinute());
        m.put("status", a.status());
        m.put("description", a.description());
        m.put("createdAt", a.createdAt());
        m.put("keys", registry.keys(a.id()));
        return m;
    }
}
