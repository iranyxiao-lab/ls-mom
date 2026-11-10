package com.lsmom.biz.production;

import com.lsmom.meta.concept.ManagedRecords;
import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.api.OperationResult;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.org.OrgUnit;
import com.lsmom.core.party.Party;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkStatus;
import com.lsmom.crud.query.QuerySpec;
import com.lsmom.meta.concept.DraftPrivacy;
import com.lsmom.pack.PackFixture;
import com.lsmom.pack.PackInstaller;
import com.lsmom.pack.PackSource;
import com.lsmom.pack.StandardPacks;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.lsmom.biz.production.ProductionPack.HANDOVER;
import static com.lsmom.biz.production.ProductionPack.MATERIAL;
import static com.lsmom.biz.production.ProductionPack.REQUEST;
import static com.lsmom.biz.production.ProductionPack.SHIFT_PLAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** spec domain/light-production：标准 Pack 安装、需求转工作、DOM-01、班次调整冲突、默认不核销、交接班双方确认与草稿不可见。 */
class ProductionPackTest {

    static final String[] PERMS = {"ls:production:request", "ls:production:schedule", "ls:production:handover",
        "lsmom:work:edit", "lsmom:work:assign", "lsmom:work:execute", "lsmom:work:confirm",
        "crud:" + REQUEST + ":add", "crud:" + REQUEST + ":list", "crud:" + REQUEST + ":query",
        "crud:" + MATERIAL + ":add", "crud:" + MATERIAL + ":query", "crud:" + SHIFT_PLAN + ":add", "crud:" + SHIFT_PLAN + ":query",
        "crud:" + HANDOVER + ":add", "crud:" + HANDOVER + ":list", "crud:" + HANDOVER + ":query", "crud:" + HANDOVER + ":edit"};

    private final PackFixture f = new PackFixture();
    private final ManagedRecords records = new ManagedRecords(f.core.jdbc, f.action.concepts);

    ProductionPackTest() {
        ProductionConfiguration cfg = new ProductionConfiguration();
        ProductionEffects e = new ProductionEffects(f.core.jdbc, records, f.core.works, f.core.assignments, new HandoverSummarizer(), Clock.systemDefaultZone());
        for (var effect : List.of(cfg.productionRequestConvertEffect(e), cfg.productionRequestCancelEffect(e), cfg.shiftPlanActivateEffect(e),
            cfg.shiftPlanMoveEffect(e), cfg.handoverGenerateEffect(e), cfg.handoverPublishEffect(e), cfg.handoverAcceptEffect(e),
            cfg.handoverReturnEffect(e))) {
            f.action.gateway.registerEffect(effect);
        }
        if (!f.action.guardRegistry.isRegistered(HandoverGuards.OWNER)) f.action.guardRegistry.register(cfg.handoverOwnerGuard(records));
        if (!f.action.guardRegistry.isRegistered(HandoverGuards.RECEIVER)) f.action.guardRegistry.register(cfg.handoverReceiverGuard(records, f.core.jdbc));
        f.crud.crud.setDraftPrivacy(List.of(new DraftPrivacy(HANDOVER, "DRAFT")));
        installPack();
    }

    private void installPack() {
        List<PackSource> found = new StandardPacks(f.installer, f.store).discover();
        assertThat(found).extracting(s -> com.lsmom.pack.PackManifest.parse(s).code()).contains(ProductionPack.CODE);
        PackSource src = found.stream().filter(s -> com.lsmom.pack.PackManifest.parse(s).code().equals(ProductionPack.CODE)).findFirst().orElseThrow();
        PackInstaller.Result r = f.installer.install(src, null);
        assertThat(r.status()).isIn("INSTALLED", "UPGRADED", "UNCHANGED");
    }

    private ActorContext user(Party p) {
        return f.action.human(p, DataScopeLevel.WORKSHOP, PERMS);
    }

