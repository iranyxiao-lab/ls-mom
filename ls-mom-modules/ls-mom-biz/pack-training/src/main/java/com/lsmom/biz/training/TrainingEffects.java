package com.lsmom.biz.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.state.StateSnapshot;
import com.lsmom.action.state.TransitionEffect;
import com.lsmom.core.error.LsBizException;
import com.lsmom.meta.concept.ManagedRecords;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;

import static com.lsmom.biz.training.TrainingPack.ANSWER;
import static com.lsmom.biz.training.TrainingPack.ANSWER_ITEM;
import static com.lsmom.biz.training.TrainingPack.CANDIDATE;
import static com.lsmom.biz.training.TrainingPack.COURSE;
import static com.lsmom.biz.training.TrainingPack.EXAM;
import static com.lsmom.biz.training.TrainingPack.EXAM_QUESTION;
import static com.lsmom.biz.training.TrainingPack.OPTION;
import static com.lsmom.biz.training.TrainingPack.QUESTION;
import static com.lsmom.biz.training.TrainingPack.RECORD;

/** 培训考试 Pack 状态机迁移的业务效果：组卷、试卷提交校验、作答保存、交卷判分、简答评分、及格生成培训记录。 */
public class TrainingEffects {

    private final ManagedRecords records;
    private final Clock clock;

