package com.lsmom.biz.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.Exposure;
import com.lsmom.action.api.LsOperation;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.api.OperationHandler;
import com.lsmom.action.api.Risk;
import com.lsmom.core.error.LsBizException;
import com.lsmom.meta.concept.ManagedRecords;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.lsmom.biz.training.TrainingEffects.num;
import static com.lsmom.biz.training.TrainingEffects.time;
import static com.lsmom.biz.training.TrainingPack.ANSWER;
import static com.lsmom.biz.training.TrainingPack.ANSWER_ITEM;
import static com.lsmom.biz.training.TrainingPack.CANDIDATE;
import static com.lsmom.biz.training.TrainingPack.COURSE;
import static com.lsmom.biz.training.TrainingPack.EXAM;
import static com.lsmom.biz.training.TrainingPack.EXAM_QUESTION;
import static com.lsmom.biz.training.TrainingPack.OPTION;
import static com.lsmom.biz.training.TrainingPack.QUESTION;
import static com.lsmom.biz.training.TrainingPack.RECORD;

/** 培训考试 Pack 的代码 Operation：开考、批量草拟题目（Agent / LLM 只能产出草稿）、资质查询（公开给其他 Pack）。 */
public final class TrainingOperations {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TrainingOperations() {
    }

    /** 开考：考生在时间窗口内开始作答，生成答卷（题干与选项副本，不含答案）；已开考则返回原答卷继续作答。 */
    @LsOperation(code = TrainingPack.START, concept = EXAM, name = "开始考试", risk = Risk.C0, permission = "ls:training:take", inputSchema = """
        {"type":"object","required":["examId"],"properties":{"examId":{"type":"integer"}}}""")
    public static class StartExam implements OperationHandler {

        private final ManagedRecords records;
        private final Clock clock;

        public StartExam(ManagedRecords records, Clock clock) {
            this.records = records;
            this.clock = clock;
        }

        @Override
        public Object handle(OperationContext ctx) {
            long examId = ctx.input().get("examId").asLong();
            Map<String, Object> exam = records.find(EXAM, examId).orElseThrow(() -> LsBizException.notFound("考试", examId));
            Long me = ctx.actor().effectiveParty();
            if (me == null || records.children(CANDIDATE, examId).stream().noneMatch(c -> me.equals(num(c.get("party")).longValue()))) {
                throw new LsBizException("NOT_CANDIDATE", "你不在这场考试的参考人员中");
            }
            ctx.target(String.valueOf(examId), num(exam.get("version")).intValue(), String.valueOf(exam.get("status")), num(exam.get("orgId")).longValue());
            List<Map<String, Object>> mine = records.where(ANSWER, "exam", examId).stream().filter(a -> me.equals(num(a.get("candidate")).longValue())).toList();
            if (!mine.isEmpty()) {
                Map<String, Object> a = mine.get(0);
                if (!"IN_PROGRESS".equals(a.get("status"))) throw new LsBizException("ALREADY_SUBMITTED", "你已交卷");
                return result(a, true);
            }
            if (!"PUBLISHED".equals(exam.get("status"))) throw new LsBizException("EXAM_NOT_OPEN", "考试未发布或已结束");
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime start = time(exam.get("startAt"));
            LocalDateTime end = time(exam.get("endAt"));
            if (now.isBefore(start)) throw new LsBizException("EXAM_NOT_STARTED", "考试尚未开始");
            if (!now.isBefore(end)) throw new LsBizException("EXAM_ENDED", "考试已截止");
            LocalDateTime deadline = now.plusMinutes(num(exam.get("durationMinutes")).longValue());
            if (deadline.isAfter(end)) deadline = end;
            long answerId = records.insert(ANSWER, num(exam.get("orgId")).longValue(), "IN_PROGRESS", ctx.actor().userId(),
                Map.of("exam", examId, "candidate", me, "startedAt", now, "deadline", deadline, "essayPending", false));
            List<Map<String, Object>> items = new ArrayList<>();
            int seq = 1;
            for (Map<String, Object> eq : records.children(EXAM_QUESTION, examId)) {
                long qid = num(eq.get("question")).longValue();
                Map<String, Object> q = records.row(QUESTION, qid);
                ArrayNode choices = JSON.createArrayNode();
                for (Map<String, Object> o : records.children(OPTION, qid)) {
                    ObjectNode c = choices.addObject();
                    c.put("key", String.valueOf(o.get("optKey")));
                    c.put("content", String.valueOf(o.get("content")));
                }
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("question", qid);
                it.put("seq", seq++);
                it.put("qtype", q.get("qtype"));
                it.put("stem", q.get("stem"));
                it.put("choices", choices.toString());
                it.put("score", eq.get("score"));
                items.add(it);
            }
            records.replaceChildren(ANSWER_ITEM, answerId, items);
            ctx.eventPayload().put("answerId", answerId);
            ctx.eventPayload().put("candidate", me);
            return result(records.row(ANSWER, answerId), false);
        }

