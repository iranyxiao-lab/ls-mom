package com.lsmom.crud;

import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.gateway.InputSchemaValidator;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.crud.metric.CoreMetrics;
import com.lsmom.crud.metric.MetricDefinition;
import com.lsmom.crud.metric.MetricEngine;
import com.lsmom.crud.metric.MetricRegistry;
import com.lsmom.crud.metric.NamedQueryDef;
import com.lsmom.crud.metric.NamedQueryRegistry;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 11.1：spec metric/metrics-query 全部场景——重复口径、参数越界、超时、数据域、钻取、口径版本。 */
class MetricTest {

    private final CrudFixture f = new CrudFixture();
    private final ActionFixture a = f.action;
    private final CoreFixture core = a.core;
    private final MetricRegistry registry = new MetricRegistry(core.jdbc, a.concepts, a.txm);
    private final MetricEngine engine = new MetricEngine(core.jdbc, registry);
    private final NamedQueryRegistry queries = new NamedQueryRegistry(core.jdbc, new InputSchemaValidator(), a.txm);
    private final String ns = "acme.m" + Long.toString(System.nanoTime(), 36).replaceAll("[^a-z0-9]", "");

    /** 按期完成率口径；marker 让每次测试的口径在共享库中唯一。 */
    private MetricDefinition onTime(String code, String timeField, String marker) {
        return MetricDefinition.parse("""
            {"code":"%s","name":"按期完成率","concept":"ls.core.Work","unit":"%%",
             "measure":{"fn":"ratio","numerator":[{"field":"actualEnd","op":"le","valueField":"plannedEnd"}]},
             "filters":[{"field":"status","op":"eq","value":"DONE"},{"field":"description","op":"eq","value":"%s"}],
             "timeField":"%s","grains":["WEEK"],"dimensions":["orgUnitId"]}""".formatted(code, marker, timeField));
    }

    private long work(CoreFixture.Plant p, com.lsmom.core.org.OrgUnit org, String marker, int planOffsetHours, int actualOffsetHours) {
        long id = Long.parseLong(a.newWork(ActionFixture.admin(), org, "工作" + System.nanoTime()));
        LocalDateTime plan = LocalDateTime.of(2026, 11, 4, 18, 0);
        core.jdbc.update("update core_work_item set status = 'DONE', description = ?, planned_end = ?, actual_end = ? where id = ?", marker,
            plan.plusHours(planOffsetHours), plan.plusHours(actualOffsetHours), id);
        return id;
    }

    @Test
    void 重复口径提示复用_口径升级生成新版本且旧版本可查() {
        String marker = ns + "dup";
        MetricRegistry.Version v1 = registry.publish(onTime(ns + ".onTimeRate", "plannedEnd", marker), 1L);
        assertThat(v1.version()).isEqualTo(1);

        // 名称、维度不同但口径完全相同 → 重复
        MetricDefinition same = MetricDefinition.parse(onTime(ns + ".deliveryRate", "plannedEnd", marker).toJson().toString()
            .replace("按期完成率", "交付及时率").replace("[\"orgUnitId\"]", "[\"workType\"]"));
        List<MetricRegistry.Issue> issues = registry.validate(same);
        assertThat(issues).extracting(MetricRegistry.Issue::code).containsExactly("METRIC_DUPLICATE");
        assertThat(issues.get(0).message()).contains(ns + ".onTimeRate");
        assertThatThrownBy(() -> registry.publish(same, 1L)).isInstanceOf(LsBizException.class)
            .extracting(e -> ((LsBizException) e).getCode()).isEqualTo("METRIC_DUPLICATE");

        // 同一编码重复发布相同定义不生成新版本
        assertThat(registry.publish(onTime(ns + ".onTimeRate", "plannedEnd", marker), 1L).version()).isEqualTo(1);

        // 口径从"计划结束日"改为"实际结束日"统计 → v2 生效，v1 定义仍可查询
        MetricRegistry.Version v2 = registry.publish(onTime(ns + ".onTimeRate", "actualEnd", marker), 1L);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(registry.require(ns + ".onTimeRate", null).definition().timeField()).isEqualTo("actualEnd");
        assertThat(registry.get(ns + ".onTimeRate", 1).definition().timeField()).isEqualTo("plannedEnd");
        assertThat(registry.get(ns + ".onTimeRate", 1).status()).isEqualTo("RETIRED");
        assertThat(registry.history(ns + ".onTimeRate")).extracting(MetricRegistry.Version::version).containsExactly(2, 1);
        // 按旧版本计算
        MetricEngine.MetricResult old = engine.query(ns + ".onTimeRate", new MetricEngine.MetricQuery(null, null, null, null, null, 1),
            ActionFixture.admin());
        assertThat(old.version()).isEqualTo(1);
    }

