package com.lsmom.biz.training;

import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.api.OperationResult;
import com.lsmom.action.api.OperationHandler;
import com.lsmom.action.guard.Guard;
import com.lsmom.action.guard.GuardResult;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.flow.runtime.FlowInstances;
import com.lsmom.flow.runtime.FlowRuntime;
import com.lsmom.meta.concept.ManagedRecords;
import com.lsmom.pack.PackFixture;
import com.lsmom.pack.PackManifest;
import com.lsmom.pack.PackSource;
import com.lsmom.pack.StandardPacks;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lsmom.biz.training.TrainingPack.ANSWER;
import static com.lsmom.biz.training.TrainingPack.ANSWER_ITEM;
import static com.lsmom.biz.training.TrainingPack.COURSE;
import static com.lsmom.biz.training.TrainingPack.EXAM;
import static com.lsmom.biz.training.TrainingPack.EXAM_QUESTION;
import static com.lsmom.biz.training.TrainingPack.OPTION;
import static com.lsmom.biz.training.TrainingPack.QUESTION;
import static com.lsmom.biz.training.TrainingPack.RECORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** spec packs/training-exam：题库独立表、组卷与限时作答超时自动交卷、判分、培训记录与资质查询 Operation、发布审批、LLM 草拟只能为草稿。 */
class TrainingPackTest {

    /** 可拨动的时钟：模拟考试时间流逝。 */
    static final class TestClock extends Clock {
        Instant now = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final String MANAGE = "ls:training:manage";
    static final String[] ADMIN_PERMS = {MANAGE, "ls:training:grade", "ls:training:qualification",
        "crud:" + COURSE + ":add", "crud:" + COURSE + ":edit", "crud:" + COURSE + ":query", "crud:" + COURSE + ":list",
        "crud:" + QUESTION + ":add", "crud:" + QUESTION + ":edit", "crud:" + QUESTION + ":query", "crud:" + QUESTION + ":list",
        "crud:" + EXAM + ":add", "crud:" + EXAM + ":edit", "crud:" + EXAM + ":query", "crud:" + EXAM + ":list",
        "crud:" + ANSWER + ":query", "crud:" + ANSWER + ":list", "crud:" + RECORD + ":query", "crud:" + RECORD + ":list"};

    /** 共享时钟：处理器在每个夹具对账时都要登记（共享测试库）。 */
    static final TestClock CLOCK = new TestClock();

    static {
        TrainingConfiguration c = new TrainingConfiguration();
        ActionFixture.extraHandlers = af -> {
            ManagedRecords r = new ManagedRecords(af.core.jdbc, af.concepts);
            return List.of(c.trainingStartExam(r, CLOCK), c.trainingDraftQuestions(r), c.trainingQualification(af.core.jdbc, r, CLOCK));
        };
    }

    final PackFixture f = new PackFixture();
    final ManagedRecords records = new ManagedRecords(f.core.jdbc, f.action.concepts);
    final TestClock clock = CLOCK;
    final TrainingScheduler scheduler;
    final Party admin = f.core.employee("培训管理员", f.plant.workshop());
    final Party approver = f.core.employee("周主任", f.plant.workshop());
    final Party zheng = f.core.employee("郑师傅", f.plant.teamA());
    final Party wu = f.core.employee("吴班长", f.plant.teamA());

    TrainingPackTest() {
        TrainingConfiguration cfg = new TrainingConfiguration();
        TrainingEffects e = cfg.trainingEffects(records, clock);
        for (var effect : List.of(cfg.trainingComposeEffect(e), cfg.trainingExamSubmitEffect(e), cfg.trainingAnswerSaveEffect(e),
            cfg.trainingAnswerSubmitEffect(e), cfg.trainingGradeEssayEffect(e))) {
            f.action.gateway.registerEffect(effect);
        }
        f.crud.crud.setPartyAccess(List.of(cfg.trainingAnswerCandidateAccess(), cfg.trainingRecordOwnerAccess()));
        f.crud.states.setPartyAccess(List.of(cfg.trainingAnswerCandidateAccess(), cfg.trainingRecordOwnerAccess()));
        scheduler = cfg.trainingScheduler(f.core.jdbc, records, f.action.gateway, clock);
        // 发布审批流的办理人：课程 / 考试所属车间的负责人，需有审批权限
        f.core.orgUnits.setLeader(f.plant.workshop().id(), approver.id());
        String role = "tr_appr_" + Long.toString(System.nanoTime(), 36);
        f.flow.role(role, approver);
        grant("ls:training:approve", role);
        PackSource src = new StandardPacks(f.installer, f.store).discover().stream()
            .filter(s -> PackManifest.parse(s).code().equals(TrainingPack.CODE)).findFirst().orElseThrow();
        assertThat(f.installer.install(src, null).status()).isIn("INSTALLED", "UPGRADED", "UNCHANGED");
        // 与启动顺序一致：核心对账在 Pack 安装之前，Pack 自带的代码 Operation 安装后补登记
        f.action.operations.reconcilePending();
        assertThat(f.action.operations.pendingCodes()).isEmpty();
    }

