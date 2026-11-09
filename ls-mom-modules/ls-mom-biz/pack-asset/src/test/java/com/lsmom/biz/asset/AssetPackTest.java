package com.lsmom.biz.asset;

import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.api.OperationResult;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueService;
import com.lsmom.core.issue.IssueStatus;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.lsmom.biz.asset.AssetPack.ASSET;
import static com.lsmom.biz.asset.AssetPack.DEFECT;
import static com.lsmom.biz.asset.AssetPack.PLAN;
import static com.lsmom.biz.asset.AssetPack.RECORD;
import static com.lsmom.biz.asset.AssetPack.RECORD_ITEM;
import static com.lsmom.biz.asset.AssetPack.STATUS_LOG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** spec domain/asset-management：台账状态留痕、点检计划生成点检工作、异常项一键生成缺陷草稿、缺陷 Issue 外壳、维修到验证关闭、设备时间轴。 */
class AssetPackTest {

    static final String[] PERMS = {"ls:asset:manage", "ls:asset:plan", "ls:asset:inspect", "ls:asset:repair", "ls:asset:verify",
        "lsmom:work:edit", "lsmom:work:assign", "lsmom:work:execute", "lsmom:work:confirm",
        "crud:" + ASSET + ":add", "crud:" + ASSET + ":list", "crud:" + ASSET + ":query", "crud:" + ASSET + ":edit",
        "crud:" + PLAN + ":add", "crud:" + PLAN + ":query", "crud:" + RECORD + ":query", "crud:" + RECORD + ":edit",
        "crud:" + DEFECT + ":add", "crud:" + DEFECT + ":query", "crud:" + STATUS_LOG + ":list", "crud:" + STATUS_LOG + ":query"};

    final PackFixture f = new PackFixture();
    final ManagedRecords records = new ManagedRecords(f.core.jdbc, f.action.concepts);
    final IssueService issues = f.action.issues;
    final AssetTimeline timeline = new AssetTimeline(f.core.jdbc, records);
    final InspectionScheduler scheduler;

    AssetPackTest() {
        AssetConfiguration cfg = new AssetConfiguration();
        AssetEffects e = new AssetEffects(f.core.jdbc, records, f.core.works, f.core.assignments, issues, new ObservationService(f.core.jdbc),
            f.action.docs, f.core.parties, Clock.systemDefaultZone());
        for (var effect : List.of(cfg.assetCommissionEffect(e), cfg.assetRunEffect(e), cfg.assetStandbyEffect(e), cfg.assetMaintainEffect(e),
            cfg.assetStopEffect(e), cfg.assetScrapEffect(e), cfg.inspectionGenerateEffect(e), cfg.inspectionSubmitEffect(e),
            cfg.inspectionRaiseDefectsEffect(e), cfg.defectReportEffect(e), cfg.defectCreateRepairEffect(e), cfg.defectSubmitVerifyEffect(e),
            cfg.defectVerifyPassEffect(e), cfg.defectVerifyFailEffect(e), cfg.defectCancelEffect(e))) {
            f.action.gateway.registerEffect(effect);
        }
        for (var g : List.of(cfg.assetPlanReadyGuard(records), cfg.assetRepairsDoneGuard(records, issues), cfg.assetVerifierGuard(records, issues))) {
            if (!f.action.guardRegistry.isRegistered(g.code())) f.action.guardRegistry.register(g);
        }
        scheduler = new InspectionScheduler(f.core.jdbc, records, f.action.gateway, Clock.systemDefaultZone());
        installPack();
    }

    static PackSource source(PackFixture f) {
        return new StandardPacks(f.installer, f.store).discover().stream()
            .filter(s -> PackManifest.parse(s).code().equals(AssetPack.CODE)).findFirst().orElseThrow();
    }

    private void installPack() {
        PackInstaller.Result r = f.installer.install(source(f), null);
        assertThat(r.status()).isIn("INSTALLED", "UPGRADED", "UNCHANGED");
    }

