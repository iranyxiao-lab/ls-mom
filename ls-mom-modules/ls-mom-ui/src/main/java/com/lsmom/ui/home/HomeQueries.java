package com.lsmom.ui.home;

import com.lsmom.crud.metric.NamedQueryDef;
import com.lsmom.crud.metric.NamedQueryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/** 首页卡片的数据：命名 Query（数据域由命名 Query 机制注入，"我的"类查询用 {{actor}}）。 */
@Configuration
public class HomeQueries {

    private static final String LIMIT = """
        {"type":"object","additionalProperties":false,"properties":{"limit":{"type":"integer","minimum":1,"maximum":20}}}""";

    /** 本班组今日工作：计划在今天的、以及正在进行或受阻的工作（数据域内）。 */
    @Bean
    public NamedQueryDef homeTeamWorkQuery() {
        return new NamedQueryDef("ls.ui.homeTeamWork", "本班组今日工作", "数据域内今天计划开展、进行中或受阻的工作；total 为总数", LIMIT, """
            select t.id, t.code, t.title, t.status, t.planned_start as "plannedStart", t.planned_end as "plannedEnd", o.name as "orgName",
                   count(*) over () as total
              from core_work_item t left join core_org_unit o on o.id = t.org_unit_id
             where t.status not in ('DONE', 'CANCELLED', 'DRAFT')
               and (t.status in ('IN_PROGRESS', 'BLOCKED', 'PENDING_CONFIRM')
                    or (t.planned_start::date <= current_date and coalesce(t.planned_end, t.planned_start)::date >= current_date))
               and {{scope:t.org_unit_id|t.created_by_party}}
             order by case t.status when 'BLOCKED' then 0 when 'IN_PROGRESS' then 1 when 'PENDING_CONFIRM' then 2 else 3 end,
                      t.planned_start nulls last, t.id desc
             limit coalesce(cast(:limit as integer), 5)""", 3000, null,
            List.of(NamedQueryRegistry.json(Map.of("limit", 5)), "{}"));
    }

    /** 待我审核：分给我或我是候选人的流程待办。 */
    @Bean
    public NamedQueryDef homeMyReviewsQuery() {
        return new NamedQueryDef("ls.ui.homeMyReviews", "待我审核", "分给我或我是候选人的流程待办（按截止时间）；total 为总数", LIMIT, """
            select t.task_id as "taskId", t.node_name as "nodeName", t.biz_type as "bizType", t.biz_id as "bizId", t.due_at as "dueAt",
                   t.created_at as "createdAt", (t.due_at is not null and t.due_at < now()) as overdue, count(*) over () as total
              from biz_task t
             where t.status = 'TODO' and t.kind in ('TASK', 'EXCEPTION', 'ESCALATION')
               and (t.assignee_party = {{actor}} or (t.assignee_party is null and t.candidates @> to_jsonb({{actor}})))
               and ({{scope:t.org_unit_id|t.assignee_party}} or t.assignee_party = {{actor}} or t.candidates @> to_jsonb({{actor}}))
             order by t.due_at nulls last, t.id
             limit coalesce(cast(:limit as integer), 5)""", 3000, null,
            List.of(NamedQueryRegistry.json(Map.of("limit", 5)), "{}"));
    }

    /** 未关闭异常：数据域内已提报、尚未关闭的异常。 */
    @Bean
    public NamedQueryDef homeOpenIssuesQuery() {
        return new NamedQueryDef("ls.ui.homeOpenIssues", "未关闭异常", "数据域内已提报、尚未关闭的异常（高严重度在前）；total 为总数", LIMIT, """
            select t.id, t.code, t.title, t.severity, t.status, t.issue_type as "issueType", t.due_at as "dueAt", o.name as "orgName",
                   count(*) over () as total
              from core_issue t left join core_org_unit o on o.id = t.org_unit_id
             where t.status in ('OPEN', 'IN_RECTIFICATION', 'PENDING_VERIFY')
               and {{scope:t.org_unit_id|t.reporter_party}}
             order by case t.severity when 'CRITICAL' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end, t.created_at desc
             limit coalesce(cast(:limit as integer), 5)""", 3000, null,
            List.of(NamedQueryRegistry.json(Map.of("limit", 5)), "{}"));
    }
}
