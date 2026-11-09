package com.lsmom.biz.quality;

import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.timeline.TimelineService;
import com.lsmom.biz.asset.AssetTimeline;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueStatus;
import com.lsmom.core.observation.Observation;
import com.lsmom.core.observation.ObservationService;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkStatus;
import com.lsmom.core.work.WorkType;
import com.lsmom.meta.concept.ManagedRecords;
import com.lsmom.pack.PackFixture;
import com.lsmom.pack.PackInstaller;
import com.lsmom.pack.PackManifest;
import com.lsmom.pack.PackSource;
import com.lsmom.pack.StandardPacks;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lsmom.biz.quality.QualityPack.NC;
import static com.lsmom.biz.quality.QualityPack.RECORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** spec domain/quality-management：DOM-04、不符合项条件必填、受控质量流程（审核节点字段只读）、整改证据、终判仅限真人。 */
class QualityPackTest {

    static final String ASSET = "ls.asset.Asset";
    static final String[] PERMS = {"ls:quality:record", "ls:quality:report", "ls:quality:audit", "ls:quality:rectify", "ls:quality:verify",
        "lsmom:work:edit", "lsmom:work:assign", "lsmom:work:execute", "lsmom:work:confirm",
        "crud:" + RECORD + ":add", "crud:" + RECORD + ":list", "crud:" + RECORD + ":query",
        "crud:" + NC + ":add", "crud:" + NC + ":edit", "crud:" + NC + ":list", "crud:" + NC + ":query", "crud:" + ASSET + ":add"};

    final PackFixture f = new PackFixture();
    final ManagedRecords records = new ManagedRecords(f.core.jdbc, f.action.concepts);

    QualityPackTest() {
        QualityConfiguration cfg = new QualityConfiguration();
        QualityEffects e = new QualityEffects(records, f.action.issues, f.core.works, f.core.assignments, Clock.systemDefaultZone());
        for (var effect : List.of(cfg.qualityRecordSubmitEffect(e), cfg.ncSubmitEffect(e), cfg.ncAuditEffect(e), cfg.ncRejectEffect(e),
            cfg.ncSubmitVerifyEffect(e), cfg.ncCloseEffect(e), cfg.ncVerifyFailEffect(e))) {
            f.action.gateway.registerEffect(effect);
        }
        for (var g : List.of(cfg.qualityRectificationDoneGuard(records, f.action.issues), cfg.qualityEvidenceGuard(records, f.action.issues, f.core.jdbc),
            cfg.qualityVerifierGuard(records, f.action.issues))) {
            if (!f.action.guardRegistry.isRegistered(g.code())) f.action.guardRegistry.register(g);
        }
        f.crud.crud.setPartyAccess(List.of(cfg.ncRectifyOwnerAccess()));
        f.crud.states.setPartyAccess(List.of(cfg.ncRectifyOwnerAccess()));
        List<PackSource> found = new StandardPacks(f.installer, f.store).discover();
        for (String code : List.of("ls.asset", QualityPack.CODE)) {
            PackSource src = found.stream().filter(s -> PackManifest.parse(s).code().equals(code)).findFirst().orElseThrow();
            assertThat(f.installer.install(src, null).status()).isIn("INSTALLED", "UPGRADED", "UNCHANGED");
        }
    }

    ActorContext user(Party p) {
        return f.action.human(p, DataScopeLevel.WORKSHOP, PERMS);
    }

    ActorContext agentFor(Party human) {
        ActorContext h = user(human);
        Party bot = f.core.employee("质量助手", f.plant.workshop());
        return new ActorContext(ActorContext.ActorType.AGENT, null, bot.id(), human.id(), false, Set.of(PERMS), h.dataScope(), "质量助手");
    }

    long create(String concept, ActorContext a, String body) {
        return ((Number) f.crud.crud.create(concept, ActionFixture.json(body), a, null).get("id")).longValue();
    }

    int version(String concept, long id) {
        return ((Number) records.row(concept, id).get("version")).intValue();
    }

    void step(String op, ActorContext a, String concept, long id, String input) {
        f.action.gateway.execute(op, OperationRequest.on(String.valueOf(id), version(concept, id), ActionFixture.json(input)), a);
    }