    ActorContext user(Party p) {
        return f.action.human(p, DataScopeLevel.WORKSHOP, PERMS);
    }

    long create(String concept, ActorContext a, String body) {
        return ((Number) f.crud.crud.create(concept, ActionFixture.json(body), a, null).get("id")).longValue();
    }

    int version(String concept, long id) {
        return ((Number) records.row(concept, id).get("version")).intValue();
    }

    OperationResult step(String op, ActorContext a, String concept, long id, String input) {
        return f.action.gateway.execute(op, OperationRequest.on(String.valueOf(id), version(concept, id), ActionFixture.json(input)), a);
    }

    /** 错误码；守卫阻断（GUARD_FAILED）时取守卫给出的原因码。 */
    static String code(Throwable e) {
        LsBizException b = (LsBizException) e;
        return "GUARD_FAILED".equals(b.getCode()) ? String.valueOf(b.getDetails().get("reason")) : b.getCode();
    }

    long pump(ActorContext a, String code) {
        long id = create(ASSET, a, "{\"assetCode\":\"" + code + "\",\"name\":\"" + code + " 循环泵\",\"assetType\":\"PUMP\",\"location\":\"A 线\",\"orgId\":"
            + f.plant.workshop().id() + "}");
        step(ASSET + ".commission", a, ASSET, id, "{}");
        return id;
    }

    long plan(ActorContext a, long asset, Party responsible, LocalDate due) {
        long id = create(PLAN, a, """
            {"name":"循环泵日检","asset":%d,"cycle":"DAILY","responsible":%d,"nextDue":"%s","orgId":%d,
             "inspectionPlanItem":[{"itemCode":"TEMP","name":"轴承温度","valueType":"NUMBER","unit":"℃","upperLimit":70,"standard":"≤70℃"},
                                   {"itemCode":"LEAK","name":"机封泄漏","valueType":"CHECK","standard":"无滴漏"}]}"""
            .formatted(asset, responsible.id(), due, f.plant.workshop().id()));
        step(PLAN + ".activate", a, PLAN, id, "{}");
        return id;
    }

    @Test
    void 标准Pack安装_状态变更留痕() {
        assertThat(f.store.find(AssetPack.CODE)).hasValueSatisfying(p -> assertThat(p.status()).isEqualTo("ENABLED"));
        for (String c : List.of(ASSET, STATUS_LOG, PLAN, AssetPack.PLAN_ITEM, RECORD, RECORD_ITEM, DEFECT)) {
            assertThat(f.action.concepts.find(c)).as(c).isPresent();
        }
        assertThat(f.installer.install(source(f), null).status()).isEqualTo("UNCHANGED");
        var t = f.tester.run(AssetPack.CODE);
        assertThat(t.passed()).as(String.valueOf(t)).isTrue();

        Party keeper = f.core.employee("设备员", f.plant.workshop());
        ActorContext a = user(keeper);
        long p = pump(a, PackFixture.code("P"));
        // 停机检修必须写原因
        assertThatThrownBy(() -> step(ASSET + ".maintain", a, ASSET, p, "{}")).extracting(AssetPackTest::code).isEqualTo("VALIDATION_FAILED");
        step(ASSET + ".maintain", a, ASSET, p, "{\"reason\":\"机封泄漏，停机检修\"}");
        assertThatThrownBy(() -> step(ASSET + ".standby", a, ASSET, p, "{}")).extracting(AssetPackTest::code).isEqualTo("INVALID_TRANSITION");
        Map<String, Object> row = records.row(ASSET, p);
        assertThat(row.get("status")).isEqualTo("MAINTENANCE");
        assertThat(row.get("statusReason")).isEqualTo("机封泄漏，停机检修");
        assertThat(row.get("commissionedOn")).isNotNull();
        List<Map<String, Object>> logs = records.where(STATUS_LOG, "asset", p);
        assertThat(logs).extracting(l -> l.get("fromStatus") + ">" + l.get("toStatus")).containsExactly("DRAFT>RUNNING", "RUNNING>MAINTENANCE");
        assertThat(logs.get(1)).containsEntry("reason", "机封泄漏，停机检修").containsEntry("operator", keeper.id());
        assertThat(timeline.of(p, 50)).extracting(m -> m.get("kind")).contains("STATUS");
    }

