package com.lsmom.biz.production;

import com.lsmom.meta.concept.ManagedRecords;
import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.state.StateSnapshot;
import com.lsmom.action.state.TransitionEffect;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.work.AssignmentService;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkItemService;
import com.lsmom.core.work.WorkType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lsmom.flow.notice.Notices;
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
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

import static com.lsmom.biz.production.ProductionPack.CONTEXT;
import static com.lsmom.biz.production.ProductionPack.HANDOVER;
import static com.lsmom.biz.production.ProductionPack.HANDOVER_ITEM;
import static com.lsmom.biz.production.ProductionPack.REQUEST;
import static com.lsmom.biz.production.ProductionPack.SHIFT_PLAN;

/**
 * 轻生产状态机迁移的业务效果。每个效果与迁移在同一事务内执行，抛错即整体回滚；返回值是随状态一起写入的字段。
 */
public class ProductionEffects {

    private final JdbcTemplate jdbc;
    private final ManagedRecords records;
    private final WorkItemService works;
    private final AssignmentService assignments;
    private final HandoverSummarizer summarizer;
    private final Clock clock;
    private final Notices notices;

    public ProductionEffects(JdbcTemplate jdbc, ManagedRecords records, WorkItemService works, AssignmentService assignments,
                             HandoverSummarizer summarizer, Clock clock) {
        this.jdbc = jdbc;
        this.notices = new Notices(jdbc);
        this.records = records;
        this.works = works;
        this.assignments = assignments;
        this.summarizer = summarizer;
        this.clock = clock;
    }

