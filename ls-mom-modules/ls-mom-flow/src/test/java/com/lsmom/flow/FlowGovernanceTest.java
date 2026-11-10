package com.lsmom.flow;

import com.lsmom.core.error.LsBizException;
import com.lsmom.flow.model.FlowDefinition;
import com.lsmom.flow.runtime.FlowInstances;
import com.lsmom.flow.runtime.FlowReconciler;
import com.lsmom.flow.runtime.FlowRegistry;
import com.lsmom.flow.runtime.FlowRuntime;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.lsmom.flow.FlowFixture.j;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 9.8 超时升级、9.9 版本冻结（FLOW-06）、9.10 批准证据、9.11 对账重建、10.3 发布校验。 */
class FlowGovernanceTest {

    private final FlowFixture f = new FlowFixture();
    private FlowFixture.Scene s;

    @BeforeEach
    void setUp() {
        s = f.scene();
    }

    @Test
    void 同一发起动作只能对应一条已发布流程_停用后可发布新流程() {
        FlowDefinition first = f.standardFlow(s);
        f.runtime.publish(first, 1L);
        FlowDefinition second = f.flow(first.toJson().toString().replace(".loanApproval\"", ".loanApprovalB\""));
        assertThat(f.registry.validate(second)).extracting(FlowRegistry.Issue::code).contains("FLOW_START_CONFLICT");
        assertThat(f.registry.retire(first.code())).isEqualTo(1);
        assertThat(f.registry.validate(second)).extracting(FlowRegistry.Issue::code).doesNotContain("FLOW_START_CONFLICT");
        f.runtime.publish(second, 1L);
        String id = f.submitLoan(s, s.applicant(), "L-RT1");
        assertThat(f.instance(s, id).flowCode()).isEqualTo(second.code());
    }

    @Test
    void 超时未办理_办理人收到提醒且上级负责人收到升级待办() {
        f.runtime.publish(f.loanFlow(s, """
            [{"key":"deptAudit","type":"AUDIT","name":"车间审核","assignee":{"strategy":"DEPT_LEADER","of":"INITIATOR"},"operation":"%s.audit",
              "timeout":{"hours":24,"action":"REMIND_ESCALATE"}}]""".formatted(s.order())), 1L);
        String id = f.submitLoan(s, s.applicant(), "L-3001");
        String pi = f.instance(s, id).procInstId();
        Task audit = f.task(pi, "deptAudit");
        assertThat(f.runtime.task(audit.getId()).dueAt()).isNotNull();
        f.advance(23);
        assertThat(executeDueTimers(pi)).isZero();
        f.advance(2);
        assertThat(executeDueTimers(pi)).isEqualTo(1);
        // 办理人（部门负责人）收到提醒；上级（公司负责人）收到升级待办并成为候选人
        assertThat(f.runtime.notices(s.leader().id())).anySatisfy(n -> assertThat(n.get("kind")).isEqualTo("REMIND"));
        Long superior = f.action.core.jdbc.queryForObject("select leader_party_id from core_org_unit where id = ?", Long.class, s.company().id());
        assertThat(f.runtime.notices(superior)).anySatisfy(n -> assertThat(n.get("kind")).isEqualTo("ESCALATE"));
        assertThat(f.runtime.todo(superior)).extracting(FlowRuntime.TaskView::kind).contains("ESCALATION", "TASK");
        // 上级可以直接办理
        f.runtime.complete(audit.getId(), FlowRuntime.Action.APPROVE, "代办", j("{\"auditNote\":\"上级代审\"}"),
            f.actor(f.action.core.parties.get(superior)));
        assertThat(f.status(s, id)).isEqualTo("ARCHIVED");
    }

    private int executeDueTimers(String pi) {
        var ms = f.engine.getManagementService();
        List<Job> due = ms.createTimerJobQuery().processInstanceId(pi).executable().list();
        for (Job j : due) {
            Job moved = ms.moveTimerToExecutableJob(j.getId());
            ms.executeJob(moved.getId());
        }
        return due.size();
    }