    private Map<String, Object> create(String concept, ActorContext actor, String body) {
        return f.crud.crud.create(concept, ActionFixture.json(body), actor, null);
    }

    private long id(Map<String, Object> r) {
        return ((Number) r.get("id")).longValue();
    }

    private int version(String concept, long id) {
        return ((Number) records.row(concept, id).get("version")).intValue();
    }

    private OperationResult step(String op, ActorContext actor, String concept, long id, String input) {
        return f.action.gateway.execute(op, OperationRequest.on(String.valueOf(id), version(concept, id), ActionFixture.json(input)), actor);
    }

    private OperationResult work(String op, ActorContext actor, long workId, String input) {
        return f.action.step(op, actor, "ls.core.Work", String.valueOf(workId), input);
    }

    private static String code(Throwable e) {
        return ((LsBizException) e).getCode();
    }

    private List<WorkItem> worksOf(long requestId) {
        return f.core.jdbc.queryForList("select id from core_work_item where source_type = ? and source_id = ? order by id", Long.class, REQUEST, requestId)
            .stream().map(f.core.works::get).toList();
    }

    private long request(ActorContext actor, OrgUnit org, String title) {
        long id = id(create(REQUEST, actor, "{\"title\":\"" + title + "\",\"period\":\"WEEK\",\"orgId\":" + org.id() + "}"));
        step(REQUEST + ".submit", actor, REQUEST, id, "{}");
        return id;
    }

    @Test
    void 标准Pack安装_投料无领用不报错() {
        assertThat(f.store.find(ProductionPack.CODE)).hasValueSatisfying(p -> assertThat(p.status()).isEqualTo("ENABLED"));
        for (String c : List.of(REQUEST, ProductionPack.CONTEXT, SHIFT_PLAN, ProductionPack.OUTPUT, MATERIAL, HANDOVER, ProductionPack.HANDOVER_ITEM)) {
            assertThat(f.action.concepts.find(c)).as(c).isPresent();
        }
        // 重复安装同一制品不产生变化
        assertThat(f.installer.install(new StandardPacks(f.installer, f.store).discover().stream()
            .filter(s -> com.lsmom.pack.PackManifest.parse(s).code().equals(ProductionPack.CODE)).findFirst().orElseThrow(), null).status())
            .isEqualTo("UNCHANGED");

        Party op = f.core.employee("投料工", f.plant.teamA());
        ActorContext a = user(op);
        Map<String, Object> m = create(MATERIAL, a, "{\"kind\":\"FEED\",\"material\":\"清洗剂\",\"quantity\":50,\"unit\":\"kg\",\"orgId\":" + f.plant.teamA().id() + "}");
        Map<String, Object> row = f.crud.crud.get(MATERIAL, id(m), a);
        assertThat(row.get("kind")).isEqualTo("FEED");
        assertThat(f.crud.crud.list(MATERIAL, new QuerySpec(List.of(new QuerySpec.Filter("material", "eq", "清洗剂")), List.of(), 1, 10, List.of(), List.of(), List.of()),
            ActionFixture.admin()).total()).isGreaterThanOrEqualTo(1);
        // Pack 自带黄金场景（需求转工作、默认不核销）与越权检查全部通过
        var t = f.tester.run(ProductionPack.CODE);
        assertThat(t.passed()).as(String.valueOf(t)).isTrue();
    }