    @Test
    void 校验_字段与度量_字段间比较只允许比较操作符() {
        MetricDefinition bad = MetricDefinition.parse("""
            {"code":"%s.bad","name":"坏指标","concept":"ls.core.Work","measure":{"fn":"sum","field":"title"},
             "filters":[{"field":"nope","op":"eq","value":1},{"field":"actualEnd","op":"like","valueField":"plannedEnd"}],
             "timeField":"title","grains":["YEAR"],"dimensions":["description"]}""".formatted(ns));
        assertThat(registry.validate(bad)).extracting(MetricRegistry.Issue::code)
            .contains("FIELD_NOT_NUMERIC", "FIELD_NOT_FOUND", "OP_INVALID", "TIME_FIELD_INVALID", "GRAIN_INVALID", "DIMENSION_INVALID");
        assertThatThrownBy(() -> MetricDefinition.parse("{\"code\":\"x.y\",\"sql\":\"select 1\"}")).isInstanceOf(LsBizException.class)
            .hasMessageContaining("sql");
    }

    @Test
    void 班组长看按期完成率只基于本班组工作() {
        CoreFixture.Plant p = core.plant();
        String marker = ns + "scope";
        // 甲班 3 条：2 条按期；乙班 2 条：0 条按期
        work(p, p.teamA(), marker, 0, -2);
        work(p, p.teamA(), marker, 0, 0);
        work(p, p.teamA(), marker, 0, 5);
        work(p, p.teamB(), marker, 0, 3);
        work(p, p.teamB(), marker, 0, 4);
        registry.publish(onTime(ns + ".teamOnTime", "plannedEnd", marker), 1L);

        Party leaderA = core.employee("甲班长", p.teamA());
        ActorContext leader = a.human(leaderA, DataScopeLevel.TEAM);
        MetricEngine.MetricResult mine = engine.query(ns + ".teamOnTime", new MetricEngine.MetricQuery(null, null, null, null, null, null), leader);
        assertThat(mine.rows()).hasSize(1);
        assertThat(mine.rows().get(0)).containsEntry("numerator", 2L).containsEntry("denominator", 3L);
        assertThat((BigDecimal) mine.rows().get(0).get("value")).isEqualByComparingTo("0.6667");

        // 全部数据域：两个班组合计；按组织分组
        MetricEngine.MetricResult all = engine.query(ns + ".teamOnTime",
            new MetricEngine.MetricQuery("2026-11-01", "2026-11-10", "WEEK", List.of("orgUnitId"), null, null), ActionFixture.admin());
        Map<Object, Map<String, Object>> byOrg = all.rows().stream().collect(Collectors.toMap(r -> r.get("orgUnitId"), r -> r));
        assertThat(byOrg.get(p.teamA().id())).containsEntry("denominator", 3L).containsEntry("bucket", "2026-11-02");
        assertThat(byOrg.get(p.teamB().id())).containsEntry("numerator", 0L).containsEntry("denominator", 2L);

        // Agent 与人同一过滤逻辑
        ActorContext agent = new ActorContext(ActorContext.ActorType.AGENT, leader.userId(), leaderA.id(), null, false, Set.of(), leader.dataScope(),
            "agent");
        assertThat(engine.query(ns + ".teamOnTime", new MetricEngine.MetricQuery(null, null, null, null, null, null), agent).rows().get(0))
            .containsEntry("denominator", 3L);

        // 分组维度、筛选只能用口径维度
        assertThatThrownBy(() -> engine.query(ns + ".teamOnTime", new MetricEngine.MetricQuery(null, null, null, List.of("title"), null, null),
            leader)).hasMessageContaining("维度不在指标口径内");
        assertThatThrownBy(() -> engine.query(ns + ".teamOnTime", new MetricEngine.MetricQuery(null, null, "DAY", null, null, null), leader))
            .hasMessageContaining("不支持粒度");
    }

