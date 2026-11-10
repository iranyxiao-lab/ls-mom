package com.lsmom.ai.knowledge;

import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.MetricPort;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** spec ai/agent-gateway「带引用的知识问答」：只引用已发布版本；检索前后双重权限过滤；业务数字来自指标（AI-10）。 */
class KnowledgeTest {

    static {
        // 共享测试库中每个夹具对账时都要带上知识问答的代码 Operation
        ActionFixture.extraHandlers = af -> List.of(new KnowledgeOperations.Ask(new KnowledgeAnswerer(
            new KnowledgeService(af.core.jdbc, new HashingEmbedder(), af.txm), new FakeMetrics(), af.core.jdbc, af.core.clock)));
    }

    final ActionFixture f = new ActionFixture();
    final CoreFixture.Plant plant = f.core.plant();
    final KnowledgeService kb = new KnowledgeService(f.core.jdbc, new HashingEmbedder(), f.txm);
    final String tag = Long.toString(System.nanoTime(), 36);
    final Party alice = f.core.employee("班长甲", plant.teamA());
    final ActorContext human = f.human(alice, DataScopeLevel.TEAM, "lsmom:knowledge:ask");
    final ActorContext admin = ActionFixture.admin();

    private List<Integer> citedVersions(Map<String, Object> answer, String code) {
        List<Integer> out = new ArrayList<>();
        for (Object c : (List<?>) answer.get("citations")) {
            Map<?, ?> m = (Map<?, ?>) c;
            if (code.equals(m.get("documentCode"))) out.add((Integer) m.get("version"));
        }
        return out;
    }

    @Test
    void 未发布修订稿不被引用_发布后引用新版本() {
        String code = "SOP-LOTO-" + tag;
        long id = kb.create(code, "停机检修挂牌上锁 " + tag, "SOP", plant.workshop().id(), "INTERNAL", """
            # 适用范围
            设备停机检修、清理堵料时适用。
            # 步骤
            检修前必须切断电源并挂牌上锁，确认能量已隔离。验电后方可作业。""", admin);
        assertThat(kb.search("挂牌上锁 " + tag, 3, human)).isEmpty(); // v1 尚未发布
        assertThat(kb.publish(id, admin)).isEqualTo(1);

        kb.saveDraft(id, "检修前必须由双人确认联锁解除" + tag + "，并在交接记录签字。", "增加双人确认", admin);
        Map<String, Object> a = new KnowledgeAnswerer(kb, null, f.core.jdbc, f.core.clock).ask("检修前挂牌上锁怎么做 双人确认联锁解除" + tag, human);
        assertThat(a.get("type")).isEqualTo("DOCUMENT");
        assertThat(citedVersions(a, code)).isNotEmpty().allMatch(v -> v == 1);
        assertThat(a.get("answer") + "" + a.get("citations")).doesNotContain("双人确认联锁解除" + tag);
        assertThat((String) a.get("answer")).contains("挂牌上锁").contains("[1]");

        assertThat(kb.publish(id, admin)).isEqualTo(2);
        Map<String, Object> b = new KnowledgeAnswerer(kb, null, f.core.jdbc, f.core.clock).ask("双人确认联锁解除" + tag, human);
        assertThat(citedVersions(b, code)).isNotEmpty().allMatch(v -> v == 2);
        assertThat(f.count("select count(*) from core_knowledge_version where document_id = ? and status = 'SUPERSEDED'", id)).isEqualTo(1);
        assertThatThrownBy(() -> kb.publish(id, admin)).extracting(e -> ((LsBizException) e).getCode()).isEqualTo("KB_NO_DRAFT");
    }

    @Test
    void 检索前按组织与密级过滤_返回前复核文档当前状态() {
        CoreFixture.Plant other = f.core.plant();
        long mine = kb.create("SOP-A-" + tag, "甲班润滑 " + tag, "SOP", plant.workshop().id(), "INTERNAL", "润滑点每班加注润滑脂" + tag + "。", admin);
        long theirs = kb.create("SOP-B-" + tag, "外厂润滑 " + tag, "SOP", other.workshop().id(), "INTERNAL", "润滑点每班加注润滑脂" + tag + "。", admin);
        long secret = kb.create("SOP-C-" + tag, "机密配方 " + tag, "SOP", null, "CONFIDENTIAL", "润滑点每班加注润滑脂" + tag + "（机密）。", admin);
        kb.publish(mine, admin);
        kb.publish(theirs, admin);
        kb.publish(secret, admin);

        // 上级车间的文档对本班组成员可见；其他工厂与高密级的不可见
        List<KnowledgeService.Hit> hits = kb.search("润滑点每班加注润滑脂" + tag, 5, human);
        assertThat(hits).extracting(KnowledgeService.Hit::documentId).containsExactly(mine);
        assertThat(kb.list(human)).extracting(r -> r.get("id")).contains(mine).doesNotContain(theirs, secret);
        assertThatThrownBy(() -> kb.get(theirs, human)).extracting(e -> ((LsBizException) e).getCode()).isEqualTo("NOT_FOUND");
        assertThat(kb.search("润滑点每班加注润滑脂" + tag, 5, admin)).hasSize(3);

        // 文档改到其他工厂但分块尚未刷新：检索 SQL 仍会命中旧分块，返回前复核把它去掉
        f.core.jdbc.update("update core_knowledge_document set org_unit_id = ? where id = ?", other.workshop().id(), mine);
        assertThat(f.count("select count(*) from core_knowledge_chunk where document_id = ? and org_unit_id = ?", mine, plant.workshop().id())).isPositive();
        assertThat(kb.search("润滑点每班加注润滑脂" + tag, 5, human)).isEmpty();
        // 补建索引后分块跟随文档的新组织
        assertThat(kb.reindexStale()).isGreaterThanOrEqualTo(1);
        assertThat(f.count("select count(*) from core_knowledge_chunk where document_id = ? and org_unit_id = ?", mine, other.workshop().id())).isPositive();
        assertThat(kb.search("润滑点每班加注润滑脂" + tag, 5, human)).isEmpty();

        // 不能在数据范围外建文档；Agent 不能发布
        ActorContext editor = f.human(alice, DataScopeLevel.TEAM, "lsmom:knowledge:manage");
        assertThatThrownBy(() -> kb.create("SOP-D-" + tag, "x", "SOP", other.teamA().id(), "INTERNAL", "x", editor))
            .extracting(e -> ((LsBizException) e).getCode()).isEqualTo("ACCESS_DENIED");
        ActorContext agent = new ActorContext(ActorContext.ActorType.AGENT, null, 999_997L, alice.id(), false, Set.of("lsmom:knowledge:manage"),
            f.core.dataScopes.resolve(alice.id(), DataScopeLevel.TEAM), "知识助手");
        long d = kb.create("SOP-E-" + tag, "x", "SOP", plant.teamA().id(), "INTERNAL", "草稿内容", editor);
        assertThatThrownBy(() -> kb.publish(d, agent)).extracting(e -> ((LsBizException) e).getCode()).isEqualTo("AGENT_TOOL_DENIED");
    }

