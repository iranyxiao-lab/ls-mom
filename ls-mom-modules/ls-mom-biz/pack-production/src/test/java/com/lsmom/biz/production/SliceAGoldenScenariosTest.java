package com.lsmom.biz.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.api.OperationResult;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.org.OrgUnit;
import com.lsmom.core.org.OrgUnitType;
import com.lsmom.core.party.Party;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkStatus;
import com.lsmom.crud.query.QuerySpec;
import com.lsmom.meta.concept.DraftPrivacy;
import com.lsmom.pack.PackFixture;
import com.lsmom.pack.PackManifest;
import com.lsmom.pack.StandardPacks;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.lsmom.biz.production.ProductionPack.HANDOVER;
import static com.lsmom.biz.production.ProductionPack.MATERIAL;
import static com.lsmom.biz.production.ProductionPack.OUTPUT;
import static com.lsmom.biz.production.ProductionPack.REQUEST;
import static com.lsmom.biz.production.ProductionPack.SHIFT_PLAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 13.5 切片 A 黄金场景：由数据集 {@code golden/slice-a.yaml} 驱动。
 * DOM-01 无制造主数据的完整闭环；DOM-02 约束 Profile 宽严切换（冲突提示 / 阻断、严格模式的填报要求）；
 * DOM-03 产出与物料只记录不核销、交接班双方确认后未完成事项流转。
 */
class SliceAGoldenScenariosTest {

    private static final String[] PERMS = ProductionPackTest.PERMS;
    private static final String[] RECORD_PERMS = {"crud:" + OUTPUT + ":add", "crud:" + OUTPUT + ":query", "crud:" + MATERIAL + ":add",
        "crud:" + MATERIAL + ":query", "lsmom:work:execute"};

    private final PackFixture f = new PackFixture();
    private final ProductionRecords records = new ProductionRecords(f.core.jdbc, f.action.concepts);
    private final JsonNode ds = load();
    private final String day = LocalDate.now().plusDays(3).toString();

    SliceAGoldenScenariosTest() {
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
        f.installer.install(new StandardPacks(f.installer, f.store).discover().stream()
            .filter(s -> PackManifest.parse(s).code().equals(ProductionPack.CODE)).findFirst().orElseThrow(), null);
    }

