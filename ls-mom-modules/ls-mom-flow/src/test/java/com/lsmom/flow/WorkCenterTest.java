package com.lsmom.flow;

import com.lsmom.action.timeline.TimelineService;
import com.lsmom.core.error.LsBizException;
import com.lsmom.flow.runtime.FlowRuntime;
import com.lsmom.flow.runtime.WorkCenter;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 11.3 后端：待办排序与标识、已读不等于完成、转办留痕与委托记录、催办限频提示、已办/发起/抄送、办理上下文与版本快照。 */
class WorkCenterTest {

    private final FlowFixture f = new FlowFixture();
    private final WorkCenter center = new WorkCenter(f.action.core.jdbc, f.runtime, f.registry, f.form.forms, f.action.concepts, f.crud.crud,
        f.action.gateway, new TimelineService(f.action.core.jdbc), f.action.core.clock);
    private FlowFixture.Scene s;

    @BeforeEach
    void setUp() {
        s = f.scene();
        f.runtime.publish(f.loanFlow(s, """
            [{"key":"deptAudit","type":"AUDIT","name":"部门审核","assignee":{"strategy":"USER","value":[%d]},"policy":"deptAudit","operation":"%s.audit"},
             {"key":"confirm","type":"CONFIRM","name":"确认归档","assignee":{"strategy":"INITIATOR"}}]""".formatted(s.auditor1().id(), s.order())), 1L);
    }

    private void due(String procInstId, LocalDateTime at) {
        f.action.core.jdbc.update("update biz_task set due_at = ? where proc_inst_id = ? and status = 'TODO'", at, procInstId);
    }

    @Test
    void 待办超时与即将到期排在前列并有标识() {
        String a = f.submitLoan(s, s.applicant(), "W-1");
        String b = f.submitLoan(s, s.applicant(), "W-2");
        String c = f.submitLoan(s, s.applicant(), "W-3");
        LocalDateTime now = LocalDateTime.now();
        due(f.instance(s, a).procInstId(), now.plusDays(5));
        due(f.instance(s, b).procInstId(), now.minusHours(2));
        due(f.instance(s, c).procInstId(), now.plusHours(3));
        List<WorkCenter.Item> todo = center.todo(s.auditor1().id(), WorkCenter.Filter.none());
        assertThat(todo).extracting(i -> i.task().bizId()).containsExactly(b, c, a);
        assertThat(todo).extracting(WorkCenter.Item::urgency)
            .containsExactly(WorkCenter.Urgency.OVERDUE, WorkCenter.Urgency.DUE_SOON, WorkCenter.Urgency.NORMAL);
        assertThat(todo.get(0).initiatorName()).isEqualTo(s.applicant().name());
        assertThat(todo.get(0).bizTitle()).isEqualTo("W-2");
        // 筛选：紧急程度、业务类型
        assertThat(center.todo(s.auditor1().id(), new WorkCenter.Filter(null, WorkCenter.Urgency.OVERDUE, null))).extracting(i -> i.task().bizId())
            .containsExactly(b);
        assertThat(center.todo(s.auditor1().id(), new WorkCenter.Filter("acme.none.X", null, null))).isEmpty();
        assertThat(center.counts(s.auditor1().id())).containsEntry("todo", 3L).containsEntry("overdue", 1L);
    }

    @Test
    void 已读不等于完成_待办只在办理后消失() {
        String id = f.submitLoan(s, s.applicant(), "W-10");
        String pi = f.instance(s, id).procInstId();
        Task t = f.task(pi, "deptAudit");
        f.runtime.urge(t.getId(), f.actor(s.applicant()));
        List<Map<String, Object>> msgs = center.messages(s.auditor1().id(), true);
        assertThat(msgs).anySatisfy(m -> assertThat(m.get("kind")).isEqualTo("URGE"));
        center.readMessages(s.auditor1().id(), msgs.stream().map(m -> ((Number) m.get("id")).longValue()).toList());
        assertThat(center.messages(s.auditor1().id(), true)).isEmpty();
        assertThat(center.todo(s.auditor1().id(), WorkCenter.Filter.none())).extracting(i -> i.task().taskId()).contains(t.getId());

        f.runtime.complete(t.getId(), FlowRuntime.Action.APPROVE, "同意", FlowFixture.j("{\"auditNote\":\"同意\"}"), f.actor(s.auditor1(), FlowFixture.AUDIT_PERM));
        assertThat(center.todo(s.auditor1().id(), WorkCenter.Filter.none())).extracting(i -> i.task().taskId()).doesNotContain(t.getId());
        assertThat(center.done(s.auditor1().id(), WorkCenter.Filter.none())).extracting(i -> i.task().taskId()).contains(t.getId());
        List<Map<String, Object>> started = center.started(s.applicant().id(), WorkCenter.Filter.none());
        assertThat(started).anySatisfy(r -> {
            assertThat(r.get("bizId")).isEqualTo(id);
            assertThat(r.get("currentNodes")).isEqualTo("确认归档");
        });
    }