    /** 记录查询参数的指标门面替身。 */
    static final class FakeMetrics implements MetricPort {
        MetricPort.Query last;

        @Override
        public List<MetricInfo> metrics() {
            return List.of(new MetricInfo("ls.core.issueCount", "异常数", "登记的异常（不含草稿与已取消），按登记时间统计", "条", "ls.core.Issue",
                List.of("status", "orgUnitId"), true), new MetricInfo("ls.core.workOnTimeRate", "按期完成率", "按期", "%", "ls.core.Work", List.of("orgUnitId"), true));
        }

        @Override
        public Value value(Query q, ActorContext actor) {
            last = q;
            return new Value(q.code(), 3, "异常数", "条", 2L);
        }

        @Override
        public Drill drill(Query q, int size, ActorContext actor) {
            return new Drill(q.code(), 3, "ls.core.Issue", 2, List.of(Map.of("id", 41L, "code", "IS-41", "title", "跑偏"),
                Map.of("id", 42L, "code", "IS-42", "title", "漏油")));
        }
    }

    @Test
    void AI10_本周某班组异常数_给出数值_指标编码与可钻取的记录引用() {
        FakeMetrics m = new FakeMetrics();
        KnowledgeAnswerer ans = new KnowledgeAnswerer(kb, m, f.core.jdbc, f.core.clock);
        String team = plant.teamA().name();
        Map<String, Object> a = ans.ask("本周 " + team + " 异常数是多少？", human);
        assertThat(a.get("type")).isEqualTo("METRIC");
        assertThat(a.get("value")).isEqualTo(2L);
        assertThat(a.get("metric").toString()).contains("code=ls.core.issueCount").contains("version=3");
        assertThat((String) a.get("answer")).contains("本周").contains(team).contains("2 条").contains("ls.core.issueCount v3");
        assertThat((List<?>) a.get("refs")).hasSize(2);
        assertThat(((List<?>) a.get("refs")).get(0).toString()).contains("type=ls.core.Issue").contains("id=41").contains("IS-41");
        LocalDate monday = LocalDate.now(f.core.clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertThat(m.last.from()).isEqualTo(monday.atStartOfDay().toString());
        assertThat(m.last.to()).isEqualTo(monday.plusWeeks(1).atStartOfDay().toString());
        assertThat(m.last.filters().get(0).get("value").toString()).contains(String.valueOf(plant.teamA().id()));
        // 其他班组不在数据范围内
        assertThatThrownBy(() -> ans.ask("本周 " + plant.teamB().name() + " 异常数", human))
            .extracting(e -> ((LsBizException) e).getCode()).isEqualTo("ACCESS_DENIED");
        // 不含指标名的问题走文档检索
        assertThat(ans.ask("润滑怎么做", human).get("type")).isEqualTo("DOCUMENT");
    }

    @Test
    void 经网关调用_Agent以真人数据域问答() {
        ActorContext agent = new ActorContext(ActorContext.ActorType.AGENT, null, 999_996L, alice.id(), false, Set.of("lsmom:knowledge:ask"),
            f.core.dataScopes.resolve(alice.id(), DataScopeLevel.TEAM), "知识助手");
        var r = f.gateway.execute("ls.core.KnowledgeDocument.ask", OperationRequest.of(ActionFixture.json(
            "{\"question\":\"本周" + plant.teamA().name() + "异常数\"}")), agent);
        assertThat(((Map<?, ?>) r.data()).get("value")).isEqualTo(2L);
        assertThat(f.count("select count(*) from op_audit where op_code = 'ls.core.KnowledgeDocument.ask' and actor_type = 'AGENT' and on_behalf_of = ?",
            alice.id())).isEqualTo(1);
    }

    @Test
    void 分块_按标题与长度切分() {
        List<String[]> c = KnowledgeService.chunks("# 一\n" + "甲".repeat(500) + "\n\n# 二\n乙乙乙\n");
        assertThat(c).hasSize(3);
        assertThat(c.get(0)[0]).isEqualTo("一");
        assertThat(c.get(0)[1]).hasSize(KnowledgeService.CHUNK_CHARS);
        assertThat(c.get(2)).containsExactly("二", "乙乙乙");
        assertThat(HashingEmbedder.tokens("挂牌 LOTO-2")).contains("挂", "牌", "挂牌", "loto", "2");
    }
}
