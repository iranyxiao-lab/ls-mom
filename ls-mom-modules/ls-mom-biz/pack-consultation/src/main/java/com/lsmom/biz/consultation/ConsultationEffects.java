package com.lsmom.biz.consultation;

import com.lsmom.action.state.TransitionEffect;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueService;
import com.lsmom.meta.concept.ManagedRecords;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lsmom.biz.consultation.ConsultationPack.ISSUE_TYPE;
import static com.lsmom.biz.consultation.ConsultationPack.OPINION;
import static com.lsmom.biz.consultation.ConsultationPack.POINT;
import static com.lsmom.biz.consultation.ConsultationPack.SUMMARY;
import static com.lsmom.biz.consultation.ConsultationPack.TOPIC;
import static com.lsmom.biz.consultation.ConsultationSupport.effect;
import static com.lsmom.biz.consultation.ConsultationSupport.num;
import static com.lsmom.biz.consultation.ConsultationSupport.time;

/** 议题发布 / 截止、意见答复与转整改、总结发布的迁移效果。 */
public class ConsultationEffects {

    static final Set<String> CATEGORIES = Set.of("SAFETY", "QUALITY", "PROCESS", "EQUIPMENT", "WELFARE", "OTHER");

    private final ManagedRecords records;
    private final IssueService issues;
    private final Clock clock;

    public ConsultationEffects(ManagedRecords records, IssueService issues, Clock clock) {
        this.records = records;
        this.issues = issues;
        this.clock = clock;
    }

    /** 发布征求：截止时间须晚于当前时间。 */
    public TransitionEffect open() {
        return effect(TOPIC + ".open", (ctx, before) -> {
            Map<String, Object> t = records.row(TOPIC, Long.parseLong(before.id()));
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime deadline = time(t.get("deadline"));
            if (deadline == null || !deadline.isAfter(now)) throw LsBizException.validation(Map.of("deadline", "截止时间须晚于当前时间"));
            return Map.of("openedAt", now);
        });
    }

    public TransitionEffect close() {
        return effect(TOPIC + ".close", (ctx, before) -> Map.of("closedAt", LocalDateTime.now(clock)));
    }

    /** 答复（可同时归类）。 */
    public TransitionEffect reply() {
        return effect(OPINION + ".reply", (ctx, before) -> {
            Map<String, Object> changes = new HashMap<>();
            changes.put("reply", ctx.input().get("reply").asText().trim());
            changes.put("repliedAt", LocalDateTime.now(clock));
            category(ctx.input().path("category").asText(null), changes);
            return changes;
        });
    }

    /**
     * 转整改：生成关联本意见的 Issue（草稿，所属组织取议题组织，登记人为执行人——匿名意见不暴露提交人），意见状态为已转整改。
     * 同一意见只能生成一个 Issue（来源键去重）。
     */
    public TransitionEffect toIssue() {
        return effect(OPINION + ".toIssue", (ctx, before) -> {
            long id = Long.parseLong(before.id());
            Map<String, Object> o = records.row(OPINION, id);
            Map<String, Object> topic = records.row(TOPIC, num(o.get("topic")));
            String content = String.valueOf(o.get("content"));
            String title = ctx.input().path("title").asText("").isBlank()
                ? "意见整改：" + (content.length() > 40 ? content.substring(0, 40) + "…" : content)
                : ctx.input().get("title").asText().trim();
            String desc = "来自咨询议题「" + topic.get("title") + "」的意见：\n" + content;
            Long owner = ctx.input().hasNonNull("owner") ? ctx.input().get("owner").asLong() : null;
            IssueService.RaiseResult r = issues.raise(new IssueService.RaiseIssue(ISSUE_TYPE, title, desc, ctx.input().path("severity").asText("MEDIUM"),
                num(o.get("orgId")), "CONSULTATION", "CONSULTATION:" + id, ctx.actor().effectiveParty(), owner, null, OPINION, id,
                List.of(new IssueService.ObjectRef(OPINION, String.valueOf(id)))));
            Issue issue = r.issue();
            ctx.eventPayload().put("issueId", issue.id());
            ctx.eventPayload().put("issueCode", issue.code());
            Map<String, Object> changes = new HashMap<>();
            changes.put("issue", issue.id());
            if (ctx.input().hasNonNull("reply") && !ctx.input().get("reply").asText().isBlank()) {
                changes.put("reply", ctx.input().get("reply").asText().trim());
                changes.put("repliedAt", LocalDateTime.now(clock));
            } else if (o.get("reply") == null) {
                changes.put("reply", "已转整改（" + issue.code() + "）");
                changes.put("repliedAt", LocalDateTime.now(clock));
            }
            return changes;
        });
    }

    /** 确认发布总结：议题须已截止，每个观点至少引用一条本议题的意见。 */
    public TransitionEffect publishSummary() {
        return effect(SUMMARY + ".publish", (ctx, before) -> {
            long id = Long.parseLong(before.id());
            Map<String, Object> s = records.row(SUMMARY, id);
            long topicId = num(s.get("topic"));
            if (!"CLOSED".equals(records.row(TOPIC, topicId).get("status"))) throw new LsBizException("TOPIC_NOT_CLOSED", "议题截止后才能发布总结");
            List<Map<String, Object>> points = records.children(POINT, id);
            if (points.isEmpty()) throw LsBizException.validation(Map.of("summaryPoint", "总结至少要有一个观点"));
            for (Map<String, Object> p : points) {
                List<Long> refs = ConsultationOperations.parseIds(String.valueOf(p.get("opinionIds")));
                ConsultationOperations.requireOpinionsOf(records, topicId, refs, "第 " + p.get("seq") + " 个观点");
            }
            if (records.where(SUMMARY, "topic", topicId).stream().anyMatch(x -> "PUBLISHED".equals(x.get("status")))) {
                throw new LsBizException("SUMMARY_EXISTS", "该议题已发布总结");
            }
            Map<String, Object> changes = new HashMap<>();
            changes.put("confirmedBy", ctx.actor().effectiveParty());
            changes.put("publishedAt", LocalDateTime.now(clock));
            return changes;
        });
    }

    static void category(String c, Map<String, Object> changes) {
        if (c == null || c.isBlank()) return;
        if (!CATEGORIES.contains(c)) throw LsBizException.validation(Map.of("category", "归类取值不正确"));
        changes.put("category", c);
    }
}
