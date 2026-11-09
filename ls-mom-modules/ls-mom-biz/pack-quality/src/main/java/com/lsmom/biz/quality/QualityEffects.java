package com.lsmom.biz.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.state.StateSnapshot;
import com.lsmom.action.state.TransitionEffect;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueService;
import com.lsmom.core.issue.IssueStatus;
import com.lsmom.core.work.AssignmentService;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkItemService;
import com.lsmom.core.work.WorkStatus;
import com.lsmom.core.work.WorkType;
import com.lsmom.meta.concept.ManagedRecords;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static com.lsmom.biz.quality.QualityPack.NC;
import static com.lsmom.biz.quality.QualityPack.RECORD;
import static com.lsmom.biz.quality.QualityPack.RECORD_LINK;

/**
 * 质量 Pack 状态机迁移的业务效果。不符合项的外壳是 Issue（NONCONFORMITY），两者状态同步推进；整改工作是 Issue 的 IMPROVEMENT 整改工作。
 */
public class QualityEffects {

    private static final Map<String, String> RESULT = Map.of("PASS", "合格", "FAIL", "不合格", "CONCESSION", "让步接收", "NA", "不适用");

    private final ManagedRecords records;
    private final IssueService issues;
    private final WorkItemService works;
    private final AssignmentService assignments;
    private final Clock clock;

    public QualityEffects(ManagedRecords records, IssueService issues, WorkItemService works, AssignmentService assignments, Clock clock) {
        this.records = records;
        this.issues = issues;
        this.works = works;
        this.assignments = assignments;
        this.clock = clock;
    }

    /** 登记质量记录：在每个关联对象的时间轴上留一条事件（关联都不是必填）。 */
    public TransitionEffect recordSubmit() {
        return effect(RECORD + ".submit", (ctx, before) -> {
            long id = Long.parseLong(before.id());
            Map<String, Object> r = records.row(RECORD, id);
            String title = "质量记录：" + r.get("title") + "（" + RESULT.getOrDefault(String.valueOf(r.get("result")), String.valueOf(r.get("result"))) + "）";
            List<String> linked = new ArrayList<>();
            for (Map<String, Object> l : records.children(RECORD_LINK, id)) {
                String type = String.valueOf(l.get("objectType"));
                String subject = QualityPack.SUBJECTS.getOrDefault(type, "external");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("title", title);
                payload.put("recordId", id);
                payload.put("result", r.get("result"));
                if (l.get("label") != null) payload.put("label", l.get("label"));
                ctx.emit(RECORD + ".linked", subject, String.valueOf(l.get("objectId")), before.orgUnitId(), payload);
                linked.add(type + ":" + l.get("objectId"));
            }
            ctx.eventPayload().put("linked", linked);
            Map<String, Object> changes = new HashMap<>();
            if (r.get("recordedAt") == null) changes.put("recordedAt", LocalDateTime.now(clock));
            if (r.get("inspector") == null) changes.put("inspector", ctx.actor().effectiveParty());
            return changes;
        });
    }

