package com.lsmom.action.summary;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.SummaryContextProvider;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueService;
import com.lsmom.core.work.WorkItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 异常摘要的上下文：异常本身、整改工作、事件时间线。 */
@Component
public class IssueSummaryContext implements SummaryContextProvider {

    private final IssueService issues;
    private final JdbcTemplate jdbc;

    public IssueSummaryContext(IssueService issues, JdbcTemplate jdbc) {
        this.issues = issues;
        this.jdbc = jdbc;
    }

    @Override
    public String kind() {
        return "ISSUE";
    }

    @Override
    public Context load(String subjectId, ActorContext actor) {
        Issue i = issues.get(Long.parseLong(subjectId));
        if (!actor.dataScope().allowsOrg(i.orgUnitId())) throw new LsBizException("ACCESS_DENIED", "无权查看该异常", Map.of("issueId", subjectId));
        List<Fact> facts = new ArrayList<>();
        String id = String.valueOf(i.id());
        facts.add(new Fact(new Ref("ls.core.Issue", id, i.code()), "异常 " + i.code() + "「" + i.title() + "」类型 " + i.type()
            + "，严重度 " + i.severity() + "，状态 " + i.status() + "，发生 " + i.occurrenceCount() + " 次"
            + (i.description() == null || i.description().isBlank() ? "" : "；描述：" + i.description()), i.createdAt()));
        if (i.verifyConclusion() != null) {
            facts.add(new Fact(new Ref("ls.core.Issue", id, i.code()), "验证结论：" + i.verifyConclusion(), i.verifiedAt()));
        }
        for (WorkItem w : issues.rectifications(i.id())) {
            facts.add(new Fact(new Ref("ls.core.Work", String.valueOf(w.id()), w.code()), "整改工作 " + w.code() + "「" + w.title() + "」状态 "
                + w.status() + (w.plannedEnd() == null ? "" : "，计划完成 " + w.plannedEnd()), w.createdAt()));
        }
        events("ls.core.Issue", id, facts);
        return new Context("ls.core.Issue", id, "异常 " + i.code() + " " + i.title(), i.orgUnitId(), facts);
    }

    /** 事件时间线（按发生时间，最多 50 条）。 */
    void events(String concept, String id, List<Fact> facts) {
        jdbc.queryForList("""
            select event_id, type, occurred_at, payload::text as payload from core_domain_event
            where subject_concept = ? and subject_id = ? order by occurred_at limit 50""", concept, id).forEach(e -> {
            String type = (String) e.get("type");
            String payload = (String) e.get("payload");
            facts.add(new Fact(new Ref("ls.core.Event", e.get("event_id").toString(), type.substring(type.lastIndexOf('.') + 1)),
                "事件 " + type + ("{}".equals(payload) ? "" : "：" + payload), ((Timestamp) e.get("occurred_at")).toLocalDateTime()));
        });
    }
}