    @Test
    void 文字需求转为两条生产工作_带班次时生成班次安排() {
        Party leader = f.core.employee("车间主任", f.plant.workshop());
        ActorContext a = user(leader);
        long req = request(a, f.plant.workshop(), "周三前完成 A 线清洗与 200 件组装");
        assertThatThrownBy(() -> step(REQUEST + ".close", a, REQUEST, req, "{}")).extracting(ProductionPackTest::code).isEqualTo("INVALID_TRANSITION");
        String day = LocalDate.now().plusDays(1).toString();
        OperationResult r = step(REQUEST + ".convert", a, REQUEST, req, """
            {"works":[{"title":"A 线清洗","teamId":%d,"shiftDate":"%s","shift":"DAY","line":"A 线"},
                      {"title":"200 件组装","quantity":200,"unit":"件"}]}""".formatted(f.plant.teamA().id(), day));
        assertThat(r.status()).isEqualTo("CONVERTED");
        List<WorkItem> ws = worksOf(req);
        assertThat(ws).hasSize(2).allSatisfy(w -> {
            assertThat(w.type().name()).isEqualTo("PRODUCTION");
            assertThat(w.sourceType()).isEqualTo(REQUEST);
        });
        assertThat(ws.get(0).plannedStart()).isEqualTo(LocalDate.parse(day).atTime(8, 0));
        assertThat(((Number) records.row(REQUEST, req).get("workCount")).intValue()).isEqualTo(2);
        List<Map<String, Object>> plans = records.where(SHIFT_PLAN, "workItem", ws.get(0).id());
        assertThat(plans).singleElement().satisfies(p -> {
            assertThat(p.get("status")).isEqualTo("ACTIVE");
            assertThat(((Number) p.get("team")).longValue()).isEqualTo(f.plant.teamA().id());
        });
        assertThat(records.where(SHIFT_PLAN, "workItem", ws.get(1).id())).isEmpty();
        assertThat(records.where(ProductionPack.CONTEXT, "workItem", ws.get(1).id())).singleElement()
            .satisfies(c -> assertThat(new java.math.BigDecimal(String.valueOf(c.get("plannedQty")))).isEqualByComparingTo("200"));
        // 可以继续追加转出
        step(REQUEST + ".convert", a, REQUEST, req, "{\"works\":[{\"title\":\"补充：包装\"}]}");
        assertThat(worksOf(req)).hasSize(3);
        assertThat(((Number) records.row(REQUEST, req).get("workCount")).intValue()).isEqualTo(3);
    }

    /** DOM-01：在没有 BOM、工艺路线、仓库的环境中走完需求 → 工作 → 分派 → 填报 → 确认。 */
    @Test
    void DOM01_无制造主数据的完整闭环() {
        assertThat(f.core.jdbc.queryForObject("select count(*) from meta_concept where code ~* '(bom|routing|warehouse)'", Integer.class)).isZero();
        Party leader = f.core.employee("车间主任", f.plant.workshop());
        Party worker = f.core.employee("操作工", f.plant.teamA());
        ActorContext a = user(leader);
        ActorContext w = user(worker);
        long req = request(a, f.plant.teamA(), "今天完成 50 件试装");
        step(REQUEST + ".convert", a, REQUEST, req, "{\"works\":[{\"title\":\"50 件试装\",\"quantity\":50,\"unit\":\"件\"}]}");
        long work = worksOf(req).get(0).id();
        work("ls.core.Work.ready", a, work, "{}");
        work("ls.core.Work.assign", a, work, "{\"targets\":[{\"resourceType\":\"PARTY\",\"resourceRef\":\"" + worker.id() + "\"}]}");
        work("ls.core.Work.start", w, work, "{}");
        work("ls.core.Work.submitReport", w, work,
            "{\"progress\":100,\"laborMinutes\":120,\"outputQty\":50,\"outputUnit\":\"件\",\"consumption\":[{\"name\":\"螺丝\",\"qty\":200,\"unit\":\"个\"}]}");
        work("ls.core.Work.submitConfirm", w, work, "{}");
        work("ls.core.Work.confirm", a, work, "{}");
        assertThat(f.core.works.get(work).status()).isEqualTo(WorkStatus.DONE);
        step(REQUEST + ".close", a, REQUEST, req, "{}");
        assertThat(records.row(REQUEST, req).get("status")).isEqualTo("CLOSED");
    }

