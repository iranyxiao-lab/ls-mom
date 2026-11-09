package com.lsmom.flow;

import com.lsmom.core.error.LsBizException;
import com.lsmom.flow.runtime.FlowCallbacks;
import com.lsmom.flow.runtime.FlowInstances;
import com.lsmom.flow.runtime.FlowRuntime;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.lsmom.flow.FlowFixture.j;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FLOW-02 或签/会签、条件分支与并行、FLOW-05 节点字段与办理人双重校验、FLOW-07 幂等回调与重试、9.4 无人可办异常待办。 */
class FlowCollaborationTest {

    private final FlowFixture f = new FlowFixture();
    private FlowFixture.Scene s;

    @BeforeEach
    void setUp() {
        s = f.scene();
    }

    private String users(long... ids) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) b.append(i == 0 ? "" : ",").append(ids[i]);
        return b.append("]").toString();
    }

    @Test
    void 会签三人中两人通过节点未完成_或签任一人通过即完成且其余待办关闭() {
        f.runtime.publish(f.loanFlow(s, """
            [{"key":"joint","type":"AUDIT","name":"会签","mode":"AND","assignee":{"strategy":"USER","value":%s}},
             {"key":"anyOne","type":"AUDIT","name":"或签","mode":"OR","assignee":{"strategy":"USER","value":%s}}]"""
            .formatted(users(s.auditor1().id(), s.auditor2().id(), s.auditor3().id()), users(s.auditor1().id(), s.auditor2().id(), s.auditor3().id()))), 1L);
        String id = f.submitLoan(s, s.applicant(), "L-2001");
        String pi = f.instance(s, id).procInstId();
        List<Task> joint = f.engine.getTaskService().createTaskQuery().processInstanceId(pi).taskDefinitionKey("joint").list();
        assertThat(joint).hasSize(3);
        approveAs(joint, s.auditor1().id());
        approveAs(f.engine.getTaskService().createTaskQuery().processInstanceId(pi).taskDefinitionKey("joint").list(), s.auditor2().id());
        assertThat(f.engine.getTaskService().createTaskQuery().processInstanceId(pi).taskDefinitionKey("joint").count()).isEqualTo(1);
        assertThat(f.engine.getTaskService().createTaskQuery().processInstanceId(pi).taskDefinitionKey("anyOne").count()).isZero();
        approveAs(f.engine.getTaskService().createTaskQuery().processInstanceId(pi).taskDefinitionKey("joint").list(), s.auditor3().id());

        List<Task> any = f.engine.getTaskService().createTaskQuery().processInstanceId(pi).taskDefinitionKey("anyOne").list();
        assertThat(any).hasSize(3);
        approveAs(any, s.auditor2().id());
        assertThat(f.engine.getTaskService().createTaskQuery().processInstanceId(pi).count()).isZero();
        assertThat(f.runtime.tasksOf(pi)).filteredOn(t -> t.nodeKey().equals("anyOne")).extracting(FlowRuntime.TaskView::status)
            .containsExactlyInAnyOrder("DONE", "CANCELED", "CANCELED");
        assertThat(f.status(s, id)).isEqualTo("ARCHIVED");
    }

    @Test
    void 会签中任一人驳回则流程驳回() {
        f.runtime.publish(f.loanFlow(s, """
            [{"key":"joint","type":"AUDIT","name":"会签","mode":"AND","assignee":{"strategy":"USER","value":%s}}]"""
            .formatted(users(s.auditor1().id(), s.auditor2().id()))), 1L);
        String id = f.submitLoan(s, s.applicant(), "L-2002");
        String pi = f.instance(s, id).procInstId();
        Task t = f.engine.getTaskService().createTaskQuery().processInstanceId(pi).taskAssignee(String.valueOf(s.auditor2().id())).singleResult();
        f.runtime.complete(t.getId(), FlowRuntime.Action.REJECT, "不同意", null, f.actor(s.auditor2()));
        assertThat(f.status(s, id)).isEqualTo("REJECTED");
        assertThat(f.instance(s, id).status()).isEqualTo("REJECTED");
        assertThat(f.runtime.tasksOf(pi)).filteredOn(x -> x.nodeKey().equals("joint")).extracting(FlowRuntime.TaskView::status)
            .containsExactlyInAnyOrder("DONE", "CANCELED");
    }

    @Test
    void 条件分支按业务字段路由且并行分支全部完成后汇合() {
        f.runtime.publish(f.loanFlow(s, """
            [{"key":"route","type":"CONDITION","name":"是否其他用途","branches":[
                {"name":"其他","when":"has($model.purpose) && $model.purpose == 'OTHER'","nodes":[{"key":"extra","type":"AUDIT","name":"加审","assignee":{"strategy":"USER","value":[%d]}}]},
                {"name":"普通","nodes":[]}]},
             {"key":"par","type":"PARALLEL","name":"并行会审","branches":[
                {"name":"甲","nodes":[{"key":"pa","type":"AUDIT","name":"甲审","assignee":{"strategy":"USER","value":[%d]}}]},
                {"name":"乙","nodes":[{"key":"pb","type":"AUDIT","name":"乙审","assignee":{"strategy":"USER","value":[%d]}}]}]}]"""
            .formatted(s.auditor3().id(), s.auditor1().id(), s.auditor2().id())), 1L);
        String id = f.submitLoan(s, s.applicant(), "L-2003");
        String pi = f.instance(s, id).procInstId();
        // purpose 为空 → 走默认分支，直接到并行
        assertThat(f.tasks(pi)).extracting(Task::getTaskDefinitionKey).containsExactlyInAnyOrder("pa", "pb");
        f.runtime.complete(f.task(pi, "pa").getId(), FlowRuntime.Action.APPROVE, null, null, f.actor(s.auditor1()));
        assertThat(f.status(s, id)).isEqualTo("IN_REVIEW");
        f.runtime.complete(f.task(pi, "pb").getId(), FlowRuntime.Action.APPROVE, null, null, f.actor(s.auditor2()));
        assertThat(f.status(s, id)).isEqualTo("ARCHIVED");

        // purpose = OTHER → 先加审，再并行
        String id2 = f.createLoan(s, s.applicant(), "L-2004");
        f.action.core.jdbc.update("update " + f.crud.crud.model(s.order()).table() + " set purpose = 'OTHER' where id = ?", Long.parseLong(id2));
        f.submit(s, s.applicant(), id2);
        String pi2 = f.instance(s, id2).procInstId();
        assertThat(f.tasks(pi2)).extracting(Task::getTaskDefinitionKey).containsExactly("extra");
        f.runtime.complete(f.task(pi2, "extra").getId(), FlowRuntime.Action.APPROVE, null, null, f.actor(s.auditor3()));
        assertThat(f.tasks(pi2)).extracting(Task::getTaskDefinitionKey).containsExactlyInAnyOrder("pa", "pb");
    }

    @Test
    void 节点只读字段被拒并审计_非办理人办理被拒() {
        f.runtime.publish(f.standardFlow(s), 1L);
        String id = f.submitLoan(s, s.applicant(), "L-2005");
        Task audit = f.task(f.instance(s, id).procInstId(), "deptAudit");
        // 审核人通过待办修改只读字段 remark
        assertThatThrownBy(() -> f.runtime.complete(audit.getId(), FlowRuntime.Action.APPROVE, "改一下",
            j("{\"auditNote\":\"同意\",\"remark\":\"被审核人改掉\"}"), f.actor(s.leader())))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("FIELD_READONLY"));
        // 审核人绕过待办直接调用 API 修改字段
        assertThatThrownBy(() -> f.action.on(s.order() + ".audit", f.actor(s.leader()), id, f.version(s, id), "{\"remark\":\"直接改\"}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("FIELD_READONLY"));
        assertThat(f.action.stores.of(s.order()).attributes(id).get("remark")).isEqualTo("检修借用");
        assertThat(f.action.count("select count(*) from op_audit where op_code = ? and target_id = ? and result = 'FAILED' and error_code = 'FIELD_READONLY'",
            s.order() + ".audit", id)).isEqualTo(2);
        // 非候选人办理
        assertThatThrownBy(() -> f.runtime.complete(audit.getId(), FlowRuntime.Action.APPROVE, "x", j("{\"auditNote\":\"x\"}"), f.actor(s.auditor1())))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("TASK_NOT_ASSIGNED"));
        assertThat(f.action.count("select count(*) from op_audit where op_code = 'ls.flow.task.approve' and target_id = ? and error_code = 'TASK_NOT_ASSIGNED'",
            id)).isEqualTo(1);
        assertThat(f.status(s, id)).isEqualTo("IN_REVIEW");
    }

    @Test
    void 重复回调只迁移一次_回调失败进入重试() {
        f.runtime.publish(f.loanFlow(s, """
            [{"key":"deptReview","type":"AUDIT","name":"部门审核","assignee":{"strategy":"DEPT_LEADER","of":"INITIATOR"}}]"""), 1L);
        String id = f.submitLoan(s, s.applicant(), "L-2006");
        FlowInstances.Instance inst = f.instance(s, id);
        Task audit = f.task(inst.procInstId(), "deptReview");
        f.runtime.complete(audit.getId(), FlowRuntime.Action.APPROVE, "同意", null, f.actor(s.leader()));
        assertThat(f.status(s, id)).isEqualTo("ARCHIVED");
        int version = f.version(s, id);
        String key = FlowCallbacks.key(inst.procInstId(), audit.getId(), "approved");
        // 同一完成事件再投递两次：登记被幂等键忽略，执行回放
        assertThat(f.callbacks.enqueue(key, inst.procInstId(), audit.getId(), "approved", s.order() + ".archive", s.order(), id)).isFalse();
        assertThat(f.callbacks.dispatch(key)).isTrue();
        f.action.core.jdbc.update("update biz_flow_callback_log set status = 'PENDING' where idempotency_key = ?", key);
        assertThat(f.callbacks.dispatch(key)).isTrue();
        assertThat(f.version(s, id)).isEqualTo(version);
        assertThat(f.action.count("select count(*) from core_domain_event where type = ? and subject_id = ?", s.order() + ".archive", id)).isEqualTo(1);

        // 回调失败（业务状态被人为改走）→ PENDING_RETRY；恢复后重试成功
        String id2 = f.submitLoan(s, s.applicant(), "L-2007");
        FlowInstances.Instance inst2 = f.instance(s, id2);
        String table = f.crud.crud.model(s.order()).table();
        Task audit2 = f.task(inst2.procInstId(), "deptReview");
        f.action.core.jdbc.update("update " + table + " set status = 'DRAFT' where id = ?", Long.parseLong(id2));
        f.runtime.complete(audit2.getId(), FlowRuntime.Action.APPROVE, "同意", null, f.actor(s.leader()));
        FlowCallbacks.Callback c = f.callbacks.ofProcInst(inst2.procInstId()).get(0);
        assertThat(c.status()).isEqualTo("PENDING_RETRY");
        assertThat(c.lastError()).contains("INVALID_TRANSITION");
        assertThat(f.reconciler.check().diffs()).anySatisfy(d -> assertThat(d.procInstId()).isEqualTo(inst2.procInstId()));
        f.action.core.jdbc.update("update " + table + " set status = 'IN_REVIEW' where id = ?", Long.parseLong(id2));
        f.action.core.jdbc.update("update biz_flow_callback_log set next_retry_at = null where id = ?", c.id());
        f.runtime.retryPending();
        assertThat(f.status(s, id2)).isEqualTo("ARCHIVED");
        assertThat(f.callbacks.ofProcInst(inst2.procInstId()).get(0).status()).isEqualTo("DONE");
    }

    @Test
    void 流程启动失败记录待重试并可重试成功() {
        f.runtime.publish(f.standardFlow(s), 1L);
        f.runtime.setStartInterceptor(i -> {
            throw new IllegalStateException("模拟引擎不可用");
        });
        String id = f.submitLoan(s, s.applicant(), "L-2008");
        assertThat(f.status(s, id)).isEqualTo("IN_REVIEW");
        FlowInstances.Instance inst = f.instance(s, id);
        assertThat(inst.syncStatus()).isEqualTo("PENDING_RETRY");
        assertThat(inst.lastError()).contains("模拟引擎不可用");
        f.runtime.setStartInterceptor(null);
        f.action.core.jdbc.update("update biz_flow_instance set next_retry_at = null where id = ?", inst.id());
        f.runtime.retryPending();
        FlowInstances.Instance after = f.instances.get(inst.id());
        assertThat(after.syncStatus()).isEqualTo("SYNCED");
        assertThat(f.tasks(after.procInstId())).extracting(Task::getTaskDefinitionKey).containsExactly("deptAudit");
    }

    @Test
    void 候选人全部离职时生成异常待办给流程管理员() {
        f.runtime.publish(f.loanFlow(s, """
            [{"key":"review","type":"AUDIT","name":"部门审核","assignee":{"strategy":"USER","value":[%d,%d]}}]"""
            .formatted(s.auditor1().id(), s.auditor2().id())), 1L);
        f.action.core.jdbc.update("update sys_user set status = '1' where user_id in (?, ?)", s.auditor1().userId(), s.auditor2().userId());
        String id = f.submitLoan(s, s.applicant(), "L-2009");
        String pi = f.instance(s, id).procInstId();
        FlowRuntime.TaskView t = f.runtime.tasksOf(pi).stream().filter(x -> x.nodeKey().equals("review")).findFirst().orElseThrow();
        assertThat(t.kind()).isEqualTo("EXCEPTION");
        assertThat(t.exceptionReason()).contains("离职");
        assertThat(t.candidates()).contains(s.admin().id()).doesNotContain(s.auditor1().id(), s.auditor2().id());
        assertThat(f.runtime.notices(s.admin().id())).anySatisfy(n -> assertThat(n.get("kind")).isEqualTo("EXCEPTION"));
        // 管理员可以处理（例如转办给有效人员）
        f.runtime.transfer(t.taskId(), s.auditor3().id(), "改派", f.actor(s.admin()));
        f.runtime.complete(t.taskId(), FlowRuntime.Action.APPROVE, "同意", null, f.actor(s.auditor3()));
        assertThat(f.status(s, id)).isEqualTo("ARCHIVED");
    }

    private void approveAs(List<Task> tasks, long party) {
        Task t = tasks.stream().filter(x -> String.valueOf(party).equals(x.getAssignee())).findFirst().orElseThrow();
        f.runtime.complete(t.getId(), FlowRuntime.Action.APPROVE, "同意", null, f.actor(f.action.core.parties.get(party)));
    }
}