    @Test
    void 看板指标值可钻取到参与计算的记录() {
        CoreFixture.Plant p = core.plant();
        for (MetricDefinition m : CoreMetrics.metrics()) registry.publish(m, null);
        Party rep = core.employee("报告人", p.teamA());
        ActorContext reporter = a.human(rep, DataScopeLevel.TEAM, "lsmom:issue:raise");
        for (int i = 0; i < 12; i++) {
            String id = a.op("ls.core.Issue.raise", reporter, "{\"type\":\"DEFECT\",\"title\":\"本周异常" + i + "\",\"severity\":\"" + (i % 3 == 0 ? "HIGH" : "LOW")
                + "\",\"orgUnitId\":" + p.teamA().id() + "}").targetId();
            core.jdbc.update("update core_issue set status = 'OPEN' where id = ?", Long.parseLong(id));
        }
        // 一条草稿不计入
        a.op("ls.core.Issue.raise", reporter, "{\"type\":\"DEFECT\",\"title\":\"草稿\",\"orgUnitId\":" + p.teamA().id() + "}");

        String from = LocalDate.now().minusDays(1).toString();
        String to = LocalDate.now().plusDays(1).toString();
        var filter = List.of(new MetricDefinition.Cond("orgUnitId", "eq", p.teamA().id(), null));
        MetricEngine.MetricResult r = engine.query(CoreMetrics.ISSUE_COUNT, new MetricEngine.MetricQuery(from, to, null, null, filter, null),
            ActionFixture.admin());
        assertThat(((Number) r.rows().get(0).get("value")).longValue()).isEqualTo(12);
        assertThat(r.code()).isEqualTo(CoreMetrics.ISSUE_COUNT);
        assertThat(r.version()).isGreaterThanOrEqualTo(1);

        MetricEngine.DrillResult d = engine.drill(CoreMetrics.ISSUE_COUNT,
            new MetricEngine.DrillRequest(from, to, null, null, null, filter, null, 1, 100, null), ActionFixture.admin());
        assertThat(d.total()).isEqualTo(12);
        assertThat(d.rows()).hasSize(12).allSatisfy(row -> assertThat((String) row.get("title")).startsWith("本周异常"));
        assertThat(d.columns()).containsExactly("id", "code", "title", "severity", "status", "orgUnitId", "createdAt");

        // 按严重度分组后钻取某一格
        MetricEngine.MetricResult bySev = engine.query(CoreMetrics.ISSUE_COUNT,
            new MetricEngine.MetricQuery(from, to, "DAY", List.of("severity"), filter, null), ActionFixture.admin());
        Map<String, Object> high = bySev.rows().stream().filter(x -> "HIGH".equals(x.get("severity"))).findFirst().orElseThrow();
        assertThat(((Number) high.get("value")).longValue()).isEqualTo(4);
        MetricEngine.DrillResult cell = engine.drill(CoreMetrics.ISSUE_COUNT, new MetricEngine.DrillRequest(from, to, "DAY", (String) high.get("bucket"),
            Map.of("severity", "HIGH"), filter, null, null, null, null), ActionFixture.admin());
        assertThat(cell.total()).isEqualTo(4);
    }

