package com.lsmom.flow;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.timeline.TimelineService;
import com.lsmom.core.error.LsBizException;
import com.lsmom.flow.runtime.FlowInstances;
import com.lsmom.flow.runtime.FlowRuntime;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.lsmom.flow.FlowFixture.j;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FLOW-01 普通台账闭环、9.2 待办读模型、流程变量最小化、业务绑定查询、FLOW-03 撤回判定、转办、催办、抄送。 */
class FlowLifecycleTest {

    private final FlowFixture f = new FlowFixture();
    private FlowFixture.Scene s;

    @BeforeEach
    void setUp() {
        s = f.scene();
        f.runtime.publish(f.standardFlow(s), 1L);
    }

    @Test
    void 提交_部门审核_退回修改_再提交_确认归档() {
        String id = f.submitLoan(s, s.applicant(), "L-1001");
        FlowInstances.Instance inst = f.instance(s, id);
        assertThat(inst.syncStatus()).isEqualTo("SYNCED");
        assertThat(f.status(s, id)).isEqualTo("IN_REVIEW");

        // 部门负责人的待办出现在读模型中
        Task audit = f.task(inst.procInstId(), "deptAudit");
        assertThat(f.runtime.todo(s.leader().id())).extracting(FlowRuntime.TaskView::taskId).contains(audit.getId());
        assertThat(f.runtime.todo(s.applicant().id())).isEmpty();

        // 退回发起人
        ActorContext leader = f.actor(s.leader());
        f.runtime.returnTask(audit.getId(), null, "借用数量不清楚，请补充备注", leader);
        assertThat(f.status(s, id)).isEqualTo("RETURNED");
        assertThat(f.runtime.task(audit.getId()).outcome()).isEqualTo("RETURNED");
        Task back = f.task(inst.procInstId(), "initiator");
        assertThat(f.runtime.todo(s.applicant().id())).extracting(FlowRuntime.TaskView::taskId).contains(back.getId());

        // 发起人修改后再提交，流程回到部门审核
        f.runtime.complete(back.getId(), FlowRuntime.Action.RESUBMIT, "已补充", null, f.actor(s.applicant()));
        assertThat(f.status(s, id)).isEqualTo("IN_REVIEW");
        Task audit2 = f.task(inst.procInstId(), "deptAudit");

        // 审核节点必须填审核意见（节点策略 required）
        assertThatThrownBy(() -> f.runtime.complete(audit2.getId(), FlowRuntime.Action.APPROVE, "同意", j("{}"), leader))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED"));
        f.runtime.complete(audit2.getId(), FlowRuntime.Action.APPROVE, "同意", j("{\"auditNote\":\"同意借用\"}"), leader);
        assertThat(f.action.stores.of(s.order()).attributes(id).get("auditNote")).isEqualTo("同意借用");
        assertThat(f.runtime.task(audit2.getId()).status()).isEqualTo("DONE");

        // 发起人确认归档 → 流程结束 → onApproved（archive）推进业务状态
        Task confirm = f.task(inst.procInstId(), "confirm");
        f.runtime.complete(confirm.getId(), FlowRuntime.Action.APPROVE, "确认", null, f.actor(s.applicant()));
        assertThat(f.status(s, id)).isEqualTo("ARCHIVED");
        assertThat(f.instance(s, id).status()).isEqualTo("APPROVED");
        assertThat(f.callbacks.ofProcInst(inst.procInstId())).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo("DONE");
            assertThat(c.key()).isEqualTo(inst.procInstId() + ":" + confirm.getId() + ":approved");
        });

        // 时间轴完整：提交、发起、退回（含原因）、再提交、审核通过、确认、归档
        List<TimelineService.Entry> tl = new TimelineService(f.action.core.jdbc).of(s.order(), id);
        List<String> types = tl.stream().map(TimelineService.Entry::type).toList();
        assertThat(types).containsSubsequence(s.order() + ".submit", "ls.flow.started", s.order() + ".return", "ls.flow.task.returned",
            s.order() + ".resubmit", "ls.flow.task.resubmitted", s.order() + ".audit", "ls.flow.task.approved", "ls.flow.task.approved",
            "ls.flow.approved", s.order() + ".archive");
        assertThat(tl.stream().filter(e -> e.type().equals("ls.flow.task.returned")).findFirst().orElseThrow().payload())
            .containsEntry("comment", "借用数量不清楚，请补充备注");
    }

    @Test
    void 流程变量只含路由信息且可按业务对象查询流程() {
        String id = f.submitLoan(s, s.applicant(), "L-1002");
        FlowInstances.Instance inst = f.instance(s, id);
        Map<String, Object> vars = f.engine.getRuntimeService().getVariables(inst.procInstId());
        assertThat(vars.keySet()).allMatch(k -> List.of("flowInstanceId", "bizType", "bizId", "initiator", "outcome", "lastTaskId").contains(k)
            || k.startsWith("cand_") || k.startsWith("route_") || k.startsWith("exc_"));
        assertThat(vars).doesNotContainKeys("loanNo", "borrower", "remark", "loanLine");
        List<Map<String, Object>> bound = f.runtime.instancesOf(s.order(), id);
        assertThat(bound).singleElement().satisfies(m -> {
            FlowInstances.Instance i = (FlowInstances.Instance) m.get("instance");
            assertThat(i.flowVersion()).isEqualTo(1);
            assertThat(i.formVersion()).isEqualTo(1);
            assertThat(i.procInstId()).isEqualTo(inst.procInstId());
            assertThat(m.get("currentNodes")).isEqualTo(List.of("deptAudit"));
        });
    }

    @Test
    void 审核前可撤回_审核后撤回被业务Operation拒绝() {
        String id = f.submitLoan(s, s.applicant(), "L-1003");
        long inst = f.instance(s, id).id();
        f.runtime.withdraw(inst, "填错了", f.actor(s.applicant()));
        assertThat(f.status(s, id)).isEqualTo("DRAFT");
        assertThat(f.instances.get(inst).status()).isEqualTo("WITHDRAWN");
        assertThat(f.runtime.tasksOf(f.instances.get(inst).procInstId())).filteredOn(t -> t.nodeKey().equals("deptAudit"))
            .allSatisfy(t -> assertThat(t.status()).isEqualTo("CANCELED"));

        // 再次提交，部门审核通过后（下一节点已办理）撤回 → 业务 Operation 守卫拒绝
        f.action.on(s.order() + ".submit", f.actor(s.applicant()), id, f.version(s, id), "{}");
        FlowInstances.Instance second = f.instances.running(s.order(), id).orElseThrow();
        Task audit = f.task(second.procInstId(), "deptAudit");
        f.runtime.complete(audit.getId(), FlowRuntime.Action.APPROVE, "同意", j("{\"auditNote\":\"可以\"}"), f.actor(s.leader()));
        assertThatThrownBy(() -> f.runtime.withdraw(second.id(), "想撤回", f.actor(s.applicant())))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("GUARD_FAILED");
                assertThat(e.getDetails()).containsEntry("reason", "FLOW_ALREADY_HANDLED").containsEntry("guard", "ls.flow.notHandled");
            });
        assertThat(f.instances.get(second.id()).status()).isEqualTo("RUNNING");
        assertThat(f.status(s, id)).isEqualTo("IN_REVIEW");
    }

    @Test
    void 转办_催办限频_抄送() {
        String id = f.submitLoan(s, s.applicant(), "L-1004");
        FlowInstances.Instance inst = f.instance(s, id);
        Task audit = f.task(inst.procInstId(), "deptAudit");
        // 转办给审核一
        f.runtime.transfer(audit.getId(), s.auditor1().id(), "请代为审核", f.actor(s.leader()));
        assertThat(f.runtime.todo(s.auditor1().id())).extracting(FlowRuntime.TaskView::taskId).contains(audit.getId());
        assertThat(f.runtime.todo(s.leader().id())).isEmpty();
        assertThatThrownBy(() -> f.runtime.complete(audit.getId(), FlowRuntime.Action.APPROVE, "x", j("{\"auditNote\":\"x\"}"), f.actor(s.leader())))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("TASK_NOT_ASSIGNED"));
        // 催办：30 分钟内只能一次
        f.runtime.urge(audit.getId(), f.actor(s.applicant()));
        assertThatThrownBy(() -> f.runtime.urge(audit.getId(), f.actor(s.applicant())))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("URGE_TOO_FREQUENT"));
        f.advance(1);
        f.runtime.urge(audit.getId(), f.actor(s.applicant()));
        assertThat(f.runtime.notices(s.auditor1().id())).extracting(m -> m.get("kind")).contains("TRANSFER", "URGE");
        // 抄送
        f.runtime.ccManual(inst.id(), List.of(s.auditor2().id()), "请知悉", f.actor(s.applicant()));
        assertThat(f.runtime.todo(s.auditor2().id())).singleElement().satisfies(t -> assertThat(t.kind()).isEqualTo("CC"));
        f.runtime.markRead(f.runtime.todo(s.auditor2().id()).get(0).id(), f.actor(s.auditor2()));
        assertThat(f.runtime.todo(s.auditor2().id())).isEmpty();
    }
}
