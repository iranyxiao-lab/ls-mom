package com.lsmom.flow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.sandbox.Sandbox;
import com.lsmom.crud.GenericCrudService;
import com.lsmom.flow.bpmn.BpmnTemplateGenerator;
import com.lsmom.flow.model.FlowDefinition;
import com.lsmom.form.FieldPolicy;
import com.lsmom.form.FormRegistry;
import com.lsmom.meta.concept.ConceptRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 设计器沙箱试运行：用当前表单草稿填写一张单据，以各节点候选办理人的身份（模拟角色：其账号的角色、权限与数据范围）
 * 走完提交 → 审核 → 确认。整个过程在只回滚的事务中执行（{@link Sandbox}：独立事务并入、提交后回调立即执行），
 * 结束后业务表、流程实例、待办、回调、审计与事件全部回滚；只把轨迹写入 flow_trial_run，可一键清除。
 */
public class TrialRunner {

    static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_STEPS = 20;

    /** 某节点的试运行办理：决定（默认通过）、该节点填写的字段、意见、指定办理人（默认第一个候选人） */
    public record NodeStep(String decision, ObjectNode input, String comment, Long party) {
    }

    public record Request(String formCode, JsonNode schema, ObjectNode values, Long initiatorParty, Map<String, NodeStep> nodes) {
    }

    public record Result(long id, String status, String summary, List<Map<String, Object>> trace) {
    }

    private static final class Abort extends RuntimeException {
        Abort(String m) {
            super(m);
        }
    }

    private final JdbcTemplate jdbc;
    private final FlowRegistry registry;
    private final FlowRuntime runtime;
    private final FlowInstances instances;
    private final FlowCallbacks callbacks;
    private final GenericCrudService crud;
    private final OperationGateway gateway;
    private final ConceptRegistry concepts;
    private final FormRegistry forms;
    private final ActorResolver actors;
    private final TransactionTemplate tx;
    private final TransactionTemplate save;

    public TrialRunner(JdbcTemplate jdbc, FlowRegistry registry, FlowRuntime runtime, FlowInstances instances, FlowCallbacks callbacks,
                       GenericCrudService crud, OperationGateway gateway, ConceptRegistry concepts, FormRegistry forms, ActorResolver actors,
                       PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.runtime = runtime;
        this.instances = instances;
        this.callbacks = callbacks;
        this.crud = crud;
        this.gateway = gateway;
        this.concepts = concepts;
        this.forms = forms;
        this.actors = actors;
        this.tx = new TransactionTemplate(txm);
        this.save = new TransactionTemplate(txm);
    }

    /** 绑定该表单的已发布审批流。 */
    public FlowRegistry.Version flowFor(String formCode) {
        return registry.list().stream()
            .filter(v -> "PUBLISHED".equals(v.status()) && v.definition() != null && v.definition().form() != null
                && formCode.equals(v.definition().form().code()))
            .findFirst()
            .orElseThrow(() -> new LsBizException("TRIAL_NO_FLOW", "表单 " + formCode + " 没有绑定已发布的审批流，不能试运行"));
    }

    /** 试运行准备：流程节点与可选发起人（有提交权限的人）。 */
    public Map<String, Object> setup(String formCode) {
        FlowRegistry.Version v = flowFor(formCode);
        FlowDefinition d = v.definition();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flowCode", v.code());
        m.put("flowVersion", v.version());
        m.put("flowName", v.name());
        m.put("concept", d.concept());
        m.put("startOn", d.startOn());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (FlowDefinition.Node n : d.nodes()) {
            if (!n.human()) continue;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("key", n.key());
            x.put("name", n.name());
            x.put("type", n.type().name());
            x.put("policyKey", n.policyKey());
            nodes.add(x);
        }
        m.put("nodes", nodes);
        m.put("initiators", initiators(d.startOn()));
        return m;
    }

    List<Map<String, Object>> initiators(String startOn) {
        String perm = gatewayPermission(startOn);
        return jdbc.queryForList("""
            select distinct p.id as "party", p.name, p.org_name as "orgName" from core_party p
              join sys_user_role ur on ur.user_id = p.user_id join sys_role r on r.role_id = ur.role_id and r.status = '0' and r.del_flag = '0'
              join sys_role_menu rm on rm.role_id = r.role_id join sys_menu m on m.menu_id = rm.menu_id
            where p.status = 'ACTIVE' and p.user_id <> 1 and m.perms = ? order by p.id limit 20""", perm);
    }

    private String gatewayPermission(String op) {
        return jdbc.queryForList("select permission from meta_operation where code = ? limit 1", String.class, op)
            .stream().findFirst().orElse("");
    }