    public TrainingEffects(ManagedRecords records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    /** 组卷：从该课程已审核的题目中抽题（drawCount 为空则全部），按种子确定顺序。 */
    public TransitionEffect compose() {
        return effect(EXAM + ".compose", (ctx, before) -> {
            long id = Long.parseLong(before.id());
            Map<String, Object> exam = records.row(EXAM, id);
            List<Map<String, Object>> pool = new ArrayList<>(records.where(QUESTION, "course", num(exam.get("course")).longValue()).stream()
                .filter(q -> "PUBLISHED".equals(q.get("status"))).toList());
            if (pool.isEmpty()) throw LsBizException.validation(Map.of("course", "该课程没有已审核的题目，不能组卷"));
            long seed = ctx.input().has("seed") ? ctx.input().get("seed").asLong() : id;
            Collections.shuffle(pool, new Random(seed));
            Integer draw = exam.get("drawCount") == null ? null : num(exam.get("drawCount")).intValue();
            List<Map<String, Object>> picked = draw == null || draw >= pool.size() ? pool : pool.subList(0, draw);
            List<Map<String, Object>> rows = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            for (Map<String, Object> q : picked) {
                BigDecimal score = dec(q.get("score"));
                rows.add(Map.of("question", q.get("id"), "score", score));
                total = total.add(score);
            }
            records.replaceChildren(EXAM_QUESTION, id, rows);
            ctx.eventPayload().put("questions", rows.size());
            return Map.of("totalScore", total);
        });
    }

    /** 提交发布审批前校验：已组卷、有参考人员、时间窗口有效。 */
    public TransitionEffect examSubmit() {
        return effect(EXAM + ".submit", (ctx, before) -> {
            long id = Long.parseLong(before.id());
            Map<String, Object> exam = records.row(EXAM, id);
            Map<String, String> errors = new LinkedHashMap<>();
            if (records.children(EXAM_QUESTION, id).isEmpty()) errors.put("examQuestion", "请先组卷");
            if (records.children(CANDIDATE, id).isEmpty()) errors.put("examCandidate", "请指定参考人员");
            LocalDateTime s = time(exam.get("startAt"));
            LocalDateTime e = time(exam.get("endAt"));
            if (s != null && e != null && !e.isAfter(s)) errors.put("endAt", "截止时间须晚于开始时间");
            if (num(exam.get("durationMinutes")).intValue() <= 0) errors.put("durationMinutes", "作答时长须大于 0");
            if (!errors.isEmpty()) throw LsBizException.validation(errors);
            return Map.of();
        });
    }

    /** 保存作答（作答中，考生本人，截止前）。responses：{题号: 作答}，多选用逗号分隔选项。 */
    public TransitionEffect save() {
        return effect(ANSWER + ".save", (ctx, before) -> {
            long id = Long.parseLong(before.id());
            Map<String, Object> answer = records.row(ANSWER, id);
            requireCandidate(ctx.actor(), answer);
            if (LocalDateTime.now(clock).isAfter(time(answer.get("deadline")))) throw new LsBizException("TIME_UP", "作答时间已到，不能再修改");
            int n = merge(id, ctx.input().get("responses"));
            ctx.eventPayload().put("saved", n);
            return Map.of();
        });
    }

    /** 交卷：考生本人或到时由系统自动交卷；客观题自动判分，简答题待人工评分（未作答的简答记 0 分）。 */
    public TransitionEffect submit() {
        return effect(ANSWER + ".submit", (ctx, before) -> {
            long id = Long.parseLong(before.id());
            Map<String, Object> answer = records.row(ANSWER, id);
            boolean system = ctx.actor().type() == ActorContext.ActorType.SYSTEM;
            if (!system) requireCandidate(ctx.actor(), answer);
            LocalDateTime now = LocalDateTime.now(clock);
            boolean late = now.isAfter(time(answer.get("deadline")).plusSeconds(30));
            // 超时后只接受已保存的作答（自动交卷）；时限内交卷可同时带上最后的作答
            if (ctx.input().has("responses") && !late) merge(id, ctx.input().get("responses"));
            List<Map<String, Object>> items = new ArrayList<>(records.children(ANSWER_ITEM, id));
            BigDecimal objective = BigDecimal.ZERO;
            boolean pending = false;
            List<Map<String, Object>> graded = new ArrayList<>();
            for (Map<String, Object> it : items) {
                Map<String, Object> g = new HashMap<>(childValues(it));
                BigDecimal score = dec(it.get("score"));
                String resp = it.get("response") == null ? "" : String.valueOf(it.get("response")).trim();
                if ("ESSAY".equals(it.get("qtype"))) {
                    if (resp.isEmpty()) {
                        g.put("earned", BigDecimal.ZERO);
                        g.put("correct", false);
                    } else {
                        pending = true;
                    }
                } else {
                    boolean ok = keys(resp).equals(correctKeys(num(it.get("question")).longValue()));
                    g.put("correct", ok);
                    g.put("earned", ok ? score : BigDecimal.ZERO);
                    objective = objective.add(ok ? score : BigDecimal.ZERO);
                }
                graded.add(g);
            }
            records.replaceChildren(ANSWER_ITEM, id, graded);
            Map<String, Object> changes = new HashMap<>();
            changes.put("submittedAt", now);
            changes.put("autoSubmitted", system || late);
            changes.put("objectiveScore", objective);
            changes.put("essayPending", pending);
            ctx.eventPayload().put("autoSubmitted", system || late);
            ctx.eventPayload().put("objectiveScore", objective);
            if (!pending) finish(ctx, id, answer, graded, changes);
            return changes;
        });
    }

    /** 简答评分：scores：{题号: 分数}，0 ≤ 分数 ≤ 分值；全部简答评完后计算总分，及格生成培训记录。 */
    public TransitionEffect gradeEssay() {
        return effect(ANSWER + ".gradeEssay", (ctx, before) -> {
            long id = Long.parseLong(before.id());
            Map<String, Object> answer = records.row(ANSWER, id);
            if (!Boolean.TRUE.equals(answer.get("essayPending"))) throw new LsBizException("NOTHING_TO_GRADE", "这份答卷没有待评的简答题");
            Long me = ctx.actor().effectiveParty();
            if (me != null && me.equals(num(answer.get("candidate")).longValue())) throw new LsBizException("SELF_GRADING", "不能给自己的答卷评分");
            JsonNode scores = ctx.input().get("scores");
            List<Map<String, Object>> items = new ArrayList<>();
            boolean pending = false;
            Map<String, String> errors = new LinkedHashMap<>();
            for (Map<String, Object> it : records.children(ANSWER_ITEM, id)) {
                Map<String, Object> g = new HashMap<>(childValues(it));
                String seq = String.valueOf(num(it.get("seq")).intValue());
                if ("ESSAY".equals(it.get("qtype")) && scores.has(seq)) {
                    BigDecimal v = scores.get(seq).decimalValue();
                    BigDecimal max = dec(it.get("score"));
                    if (v.signum() < 0 || v.compareTo(max) > 0) errors.put(seq, "第 " + seq + " 题得分须在 0 ~ " + max.stripTrailingZeros().toPlainString() + " 之间");
                    g.put("earned", v);
                    g.put("correct", v.compareTo(max) == 0);
                }
                if ("ESSAY".equals(it.get("qtype")) && g.get("earned") == null) pending = true;
                items.add(g);
            }
            if (!errors.isEmpty()) throw LsBizException.validation(errors);
            records.replaceChildren(ANSWER_ITEM, id, items);
            Map<String, Object> changes = new HashMap<>();
            changes.put("essayPending", pending);
            changes.put("grader", me);
            if (!pending) finish(ctx, id, answer, items, changes);
            return changes;
        });
    }

    /** 计算总分与是否及格；及格生成培训记录（人员、课程、成绩、有效期）。 */
    private void finish(OperationContext ctx, long answerId, Map<String, Object> answer, List<Map<String, Object>> items, Map<String, Object> changes) {
        BigDecimal objective = BigDecimal.ZERO;
        BigDecimal essay = BigDecimal.ZERO;
        for (Map<String, Object> it : items) {
            BigDecimal e = it.get("earned") == null ? BigDecimal.ZERO : dec(it.get("earned"));
            if ("ESSAY".equals(it.get("qtype"))) essay = essay.add(e);
            else objective = objective.add(e);
        }
        BigDecimal total = objective.add(essay);
        Map<String, Object> exam = records.row(EXAM, num(answer.get("exam")).longValue());
        Map<String, Object> course = records.row(COURSE, num(exam.get("course")).longValue());
        boolean passed = total.compareTo(dec(course.get("passScore"))) >= 0;
        changes.put("objectiveScore", objective);
        changes.put("essayScore", essay);
        changes.put("totalScore", total);
        changes.put("passed", passed);
        ctx.eventPayload().put("totalScore", total);
        ctx.eventPayload().put("passed", passed);
        if (passed) {
            LocalDate today = LocalDate.now(clock);
            long rec = records.insert(RECORD, num(answer.get("orgId")).longValue(), "VALID", ctx.actor().userId(), Map.of(
                "party", num(answer.get("candidate")).longValue(), "course", num(course.get("id")).longValue(), "exam", num(exam.get("id")).longValue(),
                "answer", answerId, "score", total, "passedOn", today, "validUntil", today.plusMonths(num(course.get("validMonths")).longValue())));
            ctx.eventPayload().put("trainingRecord", rec);
            ctx.emit(RECORD + ".created", RECORD, String.valueOf(rec), num(answer.get("orgId")).longValue(),
                Map.of("party", answer.get("candidate"), "course", course.get("courseCode"), "score", total));
        }
    }

    private int merge(long answerId, JsonNode responses) {
        if (responses == null || !responses.isObject()) return 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> it : records.children(ANSWER_ITEM, answerId)) {
            Map<String, Object> v = new HashMap<>(childValues(it));
            String seq = String.valueOf(num(it.get("seq")).intValue());
            if (responses.has(seq)) {
                JsonNode r = responses.get(seq);
                String text = r.isArray() ? String.join(",", toList(r)) : r.isNull() ? null : r.asText();
                if (text != null && text.length() > 4000) throw LsBizException.validation(Map.of(seq, "作答不能超过 4000 字"));
                v.put("response", text);
                n++;
            }
            rows.add(v);
        }
        records.replaceChildren(ANSWER_ITEM, answerId, rows);
        return n;
    }

