package com.lsmom.biz.training;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScope;
import com.lsmom.meta.concept.ManagedRecords;
import org.dromara.common.core.domain.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.lsmom.biz.training.TrainingPack.ANSWER;
import static com.lsmom.biz.training.TrainingPack.ANSWER_ITEM;
import static com.lsmom.biz.training.TrainingPack.CANDIDATE;
import static com.lsmom.biz.training.TrainingPack.COURSE;
import static com.lsmom.biz.training.TrainingPack.EXAM;
import static com.lsmom.biz.training.TrainingPack.RECORD;

/** 培训考试的只读查询：我的考试、答卷（不含正确答案）、待评简答、我的培训记录。写操作一律走 Operation。 */
@RestController
@RequestMapping("/api/training")
public class TrainingController {

    private final ActorResolver actors;
    private final JdbcTemplate jdbc;
    private final ManagedRecords records;

    public TrainingController(ActorResolver actors, JdbcTemplate jdbc, ManagedRecords records) {
        this.actors = actors;
        this.jdbc = jdbc;
        this.records = records;
    }

    /** 我参加的考试（已发布或已结束）及我的答卷状态。 */
    @GetMapping("/my/exams")
    public R<List<Map<String, Object>>> myExams() {
        ActorContext a = require("ls:training:take");
        if (a.effectiveParty() == null) return R.ok(List.of());
        String now = LocalDateTime.now().toString();
        String e = records.table(EXAM), c = records.table(CANDIDATE), co = records.table(COURSE), an = records.table(ANSWER);
        return R.ok(jdbc.queryForList("""
            select e.id, e.version, e.status, e.%s as title, e.%s as start_at, e.%s as end_at, e.%s as duration_minutes, e.%s as total_score,
                   co.%s as course_title, co.%s as pass_score,
                   a.id as answer_id, a.version as answer_version, a.status as answer_status, a.%s as deadline, a.%s as score,
                   a.%s as passed, a.%s as essay_pending, a.%s as auto_submitted
              from %s e join %s c on c.parent_id = e.id and c.%s = ? join %s co on co.id = e.%s
              left join %s a on a.%s = e.id and a.%s = ? and a.del_flag = '0'
             where e.del_flag = '0' and e.status in ('PUBLISHED', 'CLOSED') order by e.%s desc, e.id desc""".formatted(
            col(EXAM, "title"), col(EXAM, "startAt"), col(EXAM, "endAt"), col(EXAM, "durationMinutes"), col(EXAM, "totalScore"),
            col(COURSE, "title"), col(COURSE, "passScore"), col(ANSWER, "deadline"), col(ANSWER, "totalScore"), col(ANSWER, "passed"),
            col(ANSWER, "essayPending"), col(ANSWER, "autoSubmitted"), e, c, col(CANDIDATE, "party"), co, col(EXAM, "course"), an,
            col(ANSWER, "exam"), col(ANSWER, "candidate"), col(EXAM, "startAt")), a.effectiveParty(), a.effectiveParty())
            .stream().peek(r -> r.put("server_now", now)).toList());
    }

