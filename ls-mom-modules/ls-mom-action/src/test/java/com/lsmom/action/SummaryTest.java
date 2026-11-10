package com.lsmom.action;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationResult;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** spec ai/agent-gateway「摘要」AI-09：草稿带引用、待真人确认后发布，不回写原始事实。 */
class SummaryTest {

    private final ActionFixture f = new ActionFixture();

    @Test
    void AI09_Agent草拟带引用的摘要_待确认_真人发布_不改原记录() {
        CoreFixture.Plant p = f.core.plant();
        Party boss = f.core.employee("车间主任", p.workshop());
        ActorContext human = f.human(boss, DataScopeLevel.WORKSHOP, "lsmom:issue:raise", "lsmom:summary:draft", "lsmom:summary:confirm");
        ActorContext agent = new ActorContext(ActorContext.ActorType.AGENT, null, 999_998L, boss.id(), false,
            Set.of("lsmom:summary:draft", "lsmom:summary:confirm"), f.core.dataScopes.resolve(boss.id(), DataScopeLevel.WORKSHOP), "交接班助手");

        String issueId = f.op("ls.core.Issue.raise", human, "{\"type\":\"DEFECT\",\"title\":\"传送带跑偏\",\"orgUnitId\":" + p.teamA().id() + "}").targetId();
        Issue before = f.issues.get(Long.parseLong(issueId));

        Map<?, ?> ctx = (Map<?, ?>) f.op("ls.core.Summary.context", agent, "{\"kind\":\"ISSUE\",\"subjectId\":\"" + issueId + "\"}").data();
        List<?> facts = (List<?>) ctx.get("facts");
        assertThat(facts).isNotEmpty();
        Map<?, ?> eventRef = facts.stream().map(x -> (Map<?, ?>) ((Map<?, ?>) x).get("ref")).filter(r -> "ls.core.Event".equals(r.get("type"))).findFirst().orElseThrow();

        // 要点不带引用 / 引用不在上下文中 → 拒绝
        assertThatThrownBy(() -> f.op("ls.core.Summary.draft", agent, "{\"kind\":\"ISSUE\",\"subjectId\":\"" + issueId
            + "\",\"content\":\"概述\",\"points\":[{\"text\":\"无引用\",\"refs\":[]}]}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("SUMMARY_CITATION_INVALID"));
        assertThatThrownBy(() -> f.op("ls.core.Summary.draft", agent, "{\"kind\":\"ISSUE\",\"subjectId\":\"" + issueId
            + "\",\"content\":\"概述\",\"points\":[{\"text\":\"编造\",\"refs\":[{\"type\":\"ls.core.Work\",\"id\":\"424242\"}]}]}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getMessage()).contains("不在该对象的上下文中"));

        OperationResult d = f.op("ls.core.Summary.draft", agent, "{\"kind\":\"ISSUE\",\"subjectId\":\"" + issueId
            + "\",\"content\":\"传送带跑偏已登记，待处理\",\"model\":\"test-model\",\"points\":["
            + "{\"text\":\"已登记缺陷\",\"refs\":[{\"type\":\"ls.core.Issue\",\"id\":\"" + issueId + "\"}]},"
            + "{\"text\":\"登记事件\",\"refs\":[{\"type\":\"ls.core.Event\",\"id\":\"" + eventRef.get("id") + "\"}]}]}");
        assertThat(d.status()).isEqualTo("DRAFT");
        Map<String, Object> s = f.summaries.find(Long.parseLong(d.targetId())).orElseThrow();
        assertThat(s).containsEntry("status", "DRAFT").containsEntry("source", "AGENT").containsEntry("onBehalfOf", boss.id())
            .containsEntry("orgUnitId", p.teamA().id());
        assertThat((List<?>) s.get("citations")).hasSize(2);
        assertThat(s.get("points").toString()).contains(issueId).contains("ls.core.Event");

        // Agent 不能发布
        assertThatThrownBy(() -> f.on("ls.core.Summary.publish", agent, d.targetId(), 0, "{}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("AGENT_TOOL_DENIED"));
        // 无确认权限的人不能发布
        ActorContext viewer = f.human(f.core.employee("班员", p.teamA()), DataScopeLevel.TEAM, "lsmom:summary:draft");
        assertThatThrownBy(() -> f.on("ls.core.Summary.publish", viewer, d.targetId(), 0, "{}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("ACCESS_DENIED"));

        OperationResult pub = f.on("ls.core.Summary.publish", human, d.targetId(), 0, "{}");
        assertThat(pub.status()).isEqualTo("PUBLISHED");
        Map<String, Object> after = f.summaries.find(Long.parseLong(d.targetId())).orElseThrow();
        assertThat(after).containsEntry("status", "PUBLISHED").containsEntry("publishedBy", boss.id());
        assertThat(f.issues.get(Long.parseLong(issueId))).isEqualTo(before);

        // 列表按数据域过滤
        ActorContext other = f.human(f.core.employee("外车间", f.core.plant().workshop()), DataScopeLevel.WORKSHOP, "lsmom:summary:draft");
        assertThat(f.summaries.list(null, null, null, issueId, other)).isEmpty();
        assertThat(f.summaries.list(null, "PUBLISHED", null, issueId, human)).hasSize(1);
        assertThatThrownBy(() -> f.op("ls.core.Summary.context", other, "{\"kind\":\"ISSUE\",\"subjectId\":\"" + issueId + "\"}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("ACCESS_DENIED"));
    }

    @Test
    void 作废须说明原因_未知类型拒绝() {
        CoreFixture.Plant p = f.core.plant();
        Party boss = f.core.employee("车间主任", p.workshop());
        ActorContext human = f.human(boss, DataScopeLevel.WORKSHOP, "lsmom:issue:raise", "lsmom:summary:draft", "lsmom:summary:confirm");
        String issueId = f.op("ls.core.Issue.raise", human, "{\"type\":\"DEFECT\",\"title\":\"漏油\",\"orgUnitId\":" + p.teamA().id() + "}").targetId();
        String id = f.op("ls.core.Summary.draft", human, "{\"kind\":\"ISSUE\",\"subjectId\":\"" + issueId
            + "\",\"content\":\"x\",\"points\":[{\"text\":\"漏油\",\"refs\":[{\"type\":\"ls.core.Issue\",\"id\":\"" + issueId + "\"}]}]}").targetId();
        assertThat(f.summaries.find(Long.parseLong(id)).orElseThrow()).containsEntry("source", "HUMAN");
        assertThatThrownBy(() -> f.on("ls.core.Summary.discard", human, id, 0, "{}")).isInstanceOf(LsBizException.class);
        assertThat(f.on("ls.core.Summary.discard", human, id, 0, "{\"reason\":\"内容不准\"}").status()).isEqualTo("DISCARDED");
        assertThat(f.summaries.find(Long.parseLong(id)).orElseThrow()).containsEntry("discardReason", "内容不准");
        assertThatThrownBy(() -> f.op("ls.core.Summary.context", human, "{\"kind\":\"NOPE\",\"subjectId\":\"1\"}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getMessage()).contains("ISSUE"));
    }
}
