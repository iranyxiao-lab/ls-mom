package com.lsmom.crud.metric;

import java.util.List;
import java.util.Map;

/** 平台内置指标与命名 Query（核心对象：异常、工作）。业务 Pack 用同样的方式提供自己的定义。 */
public final class CoreMetrics {

    private CoreMetrics() {
    }

    public static final String ISSUE_COUNT = "ls.core.issueCount";
    public static final String WORK_ON_TIME_RATE = "ls.core.workOnTimeRate";
    public static final String OPEN_ISSUES_BY_ORG = "ls.core.openIssuesByOrg";

    public static List<MetricDefinition> metrics() {
        return List.of(
            MetricDefinition.parse("""
                {"code":"%s","name":"异常数","concept":"ls.core.Issue","description":"登记的异常（不含草稿与已取消），按登记时间统计",
                 "unit":"条","measure":{"fn":"count"},
                 "filters":[{"field":"status","op":"notIn","value":["DRAFT","CANCELLED"]}],
                 "timeField":"createdAt","grains":["DAY","WEEK","MONTH"],
                 "dimensions":["status","severity","issueType","orgUnitId"],
                 "drillColumns":["code","title","severity","status","orgUnitId","createdAt"]}""".formatted(ISSUE_COUNT)),
            MetricDefinition.parse("""
                {"code":"%s","name":"按期完成率","concept":"ls.core.Work",
                 "description":"已完成且有计划结束时间的工作中，实际结束不晚于计划结束的比例；按计划结束时间统计",
                 "unit":"%%","measure":{"fn":"ratio","numerator":[{"field":"actualEnd","op":"le","valueField":"plannedEnd"}]},
                 "filters":[{"field":"status","op":"eq","value":"DONE"},{"field":"plannedEnd","op":"notNull"}],
                 "timeField":"plannedEnd","grains":["DAY","WEEK","MONTH"],"dimensions":["orgUnitId","workType"],
                 "drillColumns":["code","title","orgUnitId","plannedEnd","actualEnd"]}""".formatted(WORK_ON_TIME_RATE)));
    }

    public static List<NamedQueryDef> queries() {
        return List.of(new NamedQueryDef(OPEN_ISSUES_BY_ORG, "各组织未关闭异常", "按组织统计未关闭的异常数量与最早登记时间，可按严重度筛选",
            """
                {"type":"object","additionalProperties":false,"properties":{
                  "severity":{"type":"string","enum":["LOW","MEDIUM","HIGH","CRITICAL"]},
                  "since":{"type":"string","format":"date"}}}""",
            """
                select o.id as org_unit_id, o.name as org_name, count(*) as open_count, min(t.created_at) as oldest_at
                  from core_issue t join core_org_unit o on o.id = t.org_unit_id
                 where t.status not in ('DRAFT', 'CLOSED', 'REJECTED', 'CANCELLED')
                   and (cast(:severity as varchar) is null or t.severity = :severity)
                   and (cast(:since as date) is null or t.created_at >= :since)
                   and {{scope:t.org_unit_id|t.reporter_party}}
                 group by o.id, o.name
                 order by open_count desc""",
            5000, null, List.of("{}", NamedQueryRegistry.json(Map.of("severity", "HIGH", "since", "2026-01-01")))));
    }
}