    @Test
    void 点检计划按周期生成点检工作_异常项一键生成缺陷草稿() {
        Party keeper = f.core.employee("设备员", f.plant.workshop());
        Party inspector = f.core.employee("点检员", f.plant.teamA());
        ActorContext a = user(keeper);
        ActorContext ins = user(inspector);
        long p = pump(a, PackFixture.code("P"));
        LocalDate today = LocalDate.now();
        // 没有检查项的计划不能启用
        long empty = create(PLAN, a, "{\"name\":\"空计划\",\"asset\":%d,\"cycle\":\"DAILY\",\"responsible\":%d,\"nextDue\":\"%s\",\"orgId\":%d}"
            .formatted(p, inspector.id(), today, f.plant.workshop().id()));
        assertThatThrownBy(() -> step(PLAN + ".activate", a, PLAN, empty, "{}")).extracting(AssetPackTest::code).isEqualTo("PLAN_NO_ITEMS");
        long plan = plan(a, p, inspector, today);

        // 定时任务：到期的计划生成一次点检工作 + 点检记录，下次日期顺延一天；同一天再跑不重复
        assertThat(scheduler.generateDue(today)).isGreaterThanOrEqualTo(1);
        List<Map<String, Object>> recs = records.where(RECORD, "plan", plan);
        assertThat(recs).hasSize(1);
        assertThat(records.row(PLAN, plan).get("nextDue").toString()).isEqualTo(today.plusDays(1).toString());
        scheduler.generateDue(today);
        assertThat(records.where(RECORD, "plan", plan)).hasSize(1);

        Map<String, Object> rec = recs.get(0);
        long recId = ((Number) rec.get("id")).longValue();
        WorkItem work = f.core.works.get(((Number) rec.get("workItem")).longValue());
        assertThat(work.type()).isEqualTo(WorkType.INSPECTION);
        assertThat(work.status()).isEqualTo(WorkStatus.ASSIGNED);
        assertThat(f.core.works.assignedTo(work.id(), inspector.id())).isTrue();
        assertThat(rec.get("inspector")).isEqualTo(inspector.id());
        assertThat(rec.get("orgId")).as("记录归点检人所在班组").isEqualTo(f.plant.teamA().id());
        assertThat(work.orgUnitId()).isEqualTo(f.plant.teamA().id());
        assertThat(records.children(RECORD_ITEM, recId)).extracting(i -> i.get("itemCode")).containsExactly("TEMP", "LEAK");

        // 漏填读数不能提交
        assertThatThrownBy(() -> step(RECORD + ".submit", ins, RECORD, recId, "{\"items\":[{\"itemCode\":\"LEAK\",\"outcome\":\"OK\"}]}"))
            .extracting(AssetPackTest::code).isEqualTo("VALIDATION_FAILED");
        // 温度 82℃ 超上限 → 自动判异常并提示
        OperationResult r = step(RECORD + ".submit", ins, RECORD, recId,
            "{\"items\":[{\"itemCode\":\"TEMP\",\"valueNum\":82},{\"itemCode\":\"LEAK\",\"outcome\":\"OK\"}]}");
        assertThat(r.warnings()).anyMatch(w -> w.contains("1 项点检异常") && w.contains("轴承温度") && w.contains("缺陷草稿"));
        Map<String, Object> done = records.row(RECORD, recId);
        assertThat(done).containsEntry("status", "SUBMITTED").containsEntry("result", "ABNORMAL");
        assertThat(((Number) done.get("abnormalCount")).intValue()).isEqualTo(1);
        assertThat(f.core.works.get(work.id()).status()).isEqualTo(WorkStatus.DONE);
        assertThat(f.action.count("select count(*) from core_observation where object_type = 'ASSET' and object_id = ?", String.valueOf(p))).isEqualTo(2);
        assertThat(f.action.count("select count(*) from core_observation where object_type = 'ASSET' and object_id = ? and quality = 'BAD' and value_num = 82",
            String.valueOf(p))).isEqualTo(1);

        // 一键生成缺陷草稿：缺陷（DRAFT）+ Issue 草稿，关联设备；再点一次没有可生成的
        step(RECORD + ".raiseDefects", ins, RECORD, recId, "{}");
        List<Map<String, Object>> defects = records.where(DEFECT, "inspectionRecord", recId);
        assertThat(defects).hasSize(1);
        Map<String, Object> d = defects.get(0);
        assertThat(d).containsEntry("status", "DRAFT").containsEntry("asset", p).containsEntry("inspectionItem", "TEMP");
        assertThat(String.valueOf(d.get("symptom"))).contains("82").contains("≤70℃");
        Issue issue = issues.get(((Number) d.get("issue")).longValue());
        assertThat(issue.status()).isEqualTo(IssueStatus.DRAFT);
        assertThat(issue.extConcept()).isEqualTo(DEFECT);
        assertThat(issues.links(issue.id(), null)).extracting(IssueService.ObjectRef::objectType).contains("ASSET", RECORD);
        assertThat(records.children(RECORD_ITEM, recId).get(0).get("defect")).isEqualTo(d.get("id"));
        assertThatThrownBy(() -> step(RECORD + ".raiseDefects", ins, RECORD, recId, "{}")).extracting(AssetPackTest::code).isEqualTo("NO_ABNORMAL_ITEMS");
        assertThat(timeline.of(p, 50)).extracting(m -> m.get("kind")).contains("INSPECTION", "OBSERVATION");
    }