    @Test
    void 转办后待办转到同事名下_时间轴记录转办人接收人和原因_委托记录可查() {
        String id = f.submitLoan(s, s.applicant(), "W-20");
        String pi = f.instance(s, id).procInstId();
        Task t = f.task(pi, "deptAudit");
        f.runtime.transfer(t.getId(), s.auditor2().id(), "出差，请代为审核", f.actor(s.auditor1(), FlowFixture.AUDIT_PERM));
        assertThat(center.todo(s.auditor1().id(), WorkCenter.Filter.none())).isEmpty();
        assertThat(center.todo(s.auditor2().id(), WorkCenter.Filter.none())).extracting(i -> i.task().taskId()).containsExactly(t.getId());
        Map<String, Object> ev = new TimelineService(f.action.core.jdbc).of(s.order(), id).stream()
            .filter(e -> e.type().equals("ls.flow.task.transferred")).findFirst().orElseThrow().payload();
        assertThat(ev).containsEntry("comment", "出差，请代为审核").containsEntry("target", String.valueOf(s.auditor2().id()))
            .containsEntry("targetName", s.auditor2().name());
        List<Map<String, Object>> out = center.delegations(s.auditor1().id());
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("direction", "OUT").containsEntry("toName", s.auditor2().name()).containsEntry("reason", "出差，请代为审核");
        assertThat(center.delegations(s.auditor2().id()).get(0)).containsEntry("direction", "IN").containsEntry("fromName", s.auditor1().name());
        assertThat(center.messages(s.auditor2().id(), true)).anySatisfy(m -> assertThat((String) m.get("message")).contains("转办给你"));
    }

    @Test
    void 限制间隔内第二次催办被拒并提示下次可催办时间() {
        String id = f.submitLoan(s, s.applicant(), "W-30");
        Task t = f.task(f.instance(s, id).procInstId(), "deptAudit");
        f.runtime.urge(t.getId(), f.actor(s.applicant()));
        assertThatThrownBy(() -> f.runtime.urge(t.getId(), f.actor(s.applicant()))).isInstanceOf(LsBizException.class).satisfies(e -> {
            LsBizException b = (LsBizException) e;
            assertThat(b.getCode()).isEqualTo("URGE_TOO_FREQUENT");
            assertThat(b.getMessage()).contains("分钟后再试");
            assertThat(b.getDetails()).containsKey("nextAllowedAt");
        });
        // 设定最短时长后，新待办未满时长不能催办
        f.runtime.setUrgeMinAge(java.time.Duration.ofHours(2));
        try {
            String id2 = f.submitLoan(s, s.applicant(), "W-31");
            Task t2 = f.task(f.instance(s, id2).procInstId(), "deptAudit");
            assertThatThrownBy(() -> f.runtime.urge(t2.getId(), f.actor(s.applicant()))).extracting(e -> ((LsBizException) e).getCode())
                .isEqualTo("URGE_TOO_EARLY");
        } finally {
            f.runtime.setUrgeMinAge(null);
        }
    }

    @Test
    void 办理上下文含冻结表单_节点策略_业务记录_可退回节点与版本快照_非相关人被拒() {
        String id = f.submitLoan(s, s.applicant(), "W-40");
        String pi = f.instance(s, id).procInstId();
        Task t = f.task(pi, "deptAudit");
        Map<String, Object> ctx = center.context(t.getId(), f.actor(s.auditor1(), FlowFixture.AUDIT_PERM));
        assertThat(((com.fasterxml.jackson.databind.JsonNode) ctx.get("form")).path("formCode").asText()).isEqualTo(s.formCode());
        assertThat(ctx.get("fieldPolicy")).isNotNull();
        assertThat((Map<String, Object>) ctx.get("record")).containsEntry("loanNo", "W-40");
        assertThat((Map<String, Object>) ctx.get("snapshot")).containsEntry("flowVersion", 1).containsEntry("formVersion", 1)
            .containsEntry("recordStatus", "IN_REVIEW");
        assertThat((Map<String, Object>) ctx.get("actions")).containsEntry("approve", true).containsEntry("resubmit", false).containsEntry("urge", false);
        assertThat((List<?>) ctx.get("timeline")).isNotEmpty();
        assertThat((List<Map<String, Object>>) ctx.get("returnTargets")).extracting(m -> m.get("key")).containsExactly("initiator");
        // 发起人可看（可催办、不可办理）；无关人员被拒
        Map<String, Object> mine = center.context(t.getId(), f.actor(s.applicant()));
        assertThat((Map<String, Object>) mine.get("actions")).containsEntry("approve", false).containsEntry("urge", true);
        assertThatThrownBy(() -> center.context(t.getId(), f.actor(s.auditor3()))).extracting(e -> ((LsBizException) e).getCode())
            .isEqualTo("ACCESS_DENIED");
        // 通过后在确认节点：可退回到部门审核
        f.runtime.complete(t.getId(), FlowRuntime.Action.APPROVE, "同意", FlowFixture.j("{\"auditNote\":\"同意\"}"), f.actor(s.auditor1(), FlowFixture.AUDIT_PERM));
        Task c = f.task(pi, "confirm");
        assertThat((List<Map<String, Object>>) center.context(c.getId(), f.actor(s.applicant())).get("returnTargets")).extracting(m -> m.get("key"))
            .containsExactly("initiator", "deptAudit");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return (List<Map<String, Object>>) o;
    }