    public Result run(Request req, ActorContext designer) {
        if (req.formCode() == null || req.formCode().isBlank()) throw LsBizException.validation(Map.of("formCode", "缺少表单编码"));
        FlowRegistry.Version v = flowFor(req.formCode());
        FlowDefinition def = v.definition();
        List<Map<String, Object>> trace = new ArrayList<>();
        String[] summary = {null};
        String status;
        try {
            Sandbox.run("trial-" + UUID.randomUUID(), () -> tx.execute(st -> {
                st.setRollbackOnly();
                summary[0] = body(req, v, def, trace, designer);
                return null;
            }));
            status = "PASSED";
        } catch (Abort e) {
            status = "FAILED";
            summary[0] = e.getMessage();
        }
        String fs = status;
        String text = summary[0] == null ? "" : summary[0].length() > 500 ? summary[0].substring(0, 500) : summary[0];
        Long id = save.execute(st -> jdbc.queryForObject("""
            insert into flow_trial_run (form_code, flow_code, status, summary, trace, created_by) values (?, ?, ?, ?, ?::jsonb, ?) returning id""",
            Long.class, req.formCode(), v.code(), fs, text, JSON.valueToTree(trace).toString(), designer.effectiveParty() == null ? 0L : designer.effectiveParty()));
        return new Result(id == null ? 0 : id, fs, text, trace);
    }

    private String body(Request req, FlowRegistry.Version v, FlowDefinition def, List<Map<String, Object>> trace, ActorContext designer) {
        String concept = def.concept();
        // 1. 草稿：在沙箱内存成新版本，流程实例改绑到这个版本（随事务回滚）
        JsonNode schema;
        Integer draftVersion = null;
        if (req.schema() != null && req.schema().isObject()) {
            ObjectNode copy = ((ObjectNode) req.schema()).deepCopy();
            if (!req.formCode().equals(copy.path("formCode").asText())) throw new Abort("草稿的表单编码与试运行表单不一致");
            Integer max = jdbc.queryForObject("select coalesce(max(version), 0) from meta_view where code = ?", Integer.class, req.formCode());
            draftVersion = (max == null ? 0 : max) + 1;
            copy.put("version", draftVersion);
            step(trace, "draft", "使用当前草稿", null, () -> forms.saveDraft(copy, designer.userId(), "沙箱试运行"));
            schema = copy;
        } else {
            schema = forms.published(req.formCode()).map(FormRegistry.View::schema)
                .orElseThrow(() -> new Abort("表单 " + req.formCode() + " 没有已发布版本，请传入草稿"));
        }
        // 2. 发起人填写并提交
        Long initiatorParty = req.initiatorParty();
        if (initiatorParty == null) {
            List<Map<String, Object>> c = initiators(def.startOn());
            if (c.isEmpty()) throw new Abort("没有人拥有提交权限（" + gatewayPermission(def.startOn()) + "），请先授权");
            initiatorParty = ((Number) c.get(0).get("party")).longValue();
        }
        ActorContext initiator = actors.simulate(initiatorParty);
        ObjectNode values = req.values() == null ? JSON.createObjectNode() : req.values().deepCopy();
        if (!values.hasNonNull("orgId")) {
            long pid = initiatorParty;
            jdbc.queryForList("select org_unit_id from core_party_org where party_id = ? order by is_primary desc, id limit 1", Long.class, pid)
                .stream().findFirst().ifPresent(o -> values.put("orgId", o));
        }
        long[] id = {0};
        step(trace, "fill", "填写并保存", initiator, () -> {
            Map<String, Object> r = crud.create(concept, values, initiator, null);
            id[0] = ((Number) r.get("id")).longValue();
        }).put("values", values);
        String table = concepts.get(concept).tableName();
        step(trace, "submit", "提交", initiator, () -> {
            Integer ver = jdbc.queryForObject("select version from " + table + " where id = ?", Integer.class, id[0]);
            gateway.execute(def.startOn(), new OperationRequest(JSON.createObjectNode(), String.valueOf(id[0]), ver, null, false), initiator);
        }).put("status", recordStatus(table, id[0]));
        FlowInstances.Instance inst = instances.running(concept, String.valueOf(id[0]))
            .orElseThrow(() -> new Abort("提交后没有启动流程（提交 Operation " + def.startOn() + " 未触发审批流）"));
        if (draftVersion != null) jdbc.update("update biz_flow_instance set form_version = ? where id = ?", draftVersion, inst.id());
        // 3. 逐个节点以候选办理人身份办理
        List<String> path = new ArrayList<>();
        path.add("提交");
        for (int i = 0; i < MAX_STEPS; i++) {
            inst = instances.get(inst.id());
            if (!"RUNNING".equals(inst.status())) break;
            FlowRuntime.TaskView task = runtime.tasksOf(inst.procInstId()).stream()
                .filter(t -> "TODO".equals(t.status()) && !"CC".equals(t.kind())).findFirst().orElse(null);
            if (task == null) throw new Abort("流程仍在进行，但没有待办（可能卡在服务节点或等待外部事件）");
            if (BpmnTemplateGenerator.INITIATOR.equals(task.nodeKey())) {
                path.add("退回发起人");
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("kind", "returned");
                s.put("label", "退回发起人，试运行到此结束");
                trace.add(s);
                break;
            }
            FlowDefinition.Node node = def.node(task.nodeKey());
            NodeStep ns = req.nodes() == null ? null : req.nodes().get(task.nodeKey());
            Long party = ns != null && ns.party() != null ? ns.party()
                : task.assigneeParty() != null ? task.assigneeParty()
                : task.candidates() == null || task.candidates().isEmpty() ? null : task.candidates().get(0);
            if (party == null) throw new Abort("节点「" + task.nodeName() + "」没有可办理的人（办理人规则未匹配到任何人）");
            ActorContext who = actors.simulate(party);
            FlowRuntime.Action action = ns != null && "REJECT".equalsIgnoreCase(ns.decision()) ? FlowRuntime.Action.REJECT : FlowRuntime.Action.APPROVE;
            ObjectNode input = ns == null || ns.input() == null ? null : ns.input();
            String comment = ns == null || ns.comment() == null ? "试运行" : ns.comment();
            JsonNode policy = node.fieldPolicy() != null && !node.fieldPolicy().isNull() ? node.fieldPolicy() : schema.path("nodePolicies").get(node.policyKey());
            Map<String, FieldPolicy.Access> access = FieldPolicy.resolve(schema, policy);
            Map<String, Object> s = step(trace, "task", task.nodeName(), who,
                () -> runtime.complete(task.taskId(), action, comment, input, who));
            s.put("node", task.nodeKey());
            s.put("decision", action.name());
            s.put("candidates", task.candidates() == null ? 0 : task.candidates().size());
            if (input != null) s.put("input", input);
            s.put("editable", access.entrySet().stream().filter(e -> e.getValue().editable() && !e.getKey().contains(".")).map(Map.Entry::getKey).toList());
            s.put("required", access.entrySet().stream().filter(e -> e.getValue().required() && e.getValue().editable() && !e.getKey().contains("."))
                .map(Map.Entry::getKey).toList());
            s.put("status", recordStatus(table, id[0]));
            path.add(task.nodeName() + "（" + (action == FlowRuntime.Action.APPROVE ? "通过" : "驳回") + "）");
        }
        inst = instances.get(inst.id());
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("kind", "end");
        end.put("label", "结果");
        end.put("flowStatus", inst.status());
        end.put("status", recordStatus(table, id[0]));
        end.put("callbacks", callbacks.ofProcInst(inst.procInstId()).stream()
            .map(c -> Map.of("event", c.event(), "operation", String.valueOf(c.operation()), "status", c.status())).toList());
        trace.add(end);
        if ("RUNNING".equals(inst.status()) && path.stream().noneMatch(p -> p.equals("退回发起人"))) throw new Abort("超过 " + MAX_STEPS + " 步仍未结束");
        return String.join(" → ", path) + "：流程 " + inst.status() + "，单据状态 " + end.get("status");
    }

