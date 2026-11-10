package com.lsmom.biz.consultation;

import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.api.OperationResult;
import com.lsmom.action.timeline.TimelineService;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.org.OrgUnitType;
import com.lsmom.core.party.Party;
import com.lsmom.crud.query.QuerySpec;
import com.lsmom.meta.concept.ManagedRecords;
import com.lsmom.pack.PackFixture;
import com.lsmom.pack.PackManifest;
import com.lsmom.pack.PackSource;
import com.lsmom.pack.StandardPacks;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lsmom.biz.consultation.ConsultationPack.OPINION;
import static com.lsmom.biz.consultation.ConsultationPack.POINT;
import static com.lsmom.biz.consultation.ConsultationPack.SUMMARY;
import static com.lsmom.biz.consultation.ConsultationPack.TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** spec packs/consultation：匿名意见（答复人不可见、审计保留）、答复与转整改、总结引用来源意见（LLM 草拟、真人发布）、独立安装。 */
class ConsultationPackTest {

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

    static final TestClock CLOCK = new TestClock();
    static final String MANAGE = "ls:consultation:manage";
    static final String[] MANAGER_PERMS = {MANAGE, "ls:consultation:reply", "ls:consultation:opine",
        "crud:" + TOPIC + ":add", "crud:" + TOPIC + ":edit", "crud:" + TOPIC + ":query", "crud:" + TOPIC + ":list",
        "crud:" + OPINION + ":query", "crud:" + OPINION + ":list", "crud:" + SUMMARY + ":query", "crud:" + SUMMARY + ":list"};

    static {
        ActionFixture.extraHandlers = af -> {
            ManagedRecords r = new ManagedRecords(af.core.jdbc, af.concepts);
            ConsultationConfiguration c = new ConsultationConfiguration();
            return List.of(c.consultationSubmitOpinion(af.core.jdbc, r, CLOCK), c.consultationDraftSummary(r));
        };
    }

    final PackFixture f = new PackFixture();
    final ManagedRecords records = new ManagedRecords(f.core.jdbc, f.action.concepts);
    final TestClock clock = CLOCK;
    final ConsultationScheduler scheduler;
    final TimelineService timeline = new TimelineService(f.core.jdbc);
    final Party leader = f.core.employee("周主任", f.plant.workshop());
    final Party zheng = f.core.employee("郑师傅", f.plant.teamA());
    final Party wu = f.core.employee("吴班长", f.plant.teamB());
    final Party outsider = f.core.employee("外车间员工", f.core.org(PackFixture.code("二车间"), OrgUnitType.WORKSHOP, f.plant.company().id()));

    ConsultationPackTest() {
        ConsultationConfiguration cfg = new ConsultationConfiguration();
        ConsultationEffects e = cfg.consultationEffects(records, f.action.issues, clock);
        for (var effect : List.of(cfg.consultationTopicOpenEffect(e), cfg.consultationTopicCloseEffect(e), cfg.consultationReplyEffect(e),
            cfg.consultationToIssueEffect(e), cfg.consultationSummaryPublishEffect(e))) {
            f.action.gateway.registerEffect(effect);
        }
        timeline.setRedactors(List.of(cfg.consultationAnonymousTimeline(records)));
        scheduler = cfg.consultationScheduler(f.core.jdbc, records, f.action.gateway, clock);
        PackSource src = new StandardPacks(f.installer, f.store).discover().stream()
            .filter(s -> PackManifest.parse(s).code().equals(ConsultationPack.CODE)).findFirst().orElseThrow();
        try {
            assertThat(f.installer.install(src, null).status()).isIn("INSTALLED", "UPGRADED", "UNCHANGED");
        } catch (LsBizException ex) {
            // 升级测试已在本 JVM 中装了更高版本
            assertThat(ex.getCode()).isEqualTo("PACK_DOWNGRADE");
        }
        f.action.operations.reconcilePending();
        assertThat(f.action.operations.pendingCodes()).isEmpty();
    }

    ActorContext manager() {
        return f.action.human(leader, DataScopeLevel.WORKSHOP, MANAGER_PERMS);
    }

    ActorContext employee(Party p) {
        return f.action.human(p, DataScopeLevel.SELF, "ls:consultation:opine", "crud:" + SUMMARY + ":query");
    }

    ActorContext auditor() {
        return f.action.human(leader, DataScopeLevel.WORKSHOP, "ls:consultation:reply", ConsultationPack.AUDIT, "crud:" + OPINION + ":query");
    }