        private static Map<String, Object> result(Map<String, Object> a, boolean resumed) {
            return Map.of("answerId", a.get("id"), "version", a.get("version"), "deadline", String.valueOf(a.get("deadline")), "resumed", resumed);
        }
    }

    /**
     * 批量草拟题目：Agent 依据 SOP 等资料（经 LLM）草拟的题目一律保存为草稿、来源标记为 LLM，必须经真人审核
     * （ls.training.Question.approve，规则限定真人）后才能组卷。
     */
    @LsOperation(code = TrainingPack.DRAFT_BATCH, concept = QUESTION, name = "批量草拟题目", risk = Risk.C1, exposure = Exposure.DRAFT,
        permission = "ls:training:manage", inputSchema = """
        {"type":"object","required":["courseId","questions"],"properties":{
          "courseId":{"type":"integer"},"sourceRef":{"type":"string","maxLength":200},
          "questions":{"type":"array","minItems":1,"maxItems":50,"items":{"type":"object","required":["qtype","stem","score"],"properties":{
            "qtype":{"enum":["SINGLE","MULTI","JUDGE","ESSAY"]},"stem":{"type":"string","minLength":1},"score":{"type":"number","exclusiveMinimum":0},
            "referenceAnswer":{"type":"string"},
            "options":{"type":"array","items":{"type":"object","required":["key","content"],"properties":{
              "key":{"type":"string","maxLength":4},"content":{"type":"string"},"correct":{"type":"boolean"}}}}}}}}}""")
    public static class DraftQuestions implements OperationHandler {

        private final ManagedRecords records;

        public DraftQuestions(ManagedRecords records) {
            this.records = records;
        }