    @Test
    void 在途实例按旧版本继续_新实例使用新版本() {
        FlowDefinition v1 = f.standardFlow(s);
        f.runtime.publish(v1, 1L);
        String a = f.submitLoan(s, s.applicant(), "L-3101");
        String b = f.submitLoan(s, s.applicant(), "L-3102");
        // v2：部门审核后增加会签
        f.runtime.publish(f.loanFlow(s, """
            [{"key":"deptAudit","type":"AUDIT","name":"部门审核","assignee":{"strategy":"DEPT_LEADER","of":"INITIATOR"},"operation":"%s.audit"},
             {"key":"joint","type":"AUDIT","name":"会签","mode":"AND","assignee":{"strategy":"USER","value":[%d,%d]}},
             {"key":"confirm","type":"CONFIRM","name":"确认归档","assignee":{"strategy":"INITIATOR"}}]"""
            .formatted(s.order(), s.auditor1().id(), s.auditor2().id())), 1L);
        String c = f.submitLoan(s, s.applicant(), "L-3103");
        assertThat(f.instance(s, a).flowVersion()).isEqualTo(1);
        assertThat(f.instance(s, c).flowVersion()).isEqualTo(2);
        for (String id : List.of(a, b)) {
            String pi = f.instance(s, id).procInstId();
            f.runtime.complete(f.task(pi, "deptAudit").getId(), FlowRuntime.Action.APPROVE, null, j("{\"auditNote\":\"同意\"}"), f.actor(s.leader()));
            assertThat(f.tasks(pi)).extracting(Task::getTaskDefinitionKey).containsExactly("confirm");
            f.runtime.complete(f.task(pi, "confirm").getId(), FlowRuntime.Action.APPROVE, null, null, f.actor(s.applicant()));
            assertThat(f.status(s, id)).isEqualTo("ARCHIVED");
        }
        String pc = f.instance(s, c).procInstId();
        f.runtime.complete(f.task(pc, "deptAudit").getId(), FlowRuntime.Action.APPROVE, null, j("{\"auditNote\":\"同意\"}"), f.actor(s.leader()));
        assertThat(f.tasks(pc)).extracting(Task::getTaskDefinitionKey).containsOnly("joint");
    }