    /** 答卷：考生本人或阅卷人可看；作答中不含判分，任何时候都不含正确答案。 */
    @GetMapping("/answers/{id}")
    public R<Map<String, Object>> answer(@PathVariable long id) {
        ActorContext a = actors.current();
        Map<String, Object> ans = records.find(ANSWER, id).orElseThrow(() -> LsBizException.notFound("答卷", id));
        boolean mine = a.effectiveParty() != null && a.effectiveParty().equals(((Number) ans.get("candidate")).longValue());
        boolean grader = a.hasPermission("ls:training:grade") && inScope(a, ans.get("orgId"));
        if (!mine && !grader) throw LsBizException.notFound("答卷", id);
        Map<String, Object> exam = records.row(EXAM, ((Number) ans.get("exam")).longValue());
        Map<String, Object> out = new LinkedHashMap<>(ans);
        out.put("examTitle", exam.get("title"));
        out.put("candidateName", jdbc.queryForList("select name from core_party where id = ?", String.class, ans.get("candidate")).stream().findFirst().orElse(""));
        boolean graded = "GRADED".equals(ans.get("status"));
        // 剩余秒数按服务器时钟计算：截止时间不带时区，浏览器与服务器不在同一时区时前端不能自行比较
        if (!graded && ans.get("deadline") != null) {
            out.put("remainingSeconds", Math.max(0, Duration.between(LocalDateTime.now(), TrainingEffects.time(ans.get("deadline"))).getSeconds()));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> it : records.children(ANSWER_ITEM, id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : List.of("seq", "qtype", "stem", "choices", "score", "response")) m.put(k, it.get(k));
            if (graded) {
                m.put("earned", it.get("earned"));
                m.put("correct", it.get("correct"));
            }
            if (grader && "ESSAY".equals(it.get("qtype"))) {
                m.put("referenceAnswer", jdbc.queryForList("select " + col(TrainingPack.QUESTION, "referenceAnswer") + " from "
                    + records.table(TrainingPack.QUESTION) + " where id = ?", String.class, it.get("question")).stream().findFirst().orElse(null));
            }
            items.add(m);
        }
        out.put("items", items);
        return R.ok(out);
    }

    /** 待评简答的答卷（数据域内）。 */
    @GetMapping("/grading")
    public R<List<Map<String, Object>>> grading() {
        ActorContext a = require("ls:training:grade");
        String an = records.table(ANSWER), e = records.table(EXAM);
        StringBuilder sql = new StringBuilder("""
            select a.id, a.version, a.org_id, a.%s as submitted_at, a.%s as objective_score, e.%s as exam_title, p.name as candidate_name
              from %s a join %s e on e.id = a.%s left join core_party p on p.id = a.%s
             where a.del_flag = '0' and a.status = 'GRADED' and a.%s = true""".formatted(col(ANSWER, "submittedAt"), col(ANSWER, "objectiveScore"),
            col(EXAM, "title"), an, e, col(ANSWER, "exam"), col(ANSWER, "candidate"), col(ANSWER, "essayPending")));
        List<Object> args = new ArrayList<>();
        DataScope s = a.dataScope();
        if (!s.all()) {
            if (s.orgUnitIds().isEmpty()) return R.ok(List.of());
            sql.append(" and a.org_id in (").append(String.join(",", s.orgUnitIds().stream().map(x -> "?").toList())).append(")");
            args.addAll(s.orgUnitIds());
        }
        sql.append(" order by a.").append(col(ANSWER, "submittedAt"));
        return R.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    /** 我的培训记录。 */
    @GetMapping("/my/records")
    public R<List<Map<String, Object>>> myRecords() {
        ActorContext a = actors.current();
        if (a.effectiveParty() == null) return R.ok(List.of());
        return R.ok(jdbc.queryForList("""
            select r.id, c.%s as course_code, c.%s as course_title, r.%s as score, r.%s as passed_on, r.%s as valid_until
              from %s r join %s c on c.id = r.%s where r.del_flag = '0' and r.%s = ? order by r.%s desc""".formatted(
            col(COURSE, "courseCode"), col(COURSE, "title"), col(RECORD, "score"), col(RECORD, "passedOn"), col(RECORD, "validUntil"),
            records.table(RECORD), records.table(COURSE), col(RECORD, "course"), col(RECORD, "party"), col(RECORD, "validUntil")), a.effectiveParty()));
    }

    private String col(String concept, String field) {
        return records.column(concept, field);
    }

    private ActorContext require(String perm) {
        ActorContext a = actors.current();
        if (!a.hasPermission(perm)) throw new LsBizException("FORBIDDEN", "没有权限：" + perm);
        return a;
    }

    private static boolean inScope(ActorContext a, Object org) {
        DataScope s = a.dataScope();
        return s.all() || (org != null && s.orgUnitIds().contains(((Number) org).longValue()));
    }
}