        @Override
        public Object handle(OperationContext ctx) {
            long courseId = ctx.input().get("courseId").asLong();
            Map<String, Object> course = records.find(COURSE, courseId).orElseThrow(() -> LsBizException.notFound("课程", courseId));
            if (!ctx.actor().dataScope().all() && !ctx.actor().dataScope().allowsOrg(num(course.get("orgId")).longValue())) {
                throw new LsBizException("ACCESS_DENIED", "课程不在你的数据范围内");
            }
            List<Map<String, Object>> drafts = new ArrayList<>();
            int i = 0;
            for (JsonNode q : ctx.input().get("questions")) {
                i++;
                String type = q.get("qtype").asText();
                JsonNode opts = q.has("options") ? q.get("options") : JSON.createArrayNode();
                if (!"ESSAY".equals(type)) {
                    long correct = 0;
                    for (JsonNode o : opts) if (o.path("correct").asBoolean(false)) correct++;
                    if (opts.size() < 2 || correct == 0 || (!"MULTI".equals(type) && correct != 1)) {
                        throw LsBizException.validation(Map.of("questions[" + i + "]", "第 " + i + " 题选项不完整或正确答案数量不对"));
                    }
                }
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("course", courseId);
                v.put("qtype", type);
                v.put("stem", q.get("stem").asText());
                v.put("score", q.get("score").decimalValue());
                if (q.has("referenceAnswer")) v.put("referenceAnswer", q.get("referenceAnswer").asText());
                v.put("origin", "LLM");
                if (ctx.input().has("sourceRef")) v.put("sourceRef", ctx.input().get("sourceRef").asText());
                List<Map<String, Object>> options = new ArrayList<>();
                for (JsonNode o : opts) options.add(Map.of("optKey", o.get("key").asText(), "content", o.get("content").asText(), "correct", o.path("correct").asBoolean(false)));
                v.put("_options", options);
                drafts.add(v);
            }
            List<Object> ids = new ArrayList<>();
            for (Map<String, Object> v : drafts) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> options = (List<Map<String, Object>>) v.remove("_options");
                // 一律为草稿：须真人审核（ls.training.Question.approve）后才能组卷
                long id = records.insert(QUESTION, num(course.get("orgId")).longValue(), "DRAFT", ctx.actor().userId(), v);
                records.replaceChildren(OPTION, id, options);
                ids.add(id);
            }
            ctx.eventPayload().put("drafted", ids.size());
            ctx.eventPayload().put("courseId", courseId);
            ctx.eventPayload().put("questionIds", ids);
            return Map.of("ids", ids, "status", "DRAFT");
        }
    }

    /** 资质查询（公开 Operation）：某人某课程在指定日期是否有有效培训记录。其他 Pack 的守卫经此查询，不直接读表。 */
    @LsOperation(code = TrainingPack.QUALIFICATION, concept = RECORD, name = "查询培训资质", risk = Risk.C0, exposure = Exposure.QUERY,
        permission = "ls:training:qualification", inputSchema = """
        {"type":"object","required":["partyId","courseCode"],"properties":{
          "partyId":{"type":"integer"},"courseCode":{"type":"string","minLength":1},"on":{"type":"string","format":"date"}}}""")
    public static class Qualification implements OperationHandler {

        private final JdbcTemplate jdbc;
        private final ManagedRecords records;
        private final Clock clock;

        public Qualification(JdbcTemplate jdbc, ManagedRecords records, Clock clock) {
            this.jdbc = jdbc;
            this.records = records;
            this.clock = clock;
        }

        @Override
        public Object handle(OperationContext ctx) {
            long party = ctx.input().get("partyId").asLong();
            String course = ctx.input().get("courseCode").asText();
            LocalDate on = ctx.input().hasNonNull("on") ? LocalDate.parse(ctx.input().get("on").asText()) : LocalDate.now(clock);
            String r = records.table(RECORD);
            String c = records.table(COURSE);
            List<Map<String, Object>> list = jdbc.queryForList("""
                select c.%s as course_code, c.%s as course_title, r.%s as score, r.%s as passed_on, r.%s as valid_until
                  from %s r join %s c on c.id = r.%s
                 where r.del_flag = '0' and r.%s = ? and c.%s = ? and r.%s >= ? order by r.%s desc""".formatted(
                records.column(COURSE, "courseCode"), records.column(COURSE, "title"), records.column(RECORD, "score"),
                records.column(RECORD, "passedOn"), records.column(RECORD, "validUntil"), r, c, records.column(RECORD, "course"),
                records.column(RECORD, "party"), records.column(COURSE, "courseCode"), records.column(RECORD, "validUntil"),
                records.column(RECORD, "validUntil")), party, course, on);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("qualified", !list.isEmpty());
            out.put("partyId", party);
            out.put("courseCode", course);
            out.put("on", on.toString());
            out.put("records", list);
            return out;
        }
    }

    static ActorContext systemActor() {
        return ActorContext.system("培训考试");
    }
}