    /** 登记提交：创建 Issue 外壳（关联设备、工作、来源质量记录）并提交为待处理。条件必填由 Pack 规则在守卫阶段校验。 */
    public TransitionEffect submit() {
        return effect(NC + ".submit", (ctx, before) -> {
            long id = Long.parseLong(before.id());
            Map<String, Object> nc = records.row(NC, id);
            List<IssueService.ObjectRef> links = new ArrayList<>();
            if (nc.get("asset") != null) links.add(new IssueService.ObjectRef("ASSET", String.valueOf(nc.get("asset"))));
            if (nc.get("workItem") != null) links.add(new IssueService.ObjectRef("WORK", String.valueOf(nc.get("workItem"))));
            if (nc.get("qualityRecord") != null) links.add(new IssueService.ObjectRef(RECORD, String.valueOf(nc.get("qualityRecord"))));
            Issue i = nc.get("issue") != null ? issues.get(num(nc.get("issue")))
                : issues.raise(new IssueService.RaiseIssue(QualityPack.ISSUE_TYPE, String.valueOf(nc.get("title")), String.valueOf(nc.get("description")),
                    String.valueOf(nc.get("severity")), before.orgUnitId(), "MANUAL", null, ctx.actor().effectiveParty(), null, null, NC, id, links)).issue();
            if (i.status() == IssueStatus.DRAFT) issues.transition(i.id(), i.version(), IssueStatus.DRAFT, IssueStatus.OPEN, Map.of());
            ctx.eventPayload().put("issue", i.code());
            if (nc.get("asset") != null) {
                ctx.emit(NC + ".raised", QualityPack.SUBJECTS.get("ASSET"), String.valueOf(nc.get("asset")), before.orgUnitId(),
                    Map.of("title", "不符合项：" + nc.get("title"), "ncId", id));
            }
            return Map.of("issue", i.id());
        });
    }

    /**
     * 车间审核通过：只接受审核意见、立即措施与派工参数，记录的其他字段在该节点只读（输入出现即拒绝）；
     * Issue 进入整改中，按标题生成整改工作（IMPROVEMENT）并派给整改负责人。
     */
    public TransitionEffect audit() {
        return effect(NC + ".audit", (ctx, before) -> {
            requireAuditInput(ctx.input());
            long id = Long.parseLong(before.id());
            Map<String, Object> nc = records.row(NC, id);
            Issue i = issues.get(num(nc.get("issue")));
            long owner = ctx.longValue("owner");
            LocalDateTime dueAt = ctx.text("dueAt") == null ? null : LocalDateTime.parse(ctx.text("dueAt"));
            Map<String, Object> m = new HashMap<>();
            m.put("owner_party", owner);
            if (dueAt != null) m.put("due_at", dueAt);
            issues.transition(i.id(), i.version(), IssueStatus.OPEN, IssueStatus.IN_RECTIFICATION, m);
            List<String> titles = new ArrayList<>();
            if (ctx.input().has("titles")) ctx.input().get("titles").forEach(n -> titles.add(n.asText()));
            if (titles.isEmpty()) titles.add("整改：" + nc.get("title"));
            List<String> codes = new ArrayList<>();
            for (WorkItem w : issues.createRectifications(i.id(), titles, WorkType.IMPROVEMENT, "不符合项整改：" + nc.get("title"), ctx.actor().effectiveParty())) {
                if (dueAt != null) works.reschedule(w.id(), LocalDateTime.now(clock), dueAt);
                assignments.assign(w.id(), List.of(AssignmentService.AssignTarget.party(owner, null, dueAt)), ctx.actor().effectiveParty());
                WorkItem cur = works.get(w.id());
                cur = works.transition(cur.id(), cur.version(), WorkStatus.DRAFT, WorkStatus.READY, Map.of());
                works.transition(cur.id(), cur.version(), WorkStatus.READY, WorkStatus.ASSIGNED, Map.of());
                ctx.emit("ls.core.Work.create", "ls.core.Work", String.valueOf(w.id()), w.orgUnitId(), Map.of("source", "ISSUE", "issueId", i.id(), "ncId", id));
                codes.add(w.code());
            }
            ctx.eventPayload().put("works", codes);
            ctx.eventPayload().put("auditOpinion", ctx.text("auditOpinion"));
            Map<String, Object> changes = new HashMap<>();
            changes.put("auditOpinion", ctx.text("auditOpinion"));
            changes.put("rectifyOwner", owner);
            if (ctx.text("immediateAction") != null) changes.put("immediateAction", ctx.text("immediateAction"));
            return changes;
        });
    }

