package com.lsmom.action;

import com.lsmom.action.api.ActorContext;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.event.DomainEvent;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Issue 闭环全部经命名 Operation 推进（5.4：WorkItem/Issue 状态改由 Operation 推进）。 */
class IssueLifecycleTest {

    private final ActionFixture f = new ActionFixture();

    private void finish(ActorContext a, String workId) {
        f.step("ls.core.Work.ready", a, "ls.core.Work", workId, "{}");
        f.step("ls.core.Work.assign", a, "ls.core.Work", workId, "{\"targets\":[{\"resourceType\":\"TEAM\",\"resourceRef\":\"t1\"}]}");
        f.step("ls.core.Work.start", a, "ls.core.Work", workId, "{}");
        f.step("ls.core.Work.submitConfirm", a, "ls.core.Work", workId, "{}");
        f.step("ls.core.Work.confirm", a, "ls.core.Work", workId, "{}");
    }

    @Test
    void 整改闭环_未完成拒绝_验证不通过回退并通知_验证通过关闭() {
        CoreFixture.Plant p = f.core.plant();
        Party reporter = f.core.employee("报告人", p.teamA());
        Party owner = f.core.employee("整改负责人", p.teamA());
        Party verifier = f.core.employee("质检员", p.workshop());
        ActorContext rep = f.human(reporter, DataScopeLevel.TEAM, "lsmom:issue:raise");
        ActorContext handler = f.human(owner, DataScopeLevel.TEAM, "lsmom:issue:handle", "lsmom:work:edit", "lsmom:work:assign",
            "lsmom:work:execute", "lsmom:work:confirm");
        ActorContext qa = f.human(verifier, DataScopeLevel.WORKSHOP, "lsmom:issue:verify");

        String id = f.op("ls.core.Issue.raise", rep, "{\"type\":\"DEFECT\",\"title\":\"传送带跑偏\",\"orgUnitId\":" + p.teamA().id()
            + ",\"links\":[{\"objectType\":\"ASSET\",\"objectId\":\"BELT-01\"}]}").targetId();
        f.step("ls.core.Issue.submit", rep, "ls.core.Issue", id, "{}");
        f.step("ls.core.Issue.startRectification", handler, "ls.core.Issue", id, "{\"ownerParty\":" + owner.id() + "}");
        Map<?, ?> rect = (Map<?, ?>) f.on("ls.core.Issue.createRectification", handler, id, f.issues.get(Long.parseLong(id)).version(),
            "{\"titles\":[\"调整托辊\",\"更换皮带\"]}").data();
        List<String> works = ((List<?>) rect.get("works")).stream().map(w -> String.valueOf(((Map<?, ?>) w).get("id"))).toList();

        finish(handler, works.get(0));
        assertThatThrownBy(() -> f.step("ls.core.Issue.submitVerify", handler, "ls.core.Issue", id, "{}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("GUARD_FAILED");
                assertThat(e.getDetails()).containsEntry("reason", "RECTIFICATION_INCOMPLETE");
                assertThat(e.getDetails().get("unfinished").toString()).contains("更换皮带");
            });
        finish(handler, works.get(1));
        f.step("ls.core.Issue.submitVerify", handler, "ls.core.Issue", id, "{}");

        f.step("ls.core.Issue.verifyFail", qa, "ls.core.Issue", id, "{\"conclusion\":\"仍有跑偏\"}");
        Issue back = f.issues.get(Long.parseLong(id));
        assertThat(back.status().name()).isEqualTo("IN_RECTIFICATION");
        DomainEvent notice = f.events.eventsOf("ls.core.Issue", id).stream()
            .filter(e -> e.type().equals("ls.core.Issue.rectificationReturned")).findFirst().orElseThrow();
        assertThat(notice.payload().get("notify").toString()).isEqualTo("[" + owner.id() + "]");

        f.step("ls.core.Issue.submitVerify", handler, "ls.core.Issue", id, "{}");
        // 关闭前的影响说明：列出将被关闭的问题与整改工作数量；关闭不可撤销、进入终态
        List<String> impact = new com.lsmom.action.core.CoreImpacts(f.issues).describe("ls.core.Issue.verifyPass", id);
        assertThat(impact.get(0)).startsWith("将关闭 1 个问题：").endsWith("传送带跑偏");
        assertThat(impact.get(1)).isEqualTo("关联整改工作 2 项（已完成 2 项）");
        assertThat(f.operations.require("ls.core.Issue.verifyPass").undoOperation()).isNull();
        assertThat(f.machines.forConcept("ls.core.Issue").terminal("CLOSED")).isTrue();
        assertThat(f.machines.forConcept("ls.core.Issue").terminal("OPEN")).isFalse();
        f.step("ls.core.Issue.verifyPass", qa, "ls.core.Issue", id, "{\"conclusion\":\"连续运行 2 小时无跑偏\"}");
        Issue closed = f.issues.get(Long.parseLong(id));
        assertThat(closed.status().name()).isEqualTo("CLOSED");
        assertThat(closed.verifiedBy()).isEqualTo(verifier.id());
        assertThat(closed.verifyConclusion()).contains("无跑偏");

        List<String> timeline = f.events.eventsOf("ls.core.Issue", id).stream().map(DomainEvent::type).toList();
        assertThat(timeline).containsSubsequence("ls.core.Issue.raise", "ls.core.Issue.submit", "ls.core.Issue.startRectification",
            "ls.core.Issue.createRectification", "ls.core.Issue.submitVerify", "ls.core.Issue.verifyFail", "ls.core.Issue.submitVerify",
            "ls.core.Issue.verifyPass");
        DomainEvent pass = f.events.eventsOf("ls.core.Issue", id).stream().filter(e -> e.type().equals("ls.core.Issue.verifyPass")).findFirst().orElseThrow();
        assertThat(pass.actorParty()).isEqualTo(verifier.id());
        assertThat(pass.payload()).containsEntry("conclusion", "连续运行 2 小时无跑偏");
    }