    ActorContext adm() {
        return f.action.human(admin, DataScopeLevel.WORKSHOP, ADMIN_PERMS);
    }

    ActorContext candidate(Party p) {
        return f.action.human(p, DataScopeLevel.SELF, "ls:training:take", "crud:" + ANSWER + ":query");
    }

    ActorContext approverActor() {
        return f.action.human(approver, DataScopeLevel.WORKSHOP, "ls:training:approve", "crud:" + COURSE + ":query", "crud:" + EXAM + ":query");
    }

    long create(String concept, ActorContext a, String body) {
        return ((Number) f.crud.crud.create(concept, ActionFixture.json(body), a, null).get("id")).longValue();
    }

    int version(String concept, long id) {
        return ((Number) records.row(concept, id).get("version")).intValue();
    }

    String status(String concept, long id) {
        return String.valueOf(records.row(concept, id).get("status"));
    }

    OperationResult step(String op, ActorContext a, String concept, long id, String input) {
        return f.action.gateway.execute(op, OperationRequest.on(String.valueOf(id), version(concept, id), ActionFixture.json(input)), a);
    }

    OperationResult call(String op, ActorContext a, String input) {
        return f.action.gateway.execute(op, OperationRequest.of(ActionFixture.json(input)), a);
    }

    static String code(Throwable e) {
        LsBizException b = (LsBizException) e;
        return "GUARD_FAILED".equals(b.getCode()) ? String.valueOf(b.getDetails().get("reason")) : b.getCode();
    }

    /** 走完发布审批：提交 → 车间主任审批通过 → 发布。 */
    void publish(String concept, long id) {
        step(concept + ".submit", adm(), concept, id, "{}");
        assertThat(status(concept, id)).isEqualTo("IN_REVIEW");
        FlowInstances.Instance inst = f.flow.instances.ofBiz(concept, String.valueOf(id)).get(0);
        Task t = f.flow.task(inst.procInstId(), "approve");
        f.flow.runtime.complete(t.getId(), FlowRuntime.Action.APPROVE, "同意发布", null, approverActor());
        assertThat(status(concept, id)).isEqualTo("PUBLISHED");
    }

    long course(String code) {
        return create(COURSE, adm(), """
            {"courseCode":"%s","title":"受限空间作业安全","positions":"操作工、班组长","passScore":60,"validMonths":12,"origin":"MANUAL","orgId":%d}"""
            .formatted(code, f.plant.workshop().id()));
    }

    long question(long course, String type, String stem, int score, String options) {
        long id = create(QUESTION, adm(), """
            {"course":%d,"qtype":"%s","stem":"%s","score":%d,"origin":"MANUAL","orgId":%d,"questionOption":%s}"""
            .formatted(course, type, stem, score, f.plant.workshop().id(), options));
        step(QUESTION + ".approve", adm(), QUESTION, id, "{}");
        return id;
    }

