package com.lsmom.flow.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.core.error.LsBizException;
import com.lsmom.flow.model.FlowDefinition;
import com.lsmom.flow.runtime.FlowCallbacks;
import com.lsmom.flow.runtime.FlowReconciler;
import com.lsmom.flow.runtime.FlowRegistry;
import com.lsmom.flow.runtime.FlowRuntime;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程接口：设计（校验/草稿/发布，只接受配置，不接受 BPMN XML）、待办与办理、对账与回调补偿。
 * 办理接口再校验调用者是当前待办的办理人或候选人，节点 Operation 另经网关校验功能权限与字段策略。
 */
@RestController
@RequestMapping("/api/flow")
public class FlowController {

    private final FlowRegistry registry;
    private final FlowRuntime runtime;
    private final FlowReconciler reconciler;
    private final FlowCallbacks callbacks;
    private final ActorResolver actors;
    private final JdbcTemplate jdbc;

    public FlowController(FlowRegistry registry, FlowRuntime runtime, FlowReconciler reconciler, FlowCallbacks callbacks, ActorResolver actors,
                          JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.runtime = runtime;
        this.reconciler = reconciler;
        this.callbacks = callbacks;
        this.actors = actors;
    }

    // ---------- 设计 ----------

    @SaCheckPermission("lsmom:flow:design")
    @GetMapping("/definitions")
    public R<List<Map<String, Object>>> list() {
        return R.ok(registry.list().stream().map(FlowController::summary).toList());
    }

    @SaCheckPermission("lsmom:flow:design")
    @GetMapping("/definitions/{code}/history")
    public R<List<Map<String, Object>>> history(@PathVariable String code) {
        return R.ok(registry.history(code).stream().map(FlowController::summary).toList());
    }

    @SaCheckPermission("lsmom:flow:design")
    @GetMapping("/definitions/{code}/{version}")
    public R<FlowRegistry.Version> get(@PathVariable String code, @PathVariable int version) {
        return R.ok(registry.get(code, version));
    }

    @SaCheckPermission("lsmom:flow:design")
    @PostMapping("/definitions/validate")
    public R<List<FlowRegistry.Issue>> validate(@RequestBody JsonNode definition) {
        return R.ok(registry.validate(FlowDefinition.parse(definition)));
    }

    /** 预览生成的 BPMN（只读，供 LogicFlow 渲染与差异对比）。 */
    @SaCheckPermission("lsmom:flow:design")
    @PostMapping("/definitions/preview")
    public R<Map<String, Object>> preview(@RequestBody JsonNode definition) {
        FlowDefinition d = FlowDefinition.parse(definition);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issues", registry.validate(d));
        m.put("bpmnXml", registry.generator().generate(d.withVersion(d.version() == null ? 1 : d.version())));
        return R.ok(m);
    }

    @SaCheckPermission("lsmom:flow:design")
    @PostMapping("/definitions/drafts")
    public R<FlowRegistry.Version> saveDraft(@RequestBody JsonNode definition) {
        return R.ok(registry.saveDraft(FlowDefinition.parse(definition), actors.current().effectiveParty()));
    }

    @SaCheckPermission("lsmom:flow:publish")
    @PostMapping("/definitions/publish")
    public R<FlowRegistry.Version> publish(@RequestBody JsonNode definition) {
        return R.ok(runtime.publish(FlowDefinition.parse(definition), actors.current().effectiveParty()));
    }

    /** 办理人选择器：按姓名/编码搜索在职人员（含主组织）。 */
    @SaCheckPermission("lsmom:flow:design")
    @GetMapping("/parties")
    public R<List<Map<String, Object>>> parties(@RequestParam(required = false) String keyword) {
        String k = keyword == null ? "" : keyword.trim();
        return R.ok(jdbc.queryForList("""
            select p.id, p.code, p.name, o.id as "orgUnitId", o.name as "orgName"
              from core_party p
              left join core_party_org po on po.party_id = p.id and po.is_primary
              left join core_org_unit o on o.id = po.org_unit_id
             where p.party_type = 'EMPLOYEE' and p.status = 'ACTIVE' and (? = '' or p.name ilike ? or p.code ilike ?)
             order by p.id limit 50""", k, "%" + k + "%", "%" + k + "%"));
    }

