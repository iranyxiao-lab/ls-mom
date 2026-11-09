package com.lsmom.biz.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.state.StateSnapshot;
import com.lsmom.action.state.TransitionEffect;
import com.lsmom.core.document.DocumentService;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueService;
import com.lsmom.core.issue.IssueStatus;
import com.lsmom.core.observation.Observation;
import com.lsmom.core.observation.ObservationService;
import com.lsmom.core.party.PartyService;
import com.lsmom.core.work.Assignment;
import com.lsmom.core.work.AssignmentService;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkItemService;
import com.lsmom.core.work.WorkStatus;
import com.lsmom.core.work.WorkType;
import com.lsmom.meta.concept.ManagedRecords;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static com.lsmom.biz.asset.AssetPack.ASSET;
import static com.lsmom.biz.asset.AssetPack.DEFECT;
import static com.lsmom.biz.asset.AssetPack.ISSUE_TYPE;
import static com.lsmom.biz.asset.AssetPack.OBJECT_TYPE;
import static com.lsmom.biz.asset.AssetPack.PLAN;
import static com.lsmom.biz.asset.AssetPack.PLAN_ITEM;
import static com.lsmom.biz.asset.AssetPack.RECORD;
import static com.lsmom.biz.asset.AssetPack.RECORD_ITEM;
import static com.lsmom.biz.asset.AssetPack.STATUS_LOG;

/**
 * 设备 Pack 状态机迁移的业务效果（与迁移同一事务，抛错整体回滚；返回值是随状态一起写入的字段）。
 * 缺陷的外壳是 Issue：缺陷每次迁移同时把 Issue 推进到对应状态，Issue 的整改工作即维修工作（MAINTENANCE）。
 */
public class AssetEffects {

    private final JdbcTemplate jdbc;
    private final ManagedRecords records;
    private final WorkItemService works;
    private final AssignmentService assignments;
    private final IssueService issues;
    private final ObservationService observations;
    private final DocumentService docs;
    private final PartyService parties;
    private final Clock clock;