    @Test
    void 缺陷登记产生Issue_维修到验证关闭_时间轴可见报警缺陷维修验证() {
        Party keeper = f.core.employee("设备员", f.plant.workshop());
        Party fitter = f.core.employee("维修工", f.plant.teamA());
        Party verifier = f.core.employee("设备主管", f.plant.workshop());
        ActorContext a = user(keeper);
        ActorContext fit = user(fitter);
        ActorContext ver = user(verifier);
        long p = pump(a, PackFixture.code("P"));

        // DCS 报警（第 15 组接入）：以 DCS 来源 Issue 关联到该泵；由报警产生缺陷
        Issue alarm = issues.raise(new IssueService.RaiseIssue("DCS_ALARM", "出口压力低低", "", "HIGH", f.plant.workshop().id(), "DCS",
            "DCS:" + PackFixture.code("PT"), null, null, null, null, null, List.of(new IssueService.ObjectRef("ASSET", String.valueOf(p))))).issue();

        long d = create(DEFECT, a, "{\"asset\":%d,\"title\":\"出口压力低，叶轮磨损\",\"severity\":\"MEDIUM\",\"orgId\":%d}".formatted(p, f.plant.workshop().id()));
        assertThat(records.row(DEFECT, d).get("issue")).isNull();
        step(DEFECT + ".report", a, DEFECT, d, "{}");
        Map<String, Object> row = records.row(DEFECT, d);
        assertThat(row.get("status")).isEqualTo("REPORTED");
        Issue issue = issues.get(((Number) row.get("issue")).longValue());
        assertThat(issue.status()).isEqualTo(IssueStatus.OPEN);
        assertThat(issue.type()).isEqualTo(AssetPack.ISSUE_TYPE);
        assertThat(issue.extConcept()).isEqualTo(DEFECT);
        assertThat(issue.extId()).isEqualTo(d);
        assertThat(issues.links(issue.id(), null)).contains(new IssueService.ObjectRef("ASSET", String.valueOf(p)));

        // 高严重度缺陷登记前必须有现场证据
        long high = create(DEFECT, a, "{\"asset\":%d,\"title\":\"泵体裂纹\",\"severity\":\"HIGH\",\"orgId\":%d}".formatted(p, f.plant.workshop().id()));
        assertThatThrownBy(() -> step(DEFECT + ".report", a, DEFECT, high, "{}")).extracting(AssetPackTest::code).isEqualTo("EVIDENCE_REQUIRED");
        assertThat(records.row(DEFECT, high).get("issue")).as("失败整体回滚，不留 Issue").isNull();

        // 生成维修工作：MAINTENANCE，附检查清单，派给维修工并占用该泵
        OperationResult rr = step(DEFECT + ".createRepair", a, DEFECT, d,
            "{\"assignee\":%d,\"checklist\":[\"更换叶轮\",\"复测出口压力\"]}".formatted(fitter.id()));
        assertThat(rr.data()).asString().contains("works");
        assertThat(issues.get(issue.id()).status()).isEqualTo(IssueStatus.IN_RECTIFICATION);
        List<WorkItem> repairs = issues.rectifications(issue.id());
        assertThat(repairs).hasSize(1);
        WorkItem w = repairs.get(0);
        assertThat(w.type()).isEqualTo(WorkType.MAINTENANCE);
        assertThat(w.status()).isEqualTo(WorkStatus.ASSIGNED);
        assertThat(w.description()).contains("1. 更换叶轮").contains("2. 复测出口压力");
        assertThat(records.row(DEFECT, d).get("repairChecklist")).isEqualTo("更换叶轮\n复测出口压力");

        // 维修未完成不能提交验证
        assertThatThrownBy(() -> step(DEFECT + ".submitVerify", a, DEFECT, d, "{}")).extracting(AssetPackTest::code).isEqualTo("RECTIFICATION_INCOMPLETE");
        f.action.step("ls.core.Work.start", fit, "ls.core.Work", String.valueOf(w.id()), "{}");
        f.action.step("ls.core.Work.submitConfirm", fit, "ls.core.Work", String.valueOf(w.id()), "{}");
        f.action.step("ls.core.Work.confirm", a, "ls.core.Work", String.valueOf(w.id()), "{}");
        step(DEFECT + ".submitVerify", fit, DEFECT, d, "{}");
        assertThat(issues.get(issue.id()).status()).isEqualTo(IssueStatus.PENDING_VERIFY);

        // 验证不通过 → 回到维修中，可再派一次；第二次验证通过 → 缺陷与 Issue 关闭
        step(DEFECT + ".verifyFail", ver, DEFECT, d, "{\"conclusion\":\"压力仍偏低\"}");
        assertThat(records.row(DEFECT, d).get("status")).isEqualTo("REPAIRING");
        assertThat(issues.get(issue.id()).status()).isEqualTo(IssueStatus.IN_RECTIFICATION);
        step(DEFECT + ".createRepair", a, DEFECT, d, "{\"titles\":[\"复查进口滤网\"],\"assignee\":%d}".formatted(fitter.id()));
        WorkItem w2 = issues.rectifications(issue.id()).stream().filter(x -> x.status() != WorkStatus.DONE).findFirst().orElseThrow();
        f.action.step("ls.core.Work.start", fit, "ls.core.Work", String.valueOf(w2.id()), "{}");
        f.action.step("ls.core.Work.submitConfirm", fit, "ls.core.Work", String.valueOf(w2.id()), "{}");
        f.action.step("ls.core.Work.confirm", a, "ls.core.Work", String.valueOf(w2.id()), "{}");
        step(DEFECT + ".submitVerify", fit, DEFECT, d, "{}");
        step(DEFECT + ".verifyPass", ver, DEFECT, d, "{\"conclusion\":\"出口压力恢复 0.45MPa\"}");
        assertThat(records.row(DEFECT, d)).containsEntry("status", "CLOSED").containsEntry("verifyConclusion", "出口压力恢复 0.45MPa");
        Issue closed = issues.get(issue.id());
        assertThat(closed.status()).isEqualTo(IssueStatus.CLOSED);
        assertThat(closed.verifiedBy()).isEqualTo(verifier.id());

        // 设备时间轴：报警、缺陷登记、维修工作、验证都在，按时间倒序
        List<Map<String, Object>> tl = timeline.of(p, 100);
        assertThat(tl).extracting(m -> m.get("kind")).contains("ALARM", "DEFECT", "REPAIR", "VERIFY", "STATUS");
        assertThat(tl).anyMatch(m -> "ALARM".equals(m.get("kind")) && String.valueOf(m.get("title")).contains("出口压力低低"));
        assertThat(tl).anyMatch(m -> "VERIFY".equals(m.get("kind")) && String.valueOf(m.get("title")).contains("验证通过")
            && String.valueOf(m.get("detail")).contains("0.45MPa"));
        assertThat(tl).anyMatch(m -> "VERIFY".equals(m.get("kind")) && String.valueOf(m.get("detail")).contains("压力仍偏低"));
        assertThat(tl).filteredOn(m -> "REPAIR".equals(m.get("kind"))).hasSize(2);
        assertThat(tl.get(0).get("kind")).isIn("VERIFY", "DEFECT", "REPAIR");
        assertThat(alarm.id()).isPositive();
    }