    String dt(LocalDateTime t) {
        return t.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /** 课程（已发布）+ 单选 / 多选 / 简答三题 + 已发布考试（郑师傅、吴班长参考，限时 30 分钟）。 */
    long[] scene(String courseCode) {
        long c = course(courseCode);
        publish(COURSE, c);
        question(c, "SINGLE", "进入受限空间前必须先做什么", 40, """
            [{"optKey":"A","content":"气体检测","correct":true},{"optKey":"B","content":"直接进入","correct":false}]""");
        question(c, "MULTI", "以下哪些属于受限空间", 40, """
            [{"optKey":"A","content":"储罐","correct":true},{"optKey":"B","content":"地沟","correct":true},{"optKey":"C","content":"办公室","correct":false}]""");
        question(c, "ESSAY", "简述监护人职责", 20, "[]");
        LocalDateTime now = LocalDateTime.now(clock);
        long exam = create(EXAM, adm(), """
            {"title":"受限空间上岗考试","course":%d,"startAt":"%s","endAt":"%s","durationMinutes":30,"orgId":%d,
             "examCandidate":[{"party":%d},{"party":%d}]}""".formatted(c, dt(now.minusMinutes(1)), dt(now.plusHours(2)), f.plant.workshop().id(),
            zheng.id(), wu.id()));
        assertThatThrownBy(() -> step(EXAM + ".submit", adm(), EXAM, exam, "{}")).satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_FAILED"));
        step(EXAM + ".compose", adm(), EXAM, exam, "{\"seed\":7}");
        assertThat(records.children(EXAM_QUESTION, exam)).hasSize(3);
        assertThat(records.row(EXAM, exam).get("totalScore").toString()).startsWith("100");
        publish(EXAM, exam);
        return new long[]{c, exam};
    }

    @Test
    void 新增多选题保存在Pack独立表_审核后才可组卷() {
        long c = course(PackFixture.code("C"));
        long q = create(QUESTION, adm(), """
            {"course":%d,"qtype":"MULTI","stem":"动火作业需要哪些措施","score":5,"origin":"MANUAL","orgId":%d,
             "questionOption":[{"optKey":"A","content":"动火票","correct":true},{"optKey":"B","content":"监护人","correct":true},{"optKey":"C","content":"无","correct":false}]}"""
            .formatted(c, f.plant.workshop().id()));
        assertThat(records.table(QUESTION)).contains("ls_training");
        assertThat(records.children(OPTION, q)).extracting(o -> o.get("correct")).containsExactly(true, true, false);
        assertThat(status(QUESTION, q)).isEqualTo("DRAFT");
        long exam = create(EXAM, adm(), """
            {"title":"动火考试","course":%d,"startAt":"2026-11-01T08:00:00","endAt":"2026-11-01T09:00:00","durationMinutes":30,"orgId":%d}"""
            .formatted(c, f.plant.workshop().id()));
        assertThatThrownBy(() -> step(EXAM + ".compose", adm(), EXAM, exam, "{}")).hasMessageContaining("没有已审核的题目");
        step(QUESTION + ".approve", adm(), QUESTION, q, "{}");
        step(EXAM + ".compose", adm(), EXAM, exam, "{}");
        assertThat(records.children(EXAM_QUESTION, exam)).hasSize(1);
    }

    @Test
    void 限时作答_超时未交卷系统自动提交已作答部分并判分() {
        long[] s = scene(PackFixture.code("CS"));
        long exam = s[1];
        // 开考：答卷含题干与选项副本，不含答案
        Map<?, ?> started = (Map<?, ?>) call(TrainingPack.START, candidate(zheng), "{\"examId\":" + exam + "}").data();
        long answer = ((Number) started.get("answerId")).longValue();
        List<Map<String, Object>> items = records.children(ANSWER_ITEM, answer);
        assertThat(items).hasSize(3).allSatisfy(it -> assertThat(String.valueOf(it.get("choices"))).doesNotContain("correct"));
        // 不在参考人员中的人不能开考；重复开考继续原答卷
        Party other = f.core.employee("路人", f.plant.teamA());
        assertThatThrownBy(() -> call(TrainingPack.START, candidate(other), "{\"examId\":" + exam + "}")).satisfies(e -> assertThat(code(e)).isEqualTo("NOT_CANDIDATE"));
        assertThat(((Map<?, ?>) call(TrainingPack.START, candidate(zheng), "{\"examId\":" + exam + "}").data()).get("resumed")).isEqualTo(true);

        // 只答了单选与多选（多选少选一项），简答未作答
        String single = seqOf(items, "SINGLE");
        String multi = seqOf(items, "MULTI");
        step(ANSWER + ".save", candidate(zheng), ANSWER, answer, "{\"responses\":{\"%s\":\"A\",\"%s\":[\"A\"]}}".formatted(single, multi));
        // 他人不能替考
        assertThatThrownBy(() -> step(ANSWER + ".save", candidate(wu), ANSWER, answer, "{\"responses\":{}}")).isInstanceOf(LsBizException.class);

        // 时间到：未交卷
        clock.now = clock.now.plus(Duration.ofMinutes(31));
        assertThatThrownBy(() -> step(ANSWER + ".save", candidate(zheng), ANSWER, answer, "{\"responses\":{\"%s\":\"B\"}}".formatted(single)))
            .satisfies(e -> assertThat(code(e)).isEqualTo("TIME_UP"));
        assertThat(scheduler.autoSubmitDue(LocalDateTime.now(clock))).isGreaterThanOrEqualTo(1);
        Map<String, Object> a = records.row(ANSWER, answer);
        assertThat(a).containsEntry("status", "GRADED").containsEntry("autoSubmitted", true).containsEntry("essayPending", false)
            .containsEntry("passed", false);
        assertThat(a.get("totalScore").toString()).startsWith("40");
        assertThat(records.where(RECORD, "answer", answer)).isEmpty();
    }

    @Test
    void 客观题自动判分_简答人工评分_及格生成培训记录_资质经公开Operation查询() {
        String courseCode = PackFixture.code("CQ");
        long[] s = scene(courseCode);
        long exam = s[1];
        long answer = ((Number) ((Map<?, ?>) call(TrainingPack.START, candidate(wu), "{\"examId\":" + exam + "}").data()).get("answerId")).longValue();
        List<Map<String, Object>> items = records.children(ANSWER_ITEM, answer);
        String essay = seqOf(items, "ESSAY");
        step(ANSWER + ".submit", candidate(wu), ANSWER, answer, "{\"responses\":{\"%s\":\"A\",\"%s\":\"A,B\",\"%s\":\"全程监护、联络、紧急时组织救援\"}}"
            .formatted(seqOf(items, "SINGLE"), seqOf(items, "MULTI"), essay));
        Map<String, Object> a = records.row(ANSWER, answer);
        assertThat(a).containsEntry("status", "GRADED").containsEntry("essayPending", true).containsEntry("autoSubmitted", false);
        assertThat(a.get("objectiveScore").toString()).startsWith("80");
        assertThat(a.get("passed")).isNull();

        // 考生不能给自己评分；评分须在分值内；Agent 不能评分（真人规则）
        ActorContext self = f.action.human(wu, DataScopeLevel.SELF, "ls:training:grade", "crud:" + ANSWER + ":query");
        assertThatThrownBy(() -> step(ANSWER + ".gradeEssay", self, ANSWER, answer, "{\"scores\":{\"%s\":20}}".formatted(essay)))
            .satisfies(e -> assertThat(code(e)).isEqualTo("SELF_GRADING"));
        assertThatThrownBy(() -> step(ANSWER + ".gradeEssay", adm(), ANSWER, answer, "{\"scores\":{\"%s\":25}}".formatted(essay)))
            .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_FAILED"));
        step(ANSWER + ".gradeEssay", adm(), ANSWER, answer, "{\"scores\":{\"%s\":15}}".formatted(essay));
        a = records.row(ANSWER, answer);
        assertThat(a).containsEntry("essayPending", false).containsEntry("passed", true).containsEntry("grader", admin.id());
        assertThat(a.get("totalScore").toString()).startsWith("95");
        Map<String, Object> rec = records.where(RECORD, "answer", answer).get(0);
        assertThat(rec).containsEntry("party", wu.id()).containsEntry("passedOn", java.sql.Date.valueOf(LocalDateTime.now(clock).toLocalDate()));

        // 资质查询：经公开 Operation，而非直接读表
        ActorContext guardActor = ActorContext.system("高风险作业分派");
        Map<?, ?> yes = (Map<?, ?>) call(TrainingPack.QUALIFICATION, guardActor, "{\"partyId\":%d,\"courseCode\":\"%s\"}".formatted(wu.id(), courseCode)).data();
        assertThat(yes.get("qualified")).isEqualTo(true);
        Map<?, ?> no = (Map<?, ?>) call(TrainingPack.QUALIFICATION, guardActor, "{\"partyId\":%d,\"courseCode\":\"%s\"}".formatted(zheng.id(), courseCode)).data();
        assertThat(no.get("qualified")).isEqualTo(false);
        Map<?, ?> expired = (Map<?, ?>) call(TrainingPack.QUALIFICATION, guardActor, "{\"partyId\":%d,\"courseCode\":\"%s\",\"on\":\"%s\"}"
            .formatted(wu.id(), courseCode, LocalDateTime.now(clock).toLocalDate().plusMonths(13))).data();
        assertThat(expired.get("qualified")).isEqualTo(false);

        // 另一 Pack 的高风险工作分派守卫：只按 Operation 编码调用，不依赖培训 Pack 的类型
        Guard highRisk = new Guard() {
            @Override
            public String code() {
                return "acme.highRisk.qualified";
            }

            @Override
            public GuardResult check(com.lsmom.action.api.OperationContext ctx) {
                Object r = f.action.gateway.execute("ls.training.TrainingRecord.qualification", OperationRequest.of(ActionFixture.json(
                    "{\"partyId\":%s,\"courseCode\":\"%s\"}".formatted(ctx.input().get("party").asText(), courseCode))), guardActor).data();
                return Boolean.TRUE.equals(((Map<?, ?>) r).get("qualified")) ? GuardResult.pass()
                    : GuardResult.block("NOT_QUALIFIED", "该人员没有有效的受限空间作业培训记录");
            }
        };
        var ctxWu = new com.lsmom.action.api.OperationContext(f.action.operations.find(TrainingPack.QUALIFICATION).orElseThrow(), guardActor,
            OperationRequest.of(ActionFixture.json("{\"party\":" + wu.id() + "}")));
        var ctxZheng = new com.lsmom.action.api.OperationContext(f.action.operations.find(TrainingPack.QUALIFICATION).orElseThrow(), guardActor,
            OperationRequest.of(ActionFixture.json("{\"party\":" + zheng.id() + "}")));
        assertThat(highRisk.check(ctxWu).outcome()).isEqualTo(GuardResult.Outcome.PASS);
        assertThat(highRisk.check(ctxZheng).code()).isEqualTo("NOT_QUALIFIED");
        // 无资质查询权限的普通用户不能调用
        assertThatThrownBy(() -> call(TrainingPack.QUALIFICATION, candidate(zheng), "{\"partyId\":1,\"courseCode\":\"x\"}"))
            .satisfies(e -> assertThat(((LsBizException) e).getCode()).isEqualTo("ACCESS_DENIED"));
    }

    @Test
    void LLM草拟的题目只能为草稿_需真人审核后发布_Agent不能提交课程发布() {
        long c = course(PackFixture.code("CL"));
        Party bot = f.core.employee("培训助手", f.plant.workshop());
        ActorContext agent = new ActorContext(ActorContext.ActorType.AGENT, null, bot.id(), admin.id(), false, Set.of(ADMIN_PERMS),
            adm().dataScope(), "培训助手");
        StringBuilder qs = new StringBuilder("[");
        for (int i = 1; i <= 10; i++) {
            if (i > 1) qs.append(',');
            qs.append("""
                {"qtype":"SINGLE","stem":"SOP 第 %d 条要求","score":10,"options":[{"key":"A","content":"正确做法","correct":true},{"key":"B","content":"错误做法"}]}"""
                .formatted(i));
        }
        qs.append(']');
        Map<?, ?> r = (Map<?, ?>) call(TrainingPack.DRAFT_BATCH, agent, "{\"courseId\":%d,\"sourceRef\":\"SOP-受限空间-v3\",\"questions\":%s}".formatted(c, qs)).data();
        List<?> ids = (List<?>) r.get("ids");
        assertThat(ids).hasSize(10);
        for (Object id : ids) {
            Map<String, Object> q = records.row(QUESTION, ((Number) id).longValue());
            assertThat(q).containsEntry("status", "DRAFT").containsEntry("origin", "LLM").containsEntry("sourceRef", "SOP-受限空间-v3");
        }
        long first = ((Number) ids.get(0)).longValue();
        assertThatThrownBy(() -> step(QUESTION + ".approve", agent, QUESTION, first, "{}")).isInstanceOf(LsBizException.class)
            .hasMessageContaining("真人");
        step(QUESTION + ".approve", adm(), QUESTION, first, "{}");
        assertThat(status(QUESTION, first)).isEqualTo("PUBLISHED");
        // 正确答案数量不对的草稿被拒
        assertThatThrownBy(() -> call(TrainingPack.DRAFT_BATCH, agent, "{\"courseId\":%d,\"questions\":[{\"qtype\":\"SINGLE\",\"stem\":\"x\",\"score\":1,\"options\":[{\"key\":\"A\",\"content\":\"a\"},{\"key\":\"B\",\"content\":\"b\"}]}]}".formatted(c)))
            .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_FAILED"));
        // 课程发布须真人提交
        assertThatThrownBy(() -> step(COURSE + ".submit", agent, COURSE, c, "{}")).hasMessageContaining("真人");
        publish(COURSE, c);
    }

    static String seqOf(List<Map<String, Object>> items, String type) {
        return items.stream().filter(i -> type.equals(i.get("qtype"))).map(i -> String.valueOf(((Number) i.get("seq")).intValue())).findFirst().orElseThrow();
    }

    private void grant(String perms, String roleKey) {
        var jdbc = f.core.jdbc;
        Long menu = jdbc.query("select menu_id from sys_menu where perms = ?", (rs, n) -> rs.getLong(1), perms).stream().findFirst().orElseGet(() -> {
            long id = jdbc.queryForObject("select greatest(max(menu_id), 990000) + 1 from sys_menu", Long.class);
            jdbc.update("""
                insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible,
                                      status, perms, icon, create_dept, create_by, create_time)
                values (?, ?, 0, 99, '', null, '', '1', '0', 'F', '0', '0', ?, '#', 103, 1, now())""", id, perms, perms);
            return id;
        });
        jdbc.update("insert into sys_role_menu (role_id, menu_id) select role_id, ? from sys_role where role_key = ? on conflict do nothing", menu, roleKey);
    }
}