    static void requireAuditInput(JsonNode input) {
        Map<String, String> bad = new LinkedHashMap<>();
        for (Iterator<String> it = input.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!QualityPack.AUDIT_INPUT.contains(k)) bad.put(k, "车间审核节点不允许修改该字段");
        }
        if (!bad.isEmpty()) {
            throw new LsBizException("FIELD_READONLY", "车间审核只可填写审核意见与立即措施", Map.of("fields", bad, "node", "workshopAudit"));
        }
    }

    /** 审核驳回：Issue 驳回。 */
    public TransitionEffect reject() {
        return effect(NC + ".reject", (ctx, before) -> {
            requireAuditInput(ctx.input());
            Issue i = issueOf(before);
            if (i.status() == IssueStatus.OPEN) issues.transition(i.id(), i.version(), IssueStatus.OPEN, IssueStatus.REJECTED, Map.of());
            ctx.eventPayload().put("auditOpinion", ctx.text("auditOpinion"));
            return Map.of("auditOpinion", ctx.text("auditOpinion"));
        });
    }

    /** 整改完成提交验证（守卫：整改工作全部完成、有整改证据）：Issue 进入待验证。 */
    public TransitionEffect submitVerify() {
        return effect(NC + ".submitVerify", (ctx, before) -> {
            Issue i = issueOf(before);
            issues.transition(i.id(), i.version(), IssueStatus.IN_RECTIFICATION, IssueStatus.PENDING_VERIFY, Map.of());
            return Map.of();
        });
    }

    /** 终判关闭（只限真人，由 Pack 规则保证）：Issue 关闭并记下验证人、结论与时间。 */
    public TransitionEffect close() {
        return effect(NC + ".close", (ctx, before) -> {
            Issue i = issueOf(before);
            String conclusion = ctx.text("conclusion");
            LocalDateTime now = LocalDateTime.now(clock);
            Map<String, Object> m = new HashMap<>();
            m.put("verified_by", ctx.actor().effectiveParty());
            m.put("verify_conclusion", conclusion);
            m.put("verified_at", now);
            issues.transition(i.id(), i.version(), IssueStatus.PENDING_VERIFY, IssueStatus.CLOSED, m);
            ctx.eventPayload().put("conclusion", conclusion);
            Map<String, Object> changes = new HashMap<>();
            changes.put("verifyConclusion", conclusion);
            changes.put("closedBy", ctx.actor().effectiveParty());
            changes.put("closedAt", now);
            return changes;
        });
    }

    /** 验证不通过：回到整改中，通知整改负责人。 */
    public TransitionEffect verifyFail() {
        return effect(NC + ".verifyFail", (ctx, before) -> {
            Issue i = issueOf(before);
            String conclusion = ctx.text("conclusion");
            issues.transition(i.id(), i.version(), IssueStatus.PENDING_VERIFY, IssueStatus.IN_RECTIFICATION, Map.of("verify_conclusion", conclusion));
            if (i.ownerParty() != null) {
                ctx.emit("ls.core.Issue.rectificationReturned", "ls.core.Issue", String.valueOf(i.id()), i.orgUnitId(),
                    Map.of("notify", List.of(i.ownerParty()), "title", "整改验证未通过：" + i.title(), "conclusion", conclusion));
            }
            ctx.eventPayload().put("conclusion", conclusion);
            return Map.of("verifyConclusion", conclusion);
        });
    }

    Issue issueOf(StateSnapshot before) {
        Object issue = records.row(NC, Long.parseLong(before.id())).get("issue");
        if (issue == null) throw new LsBizException("NC_NO_ISSUE", "不符合项尚未登记");
        return issues.get(num(issue));
    }

    static long num(Object o) {
        return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
    }

    static TransitionEffect effect(String op, BiFunction<OperationContext, StateSnapshot, Map<String, Object>> fn) {
        return new TransitionEffect() {
            @Override
            public String operation() {
                return op;
            }

            @Override
            public Map<String, Object> apply(OperationContext ctx, StateSnapshot before) {
                return fn.apply(ctx, before);
            }
        };
    }
}