    private static JsonNode load() {
        try (InputStream in = new ClassPathResource("golden/slice-a.yaml").getInputStream()) {
            return new YAMLMapper().readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 数据集落到一个独立工厂（车间 + 两个班组）中的运行结果。 */
    private final class Run {
        final OrgUnit workshop;
        final Map<String, OrgUnit> teams = new HashMap<>();
        final Map<String, Party> people = new HashMap<>();
        final Map<String, Long> works = new HashMap<>();
        long request;
        long handover;

        Run(String profile) {
            OrgUnit company = f.core.org("黄金场景公司" + System.nanoTime(), OrgUnitType.COMPANY, null);
            workshop = f.core.org("一车间" + System.nanoTime(), OrgUnitType.WORKSHOP, company.id());
            if (profile != null) f.core.profiles.enableForOrg(workshop.id(), profile);
            for (JsonNode t : ds.get("teams")) teams.put(t.asText(), f.core.org(t.asText() + System.nanoTime(), OrgUnitType.TEAM, workshop.id()));
            for (JsonNode p : ds.get("people")) {
                String org = p.get("org").asText();
                people.put(p.get("key").asText(), f.core.employee(p.get("name").asText(), org.equals("workshop") ? workshop : teams.get(org)));
            }
        }

        ActorContext as(String key) {
            return f.action.human(people.get(key), DataScopeLevel.WORKSHOP, PERMS);
        }

        /** 操作工按"仅本人"数据域办理。 */
        ActorContext operator(String key) {
            return f.action.human(people.get(key), DataScopeLevel.SELF, RECORD_PERMS);
        }

        long work(String key) {
            return works.get(key);
        }
    }

    private int version(String concept, long id) {
        return ((Number) records.row(concept, id).get("version")).intValue();
    }

    private OperationResult step(String op, ActorContext actor, String concept, long id, Object input) {
        return f.action.gateway.execute(op, OperationRequest.on(String.valueOf(id), version(concept, id), ActionFixture.JSON.valueToTree(input)), actor);
    }

    private OperationResult workOp(String op, ActorContext actor, long workId, Object input) {
        return f.action.gateway.execute(op, OperationRequest.on(String.valueOf(workId), f.core.works.get(workId).version(),
            ActionFixture.JSON.valueToTree(input)), actor);
    }

    private long create(String concept, ActorContext actor, Map<String, Object> body) {
        return ((Number) f.crud.crud.create(concept, ActionFixture.JSON.valueToTree(body), actor, null).get("id")).longValue();
    }

    /** 需求 → 转工作（排到班组班次）→ 就绪 → 分派。 */
    private Run plan(String profile) {
        Run r = new Run(profile);
        JsonNode req = ds.get("request");
        ActorContext by = r.as(req.get("by").asText());
        r.request = create(REQUEST, by, Map.of("title", req.get("title").asText(), "period", req.get("period").asText(), "orgId", r.workshop.id()));
        step(REQUEST + ".submit", by, REQUEST, r.request, Map.of());
        ArrayNode works = ActionFixture.JSON.createArrayNode();
        for (JsonNode w : req.get("works")) {
            ObjectNode o = ((ObjectNode) w).deepCopy();
            o.remove(List.of("key", "team", "assignee"));
            o.put("teamId", r.teams.get(w.get("team").asText()).id());
            o.put("shiftDate", day);
            works.add(o);
        }
        step(REQUEST + ".convert", by, REQUEST, r.request, Map.of("works", works));
        List<Long> ids = f.core.jdbc.queryForList("select id from core_work_item where source_type = ? and source_id = ? order by id", Long.class,
            REQUEST, r.request);
        int i = 0;
        for (JsonNode w : req.get("works")) {
            long id = ids.get(i++);
            r.works.put(w.get("key").asText(), id);
            workOp("ls.core.Work.ready", by, id, Map.of());
            workOp("ls.core.Work.assign", by, id, Map.of("targets", List.of(Map.of("resourceType", "PARTY",
                "resourceRef", String.valueOf(r.people.get(w.get("assignee").asText()).id())))));
        }
        return r;
    }

    /** 执行：开始 → 填报 → 完工的提交确认 → 主任确认；再记产出与物料。 */
    private void execute(Run r) {
        for (JsonNode rep : ds.get("reports")) {
            long w = r.work(rep.get("work").asText());
            ActorContext op = r.operator(rep.get("by").asText());
            if (f.core.works.get(w).status() == WorkStatus.ASSIGNED) workOp("ls.core.Work.start", op, w, Map.of());
            ObjectNode in = ((ObjectNode) rep).deepCopy();
            in.remove(List.of("work", "by"));
            workOp("ls.core.Work.submitReport", op, w, in);
            if (rep.get("progress").asInt() == 100) workOp("ls.core.Work.submitConfirm", op, w, Map.of());
        }
        ActorContext confirmer = r.as(ds.at("/confirm/by").asText());
        for (JsonNode k : ds.at("/confirm/works")) workOp("ls.core.Work.confirm", confirmer, r.work(k.asText()), Map.of());
        for (JsonNode o : ds.get("outputs")) {
            create(OUTPUT, r.operator(o.get("by").asText()), Map.of("workItem", r.work(o.get("work").asText()), "quantity", o.get("quantity").decimalValue(),
                "unit", o.get("unit").asText(), "batchNo", o.get("batchNo").asText(), "recordedAt", LocalDate.parse(day).atTime(15, 0).toString(),
                "orgId", r.teams.get("甲班").id()));
        }
        for (JsonNode m : ds.get("materials")) {
            create(MATERIAL, r.operator(m.get("by").asText()), Map.of("workItem", r.work(m.get("work").asText()), "kind", m.get("kind").asText(),
                "material", m.get("material").asText(), "quantity", m.get("quantity").decimalValue(), "unit", m.get("unit").asText(),
                "orgId", r.teams.get("甲班").id()));
        }
    }

    /** 交接班：交班人生成并确认，接班人确认。返回发布前接班人能否看到草稿。 */
    private boolean handover(Run r) {
        JsonNode h = ds.get("handover");
        ActorContext from = r.as(h.get("by").asText());
        ActorContext to = r.as(h.get("receiver").asText());
        r.handover = create(HANDOVER, from, Map.of("fromTeam", r.teams.get(h.get("from").asText()).id(), "toTeam", r.teams.get(h.get("to").asText()).id(),
            "shiftDate", day, "shift", h.get("shift").asText(), "orgId", r.teams.get(h.get("from").asText()).id()));
        step(HANDOVER + ".generate", from, HANDOVER, r.handover, Map.of());
        QuerySpec all = new QuerySpec(List.of(new QuerySpec.Filter("id", "eq", r.handover)), List.of(), 1, 10, List.of(), List.of(), List.of());
        boolean receiverSawDraft = f.crud.crud.list(HANDOVER, all, to).total() > 0;
        step(HANDOVER + ".publish", from, HANDOVER, r.handover, Map.of());
        step(HANDOVER + ".accept", to, HANDOVER, r.handover, Map.of("note", "收到"));
        return receiverSawDraft;
    }

    @Test
    void DOM01_无BOM工艺路线仓库_需求到确认完整闭环() {
        assertThat(f.core.jdbc.queryForObject("select count(*) from meta_concept where code ~* '(bom|routing|warehouse|inventory)'", Integer.class))
            .as("环境中没有任何制造主数据对象").isZero();
        Run r = plan(null);
        execute(r);
        for (JsonNode k : ds.at("/expect/done")) assertThat(f.core.works.get(r.work(k.asText())).status()).isEqualTo(WorkStatus.DONE);
        WorkItem clean = f.core.works.get(r.work("clean"));
        assertThat(clean.sourceType()).isEqualTo(REQUEST);
        assertThat(clean.sourceId()).isEqualTo(r.request);
        assertThat(clean.actualEnd()).isNotNull();
        Integer labor = f.core.jdbc.queryForObject("select sum(labor_minutes) from core_work_report where status = 'SUBMITTED' and work_item_id in (?, ?)",
            Integer.class, r.work("clean"), r.work("assemble"));
        assertThat(labor).isEqualTo(ds.at("/expect/laborMinutes").asInt());
        step(REQUEST + ".close", r.as("leader"), REQUEST, r.request, Map.of());
        assertThat(records.row(REQUEST, r.request).get("status")).isEqualTo("CLOSED");
    }

    @Test
    void DOM02_约束Profile_宽松提示_严格阻断_严格模式填报要求() {
        // 宽松：同一人同一班次两项工作，提示冲突但允许
        Run loose = plan("LOOSE");
        OperationResult warn = workOp("ls.core.Work.assign", loose.as("leader"), loose.work("assemble"),
            Map.of("targets", List.of(Map.of("resourceType", "PARTY", "resourceRef", String.valueOf(loose.people.get("op1").id())))));
        assertThat(warn.warnings()).anySatisfy(m -> assertThat(m).contains("资源时间冲突"));

        // 严格：同样的分派被阻断；数据集本身（产出带单位、填报带物料）在严格模式下仍可完整走通
        Run strict = plan("STRICT");
        assertThat(f.core.works.get(strict.work("clean")).constraintProfile()).isEqualTo("STRICT");
        assertThatThrownBy(() -> workOp("ls.core.Work.assign", strict.as("leader"), strict.work("assemble"),
            Map.of("targets", List.of(Map.of("resourceType", "PARTY", "resourceRef", String.valueOf(strict.people.get("op1").id()))))))
            .extracting(e -> ((LsBizException) e).getCode()).isEqualTo("ASSIGNMENT_CONFLICT");
        // 严格模式：产出必须带单位、生产填报必须记录物料
        workOp("ls.core.Work.start", strict.operator("op1"), strict.work("clean"), Map.of());
        assertThatThrownBy(() -> workOp("ls.core.Work.submitReport", strict.operator("op1"), strict.work("clean"),
            Map.of("progress", 50, "outputQty", 10)))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getDetails().toString()).contains("outputUnit").contains("consumption"));
        execute(strict);
        assertThat(f.core.works.get(strict.work("clean")).status()).isEqualTo(WorkStatus.DONE);
    }

