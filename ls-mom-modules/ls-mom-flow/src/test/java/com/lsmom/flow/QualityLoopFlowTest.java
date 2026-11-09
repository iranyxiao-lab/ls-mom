package com.lsmom.flow;

import com.lsmom.action.api.ActorContext;
import com.lsmom.core.event.DomainEvent;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.flow.model.FlowDefinition;
import com.lsmom.flow.runtime.FlowInstances;
import com.lsmom.flow.runtime.FlowRuntime;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.lsmom.flow.FlowFixture.j;
import static org.assertj.core.api.Assertions.assertThat;

/** 受控质量闭环：登记 → 车间审核 → 整改工作 → 验证 → 关闭，全程由流程推进核心 Issue 的状态机。 */
class QualityLoopFlowTest {

    private final FlowFixture f = new FlowFixture();

    @Test
    void 登记_车间审核_整改工作_验证_关闭() {
        CoreFixture.Plant p = f.action.core.plant();
        Party reporter = f.action.core.employee("报告人", p.teamA());
        Party workshopLeader = f.action.core.employee("车间主任", p.workshop());
        f.action.core.orgUnits.setLeader(p.workshop().id(), workshopLeader.id());
        Party owner = f.action.core.employee("整改负责人", p.teamA());
        Party verifier = f.action.core.employee("质检员", p.workshop());
        ActorContext rep = f.action.human(reporter, DataScopeLevel.TEAM, "lsmom:issue:raise");
        ActorContext lead = f.action.human(workshopLeader, DataScopeLevel.WORKSHOP, "lsmom:issue:handle");
        ActorContext handler = f.action.human(owner, DataScopeLevel.TEAM, "lsmom:issue:handle", "lsmom:work:edit", "lsmom:work:assign",
            "lsmom:work:execute", "lsmom:work:confirm");
        ActorContext qa = f.action.human(verifier, DataScopeLevel.WORKSHOP, "lsmom:issue:verify");

        String code = "acme.quality" + Long.toString(System.nanoTime(), 36).replaceAll("[^a-z0-9]", "") + ".issueLoop";
        String i = "ls.core.Issue.";
        f.runtime.publish(FlowDefinition.parse("""
            {"code":"%s","name":"不符合项闭环","concept":"ls.core.Issue","startOn":"%ssubmit","orgUnitId":%d,
             "callbacks":{"approved":"%sverifyPass","rejected":"%sverifyFail"},
             "nodes":[
               {"key":"workshopAudit","type":"AUDIT","name":"车间审核","assignee":{"strategy":"WORKSHOP_LEADER","of":"BIZ_ORG"},"operation":"%sstartRectification"},
               {"key":"rectify","type":"FILL","name":"整改工作","assignee":{"strategy":"FIELD","value":"ownerParty"},"operation":"%screateRectification"},
               {"key":"submitVerify","type":"FILL","name":"提交验证","assignee":{"strategy":"FIELD","value":"ownerParty"},"operation":"%ssubmitVerify"},
               {"key":"verify","type":"AUDIT","name":"验证","assignee":{"strategy":"USER","value":[%d]}}]}"""
            .formatted(code, i, p.teamA().id(), i, i, i, i, i, verifier.id())), 1L);

        String id = f.action.op(i + "raise", rep, "{\"type\":\"DEFECT\",\"title\":\"传送带跑偏\",\"severity\":\"MEDIUM\",\"orgUnitId\":" + p.teamA().id() + "}").targetId();
        f.action.step(i + "submit", rep, "ls.core.Issue", id, "{}");
        FlowInstances.Instance inst = f.instances.running("ls.core.Issue", id).orElseThrow();
        assertThat(f.action.issues.get(Long.parseLong(id)).status().name()).isEqualTo("OPEN");

        // 车间审核：指定整改负责人并开始整改
        f.runtime.complete(f.task(inst.procInstId(), "workshopAudit").getId(), FlowRuntime.Action.APPROVE, "同意整改",
            j("{\"ownerParty\":" + owner.id() + "}"), lead);
        assertThat(f.action.issues.get(Long.parseLong(id)).status().name()).isEqualTo("IN_RECTIFICATION");

        // 整改负责人生成整改工作（WorkItem）
        f.runtime.complete(f.task(inst.procInstId(), "rectify").getId(), FlowRuntime.Action.APPROVE, null,
            j("{\"titles\":[\"调整托辊\"]}"), handler);
        List<String> works = f.action.events.eventsOf("ls.core.Issue", id).stream().filter(e -> e.type().equals(i + "createRectification"))
                .flatMap(e -> f.action.core.jdbc.queryForList("select id::text from core_work_item where code = any(?)", String.class,
                    (Object) ((List<?>) e.payload().get("works")).stream().map(String::valueOf).toArray(String[]::new)).stream()).toList();
        assertThat(works).hasSize(1);
        String w = works.get(0);
        f.action.step("ls.core.Work.ready", handler, "ls.core.Work", w, "{}");
        f.action.step("ls.core.Work.assign", handler, "ls.core.Work", w, "{\"targets\":[{\"resourceType\":\"TEAM\",\"resourceRef\":\"t1\"}]}");
        f.action.step("ls.core.Work.start", handler, "ls.core.Work", w, "{}");
        f.action.step("ls.core.Work.submitConfirm", handler, "ls.core.Work", w, "{}");
        f.action.step("ls.core.Work.confirm", handler, "ls.core.Work", w, "{}");

        // 提交验证 → 质检员验证通过 → onApproved（verifyPass）关闭不符合项
        f.runtime.complete(f.task(inst.procInstId(), "submitVerify").getId(), FlowRuntime.Action.APPROVE, null, null, handler);
        assertThat(f.action.issues.get(Long.parseLong(id)).status().name()).isEqualTo("PENDING_VERIFY");
        f.runtime.complete(f.task(inst.procInstId(), "verify").getId(), FlowRuntime.Action.APPROVE, "连续运行 2 小时无跑偏", null, qa);

        Issue closed = f.action.issues.get(Long.parseLong(id));
        assertThat(closed.status().name()).isEqualTo("CLOSED");
        assertThat(closed.verifiedBy()).isEqualTo(verifier.id());
        assertThat(closed.verifyConclusion()).contains("无跑偏");
        assertThat(f.instances.get(inst.id()).status()).isEqualTo("APPROVED");
        DomainEvent pass = f.action.events.eventsOf("ls.core.Issue", id).stream().filter(e -> e.type().equals(i + "verifyPass")).findFirst().orElseThrow();
        assertThat(pass.onBehalfOf()).isEqualTo(verifier.id());
        assertThat(f.action.events.eventsOf("ls.core.Issue", id).stream().map(DomainEvent::type).toList()).containsSubsequence(
            i + "submit", "ls.flow.started", i + "startRectification", i + "createRectification", i + "submitVerify", "ls.flow.approved", i + "verifyPass");
    }
}