    @Test
    void 单据进度_发起人看到等待谁审核与催办_审核人看到自己的待办() {
        String id = f.submitLoan(s, s.applicant(), "W-60");
        String pi = f.instance(s, id).procInstId();
        Task t = f.task(pi, "deptAudit");
        due(pi, LocalDateTime.now().plusHours(20));

        Map<String, Object> mine = center.progress(s.order(), id, f.actor(s.applicant()));
        assertThat(mine.get("myTask")).isNull();
        assertThat(mine.get("canUrge")).isEqualTo(true);
        List<Map<String, Object>> cur = list(mine.get("current"));
        assertThat(cur).hasSize(1);
        assertThat(cur.get(0)).containsEntry("nodeName", "部门审核").containsEntry("mine", false);
        assertThat(list(cur.get(0).get("handlers")).get(0)).containsEntry("name", s.auditor1().name());
        assertThat(((Number) cur.get(0).get("remainingMinutes")).longValue()).isBetween(19 * 60L, 20 * 60L);
        assertThat(list(mine.get("steps"))).extracting(x -> x.get("name") + ":" + x.get("state"))
            .containsExactly("发起:done", "部门审核:current", "确认归档:pending");

        Map<String, Object> auditor = center.progress(s.order(), id, f.actor(s.auditor1(), FlowFixture.AUDIT_PERM));
        assertThat(auditor.get("myTask")).isEqualTo(t.getId());
        assertThat(auditor.get("canUrge")).isEqualTo(false);
        assertThat(list(auditor.get("current")).get(0)).containsEntry("mine", true);
    }

    @Test
    void 单据进度_退回后标出退回节点与意见_再提交后不再提示() {
        String id = f.submitLoan(s, s.applicant(), "W-61");
        String pi = f.instance(s, id).procInstId();
        Task t = f.task(pi, "deptAudit");
        f.runtime.returnTask(t.getId(), null, "借用数量不清楚，请补充备注", f.actor(s.auditor1(), FlowFixture.AUDIT_PERM));

        Map<String, Object> p = center.progress(s.order(), id, f.actor(s.applicant()));
        Map<String, Object> ret = (Map<String, Object>) p.get("returned");
        assertThat(ret).containsEntry("nodeName", "部门审核").containsEntry("comment", "借用数量不清楚，请补充备注");
        assertThat((Map<String, Object>) ret.get("by")).containsEntry("name", s.auditor1().name());
        assertThat(list(p.get("steps"))).extracting(x -> x.get("name") + ":" + x.get("state"))
            .containsExactly("发起:current", "部门审核:returned", "确认归档:pending");
        Map<String, Object> audit = list(p.get("steps")).get(1);
        assertThat(list(audit.get("records")).get(0)).containsEntry("outcome", "RETURNED").containsEntry("comment", "借用数量不清楚，请补充备注");
        // 轮到发起人处理：本人有待办，不提供催办
        assertThat(p.get("myTask")).isNotNull();
        assertThat(p.get("canUrge")).isEqualTo(false);

        Task back = f.task(pi, "initiator");
        f.runtime.complete(back.getId(), FlowRuntime.Action.RESUBMIT, "已补充", FlowFixture.j("{}"), f.actor(s.applicant()));
        Map<String, Object> after = center.progress(s.order(), id, f.actor(s.applicant()));
        assertThat(after.get("returned")).isNull();
        assertThat(list(after.get("steps"))).extracting(x -> x.get("name") + ":" + x.get("state"))
            .containsExactly("发起:done", "部门审核:current", "确认归档:pending");
    }

    @Test
    void 单据进度_非参与人且无查看权限时拒绝() {
        String id = f.submitLoan(s, s.applicant(), "W-62");
        assertThatThrownBy(() -> center.progress(s.order(), id, f.actor(s.auditor2()))).extracting(e -> ((LsBizException) e).getCode())
            .isEqualTo("ACCESS_DENIED");
    }
}