    @Test
    void 验证人不能是唯一维修执行人_取消缺陷一并取消Issue() {
        Party keeper = f.core.employee("设备员", f.plant.workshop());
        ActorContext a = user(keeper);
        long p = pump(a, PackFixture.code("P"));
        f.core.profiles.enableForOrg(f.plant.teamB().id(), "STRICT");
        long d = create(DEFECT, a, "{\"asset\":%d,\"title\":\"联轴器松动\",\"severity\":\"LOW\",\"orgId\":%d}".formatted(p, f.plant.teamB().id()));
        step(DEFECT + ".report", a, DEFECT, d, "{}");
        step(DEFECT + ".createRepair", a, DEFECT, d, "{\"assignee\":%d}".formatted(keeper.id()));
        Issue issue = issues.get(((Number) records.row(DEFECT, d).get("issue")).longValue());
        WorkItem w = issues.rectifications(issue.id()).get(0);
        f.action.step("ls.core.Work.start", a, "ls.core.Work", String.valueOf(w.id()), "{}");
        f.action.step("ls.core.Work.submitConfirm", a, "ls.core.Work", String.valueOf(w.id()), "{}");
        f.action.step("ls.core.Work.confirm", a, "ls.core.Work", String.valueOf(w.id()), "{}");
        step(DEFECT + ".submitVerify", a, DEFECT, d, "{}");
        assertThatThrownBy(() -> step(DEFECT + ".verifyPass", a, DEFECT, d, "{\"conclusion\":\"好了\"}"))
            .extracting(AssetPackTest::code).isEqualTo("VERIFIER_NOT_SEPARATED");

        long d2 = create(DEFECT, a, "{\"asset\":%d,\"title\":\"地脚螺栓锈蚀\",\"severity\":\"LOW\",\"orgId\":%d}".formatted(p, f.plant.workshop().id()));
        step(DEFECT + ".report", a, DEFECT, d2, "{}");
        step(DEFECT + ".cancel", a, DEFECT, d2, "{\"reason\":\"重复登记\"}");
        assertThat(issues.get(((Number) records.row(DEFECT, d2).get("issue")).longValue()).status()).isEqualTo(IssueStatus.CANCELLED);
    }
}
