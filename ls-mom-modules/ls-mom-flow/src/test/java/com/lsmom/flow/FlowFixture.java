package com.lsmom.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.state.StateMachineDefinition;
import com.lsmom.action.state.StateSnapshot;
import com.lsmom.action.state.TransitionEffect;
import com.lsmom.core.org.DataScope;
import com.lsmom.core.org.OrgUnit;
import com.lsmom.core.party.Party;
import com.lsmom.crud.CrudFixture;
import com.lsmom.flow.engine.FlowEngineFactory;
import com.lsmom.flow.model.FlowDefinition;
import com.lsmom.flow.runtime.ApprovalEvidence;
import com.lsmom.flow.runtime.AssigneeResolver;
import com.lsmom.flow.runtime.FlowCallbacks;
import com.lsmom.flow.runtime.FlowCallbackGuard;
import com.lsmom.flow.runtime.FlowGuards;
import com.lsmom.flow.runtime.FlowInstances;
import com.lsmom.flow.runtime.FlowNodePolicyResolver;
import com.lsmom.flow.runtime.FlowReconciler;
import com.lsmom.flow.runtime.FlowRegistry;
import com.lsmom.flow.runtime.FlowRuntime;
import com.lsmom.flow.runtime.TaskSync;
import com.lsmom.form.FormFieldPolicyCheck;
import com.lsmom.form.FormFixture;
import com.lsmom.form.NodePolicyResolver;
import com.lsmom.test.PgTestSupport;
import org.flowable.common.engine.impl.util.DefaultClockImpl;
import org.flowable.engine.ProcessEngine;
import org.flowable.task.api.Task;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lsmom.action.ActionFixture.json;

/** 流程测试装配：在表单/台账装配之上嵌入 Flowable（flowable schema），与生产装配使用同一批类。 */
public class FlowFixture {

    public final FormFixture form = new FormFixture();
    public final CrudFixture crud = form.crud;
    public final ActionFixture action = crud.action;
    public final DefaultClockImpl clock = new DefaultClockImpl();
    public final TaskSync sync = new TaskSync(action.core.jdbc);
    public final ProcessEngine engine;
    public final AssigneeResolver assignees = new AssigneeResolver(action.core.jdbc, action.core.leaders, action.core.orgUnits);
    public final FlowRegistry registry;
    public final FlowInstances instances = new FlowInstances(action.core.jdbc);
    public final FlowCallbacks callbacks;
    public final ApprovalEvidence evidence = new ApprovalEvidence(action.core.jdbc, action.stores);
    public final FlowRuntime runtime;
    public final FlowReconciler reconciler;