    public AssetEffects(JdbcTemplate jdbc, ManagedRecords records, WorkItemService works, AssignmentService assignments, IssueService issues,
                        ObservationService observations, DocumentService docs, PartyService parties, Clock clock) {
        this.jdbc = jdbc;
        this.records = records;
        this.works = works;
        this.assignments = assignments;
        this.issues = issues;
        this.observations = observations;
        this.docs = docs;
        this.parties = parties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 设备状态

    /** 设备状态变更：写一条状态历史（原状态、新状态、原因、操作人），并记下最近一次原因。 */
    public TransitionEffect status(String op, String to) {
        return effect(ASSET + "." + op, (ctx, before) -> {
            long id = Long.parseLong(before.id());
            String reason = ctx.text("reason");
            Map<String, Object> log = new LinkedHashMap<>();
            log.put("asset", id);
            log.put("fromStatus", before.status());
            log.put("toStatus", to);
            log.put("reason", reason);
            log.put("operator", ctx.actor().effectiveParty());
            log.put("changedAt", LocalDateTime.now(clock));
            records.insert(STATUS_LOG, before.orgUnitId(), "DRAFT", ctx.actor().effectiveParty(), log);
            if (reason != null) ctx.eventPayload().put("reason", reason);
            Map<String, Object> changes = new HashMap<>();
            changes.put("statusReason", reason);
            if ("commission".equals(op) && records.row(ASSET, id).get("commissionedOn") == null) changes.put("commissionedOn", LocalDate.now(clock));
            return changes;
        });
    }

    // ---------------------------------------------------------------- 点检计划 → 点检工作

    /**
     * 按计划生成一次点检：INSPECTION 工作（分派给责任人并占用该设备）＋点检记录草稿（检查项从计划复制），下次点检日期顺延一个周期。
     * 同一计划同一应检日期已生成过时不重复生成，只顺延。
     */
    public TransitionEffect generate() {
        return effect(PLAN + ".generate", (ctx, before) -> {
            long planId = Long.parseLong(before.id());
            Map<String, Object> plan = records.row(PLAN, planId);
            List<Map<String, Object>> items = records.children(PLAN_ITEM, planId);
            if (items.isEmpty()) throw new LsBizException("PLAN_NO_ITEMS", "点检计划没有检查项");
            long assetId = num(plan.get("asset"));
            Map<String, Object> asset = records.row(ASSET, assetId);
            if ("SCRAPPED".equals(asset.get("status"))) throw new LsBizException("ASSET_SCRAPPED", "设备已报废，不能再生成点检");
            LocalDate due = date(plan.get("nextDue"));
            String cycle = String.valueOf(plan.get("cycle"));
            Map<String, Object> changes = new HashMap<>();
            changes.put("nextDue", next(due, cycle));
            changes.put("lastGeneratedAt", LocalDateTime.now(clock));
            Integer exists = jdbc.queryForObject("select count(*) from " + records.table(RECORD) + " where " + records.column(RECORD, "plan")
                + " = ? and " + records.column(RECORD, "dueDate") + " = ? and del_flag = '0'", Integer.class, planId, due);
            if (exists != null && exists > 0) {
                ctx.warn("该计划 " + due + " 的点检已生成，只顺延下次点检日期");
                return changes;
            }
            Long by = ctx.actor().effectiveParty();
            Long responsible = plan.get("responsible") == null ? null : num(plan.get("responsible"));
            // 点检工作与记录归责任人所在组织（班组能在自己的数据域里看到并填报），没有时归计划所在组织
            Long org = responsible == null ? before.orgUnitId() : parties.primaryOrgUnitId(responsible).orElse(before.orgUnitId());
            LocalDateTime start = due.atStartOfDay();
            LocalDateTime end = due.atTime(LocalTime.of(23, 59));
            WorkItem w = works.create(WorkItemService.CreateWorkItem.of(WorkType.INSPECTION, "点检：" + asset.get("name") + " " + due,
                String.valueOf(plan.get("name")), org).withSource(PLAN, planId).withPlan(start, end).createdBy(by));
            List<AssignmentService.AssignTarget> targets = new ArrayList<>();
            if (responsible != null) targets.add(AssignmentService.AssignTarget.party(responsible, start, end));
            targets.add(new AssignmentService.AssignTarget(Assignment.ResourceType.EQUIPMENT, String.valueOf(assetId), "ASSIST", start, end));
            assignments.assign(w.id(), targets, by).conflicts().forEach(c -> ctx.warn("资源时间冲突：" + c.workCode() + " " + c.workTitle()));
            advance(w.id(), responsible != null ? WorkStatus.ASSIGNED : WorkStatus.READY);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("plan", planId);
            rec.put("asset", assetId);
            rec.put("workItem", w.id());
            rec.put("inspector", responsible);
            rec.put("dueDate", due);
            long recordId = records.insert(RECORD, org, "DRAFT", by, rec);
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Map<String, Object> it : items) {
                Map<String, Object> l = new LinkedHashMap<>();
                for (String k : List.of("itemCode", "name", "standard", "valueType", "unit", "lowerLimit", "upperLimit")) l.put(k, it.get(k));
                lines.add(l);
            }
            records.replaceChildren(RECORD_ITEM, recordId, lines);
            ctx.emit("ls.core.Work.create", "ls.core.Work", String.valueOf(w.id()), w.orgUnitId(), Map.of("source", PLAN, "planId", planId));
            ctx.eventPayload().put("workId", w.id());
            ctx.eventPayload().put("work", w.code());
            ctx.eventPayload().put("recordId", recordId);
            return changes;
        });
    }

    static LocalDate next(LocalDate due, String cycle) {
        return switch (cycle) {
            case "WEEKLY" -> due.plusWeeks(1);
            case "MONTHLY" -> due.plusMonths(1);
            default -> due.plusDays(1);
        };
    }

    /** 新建的工作直接推进到就绪 / 已分派（生成即派给计划责任人）。 */
    private void advance(long workId, WorkStatus target) {
        WorkItem w = works.get(workId);
        if (w.status() == WorkStatus.DRAFT) w = works.transition(workId, w.version(), WorkStatus.DRAFT, WorkStatus.READY, Map.of());
        if (target == WorkStatus.ASSIGNED && w.status() == WorkStatus.READY) works.transition(workId, w.version(), WorkStatus.READY, WorkStatus.ASSIGNED, Map.of());
    }

    // ---------------------------------------------------------------- 点检记录