    private String recordStatus(String table, long id) {
        return jdbc.queryForList("select status from " + table + " where id = ?", String.class, id).stream().findFirst().orElse("");
    }

    /** 执行一步并记入轨迹；业务异常转为试运行失败（事务随之回滚）。 */
    private static Map<String, Object> step(List<Map<String, Object>> trace, String kind, String label, ActorContext who, Runnable r) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("kind", kind);
        s.put("label", label);
        if (who != null) s.put("actor", who.displayName());
        trace.add(s);
        try {
            r.run();
            s.put("ok", true);
        } catch (LsBizException e) {
            s.put("ok", false);
            s.put("error", e.getCode() + "：" + e.getMessage());
            if (e.getDetails() != null) s.put("details", e.getDetails());
            throw new Abort("「" + label + "」失败：" + e.getMessage());
        }
        return s;
    }

    public List<Map<String, Object>> list(String formCode, Long by) {
        return jdbc.queryForList("""
            select id, form_code as "formCode", flow_code as "flowCode", status, summary, trace::text as trace, created_at as "createdAt"
            from flow_trial_run where form_code = ? and created_by = ? order by id desc limit 20""", formCode, by);
    }

    /** 一键清除：删除本人在该表单上的全部试运行数据。 */
    public int clear(String formCode, Long by) {
        return jdbc.update("delete from flow_trial_run where form_code = ? and created_by = ?", formCode, by);
    }
}