    @Test
    void 调整班次_冲突按Profile提示或阻断() {
        Party leader = f.core.employee("班组长", f.plant.workshop());
        Party worker = f.core.employee("操作工", f.plant.teamA());
        ActorContext a = user(leader);
        String day = LocalDate.now().plusDays(2).toString();
        long req = request(a, f.plant.teamA(), "两条工作");
        step(REQUEST + ".convert", a, REQUEST, req, """
            {"works":[{"title":"早班工作","teamId":%1$d,"shiftDate":"%2$s","shift":"DAY"},
                      {"title":"中班工作","teamId":%1$d,"shiftDate":"%2$s","shift":"EVENING"}]}""".formatted(f.plant.teamA().id(), day));
        List<WorkItem> ws = worksOf(req);
        for (WorkItem x : ws) {
            work("ls.core.Work.ready", a, x.id(), "{}");
            work("ls.core.Work.assign", a, x.id(), "{\"targets\":[{\"resourceType\":\"PARTY\",\"resourceRef\":\"" + worker.id() + "\"}]}");
        }
        long evening = ((Number) records.where(SHIFT_PLAN, "workItem", ws.get(1).id()).get(0).get("id")).longValue();
        // 宽松 Profile：移到早班与同一人的早班工作冲突，提示但允许
        OperationResult moved = step(SHIFT_PLAN + ".move", a, SHIFT_PLAN, evening, "{\"shift\":\"DAY\"}");
        assertThat(moved.warnings()).anySatisfy(m -> assertThat(m).contains("资源时间冲突").contains(ws.get(0).code()));
        assertThat(records.row(SHIFT_PLAN, evening).get("shift")).isEqualTo("DAY");
        assertThat(f.core.works.get(ws.get(1).id()).plannedStart()).isEqualTo(LocalDate.parse(day).atTime(8, 0));
        assertThat(f.core.assignments.listActive(ws.get(1).id()).get(0).plannedStart()).isEqualTo(LocalDate.parse(day).atTime(8, 0));
        // 挪回中班无冲突
        assertThat(step(SHIFT_PLAN + ".move", a, SHIFT_PLAN, evening, "{\"shift\":\"EVENING\"}").warnings()).isEmpty();

        // 严格 Profile：同样的调整被阻断，安排保持不变
        OrgUnit strictTeam = f.core.org("严格班" + System.nanoTime(), com.lsmom.core.org.OrgUnitType.TEAM, f.plant.workshop().id());
        f.core.jdbc.update("update core_org_unit set constraint_profile = 'STRICT' where id = ?", strictTeam.id());
        Party w2 = f.core.employee("严格班操作工", strictTeam);
        ActorContext a2 = user(leader);
        long req2 = request(a2, strictTeam, "严格班两条工作");
        step(REQUEST + ".convert", a2, REQUEST, req2, """
            {"works":[{"title":"早班","teamId":%1$d,"shiftDate":"%2$s","shift":"DAY"},
                      {"title":"夜班","teamId":%1$d,"shiftDate":"%2$s","shift":"NIGHT"}]}""".formatted(strictTeam.id(), day));
        List<WorkItem> ws2 = worksOf(req2);
        assertThat(ws2.get(0).constraintProfile()).isEqualTo("STRICT");
        for (WorkItem x : ws2) {
            work("ls.core.Work.ready", a2, x.id(), "{}");
            work("ls.core.Work.assign", a2, x.id(), "{\"targets\":[{\"resourceType\":\"PARTY\",\"resourceRef\":\"" + w2.id() + "\"}]}");
        }
        long night = ((Number) records.where(SHIFT_PLAN, "workItem", ws2.get(1).id()).get(0).get("id")).longValue();
        assertThatThrownBy(() -> step(SHIFT_PLAN + ".move", a2, SHIFT_PLAN, night, "{\"shift\":\"DAY\"}"))
            .extracting(ProductionPackTest::code).isEqualTo("ASSIGNMENT_CONFLICT");
        assertThat(records.row(SHIFT_PLAN, night).get("shift")).isEqualTo("NIGHT");
    }