    @Test
    void 重复报警经Operation去重() {
        CoreFixture.Plant p = f.core.plant();
        String body = "{\"type\":\"DCS_ALARM\",\"title\":\"温度高\",\"orgUnitId\":" + p.teamA().id() + ",\"sourceType\":\"DCS\",\"sourceKey\":\"TI-" + System.nanoTime() + "\"}";
        var first = f.op("ls.core.Issue.raise", ActionFixture.admin(), body);
        var again = f.op("ls.core.Issue.raise", ActionFixture.admin(), body);
        assertThat(again.targetId()).isEqualTo(first.targetId());
        assertThat(again.warnings()).anyMatch(w -> w.contains("累计"));
        assertThat(f.issues.get(Long.parseLong(first.targetId())).occurrenceCount()).isEqualTo(2);
    }

    @Test
    void 工作填报经Operation_草稿到提交() {
        CoreFixture.Plant p = f.core.plant();
        ActorContext a = ActionFixture.admin();
        String w = f.newWork(a, p.teamA(), "移动填报");
        var draft = f.on("ls.core.Work.saveReport", a, w, 0, "{\"progress\":40,\"outputQty\":20,\"outputUnit\":\"件\"}");
        Number reportId = (Number) ((Map<?, ?>) draft.data()).get("reportId");
        assertThat(f.core.reports.totals(Long.parseLong(w)).reports()).isZero();
        f.on("ls.core.Work.submitReport", a, w, 0, "{\"reportId\":" + reportId + "}");
        assertThat(f.core.reports.totals(Long.parseLong(w)).reports()).isEqualTo(1);
        assertThat(f.core.works.get(Long.parseLong(w)).progress()).isEqualByComparingTo("40");
        assertThat(f.events.eventsOf("ls.core.WorkReport", String.valueOf(reportId))).extracting(DomainEvent::type)
            .containsExactly("ls.core.WorkReport.submitted");
    }

    @Test
    void 补偿Operation_必须是本状态机能回到原状态的迁移_并随契约登记() {
        com.fasterxml.jackson.databind.JsonNode none = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("type", "object");
        String c = "ls.core.Work";
        var bad = new com.lsmom.action.state.StateMachineDefinition("x.lifecycle", c, "测试", List.of("A", "B"), "A", List.of(
            new com.lsmom.action.state.StateMachineDefinition.Transition(c + ".go", "去", List.of("A"), "B", List.of(), "", "C1", none, "NONE",
                c + ".missing")));
        assertThat(f.machines.validate(bad)).anySatisfy(m -> assertThat(m).contains("补偿 Operation 不是本状态机的迁移"));
        var oneWay = new com.lsmom.action.state.StateMachineDefinition("x.lifecycle", c, "测试", List.of("A", "B", "C"), "A", List.of(
            new com.lsmom.action.state.StateMachineDefinition.Transition(c + ".go", "去", List.of("A"), "B", List.of(), "", "C1", none, "NONE",
                c + ".other"),
            new com.lsmom.action.state.StateMachineDefinition.Transition(c + ".other", "别处", List.of("B"), "C", List.of(), "", "C1", none, "NONE")));
        assertThat(f.machines.validate(oneWay)).anySatisfy(m -> assertThat(m).contains("不能从 B 回到原状态"));
        var ok = new com.lsmom.action.state.StateMachineDefinition("x.lifecycle", c, "测试", List.of("A", "B"), "A", List.of(
            new com.lsmom.action.state.StateMachineDefinition.Transition(c + ".go", "去", List.of("A"), "B", List.of(), "", "C1", none, "NONE",
                c + ".back"),
            new com.lsmom.action.state.StateMachineDefinition.Transition(c + ".back", "回", List.of("B"), "A", List.of(), "", "C1", none, "NONE")));
        assertThat(f.machines.validate(ok)).isEmpty();
    }
}