    public FlowFixture() {
        engine = FlowEngineFactory.build(PgTestSupport.dataSource(), action.txm, new FlowEngineFactory.Options(false, clock, List.of(sync)));
        registry = new FlowRegistry(action.core.jdbc, engine, action.concepts, action.operations, form.forms, form.usages, assignees,
            new com.lsmom.action.rule.CelRuleEngine(), action.txm);
        callbacks = new FlowCallbacks(action.core.jdbc, action.gateway, action.stores, action.txm, action.core.clock);
        runtime = new FlowRuntime(action.core.jdbc, engine, registry, instances, callbacks, evidence, assignees, action.gateway,
            new com.lsmom.action.gateway.OperationAudit(action.core.jdbc, action.txm), action.stores, new com.lsmom.action.rule.CelRuleEngine(),
            action.events, action.core.leaders, form.forms, action.concepts, sync, action.txm, action.core.clock);
        reconciler = new FlowReconciler(action.core.jdbc, engine, runtime, action.txm);
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("flowNodePolicy", new FlowNodePolicyResolver()));
        action.gateway.addFieldPolicy(new FormFieldPolicyCheck(form.forms, beans.getBeanProvider(NodePolicyResolver.class)));
        if (action.guardRegistry.registered(FlowGuards.NOT_HANDLED).isEmpty()) action.guardRegistry.register(FlowGuards.notHandled(action.core.jdbc));
        if (action.guardRegistry.registered(FlowCallbackGuard.CODE).isEmpty()) {
            FlowCallbackGuard cb = new FlowCallbackGuard(action.core.jdbc);
            action.guardRegistry.register(cb);
            action.guardRegistry.attach(cb);
        }
        if (action.guardRegistry.registered(ApprovalEvidence.GUARD).isEmpty()) action.guardRegistry.register(evidence.guard());
    }

    // ———————————— 业务场景：借还单＋审批 ————————————

    /** 借还单场景：台账、状态机（提交/退回/再提交/撤回/审核/通过/驳回/归档）、表单（审核节点策略）、人员。 */
    public record Scene(CrudFixture.Loan loan, String order, OrgUnit company, OrgUnit dept, Party applicant, Party leader, Party auditor1,
                        Party auditor2, Party auditor3, Party admin, String formCode) {
    }

    public static final String AUDIT_PERM = "acme:loan:audit";

    public Scene scene() {
        CrudFixture.Loan loan = crud.loanLedger();
        String o = loan.order().code();
        OrgUnit company = action.core.org(PgTestSupport.uniq("公司"), com.lsmom.core.org.OrgUnitType.COMPANY, null);
        OrgUnit dept = action.core.org(PgTestSupport.uniq("设备部"), com.lsmom.core.org.OrgUnitType.DEPARTMENT, company.id());
        Party applicant = action.core.employee("申请人", dept);
        Party leader = action.core.employee("部门负责人", dept);
        action.core.orgUnits.setLeader(dept.id(), leader.id());
        Party companyLeader = action.core.employee("公司负责人", company);
        action.core.orgUnits.setLeader(company.id(), companyLeader.id());
        Party a1 = action.core.employee("审核一", dept);
        Party a2 = action.core.employee("审核二", dept);
        Party a3 = action.core.employee("审核三", dept);
        Party admin = action.core.employee("流程管理员", company);
        role("flow_admin", admin);

        var obj = (com.fasterxml.jackson.databind.node.ObjectNode) json("{\"type\":\"object\"}");
        List<StateMachineDefinition.Transition> ts = List.of(
            t(o + ".submit", "提交", List.of("DRAFT"), "IN_REVIEW", List.of()),
            t(o + ".audit", "审核", List.of("IN_REVIEW"), "IN_REVIEW", List.of()),
            t(o + ".return", "退回", List.of("IN_REVIEW"), "RETURNED", List.of()),
            t(o + ".resubmit", "再提交", List.of("RETURNED"), "IN_REVIEW", List.of()),
            t(o + ".withdraw", "撤回", List.of("IN_REVIEW"), "DRAFT", List.of(FlowGuards.NOT_HANDLED)),
            t(o + ".approve", "批准", List.of("IN_REVIEW"), "APPROVED", List.of()),
            t(o + ".archive", "确认归档", List.of("IN_REVIEW"), "ARCHIVED", List.of()),
            t(o + ".reject", "驳回", List.of("IN_REVIEW"), "REJECTED", List.of()),
            t(o + ".revise", "修改方案", List.of("APPROVED"), "APPROVED", List.of()),
            t(o + ".execute", "执行", List.of("APPROVED"), "DONE", List.of(ApprovalEvidence.GUARD)));
        action.machines.publish(new StateMachineDefinition(o + ".lifecycle", o, "借还单流程",
            List.of("DRAFT", "IN_REVIEW", "RETURNED", "APPROVED", "REJECTED", "ARCHIVED", "DONE"), "DRAFT", ts));
        // 测试用效果：修改方案写备注
        action.gateway.registerEffect(new TransitionEffect() {
            public String operation() {
                return o + ".revise";
            }

            public Map<String, Object> apply(com.lsmom.action.api.OperationContext ctx, StateSnapshot before) {
                return Map.of("remark", ctx.input().path("remark").asText());
            }
        });

        String formCode = loan.ns() + ".loanForm";
        ObjectNode f = (ObjectNode) json("""
            {"viewType":"FORM","formCode":"%s","version":1,"conceptCode":"%s","title":"借还单",
             "layout":[{"type":"field","key":"loanNo"},{"type":"field","key":"borrower"},{"type":"field","key":"loanDate"},
                       {"type":"field","key":"remark"},{"type":"field","key":"auditNote"}],
             "fields":[{"key":"loanNo","label":"单号","widget":"input","required":true},{"key":"borrower","label":"借用人","widget":"input","required":true},
                       {"key":"loanDate","label":"日期","widget":"date","required":true},{"key":"remark","label":"备注","widget":"textarea"},
                       {"key":"auditNote","label":"审核意见","widget":"textarea"}],
             "nodePolicies":{"deptAudit":{"readonly":["*"],"editable":["auditNote"],"required":["auditNote"]},
                            "initiator":{"readonly":["*"],"editable":["remark"]}}}"""
            .formatted(formCode, o));
        form.forms.saveDraft(f, 1L, "flow test");
        form.forms.publish(formCode, 1, 1L);
        return new Scene(loan, o, company, dept, applicant, leader, a1, a2, a3, admin, formCode);
    }

    private static StateMachineDefinition.Transition t(String op, String name, List<String> from, String to, List<String> guards) {
        return new StateMachineDefinition.Transition(op, name, from, to, guards, null, "C1", json("{\"type\":\"object\"}"), "DRAFT");
    }

    /** 建角色并授予主体。 */
    public void role(String roleKey, Party... parties) {
        var jdbc = action.core.jdbc;
        Long roleId = jdbc.query("select role_id from sys_role where role_key = ?", (rs, i) -> rs.getLong(1), roleKey).stream().findFirst()
            .orElseGet(() -> {
                long id = 900_000L + Math.abs(roleKey.hashCode() % 90_000);
                while (jdbc.queryForObject("select count(*) from sys_role where role_id = ?", Integer.class, id) > 0) id++;
                jdbc.update("""
                    insert into sys_role (role_id, tenant_id, role_name, role_key, role_sort, data_scope, status, del_flag, create_time)
                    values (?, '000000', ?, ?, 99, '4', '0', '0', now())""", id, roleKey, roleKey);
                return id;
            });
        for (Party p : parties) {
            jdbc.update("insert into sys_user_role (user_id, role_id) values (?, ?) on conflict do nothing", p.userId(), roleId);
        }
    }

    /** 新建一张借还单（草稿）并提交，返回业务 ID。 */
    public String submitLoan(Scene s, Party who, String loanNo) {
        String id = createLoan(s, who, loanNo);
        submit(s, who, id);
        return id;
    }

    public void submit(Scene s, Party who, String id) {
        action.on(s.order() + ".submit", actor(who), id, version(s, id), "{}");
    }

    /** 新建一张借还单（草稿），返回业务 ID。 */
    public String createLoan(Scene s, Party who, String loanNo) {
        ActorContext a = actor(who, com.lsmom.crud.GenericCrudService.permission(s.order(), "add"),
            com.lsmom.crud.GenericCrudService.permission(s.order(), "edit"), com.lsmom.crud.GenericCrudService.permission(s.order(), "list"));
        Map<String, Object> r = crud.crud.create(s.order(), json("""
            {"loanNo":"%s","borrower":"张三","loanDate":"2026-11-01","orgId":%d,"remark":"检修借用"}""".formatted(loanNo, s.dept().id())),
            a, null);
        return String.valueOf(r.get("id"));
    }

    public int version(Scene s, String id) {
        return action.stores.of(s.order()).load(id).version();
    }

    public String status(Scene s, String id) {
        return action.stores.of(s.order()).load(id).status();
    }

    public ActorContext actor(Party p, String... perms) {
        return new ActorContext(ActorContext.ActorType.HUMAN, p.userId(), p.id(), null, false, Set.of(perms),
            DataScope.unrestricted(), p.name());
    }

    /** 某业务对象当前的引擎待办。 */
    public List<Task> tasks(String procInstId) {
        return engine.getTaskService().createTaskQuery().processInstanceId(procInstId).orderByTaskCreateTime().asc().list();
    }

    public Task task(String procInstId, String node) {
        return engine.getTaskService().createTaskQuery().processInstanceId(procInstId).taskDefinitionKey(node).list().stream().findFirst()
            .orElseThrow(() -> new AssertionError("没有节点待办：" + node));
    }

    public FlowInstances.Instance instance(Scene s, String bizId) {
        return instances.ofBiz(s.order(), bizId).get(0);
    }

    public FlowDefinition flow(String jsonText) {
        return FlowDefinition.parse(jsonText);
    }

    /** 标准借还单审批：部门审核（部门负责人，填审核意见）→ 确认归档（发起人）。 */
    public FlowDefinition loanFlow(Scene s, String nodesJson) {
        String o = s.order();
        return flow("""
            {"code":"%s.loanApproval","name":"借还单审批","concept":"%s","startOn":"%s.submit","form":{"code":"%s","version":1},
             "callbacks":{"approved":"%s.archive","rejected":"%s.reject","returned":"%s.return","resubmitted":"%s.resubmit","withdrawn":"%s.withdraw"},
             "orgUnitId":%d,"nodes":%s}""".formatted(s.loan().ns(), o, o, s.formCode(), o, o, o, o, o, s.dept().id(), nodesJson));
    }

    public static final String STANDARD_NODES = """
        [{"key":"deptAudit","type":"AUDIT","name":"部门审核","assignee":{"strategy":"DEPT_LEADER","of":"INITIATOR"},"operation":"%s.audit"},
         {"key":"confirm","type":"CONFIRM","name":"确认归档","assignee":{"strategy":"INITIATOR"}}]""";

    public FlowDefinition standardFlow(Scene s) {
        return loanFlow(s, STANDARD_NODES.formatted(s.order()));
    }

    public void advance(long hours) {
        clock.setCurrentTime(new Date(clock.getCurrentTime().getTime() + hours * 3600_000L));
    }

    public static JsonNode j(String s) {
        return json(s);
    }
}