    /**
     * 提交点检：逐项写入结果（数值超出上下限自动判异常），每项产生一条 Observation；有异常项时提示可一键生成缺陷草稿；
     * 对应的点检工作随之完成。
     */
    public TransitionEffect submit() {
        return effect(RECORD + ".submit", (ctx, before) -> {
            long id = Long.parseLong(before.id());
            Map<String, Object> rec = records.row(RECORD, id);
            long assetId = num(rec.get("asset"));
            Map<String, JsonNode> input = new HashMap<>();
            if (ctx.input().has("items")) ctx.input().get("items").forEach(n -> input.put(n.get("itemCode").asText(), n));
            List<Map<String, Object>> items = records.children(RECORD_ITEM, id);
            if (items.isEmpty()) throw new LsBizException("INSPECTION_NO_ITEMS", "点检记录没有检查项");
            Map<String, String> missing = new LinkedHashMap<>();
            List<String> abnormal = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now(clock);
            Long by = ctx.actor().effectiveParty();
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Map<String, Object> it : items) {
                Map<String, Object> l = line(it);
                JsonNode in = input.get(String.valueOf(it.get("itemCode")));
                if (in != null) {
                    if (in.has("valueNum")) l.put("valueNum", in.get("valueNum").isNull() ? null : in.get("valueNum").decimalValue());
                    if (in.has("outcome")) l.put("outcome", in.get("outcome").isNull() ? null : in.get("outcome").asText());
                    if (in.has("note")) l.put("note", in.get("note").isNull() ? null : in.get("note").asText());
                }
                BigDecimal v = l.get("valueNum") == null ? null : new BigDecimal(String.valueOf(l.get("valueNum")));
                boolean numeric = "NUMBER".equals(l.get("valueType"));
                if (numeric && v == null) {
                    missing.put(String.valueOf(it.get("itemCode")), it.get("name") + " 未填读数");
                } else if (numeric) {
                    BigDecimal lo = dec(l.get("lowerLimit"));
                    BigDecimal hi = dec(l.get("upperLimit"));
                    boolean out = (lo != null && v.compareTo(lo) < 0) || (hi != null && v.compareTo(hi) > 0);
                    if (out) l.put("outcome", "ABNORMAL");
                    else if (l.get("outcome") == null) l.put("outcome", "OK");
                } else if (l.get("outcome") == null) {
                    missing.put(String.valueOf(it.get("itemCode")), it.get("name") + " 未判定");
                }
                if ("ABNORMAL".equals(l.get("outcome"))) abnormal.add(String.valueOf(it.get("name")));
                lines.add(l);
            }
            if (!missing.isEmpty()) throw LsBizException.validation(missing);
            records.replaceChildren(RECORD_ITEM, id, lines);
            for (Map<String, Object> l : lines) {
                BigDecimal v = dec(l.get("valueNum"));
                boolean bad = "ABNORMAL".equals(l.get("outcome"));
                observations.record(new Observation(null, OBJECT_TYPE, String.valueOf(assetId), String.valueOf(l.get("itemCode")), v,
                    v == null ? String.valueOf(l.get("outcome")) : null, (String) l.get("unit"), now, Observation.Source.MANUAL,
                    bad ? "BAD" : "GOOD", before.orgUnitId(), by));
            }
            if (!abnormal.isEmpty()) {
                ctx.warn("有 " + abnormal.size() + " 项点检异常：" + String.join("、", abnormal) + "，可一键生成缺陷草稿");
                ctx.eventPayload().put("abnormal", abnormal);
            }
            finishWork(rec.get("workItem"), ctx);
            Map<String, Object> changes = new HashMap<>();
            changes.put("inspectedAt", now);
            changes.put("inspector", by);
            changes.put("result", abnormal.isEmpty() ? "NORMAL" : "ABNORMAL");
            changes.put("abnormalCount", abnormal.size());
            return changes;
        });
    }

    private static final List<String> RECORD_ITEM_FIELDS = List.of("itemCode", "name", "standard", "valueType", "unit", "lowerLimit",
        "upperLimit", "valueNum", "outcome", "note", "defect");

    /** 子表行只保留字段（去掉 id、行号等系统列），用于整体重建子表。 */
    private static Map<String, Object> line(Map<String, Object> row) {
        Map<String, Object> l = new LinkedHashMap<>();
        for (String k : RECORD_ITEM_FIELDS) l.put(k, row.get(k));
        return l;
    }

    private void finishWork(Object workId, OperationContext ctx) {
        if (workId == null) return;
        long wid = num(workId);
        WorkItem w = works.get(wid);
        if (w.status().terminal()) return;
        LocalDateTime now = LocalDateTime.now(clock);
        if (w.status() == WorkStatus.DRAFT) w = works.transition(wid, w.version(), WorkStatus.DRAFT, WorkStatus.READY, Map.of());
        if (w.status() == WorkStatus.READY) w = works.transition(wid, w.version(), WorkStatus.READY, WorkStatus.ASSIGNED, Map.of());
        if (w.status() == WorkStatus.ASSIGNED) w = works.transition(wid, w.version(), WorkStatus.ASSIGNED, WorkStatus.IN_PROGRESS, Map.of("actual_start", now));
        if (w.status() == WorkStatus.BLOCKED) w = works.transition(wid, w.version(), WorkStatus.BLOCKED, WorkStatus.IN_PROGRESS, Map.of());
        if (w.status() == WorkStatus.IN_PROGRESS || w.status() == WorkStatus.PENDING_CONFIRM) {
            Map<String, Object> m = new HashMap<>();
            m.put("actual_end", now);
            m.put("progress", 100);
            if (w.actualStart() == null) m.put("actual_start", now);
            works.transition(wid, w.version(), w.status(), WorkStatus.DONE, m);
        }
        ctx.emit("ls.core.Work.confirm", "ls.core.Work", String.valueOf(wid), w.orgUnitId(), Map.of("source", RECORD, "recordId", ctx.targetId()));
    }

    /** 异常项一键生成缺陷草稿：每个尚未生成缺陷的异常项一条缺陷（DRAFT）＋Issue 草稿（关联该设备与点检记录）。 */
    public TransitionEffect raiseDefects() {
        return effect(RECORD + ".raiseDefects", (ctx, before) -> {
            long id = Long.parseLong(before.id());
            Map<String, Object> rec = records.row(RECORD, id);
            long assetId = num(rec.get("asset"));
            Map<String, Object> asset = records.row(ASSET, assetId);
            List<String> only = new ArrayList<>();
            if (ctx.input().has("itemCodes")) ctx.input().get("itemCodes").forEach(n -> only.add(n.asText()));
            List<Map<String, Object>> items = records.children(RECORD_ITEM, id);
            List<Map<String, Object>> lines = new ArrayList<>();
            List<Long> created = new ArrayList<>();
            Long by = ctx.actor().effectiveParty();
            for (Map<String, Object> it : items) {
                Map<String, Object> l = line(it);
                String code = String.valueOf(it.get("itemCode"));
                if ("ABNORMAL".equals(it.get("outcome")) && it.get("defect") == null && (only.isEmpty() || only.contains(code))) {
                    String title = asset.get("name") + " " + it.get("name") + "异常";
                    StringBuilder symptom = new StringBuilder("点检发现：").append(it.get("name"));
                    if (it.get("valueNum") != null) symptom.append(" 读数 ").append(it.get("valueNum")).append(it.get("unit") == null ? "" : it.get("unit"));
                    if (it.get("standard") != null) symptom.append("（标准：").append(it.get("standard")).append("）");
                    if (it.get("note") != null) symptom.append("；").append(it.get("note"));
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("asset", assetId);
                    d.put("title", title);
                    d.put("symptom", symptom.toString());
                    d.put("severity", "MEDIUM");
                    d.put("inspectionRecord", id);
                    d.put("inspectionItem", code);
                    long defectId = records.insert(DEFECT, before.orgUnitId(), "DRAFT", by, d);
                    Issue issue = raiseIssue(defectId, assetId, title, symptom.toString(), "MEDIUM", before.orgUnitId(), by,
                        "INSPECTION", "INSPECTION:" + id + ":" + code);
                    issues.link(issue.id(), new IssueService.ObjectRef(RECORD, String.valueOf(id)), "SOURCE");
                    records.update(DEFECT, defectId, Map.of("issue", issue.id()));
                    l.put("defect", defectId);
                    created.add(defectId);
                }
                lines.add(l);
            }
            if (created.isEmpty()) throw new LsBizException("NO_ABNORMAL_ITEMS", "没有可生成缺陷的异常项");
            records.replaceChildren(RECORD_ITEM, id, lines);
            ctx.eventPayload().put("defects", created);
            int prev = rec.get("defectCount") == null ? 0 : ((Number) rec.get("defectCount")).intValue();
            return Map.of("defectCount", prev + created.size());
        });
    }

    private Issue raiseIssue(long defectId, long assetId, String title, String desc, String severity, Long org, Long by, String srcType, String srcKey) {
        return issues.raise(new IssueService.RaiseIssue(ISSUE_TYPE, title, desc, severity, org, srcType, srcKey, by, null, null, DEFECT, defectId,
            List.of(new IssueService.ObjectRef(OBJECT_TYPE, String.valueOf(assetId))))).issue();
    }

    // ---------------------------------------------------------------- 缺陷 → 维修 → 验证

    /** 登记缺陷：没有 Issue 外壳时创建（关联设备），Issue 由草稿提交为待处理。高、严重级别须先上传现场证据。 */
    public TransitionEffect report() {
        return effect(DEFECT + ".report", (ctx, before) -> {
            long id = Long.parseLong(before.id());
            Map<String, Object> d = records.row(DEFECT, id);
            String severity = String.valueOf(d.get("severity"));
            Map<String, Object> changes = new HashMap<>();
            Issue i;
            if (d.get("issue") == null) {
                i = raiseIssue(id, num(d.get("asset")), String.valueOf(d.get("title")), d.get("symptom") == null ? "" : String.valueOf(d.get("symptom")),
                    severity, before.orgUnitId(), ctx.actor().effectiveParty(), "MANUAL", null);
                changes.put("issue", i.id());
            } else {
                i = issues.get(num(d.get("issue")));
            }
            if (List.of("HIGH", "CRITICAL").contains(severity)) {
                boolean evidence = docs.listByObject(DEFECT, String.valueOf(id)).stream().anyMatch(x -> "EVIDENCE".equals(x.purpose()))
                    || docs.listByObject("ISSUE", String.valueOf(i.id())).stream().anyMatch(x -> "EVIDENCE".equals(x.purpose()));
                if (!evidence) throw new LsBizException("EVIDENCE_REQUIRED", "严重度为 " + severity + " 的缺陷登记前必须上传现场证据");
            }
            if (i.status() == IssueStatus.DRAFT) issues.transition(i.id(), i.version(), IssueStatus.DRAFT, IssueStatus.OPEN, Map.of());
            ctx.eventPayload().put("issueId", i.id());
            ctx.eventPayload().put("issue", i.code());
            return changes;
        });
    }

    /**
     * 生成维修工作：Issue 进入整改中，按标题生成 MAINTENANCE 工作（来源指向 Issue，占用该设备），可附维修检查清单、指定维修人与期限。
     * 验证不通过退回后可再次生成。
     */
    public TransitionEffect createRepair() {
        return effect(DEFECT + ".createRepair", (ctx, before) -> {
            long id = Long.parseLong(before.id());
            Map<String, Object> d = records.row(DEFECT, id);
            Issue i = issues.get(num(d.get("issue")));
            Long assignee = ctx.longValue("assignee");
            LocalDateTime dueAt = ctx.text("dueAt") == null ? null : LocalDateTime.parse(ctx.text("dueAt"));
            if (i.status() == IssueStatus.OPEN) {
                Map<String, Object> m = new HashMap<>();
                if (assignee != null) m.put("owner_party", assignee);
                if (dueAt != null) m.put("due_at", dueAt);
                i = issues.transition(i.id(), i.version(), IssueStatus.OPEN, IssueStatus.IN_RECTIFICATION, m);
            } else if (i.status() != IssueStatus.IN_RECTIFICATION) {
                throw new LsBizException("ISSUE_STATE", "缺陷对应的 Issue 当前为 " + i.status() + "，不能生成维修工作");
            }
            List<String> titles = new ArrayList<>();
            if (ctx.input().has("titles")) ctx.input().get("titles").forEach(n -> titles.add(n.asText()));
            if (titles.isEmpty()) titles.add("维修：" + d.get("title"));
            List<String> checklist = new ArrayList<>();
            if (ctx.input().has("checklist")) ctx.input().get("checklist").forEach(n -> checklist.add(n.asText()));
            StringBuilder desc = new StringBuilder("缺陷：").append(d.get("title"));
            if (!checklist.isEmpty()) {
                desc.append("\n维修检查清单：");
                for (int k = 0; k < checklist.size(); k++) desc.append("\n").append(k + 1).append(". ").append(checklist.get(k));
            }
            Long by = ctx.actor().effectiveParty();
            long assetId = num(d.get("asset"));
            List<String> codes = new ArrayList<>();
            for (WorkItem w : issues.createRectifications(i.id(), titles, WorkType.MAINTENANCE, desc.toString(), by)) {
                LocalDateTime start = dueAt == null ? null : LocalDateTime.now(clock);
                if (dueAt != null) works.reschedule(w.id(), start, dueAt);
                List<AssignmentService.AssignTarget> targets = new ArrayList<>();
                if (assignee != null) targets.add(AssignmentService.AssignTarget.party(assignee, start, dueAt));
                targets.add(new AssignmentService.AssignTarget(Assignment.ResourceType.EQUIPMENT, String.valueOf(assetId), "ASSIST", start, dueAt));
                assignments.assign(w.id(), targets, by).conflicts().forEach(c -> ctx.warn("资源时间冲突：" + c.workCode() + " " + c.workTitle()));
                advance(w.id(), assignee != null ? WorkStatus.ASSIGNED : WorkStatus.READY);
                ctx.emit("ls.core.Work.create", "ls.core.Work", String.valueOf(w.id()), w.orgUnitId(), Map.of("source", "ISSUE", "issueId", i.id(), "defectId", id));
                codes.add(w.code());
            }
            ctx.eventPayload().put("works", codes);
            Map<String, Object> changes = new HashMap<>();
            if (!checklist.isEmpty()) changes.put("repairChecklist", String.join("\n", checklist));
            return changes;
        });
    }

    /** 维修完成提交验证（守卫：维修工作全部完成）：Issue 进入待验证。 */
    public TransitionEffect submitVerify() {
        return effect(DEFECT + ".submitVerify", (ctx, before) -> {
            Issue i = issueOf(before);
            issues.transition(i.id(), i.version(), IssueStatus.IN_RECTIFICATION, IssueStatus.PENDING_VERIFY, Map.of());
            return Map.of();
        });
    }

    /** 验证通过：Issue 关闭并记下验证人、结论与时间。 */
    public TransitionEffect verifyPass() {
        return effect(DEFECT + ".verifyPass", (ctx, before) -> {
            Issue i = issueOf(before);
            String conclusion = ctx.text("conclusion");
            Map<String, Object> m = new HashMap<>();
            m.put("verified_by", ctx.actor().effectiveParty());
            m.put("verify_conclusion", conclusion);
            m.put("verified_at", LocalDateTime.now(clock));
            issues.transition(i.id(), i.version(), IssueStatus.PENDING_VERIFY, IssueStatus.CLOSED, m);
            ctx.eventPayload().put("verifier", ctx.actor().effectiveParty());
            ctx.eventPayload().put("conclusion", conclusion);
            return Map.of("verifyConclusion", conclusion);
        });
    }

    /** 验证不通过：回到维修中（可再生成维修工作），通知维修负责人。 */
    public TransitionEffect verifyFail() {
        return effect(DEFECT + ".verifyFail", (ctx, before) -> {
            Issue i = issueOf(before);
            String conclusion = ctx.text("conclusion");
            issues.transition(i.id(), i.version(), IssueStatus.PENDING_VERIFY, IssueStatus.IN_RECTIFICATION, Map.of("verify_conclusion", conclusion));
            if (i.ownerParty() != null) {
                ctx.emit("ls.core.Issue.rectificationReturned", "ls.core.Issue", String.valueOf(i.id()), i.orgUnitId(),
                    Map.of("notify", List.of(i.ownerParty()), "title", "维修验证未通过：" + i.title(), "conclusion", conclusion));
            }
            ctx.eventPayload().put("conclusion", conclusion);
            return Map.of("verifyConclusion", conclusion);
        });
    }

    /** 取消缺陷：Issue 草稿 / 待处理时一并取消。 */
    public TransitionEffect cancel() {
        return effect(DEFECT + ".cancel", (ctx, before) -> {
            Map<String, Object> d = records.row(DEFECT, Long.parseLong(before.id()));
            if (d.get("issue") != null) {
                Issue i = issues.get(num(d.get("issue")));
                if (i.status() == IssueStatus.DRAFT || i.status() == IssueStatus.OPEN) {
                    issues.transition(i.id(), i.version(), i.status(), IssueStatus.CANCELLED, Map.of());
                }
            }
            ctx.eventPayload().put("reason", ctx.text("reason"));
            return Map.of();
        });
    }

    Issue issueOf(StateSnapshot before) {
        Object issue = records.row(DEFECT, Long.parseLong(before.id())).get("issue");
        if (issue == null) throw new LsBizException("DEFECT_NO_ISSUE", "缺陷尚未登记");
        return issues.get(num(issue));
    }

    static long num(Object o) {
        return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
    }

    static BigDecimal dec(Object o) {
        return o == null ? null : o instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(o));
    }

    static LocalDate date(Object o) {
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(String.valueOf(o).substring(0, 10));
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
