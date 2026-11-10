package com.lsmom.biz.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.lsmom.action.api.ActorContext;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueStatus;
import com.lsmom.core.org.OrgUnit;
import com.lsmom.core.party.Party;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkStatus;
import com.lsmom.core.work.WorkType;
import com.lsmom.pack.PackFixture;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.lsmom.biz.asset.AssetPack.ASSET;
import static com.lsmom.biz.asset.AssetPack.DEFECT;
import static com.lsmom.biz.asset.AssetPack.PLAN;
import static com.lsmom.biz.asset.AssetPack.RECORD;
import static com.lsmom.biz.asset.AssetPack.RECORD_ITEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 14.4 切片 B 黄金场景：由数据集 {@code golden/slice-b.yaml} 驱动。
 * 点检计划生成点检工作 → 读数超限自动判异常并写观测 → 一键生成缺陷 → 登记产生 Issue → 维修工作 → 验证不通过返修 → 验证通过关闭；
 * 设备时间轴完整；验证人不能是唯一维修执行人。
 */
class SliceBGoldenScenariosTest {

    private final AssetPackTest base = new AssetPackTest();
    private final JsonNode ds = load();
    private final Map<String, Party> people = new HashMap<>();

    private static JsonNode load() {
        try (InputStream in = new ClassPathResource("golden/slice-b.yaml").getInputStream()) {
            return new YAMLMapper().readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    SliceBGoldenScenariosTest() {
        for (JsonNode p : ds.get("people")) {
            OrgUnit org = switch (p.get("org").asText()) {
                case "teamA" -> base.f.plant.teamA();
                case "teamB" -> base.f.plant.teamB();
                default -> base.f.plant.workshop();
            };
            people.put(p.get("key").asText(), base.f.core.employee(p.get("name").asText(), org));
        }
    }

    private ActorContext as(String key) {
        return base.user(people.get(key));
    }

    private void finishWork(WorkItem w, ActorContext doer, ActorContext confirmer) {
        base.f.action.step("ls.core.Work.start", doer, "ls.core.Work", String.valueOf(w.id()), "{}");
        base.f.action.step("ls.core.Work.submitConfirm", doer, "ls.core.Work", String.valueOf(w.id()), "{}");
        base.f.action.step("ls.core.Work.confirm", confirmer, "ls.core.Work", String.valueOf(w.id()), "{}");
    }

    private ObjectNode repairInput(JsonNode r) {
        ObjectNode in = JsonNodeFactory.instance.objectNode();
        in.put("assignee", people.get(r.get("assignee").asText()).id());
        if (r.has("checklist")) in.set("checklist", r.get("checklist"));
        if (r.has("titles")) in.set("titles", r.get("titles"));
        return in;
    }

    @Test
    void 点检异常到维修验证关闭() {
        ActorContext keeper = as("keeper");
        ActorContext inspector = as("inspector");
        ActorContext fitter = as("fitter");
        ActorContext verifier = as("verifier");
        JsonNode a = ds.get("asset");
        String code = PackFixture.code(a.get("code").asText());
        long asset = base.create(ASSET, keeper, """
            {"assetCode":"%s","name":"%s","assetType":"%s","location":"%s","orgId":%d}""".formatted(code, a.get("name").asText(),
            a.get("type").asText(), a.get("location").asText(), base.f.plant.workshop().id()));
        base.step(ASSET + ".commission", keeper, ASSET, asset, "{}");

        // 点检计划（检查项来自数据集）→ 到期生成点检工作，派给负责人
        JsonNode pl = ds.get("plan");
        ObjectNode plan = JsonNodeFactory.instance.objectNode();
        plan.put("name", pl.get("name").asText()).put("asset", asset).put("cycle", pl.get("cycle").asText())
            .put("responsible", people.get(pl.get("responsible").asText()).id()).put("nextDue", LocalDate.now().toString())
            .put("orgId", base.f.plant.workshop().id());
        plan.set("inspectionPlanItem", pl.get("items"));
        long planId = ((Number) base.f.crud.crud.create(PLAN, plan, keeper, null).get("id")).longValue();
        base.step(PLAN + ".activate", keeper, PLAN, planId, "{}");
        base.scheduler.generateDue(LocalDate.now());
        Map<String, Object> rec = base.records.where(RECORD, "plan", planId).get(0);
        long recId = ((Number) rec.get("id")).longValue();
        WorkItem inspection = base.f.core.works.get(((Number) rec.get("workItem")).longValue());
        assertThat(inspection.type()).isEqualTo(WorkType.INSPECTION);
        assertThat(base.f.core.works.assignedTo(inspection.id(), people.get("inspector").id())).isTrue();

        // 录入读数：超限项自动判异常并写观测
        ObjectNode readings = JsonNodeFactory.instance.objectNode();
        readings.set("items", ds.get("readings"));
        var submitted = base.step(RECORD + ".submit", inspector, RECORD, recId, readings.toString());
        JsonNode exp = ds.get("expect");
        assertThat(submitted.warnings()).anyMatch(w -> w.contains(exp.get("abnormal").size() + " 项点检异常"));
        assertThat(base.records.row(RECORD, recId)).containsEntry("result", "ABNORMAL");
        List<String> abnormal = base.records.children(RECORD_ITEM, recId).stream().filter(i -> "ABNORMAL".equals(i.get("outcome")))
            .map(i -> String.valueOf(i.get("itemCode"))).toList();
        assertThat(abnormal).containsExactlyElementsOf(toList(exp.get("abnormal")));
        assertThat(base.f.action.count("select count(*) from core_observation where object_type = 'ASSET' and object_id = ?", String.valueOf(asset)))
            .isEqualTo(exp.get("observations").asInt());
        assertThat(base.f.action.count("select count(*) from core_observation where object_type = 'ASSET' and object_id = ? and quality = 'BAD'",
            String.valueOf(asset))).isEqualTo(exp.get("badObservations").asInt());

        // 一键生成缺陷 → 登记产生 Issue
        base.step(RECORD + ".raiseDefects", inspector, RECORD, recId, "{}");
        long defect = ((Number) base.records.where(DEFECT, "inspectionRecord", recId).get(0).get("id")).longValue();
        base.step(DEFECT + ".report", keeper, DEFECT, defect, "{}");
        Issue issue = base.issues.get(((Number) base.records.row(DEFECT, defect).get("issue")).longValue());
        assertThat(issue.status()).isEqualTo(IssueStatus.OPEN);

        // 维修 → 验证不通过 → 返修 → 验证通过
        ArrayNode repairs = (ArrayNode) ds.get("repairs");
        base.step(DEFECT + ".createRepair", keeper, DEFECT, defect, repairInput(repairs.get(0)).toString());
        finishWork(base.issues.rectifications(issue.id()).get(0), fitter, keeper);
        base.step(DEFECT + ".submitVerify", fitter, DEFECT, defect, "{}");
        JsonNode v = ds.get("verify");
        base.step(DEFECT + ".verifyFail", verifier, DEFECT, defect, "{\"conclusion\":\"" + v.get("fail").get("conclusion").asText() + "\"}");
        assertThat(base.records.row(DEFECT, defect).get("status")).isEqualTo("REPAIRING");
        base.step(DEFECT + ".createRepair", keeper, DEFECT, defect, repairInput(repairs.get(1)).toString());
        WorkItem again = base.issues.rectifications(issue.id()).stream().filter(w -> w.status() != WorkStatus.DONE).findFirst().orElseThrow();
        finishWork(again, fitter, keeper);
        base.step(DEFECT + ".submitVerify", fitter, DEFECT, defect, "{}");
        String passed = v.get("pass").get("conclusion").asText();
        base.step(DEFECT + ".verifyPass", verifier, DEFECT, defect, "{\"conclusion\":\"" + passed + "\"}");
        assertThat(base.records.row(DEFECT, defect)).containsEntry("status", "CLOSED").containsEntry("verifyConclusion", passed);
        assertThat(base.issues.get(issue.id()).status()).isEqualTo(IssueStatus.CLOSED);
        assertThat(base.issues.rectifications(issue.id())).hasSize(exp.get("repairWorks").asInt()).allMatch(w -> w.status() == WorkStatus.DONE);

        // 设备时间轴：数据集要求的事件类型都在
        assertThat(base.timeline.of(asset, 100)).extracting(m -> String.valueOf(m.get("kind"))).containsAll(toList(exp.get("timelineKinds")));
    }

    @Test
    void 严格模式下验证人不能是唯一维修执行人() {
        ActorContext keeper = as("keeper");
        long asset = base.pump(keeper, PackFixture.code("P"));
        // 严格约束 Profile 下，验证人不能是唯一维修执行人
        base.f.core.profiles.enableForOrg(base.f.plant.teamB().id(), "STRICT");
        long d = base.create(DEFECT, keeper, "{\"asset\":%d,\"title\":\"联轴器松动\",\"severity\":\"LOW\",\"orgId\":%d}"
            .formatted(asset, base.f.plant.teamB().id()));
        base.step(DEFECT + ".report", keeper, DEFECT, d, "{}");
        base.step(DEFECT + ".createRepair", keeper, DEFECT, d, "{\"assignee\":%d}".formatted(people.get("keeper").id()));
        Issue issue = base.issues.get(((Number) base.records.row(DEFECT, d).get("issue")).longValue());
        finishWork(base.issues.rectifications(issue.id()).get(0), keeper, keeper);
        base.step(DEFECT + ".submitVerify", keeper, DEFECT, d, "{}");
        assertThatThrownBy(() -> base.step(DEFECT + ".verifyPass", keeper, DEFECT, d, "{\"conclusion\":\"好了\"}"))
            .extracting(AssetPackTest::code).isEqualTo("VERIFIER_NOT_SEPARATED");
        base.step(DEFECT + ".verifyPass", as("verifier"), DEFECT, d, "{\"conclusion\":\"复紧后无松动\"}");
        assertThat(base.records.row(DEFECT, d).get("status")).isEqualTo("CLOSED");
    }

    private static List<String> toList(JsonNode arr) {
        return java.util.stream.StreamSupport.stream(arr.spliterator(), false).map(JsonNode::asText).toList();
    }
}