    ActorContext agent() {
        Party bot = f.core.employee("咨询助手", f.plant.workshop());
        return new ActorContext(ActorContext.ActorType.AGENT, null, bot.id(), leader.id(), false, Set.of(MANAGER_PERMS), manager().dataScope(), "咨询助手");
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

    /** 新建并发布议题（范围：车间及下属班组，截止时间为 2 小时后）。 */
    long topic(String title, boolean anonymous) {
        String deadline = LocalDateTime.now(clock).plusHours(2).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        long id = ((Number) f.crud.crud.create(TOPIC, ActionFixture.json("""
            {"title":"%s","description":"征求意见","scopeOrg":%d,"deadline":"%s","anonymous":%s,"owner":%d,"orgId":%d}"""
            .formatted(title, f.plant.workshop().id(), deadline, anonymous, leader.id(), f.plant.workshop().id())), manager(), null).get("id")).longValue();
        step(TOPIC + ".open", manager(), TOPIC, id, "{}");
        assertThat(status(TOPIC, id)).isEqualTo("OPEN");
        return id;
    }

    long opine(Party p, long topic, String content, String category) {
        Map<?, ?> r = (Map<?, ?>) call(ConsultationPack.SUBMIT, employee(p), """
            {"topicId":%d,"content":"%s"%s}""".formatted(topic, content, category == null ? "" : ",\"category\":\"" + category + "\"")).data();
        return ((Number) r.get("opinionId")).longValue();
    }

    @Test
    void 匿名意见_答复人看不到提交人_审计保留提交主体() {
        long t = topic("班组安全隐患征集", true);
        long o = opine(zheng, t, "三号泵房照明不足，夜班巡检看不清仪表", "SAFETY");

        // 答复人：通用台账读不到提交主体，署名为空，创建人为系统
        Map<String, Object> seen = f.crud.crud.get(OPINION, o, manager());
        assertThat(seen).doesNotContainKey("submitter");
        assertThat(seen.get("author")).isNull();
        assertThat(String.valueOf(seen.get("createdBy"))).isEqualTo("0");
        // 不能按提交主体筛选
        assertThatThrownBy(() -> f.crud.crud.list(OPINION, new QuerySpec(List.of(new QuerySpec.Filter("submitter", "eq", zheng.id())), List.of(), 1, 20,
            List.of(), List.of(), List.of()), manager())).isInstanceOf(LsBizException.class);
        // 时间轴：答复人看到的提交事件没有操作人；具备审计权限的人可以看到
        TimelineService.Entry hidden = timeline.of(OPINION, String.valueOf(o), manager()).stream()
            .filter(e -> e.type().equals(ConsultationPack.SUBMIT)).findFirst().orElseThrow();
        assertThat(hidden.actorParty()).isNull();
        assertThat(hidden.actorName()).isEqualTo(TimelineService.HIDDEN_ACTOR);
        assertThat(timeline.of(OPINION, String.valueOf(o), auditor()).stream().filter(e -> e.type().equals(ConsultationPack.SUBMIT))
            .findFirst().orElseThrow().actorParty()).isEqualTo(zheng.id());
        assertThat(f.crud.crud.get(OPINION, o, auditor())).containsEntry("submitter", zheng.id());
        // 审计：领域事件保留提交主体
        assertThat(f.core.jdbc.queryForObject("select actor_party from core_domain_event where subject_concept = ? and subject_id = ? and type = ?",
            Long.class, OPINION, String.valueOf(o), ConsultationPack.SUBMIT)).isEqualTo(zheng.id());

        // 实名议题：署名可见
        long t2 = topic("食堂菜品意见", false);
        long o2 = opine(wu, t2, "希望夜班加一顿热饭", "WELFARE");
        assertThat(f.crud.crud.get(OPINION, o2, manager())).containsEntry("author", wu.id());

        // 范围外员工不能提交；截止后不能提交
        assertThatThrownBy(() -> opine(outsider, t, "我也想说", null)).satisfies(e -> assertThat(code(e)).isEqualTo("OUT_OF_SCOPE"));
        clock.now = clock.now.plus(Duration.ofHours(3));
        try {
            assertThatThrownBy(() -> opine(wu, t, "来晚了", null)).satisfies(e -> assertThat(code(e)).isEqualTo("TOPIC_EXPIRED"));
            assertThat(scheduler.closeDue(LocalDateTime.now(clock))).isGreaterThanOrEqualTo(2);
            assertThat(status(TOPIC, t)).isEqualTo("CLOSED");
        } finally {
            clock.now = Instant.now();
        }
    }

    @Test
    void 答复归类_安全意见转整改生成关联Issue_意见显示已转整改() {
        long t = topic("安全改进建议", true);
        long o = opine(zheng, t, "储罐区缺少洗眼器", null);
        long o2 = opine(wu, t, "建议更换食堂桌椅", null);
        step(OPINION + ".reply", manager(), OPINION, o2, "{\"reply\":\"已转后勤处理\",\"category\":\"WELFARE\"}");
        assertThat(records.row(OPINION, o2)).containsEntry("status", "REPLIED").containsEntry("category", "WELFARE").containsEntry("reply", "已转后勤处理");
        assertThatThrownBy(() -> step(OPINION + ".reply", manager(), OPINION, o2, "{\"reply\":\"x\",\"category\":\"BOGUS\"}"))
            .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_FAILED"));

        // Agent 不能转整改
        assertThatThrownBy(() -> step(OPINION + ".toIssue", agent(), OPINION, o, "{}")).hasMessageContaining("真人");
        step(OPINION + ".toIssue", manager(), OPINION, o, "{\"severity\":\"HIGH\",\"owner\":%d}".formatted(leader.id()));
        Map<String, Object> converted = records.row(OPINION, o);
        assertThat(converted.get("status")).isEqualTo("CONVERTED");
        long issueId = ((Number) converted.get("issue")).longValue();
        Issue issue = f.action.issues.get(issueId);
        assertThat(issue.type()).isEqualTo(ConsultationPack.ISSUE_TYPE);
        assertThat(issue.extConcept()).isEqualTo(OPINION);
        assertThat(issue.extId()).isEqualTo(o);
        assertThat(issue.description()).contains("储罐区缺少洗眼器");
        assertThat(issue.reporterParty()).isEqualTo(leader.id());
        assertThat(issue.ownerParty()).isEqualTo(leader.id());
        assertThat(f.action.issues.links(issueId, "RELATED")).extracting(r -> r.objectType() + ":" + r.objectId()).contains(OPINION + ":" + o);
        assertThat(String.valueOf(converted.get("reply"))).contains(issue.code());
        // 已转整改的不能再转
        assertThatThrownBy(() -> step(OPINION + ".toIssue", manager(), OPINION, o, "{}")).isInstanceOf(LsBizException.class);
    }

    @Test
    void LLM草拟总结_每个观点引用来源意见_真人确认后发布() {
        long t = topic("设备点检改进", false);
        long a = opine(zheng, t, "点检表项目太多，建议按设备分级", "EQUIPMENT");
        long b = opine(wu, t, "点检路线可以合并", "EQUIPMENT");
        long c = opine(wu, t, "希望增加手持终端", "EQUIPMENT");
        long other = opine(wu, topic("其他议题", false), "无关", null);
        String draft = """
            {"topicId":%d,"origin":"LLM","content":"共收到 3 条意见，集中在点检减负与工具。","points":[
              {"viewpoint":"点检项目与路线需要精简","opinionIds":[%d,%d]},{"viewpoint":"希望配备手持终端","opinionIds":[%d]}]}""";
        // 议题截止前不能生成总结
        assertThatThrownBy(() -> call(ConsultationPack.DRAFT_SUMMARY, agent(), draft.formatted(t, a, b, c)))
            .satisfies(e -> assertThat(code(e)).isEqualTo("TOPIC_NOT_CLOSED"));
        step(TOPIC + ".close", manager(), TOPIC, t, "{}");
        // 引用其他议题的意见被拒
        assertThatThrownBy(() -> call(ConsultationPack.DRAFT_SUMMARY, agent(), draft.formatted(t, a, other, c)))
            .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_FAILED"));
        long s = ((Number) ((Map<?, ?>) call(ConsultationPack.DRAFT_SUMMARY, agent(), draft.formatted(t, a, b, c)).data()).get("summaryId")).longValue();
        assertThat(records.row(SUMMARY, s)).containsEntry("status", "DRAFT").containsEntry("origin", "LLM");
        // Agent 不能发布，负责人确认后发布
        assertThatThrownBy(() -> step(SUMMARY + ".publish", agent(), SUMMARY, s, "{}")).hasMessageContaining("真人");
        step(SUMMARY + ".publish", manager(), SUMMARY, s, "{}");
        assertThat(records.row(SUMMARY, s)).containsEntry("status", "PUBLISHED").containsEntry("confirmedBy", leader.id());
        List<Map<String, Object>> points = records.children(POINT, s);
        assertThat(points).extracting(p -> ConsultationOperations.parseIds(String.valueOf(p.get("opinionIds"))))
            .containsExactly(List.of(a, b), List.of(c));
        for (Map<String, Object> p : points) {
            for (Long id : ConsultationOperations.parseIds(String.valueOf(p.get("opinionIds")))) {
                assertThat(((Number) records.row(OPINION, id).get("topic")).longValue()).isEqualTo(t);
            }
        }
        // 同一议题只发布一份总结
        long s2 = ((Number) ((Map<?, ?>) call(ConsultationPack.DRAFT_SUMMARY, manager(), draft.formatted(t, a, b, c)).data()).get("summaryId")).longValue();
        assertThatThrownBy(() -> step(SUMMARY + ".publish", manager(), SUMMARY, s2, "{}")).satisfies(e -> assertThat(code(e)).isEqualTo("SUMMARY_EXISTS"));
        step(SUMMARY + ".discard", manager(), SUMMARY, s2, "{}");
    }

    @Test
    void 不依赖培训考试Pack_独立安装() {
        PackSource src = new StandardPacks(f.installer, f.store).discover().stream()
            .filter(s -> PackManifest.parse(s).code().equals(ConsultationPack.CODE)).findFirst().orElseThrow();
        PackManifest m = PackManifest.parse(src);
        assertThat(m.dependsOn()).noneMatch(d -> d.toString().contains("ls.training"));
        assertThat(src.files().keySet()).noneMatch(k -> new String(src.files().get(k)).contains("ls.training"));
        // 本模块类路径中没有培训 Pack
        assertThat(new StandardPacks(f.installer, f.store).discover()).extracting(s -> PackManifest.parse(s).code()).doesNotContain("ls.training");
        assertThat(f.store.find(ConsultationPack.CODE).orElseThrow().status()).isEqualTo("ENABLED");
    }

    @Test
    void DOM05_独立安装_升级保留数据_停用后入口关闭数据保留_再启用恢复() {
        PackSource src = new StandardPacks(f.installer, f.store).discover().stream()
            .filter(s -> PackManifest.parse(s).code().equals(ConsultationPack.CODE)).findFirst().orElseThrow();
        assertThat(PackManifest.parse(src).dependsOn()).isEmpty();
        long kept = topic("升级前的议题", false);
        opine(zheng, kept, "升级前的意见", null);
        String table = records.table(TOPIC);
        int rows = f.core.jdbc.queryForObject("select count(*) from " + table, Integer.class);
        assertThat(table.replace("\"", "")).startsWith("c_");

        // 升级：新版本给 Concept 增加一个字段；已有数据保留，新字段可用
        java.util.Map<String, byte[]> files = new java.util.TreeMap<>(src.files());
        String manifest = new String(files.get("pack.yaml"), java.nio.charset.StandardCharsets.UTF_8);
        files.put("pack.yaml", manifest.replace("version: 1.0.0", "version: 1.0.1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String yaml = new String(files.get("concepts/topic.yaml"), java.nio.charset.StandardCharsets.UTF_8);
        files.put("concepts/topic.yaml", (yaml.stripTrailing() + "\n  - { code: upgradeNote, displayName: 升级备注, dataType: STRING }\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!"1.0.1".equals(f.store.get(ConsultationPack.CODE).version())) {
            com.lsmom.pack.PackInstaller.Result up = f.installer.install(PackSource.of(files), null);
            assertThat(up.status()).isEqualTo("UPGRADED");
            assertThat(up.previousVersion()).isEqualTo("1.0.0");
        }
        assertThat(f.action.concepts.get(TOPIC).fields()).extracting(com.lsmom.meta.concept.FieldDef::code).contains("upgradeNote");
        assertThat(f.core.jdbc.queryForObject("select count(*) from " + table, Integer.class)).isEqualTo(rows);
        assertThat(records.row(TOPIC, kept)).isNotNull();
        // 降级被拒
        assertThatThrownBy(() -> f.installer.install(src, null)).satisfies(e -> assertThat(((LsBizException) e).getCode()).isEqualTo("PACK_DOWNGRADE"));

        // 停用：台账接口与 Operation 不可用，数据保留并可导出；再启用恢复
        f.lifecycle.disable(ConsultationPack.CODE);
        try {
            assertThatThrownBy(() -> f.crud.crud.list(TOPIC, com.lsmom.crud.query.QuerySpec.all(), ActionFixture.admin()))
                .satisfies(e -> assertThat(((LsBizException) e).getCode()).isEqualTo("PACK_DISABLED"));
            assertThatThrownBy(() -> opine(wu, kept, "停用后提交", null)).satisfies(e -> assertThat(((LsBizException) e).getCode()).isEqualTo("PACK_DISABLED"));
            assertThat(f.core.jdbc.queryForObject("select count(*) from " + table, Integer.class)).isEqualTo(rows);
            @SuppressWarnings("unchecked")
            java.util.Map<String, java.util.List<?>> data = (java.util.Map<String, java.util.List<?>>) f.lifecycle.export(ConsultationPack.CODE).get("data");
            assertThat(data.get(TOPIC)).hasSize(rows);
        } finally {
            f.lifecycle.enable(ConsultationPack.CODE);
        }
        assertThat(f.crud.crud.list(TOPIC, com.lsmom.crud.query.QuerySpec.all(), ActionFixture.admin()).total()).isEqualTo(rows);
    }
}