    @Test
    void DOM03_产出物料只记录不核销_交接班双方确认后未完成事项流转() {
        Run r = plan(null);
        execute(r);
        // 只有投料、退料，没有领用：全部记录成功，不产生任何库存差异
        List<Map<String, Object>> mats = records.where(MATERIAL, "workItem", r.work("assemble"));
        assertThat(mats).singleElement().satisfies(m -> assertThat(m.get("kind")).isEqualTo("FEED"));
        assertThat(records.where(MATERIAL, "workItem", r.work("clean"))).extracting(m -> m.get("kind")).containsExactly("FEED", "RETURN");
        BigDecimal output = f.core.jdbc.queryForObject("select sum(quantity) from " + records.table(OUTPUT) + " where "
            + records.column(OUTPUT, "workItem") + " = ?", BigDecimal.class, r.work("assemble"));
        assertThat(output).isEqualByComparingTo(String.valueOf(ds.at("/expect/outputQty").asInt()));

        boolean sawDraft = handover(r);
        assertThat(sawDraft).as("接班人在交班确认前看不到草稿").isFalse();
        Map<String, Object> h = records.row(HANDOVER, r.handover);
        assertThat(h.get("status")).isEqualTo("COMPLETED");
        assertThat(((Number) h.get("publishedBy")).longValue()).isEqualTo(r.people.get("teamLead").id());
        assertThat(((Number) h.get("acceptedBy")).longValue()).isEqualTo(r.people.get("receiver").id());
        assertThat((String) h.get("summary")).contains("完成 1 项，未完成 1 项");
        for (JsonNode k : ds.at("/expect/movedToReceiver")) {
            Map<String, Object> plan = records.where(SHIFT_PLAN, "workItem", r.work(k.asText())).get(0);
            assertThat(((Number) plan.get("team")).longValue()).isEqualTo(r.teams.get("乙班").id());
            assertThat(plan.get("shift")).isEqualTo("EVENING");
        }
        Map<String, Object> donePlan = records.where(SHIFT_PLAN, "workItem", r.work("clean")).get(0);
        assertThat(((Number) donePlan.get("team")).longValue()).as("已完成的工作不移交").isEqualTo(r.teams.get("甲班").id());
    }
}
