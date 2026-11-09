package com.lsmom.form;

import com.lsmom.action.ActionFixture;
import com.lsmom.action.gateway.InputSchemaValidator;
import com.lsmom.core.error.LsBizException;
import com.lsmom.crud.metric.CoreMetrics;
import com.lsmom.crud.metric.MetricDefinition;
import com.lsmom.crud.metric.MetricRegistry;
import com.lsmom.crud.metric.NamedQueryRegistry;
import org.junit.jupiter.api.Test;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 11.2：看板只绑定已发布 Metric / 已登记命名 Query；含 SQL 的看板发布被拒；维度与粒度受口径约束。 */
class DashboardBindingTest {

    private final FormFixture f = new FormFixture();
    private final MetricRegistry metrics = new MetricRegistry(f.crud.action.core.jdbc, f.crud.action.concepts, f.crud.action.txm);
    private final NamedQueryRegistry queries = new NamedQueryRegistry(f.crud.action.core.jdbc, new InputSchemaValidator(), f.crud.action.txm);

    DashboardBindingTest() {
        f.validator.setDashboardCatalog(metrics, queries);
        for (MetricDefinition m : CoreMetrics.metrics()) metrics.publish(m, null);
        CoreMetrics.queries().forEach(queries::register);
    }

    private String board(String widgets) {
        return """
            {"viewType":"DASHBOARD","formCode":"acme.qa%s.board","version":1,"title":"质量看板","widgets":[%s]}"""
            .formatted(Long.toString(System.nanoTime(), 36).replaceAll("[^a-z0-9]", ""), widgets);
    }

    @Test
    void 合法看板通过并可发布() {
        String b = board("""
            {"type":"metricCard","title":"本周异常数","metric":"ls.core.issueCount","range":"thisWeek"},
            {"type":"trend","title":"按期完成率","metric":"ls.core.workOnTimeRate","grain":"WEEK","range":"last30Days"},
            {"type":"bar","title":"按严重度","metric":"ls.core.issueCount","dimensions":["severity"]},
            {"type":"table","title":"未关闭","query":"ls.core.openIssuesByOrg","params":{"severity":"HIGH"}}""");
        assertThat(f.validator.validate(json(b)).issues()).isEmpty();
        var draft = f.forms.saveDraft(json(b), 1L, "看板");
        assertThat(draft.issues()).isEmpty();
    }

    @Test
    void 看板含SQL发布被拒() {
        String b = board("""
            {"type":"metricCard","title":"异常","metric":"ls.core.issueCount","description":"select count(*) from core_issue"}""");
        assertThat(f.validator.validate(json(b)).issues()).extracting(ViewIssue::code).contains("DASHBOARD_SQL_FORBIDDEN");
        assertThatThrownBy(() -> f.validator.require(json(b))).isInstanceOf(LsBizException.class).hasMessageContaining("SQL");
        // 直接写 sql 属性在结构校验就被拒
        String raw = board("{\"type\":\"table\",\"title\":\"x\",\"query\":\"ls.core.openIssuesByOrg\",\"sql\":\"select 1\"}");
        assertThat(f.validator.validate(json(raw)).issues()).isNotEmpty();
    }

    @Test
    void 未发布指标_未登记查询_口径外维度与粒度被拒() {
        String b = board("""
            {"type":"metricCard","title":"a","metric":"acme.none.metric"},
            {"type":"table","title":"b","query":"acme.none.query"},
            {"type":"bar","title":"c","metric":"ls.core.issueCount","dimensions":["title"]},
            {"type":"trend","title":"d","metric":"ls.core.issueCount","grain":"MONTH"},
            {"type":"trend","title":"e","metric":"ls.core.issueCount"}""");
        assertThat(f.validator.validate(json(b)).issues()).extracting(ViewIssue::code)
            .contains("DASHBOARD_METRIC_NOT_FOUND", "DASHBOARD_QUERY_NOT_FOUND", "DASHBOARD_DIMENSION_INVALID", "DASHBOARD_GRAIN_REQUIRED")
            .doesNotContain("DASHBOARD_GRAIN_INVALID");
        String g = board("{\"type\":\"trend\",\"title\":\"d\",\"metric\":\"ls.core.workOnTimeRate\",\"grain\":\"DAY\",\"dimensions\":[\"workType\"]}");
        assertThat(f.validator.validate(json(g)).issues()).isEmpty();
    }
}