    @Test
    void 交接班_草稿不可见_双方确认_未完成事项流转到接班班组() {
        Party handing = f.core.employee("甲班班长", f.plant.teamA());
        Party receiver = f.core.employee("乙班班长", f.plant.teamB());
        Party outsider = f.core.employee("外车间", f.core.org("外车间" + System.nanoTime(), com.lsmom.core.org.OrgUnitType.WORKSHOP, f.plant.company().id()));
        ActorContext a = user(handing);
        ActorContext b = f.action.human(receiver, DataScopeLevel.WORKSHOP, PERMS);
        ActorContext o = f.action.human(outsider, DataScopeLevel.ALL, PERMS);
        String day = LocalDate.now().toString();
        long req = request(f.action.superUser(handing), f.plant.teamA(), "甲班当班工作");
        step(REQUEST + ".convert", f.action.superUser(handing), REQUEST, req, """
            {"works":[{"title":"已完成的清洗","teamId":%1$d,"shiftDate":"%2$s","shift":"DAY"},
                      {"title":"没做完的组装","teamId":%1$d,"shiftDate":"%2$s","shift":"DAY"}]}""".formatted(f.plant.teamA().id(), day));
        List<WorkItem> ws = worksOf(req);
        long doneWork = ws.get(0).id();
        long openWork = ws.get(1).id();
        for (WorkItem x : ws) {
            work("ls.core.Work.ready", a, x.id(), "{}");
            work("ls.core.Work.assign", a, x.id(), "{\"targets\":[{\"resourceType\":\"PARTY\",\"resourceRef\":\"" + handing.id() + "\"}]}");
            work("ls.core.Work.start", a, x.id(), "{}");
        }
        work("ls.core.Work.submitConfirm", a, doneWork, "{}");
        work("ls.core.Work.confirm", a, doneWork, "{}");

        long h = id(create(HANDOVER, a, """
            {"fromTeam":%d,"toTeam":%d,"shiftDate":"%s","shift":"DAY","orgId":%d}""".formatted(f.plant.teamA().id(), f.plant.teamB().id(), day,
            f.plant.teamA().id())));
        step(HANDOVER + ".generate", a, HANDOVER, h, "{}");
        Map<String, Object> draft = records.row(HANDOVER, h);
        assertThat((String) draft.get("summary")).contains("完成 1 项，未完成 1 项").contains("没做完的组装");
        assertThat(records.children(ProductionPack.HANDOVER_ITEM, h)).extracting(i -> i.get("kind")).contains("UNFINISHED", "DONE");

        // AI-09 交接班摘要：上下文只对交班人可见（草稿），要点须引用交接事项中的工作
        var summaries = f.action.summaries;
        summaries.register(new HandoverSummaryContext(records));
        var hctx = summaries.context("SHIFT_HANDOVER", String.valueOf(h), a);
        assertThat(hctx.facts()).extracting(x -> x.ref().type() + ":" + x.ref().id()).contains("ls.core.Work:" + openWork, "ls.core.Work:" + doneWork);
        assertThatThrownBy(() -> summaries.context("SHIFT_HANDOVER", String.valueOf(h), b)).extracting(ProductionPackTest::code).isEqualTo("NOT_FOUND");
        long sid = summaries.draft("SHIFT_HANDOVER", String.valueOf(h), "组装未完成，移交乙班",
            List.of(new com.lsmom.action.summary.SummaryService.Point("组装未完成", List.of(new com.lsmom.action.api.SummaryContextProvider.Ref(
                "ls.core.Work", String.valueOf(openWork), null)))), null, a);
        assertThat(summaries.find(sid).orElseThrow()).containsEntry("status", "DRAFT").containsEntry("subjectConcept", HANDOVER);

        // 草稿未确认：接班人在列表与详情里都看不到；交班人自己能看到
        QuerySpec all = new QuerySpec(List.of(), List.of(), 1, 50, List.of(), List.of(), List.of());
        assertThat(f.crud.crud.list(HANDOVER, all, b).rows()).noneMatch(r -> ((Number) r.get("id")).longValue() == h);
        assertThat(f.crud.crud.list(HANDOVER, all, o).rows()).noneMatch(r -> ((Number) r.get("id")).longValue() == h);
        assertThatThrownBy(() -> f.crud.crud.get(HANDOVER, h, b)).extracting(ProductionPackTest::code).isEqualTo("NOT_FOUND");
        assertThat(f.crud.crud.get(HANDOVER, h, a).get("status")).isEqualTo("DRAFT");
        // 别人不能替交班人确认或改写草稿
        assertThatThrownBy(() -> step(HANDOVER + ".publish", f.action.superUser(receiver), HANDOVER, h, "{}"))
            .satisfies(e -> assertThat(e.getMessage()).contains("只有交班人"));
        // 接班人不能确认尚未发布的交接班
        assertThatThrownBy(() -> step(HANDOVER + ".accept", b, HANDOVER, h, "{}")).extracting(ProductionPackTest::code).isEqualTo("INVALID_TRANSITION");

        step(HANDOVER + ".publish", a, HANDOVER, h, "{}");
        assertThat(f.crud.crud.list(HANDOVER, all, b).rows()).anyMatch(r -> ((Number) r.get("id")).longValue() == h);
        // 交班人不能自己接班；其他车间的人不能接班
        assertThatThrownBy(() -> step(HANDOVER + ".accept", a, HANDOVER, h, "{}")).satisfies(e -> assertThat(e.getMessage()).contains("不能自己接班"));
        assertThatThrownBy(() -> step(HANDOVER + ".accept", o, HANDOVER, h, "{}")).satisfies(e -> assertThat(e.getMessage()).contains("接班班组"));

        OperationResult acc = step(HANDOVER + ".accept", b, HANDOVER, h, "{\"note\":\"已了解\"}");
        assertThat(acc.status()).isEqualTo("COMPLETED");
        Map<String, Object> done = records.row(HANDOVER, h);
        assertThat(((Number) done.get("acceptedBy")).longValue()).isEqualTo(receiver.id());
        assertThat(((Number) done.get("publishedBy")).longValue()).isEqualTo(handing.id());
        // 未完成事项出现在接班班组的下一班次（早班 → 中班）；已完成的不动
        Map<String, Object> plan = records.where(SHIFT_PLAN, "workItem", openWork).get(0);
        assertThat(((Number) plan.get("team")).longValue()).isEqualTo(f.plant.teamB().id());
        assertThat(plan.get("shift")).isEqualTo("EVENING");
        assertThat(f.core.works.get(openWork).plannedStart()).isEqualTo(LocalDate.parse(day).atTime(16, 0));
        assertThat(((Number) records.where(SHIFT_PLAN, "workItem", doneWork).get(0).get("team")).longValue()).isEqualTo(f.plant.teamA().id());
        JsonNode payload = ActionFixture.json(f.core.jdbc.queryForObject(
            "select payload::text from core_domain_event where type = ? and subject_id = ? order by occurred_at desc limit 1",
            String.class, HANDOVER + ".accept", String.valueOf(h)));
        assertThat(payload.toString()).contains(f.core.works.get(openWork).code());
    }

    @Test
    void 班次时段与下一班次() {
        LocalDate d = LocalDate.of(2026, 11, 10);
        assertThat(new ShiftWindow(d, "NIGHT").start()).isEqualTo(d.atStartOfDay());
        assertThat(new ShiftWindow(d, "EVENING").end()).isEqualTo(d.plusDays(1).atStartOfDay());
        assertThat(new ShiftWindow(d, "EVENING").next()).isEqualTo(new ShiftWindow(d.plusDays(1), "NIGHT"));
        assertThat(new ShiftWindow(d, "NIGHT").next().shift()).isEqualTo("DAY");
        assertThatThrownBy(() -> new ShiftWindow(d, "NOON")).isInstanceOf(LsBizException.class);
    }
}