    @Test
    void 批准后数据被改须重新审批() {
        FlowDefinition d = f.loanFlow(s, """
            [{"key":"deptAudit","type":"AUDIT","name":"部门审核","assignee":{"strategy":"DEPT_LEADER","of":"INITIATOR"},"operation":"%s.audit"}]"""
            .formatted(s.order()));
        f.runtime.publish(new FlowDefinition(d.code(), null, d.name(), d.concept(), d.startOn(), d.form(),
            new FlowDefinition.Callbacks(s.order() + ".approve", s.order() + ".reject", null, null, null), d.orgUnitId(), null, d.nodes()), 1L);
        String id = f.submitLoan(s, s.applicant(), "L-3201");
        String pi = f.instance(s, id).procInstId();
        f.runtime.complete(f.task(pi, "deptAudit").getId(), FlowRuntime.Action.APPROVE, "同意方案", j("{\"auditNote\":\"同意\"}"), f.actor(s.leader()));
        assertThat(f.status(s, id)).isEqualTo("APPROVED");
        var ev = f.evidence.latest(s.order(), id).orElseThrow();
        assertThat(ev.approvers()).containsExactly(s.leader().id());
        assertThat(ev.procInstId()).isEqualTo(pi);
        assertThat(ev.inputDigest()).hasSize(64);

        // 申请人修改方案内容后再执行 → 守卫判定批准失效
        f.action.on(s.order() + ".revise", f.actor(s.applicant()), id, f.version(s, id), "{\"remark\":\"换了方案\"}");
        assertThatThrownBy(() -> f.action.on(s.order() + ".execute", f.actor(s.applicant()), id, f.version(s, id), "{}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getDetails()).containsEntry("reason", "APPROVAL_INVALID"));

        // 未修改的另一张单据可以执行
        String id2 = f.submitLoan(s, s.applicant(), "L-3202");
        String pi2 = f.instance(s, id2).procInstId();
        f.runtime.complete(f.task(pi2, "deptAudit").getId(), FlowRuntime.Action.APPROVE, null, j("{\"auditNote\":\"同意\"}"), f.actor(s.leader()));
        f.action.on(s.order() + ".execute", f.actor(s.applicant()), id2, f.version(s, id2), "{}");
        assertThat(f.status(s, id2)).isEqualTo("DONE");
    }

    @Test
    void 人为删除待办后对账发现并重建() {
        f.runtime.publish(f.standardFlow(s), 1L);
        String id = f.submitLoan(s, s.applicant(), "L-3301");
        FlowInstances.Instance inst = f.instance(s, id);
        Task audit = f.task(inst.procInstId(), "deptAudit");
        f.action.core.jdbc.update("delete from biz_task where task_id = ?", audit.getId());
        assertThat(f.runtime.todo(s.leader().id())).isEmpty();
        FlowReconciler.Report r = f.reconciler.check();
        assertThat(r.diffs()).anySatisfy(d -> {
            assertThat(d.type()).isEqualTo(FlowReconciler.DiffType.TASK_MISSING);
            assertThat(d.taskId()).isEqualTo(audit.getId());
        });
        FlowReconciler.Report fixed = f.reconciler.repair();
        assertThat(fixed.repaired()).isGreaterThanOrEqualTo(1);
        assertThat(f.runtime.todo(s.leader().id())).extracting(FlowRuntime.TaskView::taskId).contains(audit.getId());
        assertThat(f.reconciler.check().diffs()).noneMatch(d -> audit.getId().equals(d.taskId()));
        // 读模型多出的待办被关闭
        f.action.core.jdbc.update("""
            insert into biz_task (task_id, kind, flow_instance_id, proc_inst_id, biz_type, biz_id, node_key, status)
            values ('ghost-' || ?, 'TASK', ?, ?, ?, ?, 'deptAudit', 'TODO')""", inst.id(), inst.id(), inst.procInstId(), s.order(), id);
        f.reconciler.repair();
        assertThat(f.action.count("select count(*) from biz_task where task_id = ? and status = 'CANCELED'", "ghost-" + inst.id())).isEqualTo(1);
    }

    @Test
    void 发布校验_缺少回调_角色无成员_节点策略引用不存在字段() {
        FlowDefinition ok = f.standardFlow(s);
        FlowDefinition noCallback = new FlowDefinition(ok.code(), null, ok.name(), ok.concept(), ok.startOn(), ok.form(),
            new FlowDefinition.Callbacks(null, s.order() + ".reject", null, null, null), ok.orgUnitId(), null, ok.nodes());
        assertThat(f.registry.validate(noCallback)).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo("CALLBACK_REQUIRED");
            assertThat(i.path()).isEqualTo("/callbacks/approved");
        });
        assertThatThrownBy(() -> f.runtime.publish(noCallback, 1L)).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.getCode()).isEqualTo("FLOW_INVALID"));

        f.role("empty_role_" + s.loan().ns().replace('.', '_'));
        FlowDefinition roleless = f.loanFlow(s, """
            [{"key":"workshopAudit","type":"AUDIT","name":"车间审核","assignee":{"strategy":"ROLE","value":"empty_role_%s","of":"BIZ_ORG"}}]"""
            .formatted(s.loan().ns().replace('.', '_')));
        assertThat(f.registry.validate(roleless)).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo("ASSIGNEE_UNRESOLVED");
            assertThat(i.message()).contains("车间审核");
        });

        FlowDefinition badPolicy = f.loanFlow(s, """
            [{"key":"deptAudit","type":"AUDIT","name":"部门审核","assignee":{"strategy":"DEPT_LEADER","of":"INITIATOR"},"operation":"%s.audit",
              "fieldPolicy":{"readonly":["*"],"editable":["auditNote"],"required":["severity"]}}]""".formatted(s.order()));
        assertThat(f.registry.validate(badPolicy)).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo("POLICY_FIELD_INVALID");
            assertThat(i.message()).contains("severity");
        });

        FlowDefinition draftOp = f.loanFlow(s, """
            [{"key":"svc","type":"SERVICE","name":"自动处理","operation":"%s.nonexistent"},
             {"key":"deptAudit","type":"AUDIT","name":"部门审核","assignee":{"strategy":"DEPT_LEADER","of":"INITIATOR"},"operation":"%s.audit"}]"""
            .formatted(s.order(), s.order()));
        assertThat(f.registry.validate(draftOp)).anySatisfy(i -> assertThat(i.code()).isEqualTo("OPERATION_NOT_PUBLISHED"));
        assertThat(FlowRegistry.errors(f.registry.validate(ok))).isEmpty();
        // 警告不阻止发布：未设超时；节点没有字段策略；按角色分派未限定业务组织
        FlowDefinition loose = f.loanFlow(s, """
            [{"key":"freeAudit","type":"AUDIT","name":"自由审核","assignee":{"strategy":"ROLE","value":"workshop_leader"}}]""");
        assertThat(f.registry.validate(loose)).filteredOn(i -> !i.error()).extracting(FlowRegistry.Issue::code, FlowRegistry.Issue::path)
            .contains(org.assertj.core.groups.Tuple.tuple("TIMEOUT_MISSING", "/nodes/0/timeout"),
                org.assertj.core.groups.Tuple.tuple("POLICY_MISSING", "/nodes/0/fieldPolicy"),
                org.assertj.core.groups.Tuple.tuple("ASSIGNEE_SCOPE_WIDE", "/nodes/0/assignee"));
        FlowRegistry.Version v = f.runtime.publish(ok, 1L);
        // 策略来自表单 nodePolicies，不是节点差异，不登记 FLOW_NODE 使用方
        assertThat(f.form.usages.activeUsagesOf(s.order(), "auditNote")).noneMatch(u -> u.userType().equals("FLOW_NODE"));
        assertThat(v.version()).isEqualTo(1);
    }

    @Test
    void 节点字段策略差异登记为字段使用方_表单删除该字段时兼容性检查拦截() {
        FlowDefinition d = f.loanFlow(s, """
            [{"key":"deptAudit","type":"AUDIT","name":"部门审核","assignee":{"strategy":"DEPT_LEADER","of":"INITIATOR"},"operation":"%s.audit",
              "fieldPolicy":{"readonly":["*"],"editable":["remark"],"required":["remark"]}}]""".formatted(s.order()));
        f.runtime.publish(d, 1L);
        assertThat(f.form.usages.activeUsagesOf(s.order(), "remark")).anySatisfy(u -> assertThat(u.userType()).isEqualTo("FLOW_NODE"));
        String id = f.submitLoan(s, s.applicant(), "L-3401");
        String pi = f.instance(s, id).procInstId();
        assertThatThrownBy(() -> f.runtime.complete(f.task(pi, "deptAudit").getId(), FlowRuntime.Action.APPROVE, null,
            j("{\"auditNote\":\"x\",\"remark\":\"y\"}"), f.actor(s.leader())))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("FIELD_READONLY"));
        f.runtime.complete(f.task(pi, "deptAudit").getId(), FlowRuntime.Action.APPROVE, null, j("{\"remark\":\"审核补充\"}"), f.actor(s.leader()));
        assertThat(f.action.stores.of(s.order()).attributes(id).get("remark")).isEqualTo("审核补充");
    }
}
