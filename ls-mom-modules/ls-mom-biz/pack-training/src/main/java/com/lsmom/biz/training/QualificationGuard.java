package com.lsmom.biz.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.guard.Guard;
import com.lsmom.action.guard.GuardRegistry;
import com.lsmom.action.guard.GuardResult;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkItemService;
import com.lsmom.meta.concept.ManagedRecords;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分派资质守卫：工作标题含某门已发布课程的「适用作业」关键词（如"动火"）时，被分派的人员必须在作业日期持有该课程的有效培训记录。
 * 拒绝时说明谁的什么资质、何时过期，并给出修复入口：改派他人、查看培训记录。
 */
public class QualificationGuard implements Guard, GuardRegistry.GuardAttachment {

    public static final String CODE = "ls.training.assigneeQualified";
    static final String ASSIGN = "ls.core.Work.assign";

    private final JdbcTemplate jdbc;
    private final ManagedRecords records;
    private final WorkItemService works;
    private final Clock clock;

    public QualificationGuard(JdbcTemplate jdbc, ManagedRecords records, WorkItemService works, Clock clock) {
        this.jdbc = jdbc;
        this.records = records;
        this.works = works;
        this.clock = clock;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<String> guardsFor(String operationCode) {
        return ASSIGN.equals(operationCode) ? List.of(CODE) : List.of();
    }

    @Override
    public GuardResult check(OperationContext ctx) {
        JsonNode targets = ctx.input() == null ? null : ctx.input().get("targets");
        if (targets == null || !targets.isArray() || ctx.targetId() == null) return GuardResult.pass();
        WorkItem w = works.get(ctx.targetIdAsLong());
        List<Map<String, Object>> courses = required(w.title());
        if (courses.isEmpty()) return GuardResult.pass();
        LocalDate on = w.plannedStart() != null ? w.plannedStart().toLocalDate() : LocalDate.now(clock);
        for (JsonNode t : targets) {
            if (!"PARTY".equals(t.path("resourceType").asText())) continue;
            long party = Long.parseLong(t.path("resourceRef").asText());
            for (Map<String, Object> c : courses) {
                long course = ((Number) c.get("id")).longValue();
                Map<String, Object> latest = latest(party, course);
                if (latest != null && !toDate(latest.get("valid_until")).isBefore(on)) continue;
                return block(w, party, c, latest, on);
            }
        }
        return GuardResult.pass();
    }

    /** 标题命中「适用作业」关键词的已发布课程。 */
    private List<Map<String, Object>> required(String title) {
        String kw = records.column(TrainingPack.COURSE, "workKeywords");
        List<Map<String, Object>> rows = jdbc.queryForList("select id, %s as code, %s as title, %s as keywords from %s where del_flag = '0' and status = 'PUBLISHED' and coalesce(%s, '') <> ''"
            .formatted(records.column(TrainingPack.COURSE, "courseCode"), records.column(TrainingPack.COURSE, "title"), kw,
                records.table(TrainingPack.COURSE), kw));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String hit = Arrays.stream(String.valueOf(r.get("keywords")).split("[,，、\\s]+")).map(String::trim)
                .filter(k -> !k.isEmpty() && title.contains(k)).findFirst().orElse(null);
            if (hit != null) {
                Map<String, Object> c = new LinkedHashMap<>(r);
                c.put("hit", hit);
                out.add(c);
            }
        }
        return out;
    }

    private Map<String, Object> latest(long party, long course) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id, %s as valid_until from %s where del_flag = '0' and %s = ? and %s = ? order by %s desc limit 1"
            .formatted(records.column(TrainingPack.RECORD, "validUntil"), records.table(TrainingPack.RECORD), records.column(TrainingPack.RECORD, "party"),
                records.column(TrainingPack.RECORD, "course"), records.column(TrainingPack.RECORD, "validUntil")), party, course);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private GuardResult block(WorkItem w, long party, Map<String, Object> course, Map<String, Object> latest, LocalDate on) {
        String name = jdbc.queryForList("select name from core_party where id = ?", String.class, party).stream().findFirst().orElse("#" + party);
        String what = course.get("hit") + "作业资质";
        String message;
        Map<String, Object> details = new LinkedHashMap<>();
        if (latest == null) {
            message = name + "没有" + what + "（需要通过「" + course.get("title") + "」培训）";
        } else {
            LocalDate until = toDate(latest.get("valid_until"));
            message = name + "的" + what + "已于 " + until.getMonthValue() + " 月 " + until.getDayOfMonth() + " 日过期";
            details.put("expiredOn", until.toString());
            details.put("recordId", ((Number) latest.get("id")).longValue());
        }
        details.put("partyId", party);
        details.put("partyName", name);
        details.put("courseCode", course.get("code"));
        details.put("workOn", on.toString());
        details.put("why", "「" + w.title() + "」属于" + course.get("hit") + "作业，承担人必须在作业日期（" + on + "）持有有效的「" + course.get("title") + "」培训记录");
        List<Map<String, Object>> fix = new ArrayList<>();
        fix.add(Map.of("label", "改派他人", "action", "reassign", "partyId", party));
        fix.add(Map.of("label", "查看培训记录", "to", latest == null
            ? "/lsmom/ledger/" + TrainingPack.RECORD
            : "/lsmom/ledger/" + TrainingPack.RECORD + "?id=" + latest.get("id")));
        details.put("fix", fix);
        return GuardResult.block("QUALIFICATION_EXPIRED", message, details);
    }

    private static LocalDate toDate(Object v) {
        if (v instanceof Date d) return d.toLocalDate();
        if (v instanceof LocalDate d) return d;
        return LocalDate.parse(String.valueOf(v).substring(0, 10));
    }
}