    /** 当前登录人对应的 Party 与主组织。 */
    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        Long party = actors.current().effectiveParty();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("partyId", party);
        m.put("orgUnitId", party == null ? null : jdbc.queryForList(
            "select org_unit_id from core_party_org where party_id = ? and is_primary", Long.class, party).stream().findFirst().orElse(null));
        return R.ok(m);
    }

    // ---------- 待办与办理 ----------

    @GetMapping("/todo")
    public R<List<FlowRuntime.TaskView>> todo() {
        return R.ok(runtime.todo(actors.current().effectiveParty()));
    }

    @GetMapping("/notices")
    public R<List<Map<String, Object>>> notices() {
        return R.ok(runtime.notices(actors.current().effectiveParty()));
    }

    @GetMapping("/tasks/{taskId}")
    public R<FlowRuntime.TaskView> task(@PathVariable String taskId) {
        return R.ok(runtime.task(taskId));
    }

    @GetMapping("/biz/{concept}/{id}")
    public R<Map<String, Object>> ofBiz(@PathVariable String concept, @PathVariable String id) {
        return R.ok(Map.of("instances", runtime.instancesOf(concept, id), "tasks", runtime.tasksOfBiz(concept, id)));
    }

    public record Complete(String action, String comment, JsonNode input) {
    }

    @PostMapping("/tasks/{taskId}/complete")
    public R<Map<String, Object>> complete(@PathVariable String taskId, @RequestBody Complete b) {
        FlowRuntime.Action a;
        try {
            a = FlowRuntime.Action.valueOf(b.action() == null ? "APPROVE" : b.action().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw LsBizException.validation(Map.of("action", "只能是 APPROVE、REJECT、RESUBMIT"));
        }
        return R.ok(runtime.complete(taskId, a, b.comment(), b.input(), actor()));
    }

    public record Return(String target, String reason) {
    }

    @PostMapping("/tasks/{taskId}/return")
    public R<Map<String, Object>> returnTask(@PathVariable String taskId, @RequestBody Return b) {
        return R.ok(runtime.returnTask(taskId, b.target(), b.reason(), actor()));
    }

    public record Transfer(Long toParty, String comment) {
    }

    @PostMapping("/tasks/{taskId}/transfer")
    public R<Map<String, Object>> transfer(@PathVariable String taskId, @RequestBody Transfer b) {
        if (b.toParty() == null) throw LsBizException.validation(Map.of("toParty", "转办对象必填"));
        return R.ok(runtime.transfer(taskId, b.toParty(), b.comment(), actor()));
    }

    @PostMapping("/tasks/{taskId}/urge")
    public R<Map<String, Object>> urge(@PathVariable String taskId) {
        return R.ok(runtime.urge(taskId, actor()));
    }

    public record Reason(String reason) {
    }

    @PostMapping("/instances/{id}/withdraw")
    public R<Map<String, Object>> withdraw(@PathVariable long id, @RequestBody(required = false) Reason b) {
        return R.ok(runtime.withdraw(id, b == null ? null : b.reason(), actor()));
    }

    public record Cc(List<Long> parties, String message) {
    }

    @PostMapping("/instances/{id}/cc")
    public R<Map<String, Object>> cc(@PathVariable long id, @RequestBody Cc b) {
        return R.ok(runtime.ccManual(id, b.parties(), b.message(), actor()));
    }

    @PostMapping("/cc/{bizTaskId}/read")
    public R<Void> read(@PathVariable long bizTaskId) {
        runtime.markRead(bizTaskId, actor());
        return R.ok();
    }

    // ---------- 运维 ----------

    @SaCheckPermission("lsmom:flow:admin")
    @GetMapping("/reconcile")
    public R<FlowReconciler.Report> reconcile() {
        return R.ok(reconciler.check());
    }

    @SaCheckPermission("lsmom:flow:admin")
    @PostMapping("/reconcile/repair")
    public R<FlowReconciler.Report> repair() {
        return R.ok(reconciler.repair());
    }

    @SaCheckPermission("lsmom:flow:admin")
    @GetMapping("/callbacks")
    public R<List<FlowCallbacks.Callback>> pendingCallbacks() {
        return R.ok(callbacks.pending());
    }

    @SaCheckPermission("lsmom:flow:admin")
    @PostMapping("/callbacks/{id}/requeue")
    public R<Integer> requeue(@PathVariable long id) {
        callbacks.requeue(id);
        return R.ok(runtime.retryPending());
    }

    private ActorContext actor() {
        return actors.current();
    }

    private static Map<String, Object> summary(FlowRegistry.Version v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", v.code());
        m.put("version", v.version());
        m.put("concept", v.concept());
        m.put("name", v.name());
        m.put("status", v.status());
        m.put("publishedAt", v.publishedAt());
        return m;
    }
}