    /** 需求转工作：每条生成一个 PRODUCTION 工作（来源指向需求），附生产上下文；给了班组与班次时同时生成班次安排。 */
    public TransitionEffect convert() {
        return effect(REQUEST + ".convert", (ctx, before) -> {
            long reqId = Long.parseLong(before.id());
            Map<String, Object> req = records.row(REQUEST, reqId);
            Long org = before.orgUnitId();
            Long by = ctx.actor().effectiveParty();
            LocalDate due = req.get("dueDate") == null ? null : LocalDate.parse(String.valueOf(req.get("dueDate")).substring(0, 10));
            List<String> codes = new ArrayList<>();
            for (JsonNode w : ctx.input().get("works")) {
                ShiftWindow win = w.hasNonNull("shiftDate") && w.hasNonNull("shift") ? ShiftWindow.of(w.get("shiftDate").asText(), w.get("shift").asText()) : null;
                LocalDateTime start = win == null ? null : win.start();
                LocalDateTime end = win != null ? win.end() : due == null ? null : due.atTime(LocalTime.of(23, 59));
                WorkItem item = works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, w.get("title").asText(),
                        String.valueOf(req.get("title")), org).withSource(REQUEST, reqId).withPlan(start, end).createdBy(by));
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("workItem", item.id());
                c.put("request", reqId);
                c.put("plannedQty", w.hasNonNull("quantity") ? w.get("quantity").decimalValue() : null);
                c.put("unit", w.hasNonNull("unit") ? w.get("unit").asText() : null);
                c.put("line", w.hasNonNull("line") ? w.get("line").asText() : null);
                records.insert(CONTEXT, org, "DRAFT", by, c);
                if (win != null && w.hasNonNull("teamId")) {
                    records.insert(SHIFT_PLAN, org, "ACTIVE", by, Map.of("workItem", item.id(), "team", w.get("teamId").asLong(),
                        "shiftDate", win.date(), "shift", win.shift()));
                }
                ctx.emit("ls.core.Work.create", "ls.core.Work", String.valueOf(item.id()), item.orgUnitId(),
                    Map.of("source", REQUEST, "requestId", reqId));
                codes.add(item.code());
            }
            ctx.eventPayload().put("works", codes);
            int prev = req.get("workCount") == null ? 0 : ((Number) req.get("workCount")).intValue();
            return Map.of("workCount", prev + codes.size());
        });
    }

    /** 班次安排生效：按班次时段写入工作与分派的计划时间，资源冲突按约束 Profile 提示或阻断。 */
    public TransitionEffect activate() {
        return effect(SHIFT_PLAN + ".activate", (ctx, before) -> {
            Map<String, Object> plan = records.row(SHIFT_PLAN, Long.parseLong(before.id()));
            ShiftWindow win = ShiftWindow.of(plan.get("shiftDate"), plan.get("shift"));
            long work = ((Number) plan.get("workItem")).longValue();
            requireSinglePlan(work, Long.parseLong(before.id()));
            reschedule(ctx, work, win);
            ctx.eventPayload().put("shift", win.label());
            return Map.of();
        });
    }

    /** 调整班次（看板拖动）：可同时换班组；冲突按 Profile 提示或阻断。 */
    public TransitionEffect move() {
        return effect(SHIFT_PLAN + ".move", (ctx, before) -> {
            Map<String, Object> plan = records.row(SHIFT_PLAN, Long.parseLong(before.id()));
            ShiftWindow from = ShiftWindow.of(plan.get("shiftDate"), plan.get("shift"));
            ShiftWindow to = ShiftWindow.of(ctx.text("shiftDate") != null ? ctx.text("shiftDate") : from.date(),
                ctx.text("shift") != null ? ctx.text("shift") : from.shift());
            Long team = ctx.longValue("teamId") != null ? ctx.longValue("teamId") : ((Number) plan.get("team")).longValue();
            reschedule(ctx, ((Number) plan.get("workItem")).longValue(), to);
            ctx.eventPayload().put("from", from.label());
            ctx.eventPayload().put("to", to.label());
            Map<String, Object> changes = new HashMap<>();
            changes.put("team", team);
            changes.put("shiftDate", to.date());
            changes.put("shift", to.shift());
            return changes;
        });
    }

    private void requireSinglePlan(long work, long planId) {
        Integer n = jdbc.queryForObject("select count(*) from " + records.table(SHIFT_PLAN) + " where " + records.column(SHIFT_PLAN, "workItem")
            + " = ? and id <> ? and del_flag = '0' and status = 'ACTIVE'", Integer.class, work, planId);
        if (n != null && n > 0) throw new LsBizException("SHIFT_PLAN_EXISTS", "该工作已有生效的班次安排，请直接调整班次");
    }

    private void reschedule(OperationContext ctx, long work, ShiftWindow win) {
        List<AssignmentService.Conflict> conflicts = assignments.reschedule(work, win.start(), win.end());
        conflicts.forEach(c -> ctx.warn("资源时间冲突：" + c.workCode() + " " + c.workTitle()));
        if (!conflicts.isEmpty()) ctx.eventPayload().put("conflicts", conflicts.stream().map(AssignmentService.Conflict::workCode).toList());
    }

    /** 生成交接班摘要草稿：当班工作（已完成 / 未完成）、未关闭异常、DCS 摘要；交接事项整体重建。 */
    public TransitionEffect generate() {
        return effect(HANDOVER + ".generate", (ctx, before) -> {
            long id = Long.parseLong(before.id());
            Map<String, Object> h = records.row(HANDOVER, id);
            ShiftWindow win = ShiftWindow.of(h.get("shiftDate"), h.get("shift"));
            long fromTeam = ((Number) h.get("fromTeam")).longValue();
            List<Map<String, Object>> shiftWorks = jdbc.queryForList("select w.id, w.code, w.title, w.status, w.progress, w.block_reason"
                + " from core_work_item w join " + records.table(SHIFT_PLAN) + " p on p." + records.column(SHIFT_PLAN, "workItem") + " = w.id"
                + " where p." + records.column(SHIFT_PLAN, "team") + " = ? and p." + records.column(SHIFT_PLAN, "shiftDate") + " = ?"
                + " and p." + records.column(SHIFT_PLAN, "shift") + " = ? and p.del_flag = '0' and p.status = 'ACTIVE'"
                + " and w.status <> 'CANCELLED' order by w.id", fromTeam, win.date(), win.shift());
            List<Map<String, Object>> done = shiftWorks.stream().filter(w -> "DONE".equals(w.get("status"))).toList();
            List<Map<String, Object>> open = shiftWorks.stream().filter(w -> !"DONE".equals(w.get("status"))).toList();
            List<Map<String, Object>> issues = jdbc.queryForList("""
                select id, code, title, severity, status from core_issue
                 where org_unit_id = ? and status in ('OPEN', 'IN_RECTIFICATION', 'PENDING_VERIFY') order by id limit 50""", fromTeam);
            String teamName = jdbc.queryForList("select name from core_org_unit where id = ?", String.class, fromTeam).stream().findFirst().orElse("班组");
            HandoverSummarizer.Summary s = summarizer.summarize(win, teamName, done, open, issues);

            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> w : open) items.add(item("UNFINISHED", w.get("id"), null, w.get("code") + " " + w.get("title"), w.get("status")));
            for (Map<String, Object> w : done) items.add(item("DONE", w.get("id"), null, w.get("code") + " " + w.get("title"), w.get("status")));
            for (Map<String, Object> i : issues) items.add(item("ISSUE", null, ((Number) i.get("id")).intValue(), i.get("code") + " " + i.get("title"), i.get("status")));
            records.replaceChildren(HANDOVER_ITEM, id, items);
            ctx.eventPayload().put("unfinished", open.size());
            ctx.eventPayload().put("issues", issues.size());
            Map<String, Object> changes = new HashMap<>();
            changes.put("summary", s.summary());
            changes.put("issueSummary", s.issueSummary());
            changes.put("dcsSummary", "本班组未接入 DCS 点位，无报警摘要。");
            return changes;
        });
    }

    private static Map<String, Object> item(String kind, Object work, Integer issue, String title, Object status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("workItem", work == null ? null : ((Number) work).longValue());
        m.put("issueId", issue);
        m.put("title", title.length() > 200 ? title.substring(0, 200) : title);
        m.put("workStatus", status == null ? null : String.valueOf(status));
        return m;
    }

    /** 交班确认：摘要必须有内容（可在确认时修改），记录交班人与时间。确认后接班班组才能看到。 */
    public TransitionEffect publish() {
        return effect(HANDOVER + ".publish", (ctx, before) -> {
            Map<String, Object> h = records.row(HANDOVER, Long.parseLong(before.id()));
            String summary = ctx.text("summary") != null ? ctx.text("summary") : (String) h.get("summary");
            if (summary == null || summary.isBlank()) {
                throw new LsBizException("HANDOVER_SUMMARY_REQUIRED", "请先生成或填写交班摘要", Map.of("fields", Map.of("summary", "交班摘要必填")));
            }
            Map<String, Object> changes = new HashMap<>();
            changes.put("summary", summary);
            changes.put("publishedAt", LocalDateTime.now(clock));
            changes.put("publishedBy", ctx.actor().effectiveParty());
            notifyReceivers(ctx, before.id(), h);
            return changes;
        });
    }

    /** 接班确认：未完成事项移到接班班组的下一班次，出现在其工作板上。 */
    public TransitionEffect accept() {
        return effect(HANDOVER + ".accept", (ctx, before) -> {
            long id = Long.parseLong(before.id());
            Map<String, Object> h = records.row(HANDOVER, id);
            ShiftWindow next = ShiftWindow.of(h.get("shiftDate"), h.get("shift")).next();
            long toTeam = ((Number) h.get("toTeam")).longValue();
            List<String> moved = new ArrayList<>();
            for (Map<String, Object> it : records.children(HANDOVER_ITEM, id)) {
                if (!"UNFINISHED".equals(it.get("kind")) || it.get("workItem") == null) continue;
                long work = ((Number) it.get("workItem")).longValue();
                WorkItem w = works.get(work);
                if (w.status().terminal()) continue;
                Optional<Map<String, Object>> plan = records.where(SHIFT_PLAN, "workItem", work).stream()
                    .filter(p -> "ACTIVE".equals(p.get("status"))).findFirst();
                if (plan.isPresent()) {
                    records.update(SHIFT_PLAN, ((Number) plan.get().get("id")).longValue(),
                        Map.of("team", toTeam, "shiftDate", next.date(), "shift", next.shift()));
                } else {
                    records.insert(SHIFT_PLAN, w.orgUnitId(), "ACTIVE", ctx.actor().effectiveParty(),
                        Map.of("workItem", work, "team", toTeam, "shiftDate", next.date(), "shift", next.shift()));
                }
                try {
                    reschedule(ctx, work, next);
                } catch (LsBizException e) {
                    if (!Objects.equals(e.getCode(), "ASSIGNMENT_CONFLICT")) throw e;
                    ctx.warn(w.code() + " 移到 " + next.label() + " 与其他工作冲突，计划时间未调整，请在分派台处理");
                }
                moved.add(w.code());
            }
            ctx.eventPayload().put("moved", moved);
            ctx.eventPayload().put("to", next.label());
            Map<String, Object> changes = new HashMap<>();
            changes.put("acceptedAt", LocalDateTime.now(clock));
            changes.put("acceptedBy", ctx.actor().effectiveParty());
            changes.put("acceptNote", ctx.text("note"));
            notices.resolve(HANDOVER, String.valueOf(id));
            return changes;
        });
    }

    /** 交班确认后通知接班班组（班组没有在岗人员时通知最近一级有人员的上级组织），可在通知抽屉内直接确认接班。 */
    private void notifyReceivers(OperationContext ctx, String id, Map<String, Object> h) {
        Long toTeam = h.get("toTeam") == null ? null : ((Number) h.get("toTeam")).longValue();
        if (toTeam == null) return;
        String from = jdbc.queryForList("select name from core_org_unit where id = ?", String.class, ((Number) h.get("fromTeam")).longValue())
            .stream().findFirst().orElse("交班班组");
        String when = ShiftWindow.of(h.get("shiftDate"), h.get("shift")).label();
        List<Long> to = notices.nearestMembers(toTeam).stream().filter(p -> !p.equals(ctx.actor().effectiveParty())).toList();
        notices.notify("ACTION", to, HANDOVER, id, "交接班待确认", from + " " + when + " 的交接班已确认交班，请接班确认",
            "/production/handover?id=" + id, new Notices.Action(HANDOVER + ".accept", HANDOVER, id, "确认接班"), ctx.actor().effectiveParty());
    }

    /** 退回交班人：记录原因，接班方的待确认通知标记为已处理。 */
    public TransitionEffect handoverReturn() {
        return effect(HANDOVER + ".returnBack", (ctx, before) -> {
            ctx.eventPayload().put("reason", ctx.text("reason"));
            notices.resolve(HANDOVER, before.id());
            return Map.of();
        });
    }

    /** 只记录原因的迁移（取消、退回）。 */
    public TransitionEffect reason(String op) {
        return effect(op, (ctx, before) -> {
            ctx.eventPayload().put("reason", ctx.text("reason"));
            return Map.of();
        });
    }

    static BigDecimal decimal(Object v) {
        return v == null ? null : new BigDecimal(String.valueOf(v));
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