    @Test
    void 命名Query_参数越界报错_超时取消_数据域生效() {
        queries.register(CoreMetrics.queries().get(0));
        assertThatThrownBy(() -> queries.execute(CoreMetrics.OPEN_ISSUES_BY_ORG, json("{\"severity\":\"URGENT\"}"), ActionFixture.admin()))
            .isInstanceOf(LsBizException.class).satisfies(e -> {
                LsBizException b = (LsBizException) e;
                assertThat(b.getCode()).isEqualTo("VALIDATION_FAILED");
                assertThat(b.getDetails().toString()).contains("severity");
            });
        assertThatThrownBy(() -> queries.execute(CoreMetrics.OPEN_ISSUES_BY_ORG, json("{\"limit\":5}"), ActionFixture.admin()))
            .isInstanceOf(LsBizException.class);

        // 数据域：甲班的人只看到甲班
        CoreFixture.Plant p = core.plant();
        Party rep = core.employee("报告人", p.teamA());
        ActorContext reporter = a.human(rep, DataScopeLevel.TEAM, "lsmom:issue:raise");
        for (var org : List.of(p.teamA(), p.teamB())) {
            String id = a.op("ls.core.Issue.raise", ActionFixture.admin(), "{\"type\":\"DEFECT\",\"title\":\"x\",\"severity\":\"HIGH\",\"orgUnitId\":" + org.id() + "}")
                .targetId();
            core.jdbc.update("update core_issue set status = 'OPEN' where id = ?", Long.parseLong(id));
        }
        NamedQueryRegistry.Result mine = queries.execute(CoreMetrics.OPEN_ISSUES_BY_ORG, json("{\"severity\":\"HIGH\"}"), reporter);
        assertThat(mine.rows()).extracting(r -> ((Number) r.get("org_unit_id")).longValue()).containsExactly(p.teamA().id());
        NamedQueryRegistry.Result all = queries.execute(CoreMetrics.OPEN_ISSUES_BY_ORG, json("{\"severity\":\"HIGH\"}"), ActionFixture.admin());
        assertThat(all.rows()).extracting(r -> ((Number) r.get("org_unit_id")).longValue()).contains(p.teamA().id(), p.teamB().id());

        // 超时
        queries.register(new NamedQueryDef(ns + ".slow", "慢查询", null, "{\"type\":\"object\",\"properties\":{\"s\":{\"type\":\"number\"}}}",
            "select pg_sleep(:s) as slept from core_org_unit t where {{scope:t.id}} limit 1", 300, null, List.of("{\"s\":0}")));
        long t0 = System.currentTimeMillis();
        assertThatThrownBy(() -> queries.execute(ns + ".slow", json("{\"s\":3}"), ActionFixture.admin()))
            .isInstanceOf(LsBizException.class).extracting(e -> ((LsBizException) e).getCode()).isEqualTo("QUERY_TIMEOUT");
        assertThat(System.currentTimeMillis() - t0).isLessThan(2500);
        // 超时后连接仍可用
        assertThat(queries.execute(ns + ".slow", json("{\"s\":0}"), ActionFixture.admin()).rows()).hasSize(1);
    }

    @Test
    void 命名Query登记校验_写语句_缺数据域_样例不合格均被拒() {
        String schema = "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"maximum\":10}}}";
        assertThatThrownBy(() -> queries.register(new NamedQueryDef(ns + ".w", "写", null, schema,
            "select 1 from core_issue t where {{scope:t.org_unit_id}}; delete from core_issue", 1000, null, List.of("{}"))))
            .hasMessageContaining("写操作");
        assertThatThrownBy(() -> queries.register(new NamedQueryDef(ns + ".s", "无数据域", null, schema,
            "select count(*) from core_issue t", 1000, null, List.of("{}")))).hasMessageContaining("数据域占位");
        assertThatThrownBy(() -> queries.register(new NamedQueryDef(ns + ".e", "样例越界", null, schema,
            "select :n as n from core_issue t where {{scope:t.org_unit_id}}", 1000, null, List.of("{\"n\":99}")))).hasMessageContaining("样例参数");
        assertThatThrownBy(() -> queries.register(new NamedQueryDef(ns + ".x", "不能执行", null, schema,
            "select nope from core_issue t where {{scope:t.org_unit_id}}", 1000, null, List.of("{}")))).hasMessageContaining("样例无法执行");
        // 合格的登记后版本稳定，SQL 变化版本加一
        NamedQueryDef ok = new NamedQueryDef(ns + ".ok", "合格", null, schema, "select :n as n from core_issue t where {{scope:t.org_unit_id}} limit 1",
            1000, null, List.of("{\"n\":1}"));
        assertThat(queries.register(ok).version()).isEqualTo(1);
        assertThat(queries.register(ok).version()).isEqualTo(1);
        assertThat(queries.register(new NamedQueryDef(ok.code(), ok.name(), null, schema, ok.sql().replace("limit 1", "limit 2"), 1000, null,
            ok.examples())).version()).isEqualTo(2);
    }
}