    static String code(Throwable e) {
        LsBizException b = (LsBizException) e;
        return "GUARD_FAILED".equals(b.getCode()) ? String.valueOf(b.getDetails().get("reason")) : b.getCode();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fields(Throwable e) {
        return (Map<String, Object>) ((LsBizException) e).getDetails().get("fields");
    }

    long asset(ActorContext a) {
        return create(ASSET, a, "{\"assetCode\":\"%s\",\"name\":\"焊机\",\"assetType\":\"STATION\",\"orgId\":%d}".formatted(PackFixture.code("W"), f.plant.workshop().id()));
    }

    @Test
    void DOM04_质量记录可独立也可同时关联工作与设备_均进入对象时间轴() {
        assertThat(f.store.find(QualityPack.CODE)).hasValueSatisfying(p -> assertThat(p.status()).isEqualTo("ENABLED"));
        var t = f.tester.run(QualityPack.CODE);
        assertThat(t.passed()).as(String.valueOf(t)).isTrue();

        Party qa = f.core.employee("质检员", f.plant.workshop());
        ActorContext a = user(qa);
        long free = create(RECORD, a, "{\"title\":\"来料抽检\",\"recordType\":\"SAMPLING\",\"result\":\"PASS\",\"orgId\":%d}".formatted(f.plant.workshop().id()));
        step(RECORD + ".submit", a, RECORD, free, "{}");
        assertThat(records.row(RECORD, free)).containsEntry("status", "SUBMITTED").containsEntry("inspector", qa.id());

        String work = f.action.newWork(ActionFixture.admin(), f.plant.teamA(), "焊接 A 段");
        long asset = asset(a);
        long linked = create(RECORD, a, """
            {"title":"焊缝外观检验","recordType":"INSPECTION","result":"FAIL","orgId":%d,
             "qualityRecordLink":[{"objectType":"WORK","objectId":"%s","label":"焊接 A 段"},{"objectType":"ASSET","objectId":"%d","label":"焊机"}]}"""
            .formatted(f.plant.workshop().id(), work, asset));
        step(RECORD + ".submit", a, RECORD, linked, "{}");
        assertThat(records.row(RECORD, linked).get("status")).isEqualTo("SUBMITTED");

        List<TimelineService.Entry> wt = new TimelineService(f.core.jdbc).of("ls.core.Work", work);
        assertThat(wt).anyMatch(x -> x.type().equals(RECORD + ".linked") && String.valueOf(x.payload().get("title")).contains("焊缝外观检验（不合格）"));
        assertThat(new AssetTimeline(f.core.jdbc, records).of(asset, 50))
            .anyMatch(m -> "EVENT".equals(m.get("kind")) && String.valueOf(m.get("title")).contains("焊缝外观检验"));
        assertThat(new TimelineService(f.core.jdbc).of("ls.core.Work", work).stream().filter(x -> x.type().equals(RECORD + ".linked"))).hasSize(1);
    }

    @Test
    void 高严重度不符合项缺设备或立即措施_保存与提交都被拒() {
        Party qa = f.core.employee("质检员", f.plant.workshop());
        ActorContext a = user(qa);
        long asset = asset(a);
        // 表单规则：保存即校验（前后端同一组 CEL 规则）
        assertThatThrownBy(() -> create(NC, a, "{\"title\":\"焊缝气孔\",\"description\":\"抽检发现焊缝气孔\",\"severity\":\"HIGH\",\"asset\":%d,\"orgId\":%d}"
            .formatted(asset, f.plant.workshop().id())))
            .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_FAILED"))
            .satisfies(e -> assertThat(fields(e)).containsOnlyKeys("immediateAction"));
        assertThatThrownBy(() -> create(NC, a, "{\"title\":\"焊缝气孔\",\"description\":\"抽检发现焊缝气孔\",\"severity\":\"HIGH\",\"immediateAction\":\"隔离\",\"orgId\":%d}"
            .formatted(f.plant.workshop().id())))
            .satisfies(e -> assertThat(fields(e)).containsOnlyKeys("asset"));
        // Pack 规则：提交时再校验一次（草稿先存为中等、再改高严重度的情况）
        long nc = create(NC, a, "{\"title\":\"焊缝气孔\",\"description\":\"抽检发现焊缝气孔\",\"severity\":\"MEDIUM\",\"orgId\":%d}".formatted(f.plant.workshop().id()));
        f.core.jdbc.update("update " + records.table(NC) + " set " + records.column(NC, "severity") + " = 'HIGH' where id = ?", nc);
        assertThatThrownBy(() -> step(NC + ".submit", a, NC, nc, "{}"))
            .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_FAILED"))
            .satisfies(e -> assertThat(fields(e).keySet()).isNotEmpty().isSubsetOf("asset", "immediateAction"));
        assertThat(records.row(NC, nc).get("status")).isEqualTo("DRAFT");
        f.crud.crud.update(NC, nc, version(NC, nc), ActionFixture.json("{\"asset\":%d,\"immediateAction\":\"隔离该批次\"}".formatted(asset)), a, null);
        step(NC + ".submit", a, NC, nc, "{}");
        assertThat(records.row(NC, nc).get("status")).isEqualTo("PENDING_AUDIT");
    }

    @Test
    void 受控质量流程_审核节点只读_整改证据_终判仅限真人() {
        Party qa = f.core.employee("质检员", f.plant.workshop());
        Party leader = f.core.employee("车间主任", f.plant.workshop());
        Party owner = f.core.employee("整改负责人", f.plant.teamA());
        ActorContext reporter = user(qa);
        ActorContext lead = user(leader);
        // 整改负责人在班组，数据域只有本班组
        ActorContext fixer = f.action.human(owner, DataScopeLevel.TEAM, PERMS);
        long nc = create(NC, reporter, "{\"title\":\"尺寸超差\",\"description\":\"轴径 50.08，公差 ±0.05\",\"severity\":\"MEDIUM\",\"orgId\":%d}"
            .formatted(f.plant.workshop().id()));
        step(NC + ".submit", reporter, NC, nc, "{}");
        Issue issue = f.action.issues.get(((Number) records.row(NC, nc).get("issue")).longValue());
        assertThat(issue.status()).isEqualTo(IssueStatus.OPEN);
        assertThat(issue.type()).isEqualTo(QualityPack.ISSUE_TYPE);
        assertThat(issue.extConcept()).isEqualTo(NC);

        // 审核节点：直接改问题描述被拒（通用编辑与审核 Operation 都不行）
        assertThatThrownBy(() -> f.crud.crud.update(NC, nc, version(NC, nc), ActionFixture.json("{\"description\":\"改一下\"}"), lead, null))
            .extracting(QualityPackTest::code).isEqualTo("GENERIC_EDIT_FORBIDDEN");
        assertThatThrownBy(() -> step(NC + ".audit", lead, NC, nc, "{\"auditOpinion\":\"同意\",\"owner\":%d,\"description\":\"改一下\"}".formatted(owner.id())))
            .satisfies(e -> assertThat(code(e)).isEqualTo("FIELD_READONLY"))
            .satisfies(e -> assertThat(fields(e)).containsOnlyKeys("description"));
        assertThat(records.row(NC, nc).get("description")).isEqualTo("轴径 50.08，公差 ±0.05");

        step(NC + ".audit", lead, NC, nc, "{\"auditOpinion\":\"同意整改，先隔离\",\"immediateAction\":\"隔离 3 件\",\"owner\":%d,\"titles\":[\"调整刀补并复检\"]}"
            .formatted(owner.id()));
        Map<String, Object> row = records.row(NC, nc);
        assertThat(row).containsEntry("status", "IN_RECTIFICATION").containsEntry("auditOpinion", "同意整改，先隔离")
            .containsEntry("immediateAction", "隔离 3 件").containsEntry("rectifyOwner", owner.id());
        List<WorkItem> rect = f.action.issues.rectifications(issue.id());
        assertThat(rect).hasSize(1);
        WorkItem w = rect.get(0);
        assertThat(w.type()).isEqualTo(WorkType.IMPROVEMENT);
        assertThat(w.status()).isEqualTo(WorkStatus.ASSIGNED);
        assertThat(f.core.works.assignedTo(w.id(), owner.id())).isTrue();

        // 派给自己后，班组的整改负责人能看到这条车间级不符合项
        assertThat(f.crud.crud.get(NC, nc, fixer)).containsEntry("title", "尺寸超差");
        assertThat(f.crud.crud.list(NC, new com.lsmom.crud.query.QuerySpec(List.of(), List.of(), 1, 50, List.of(), List.of(), List.of()), fixer).rows())
            .anyMatch(r -> ((Number) r.get("id")).longValue() == nc);
        // 整改未完成、无证据都不能提交验证
        assertThatThrownBy(() -> step(NC + ".submitVerify", fixer, NC, nc, "{}")).extracting(QualityPackTest::code).isEqualTo("RECTIFICATION_INCOMPLETE");
        f.action.step("ls.core.Work.start", fixer, "ls.core.Work", String.valueOf(w.id()), "{}");
        f.action.step("ls.core.Work.submitConfirm", fixer, "ls.core.Work", String.valueOf(w.id()), "{}");
        f.action.step("ls.core.Work.confirm", lead, "ls.core.Work", String.valueOf(w.id()), "{}");
        assertThatThrownBy(() -> step(NC + ".submitVerify", fixer, NC, nc, "{}")).extracting(QualityPackTest::code).isEqualTo("EVIDENCE_REQUIRED");
        new ObservationService(f.core.jdbc).record(new Observation(null, "WORK", String.valueOf(w.id()), "SHAFT_DIA", new BigDecimal("50.01"), null, "mm",
            LocalDateTime.now(), Observation.Source.MANUAL, "GOOD", f.plant.teamA().id(), owner.id()));
        step(NC + ".submitVerify", fixer, NC, nc, "{}");
        assertThat(f.action.issues.get(issue.id()).status()).isEqualTo(IssueStatus.PENDING_VERIFY);

        // Agent 代质检员终判：被拒并审计
        ActorContext agent = agentFor(qa);
        assertThatThrownBy(() -> step(NC + ".close", agent, NC, nc, "{\"conclusion\":\"复检合格\"}"))
            .satisfies(e -> assertThat(((LsBizException) e).getCode()).isEqualTo("GUARD_FAILED"))
            .satisfies(e -> assertThat(e.getMessage()).contains("真人"));
        assertThatThrownBy(() -> step(NC + ".verifyFail", agent, NC, nc, "{\"conclusion\":\"不行\"}"))
            .satisfies(e -> assertThat(((LsBizException) e).getCode()).isEqualTo("GUARD_FAILED"));
        assertThat(f.action.count("select count(*) from op_audit where op_code = ? and target_id = ? and result = 'FAILED' and actor_type = 'AGENT'",
            NC + ".close", String.valueOf(nc))).isEqualTo(1);
        assertThat(records.row(NC, nc).get("status")).isEqualTo("PENDING_VERIFY");

        // 真人终判
        step(NC + ".close", reporter, NC, nc, "{\"conclusion\":\"复检 5 件合格\"}");
        assertThat(records.row(NC, nc)).containsEntry("status", "CLOSED").containsEntry("verifyConclusion", "复检 5 件合格")
            .containsEntry("closedBy", qa.id());
        Issue closed = f.action.issues.get(issue.id());
        assertThat(closed.status()).isEqualTo(IssueStatus.CLOSED);
        assertThat(closed.verifiedBy()).isEqualTo(qa.id());
    }

    @Test
    void 审核驳回_验证不通过回到整改() {
        Party qa = f.core.employee("质检员", f.plant.workshop());
        Party owner = f.core.employee("整改负责人", f.plant.teamA());
        ActorContext a = user(qa);
        long r = create(NC, a, "{\"title\":\"重复登记\",\"description\":\"同一问题\",\"severity\":\"LOW\",\"orgId\":%d}".formatted(f.plant.workshop().id()));
        step(NC + ".submit", a, NC, r, "{}");
        step(NC + ".reject", a, NC, r, "{\"auditOpinion\":\"重复，已有不符合项\"}");
        assertThat(records.row(NC, r).get("status")).isEqualTo("REJECTED");
        assertThat(f.action.issues.get(((Number) records.row(NC, r).get("issue")).longValue()).status()).isEqualTo(IssueStatus.REJECTED);

        long nc = create(NC, a, "{\"title\":\"毛刺\",\"description\":\"边缘毛刺\",\"severity\":\"LOW\",\"orgId\":%d}".formatted(f.plant.workshop().id()));
        step(NC + ".submit", a, NC, nc, "{}");
        step(NC + ".audit", a, NC, nc, "{\"auditOpinion\":\"去毛刺\",\"owner\":%d}".formatted(owner.id()));
        ActorContext fixer = user(owner);
        WorkItem w = f.action.issues.rectifications(((Number) records.row(NC, nc).get("issue")).longValue()).get(0);
        f.action.step("ls.core.Work.start", fixer, "ls.core.Work", String.valueOf(w.id()), "{}");
        f.action.step("ls.core.Work.submitConfirm", fixer, "ls.core.Work", String.valueOf(w.id()), "{}");
        f.action.step("ls.core.Work.confirm", a, "ls.core.Work", String.valueOf(w.id()), "{}");
        f.core.jdbc.update("insert into core_document_link (oss_id, object_type, object_id, purpose, file_name) values (?, 'ls.core.Work', ?, 'EVIDENCE', 'after.jpg')",
            System.nanoTime() % 1_000_000_000L, String.valueOf(w.id()));
        step(NC + ".submitVerify", fixer, NC, nc, "{}");
        step(NC + ".verifyFail", a, NC, nc, "{\"conclusion\":\"仍有毛刺\"}");
        assertThat(records.row(NC, nc)).containsEntry("status", "IN_RECTIFICATION").containsEntry("verifyConclusion", "仍有毛刺");
    }
}