    private Set<String> correctKeys(long questionId) {
        Set<String> s = new TreeSet<>();
        for (Map<String, Object> o : records.children(OPTION, questionId)) {
            if (Boolean.TRUE.equals(o.get("correct"))) s.add(String.valueOf(o.get("optKey")).trim().toUpperCase());
        }
        return s;
    }

    static Set<String> keys(String response) {
        Set<String> s = new TreeSet<>();
        for (String k : response.split("[,，\\s]+")) if (!k.isBlank()) s.add(k.trim().toUpperCase());
        return s;
    }

    private static List<String> toList(JsonNode arr) {
        List<String> l = new ArrayList<>();
        arr.forEach(x -> l.add(x.asText()));
        return l;
    }

    /** 子表行去掉系统列，便于整表回写。 */
    static Map<String, Object> childValues(Map<String, Object> row) {
        Map<String, Object> m = new HashMap<>(row);
        for (String k : List.of("id", "parentId", "lineNo", "extAttrs", "createdAt", "updatedAt")) m.remove(k);
        m.values().removeIf(v -> v == null);
        return m;
    }

    static void requireCandidate(ActorContext actor, Map<String, Object> answer) {
        Long me = actor.effectiveParty();
        if (me == null || !me.equals(num(answer.get("candidate")).longValue())) throw new LsBizException("NOT_CANDIDATE", "只有考生本人可以作答");
    }

    static Number num(Object v) {
        if (v == null) throw new IllegalStateException("缺少必填值");
        return v instanceof Number n ? n : Long.valueOf(String.valueOf(v));
    }

    static BigDecimal dec(Object v) {
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }

    static LocalDateTime time(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime t) return t;
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return LocalDateTime.parse(String.valueOf(v).replace(' ', 'T'));
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
