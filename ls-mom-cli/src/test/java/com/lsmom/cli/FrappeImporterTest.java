package com.lsmom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScope;
import com.lsmom.pack.ChangeSetPipeline;
import com.lsmom.pack.PackFixture;
import com.lsmom.pack.PackProvenance;
import com.lsmom.pack.PackSource;
import com.lsmom.pack.PackTester;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * spec ai/ls-ir-conversion「Frappe 静态转换」「危险资产阻断」「来源追溯」：
 * frappe/frappe 的 ToDo DocType（MIT）＋自编 Workflow / Report / Fixture / Patch / Server Script / Client Script 样例。
 */
class FrappeImporterTest {

    static final Path APP = Path.of("src/test/resources/frappe/todo-app");
    static final FrappeImporter.Options OPT = new FrappeImporter.Options("acme.todo", "frappe", "develop@5511749c", null);

    @Test
    void 静态转换DocType_Workflow_DocPerm_Report_Fixture并标记Python与脚本() {
        ObjectNode ir = FrappeImporter.convert(APP, OPT);
        assertThat(LsIr.validate(ir)).isEmpty();

        JsonNode todo = LsIr.concept(ir, "acme.todo.ToDo");
        assertThat(todo.path("source").path("type").asText()).isEqualTo("DocType");
        assertThat(todo.path("source").path("name").asText()).isEqualTo("ToDo");
        List<String> codes = new ArrayList<>();
        todo.path("fields").forEach(f -> codes.add(f.path("code").asText() + ":" + f.path("dataType").asText()));
        // 按 field_order；布局字段、只读字段、工作流状态字段（status）不进入字段
        assertThat(codes).containsExactly("priority:ENUM", "color:STRING", "date:DATE", "allocatedTo:REF", "description:TEXT", "referenceType:STRING",
            "referenceName:STRING", "role:STRING", "assignedBy:REF", "sender:STRING", "assignmentRule:STRING");
        JsonNode priority = BuildCopilotTest.field(todo, "priority");
        assertThat(priority.path("options").toString()).isEqualTo("[\"HIGH\",\"MEDIUM\",\"LOW\"]");
        assertThat(priority.path("optionLabels").path("HIGH").asText()).isEqualTo("High");
        assertThat(BuildCopilotTest.field(todo, "description").path("required").asBoolean()).isTrue();
        assertThat(BuildCopilotTest.field(todo, "allocatedTo").path("refConcept").asText()).isEqualTo("ls.core.Party");
        assertThat(BuildCopilotTest.field(todo, "allocatedTo").path("source").path("field").asText()).isEqualTo("allocated_to");
        assertThat(ir.path("reuse").get(0).path("concept").asText()).isEqualTo("ls.core.Party");

        // Workflow → 状态机；有条件的迁移须人工改写
        JsonNode sm = ir.path("operations").get(0);
        assertThat(sm.path("states").toString()).isEqualTo("[\"DRAFT\",\"CLOSED\",\"CANCELLED\"]");
        assertThat(sm.path("initial").asText()).isEqualTo("DRAFT");
        List<String> ts = new ArrayList<>();
        sm.path("transitions").forEach(t -> ts.add(t.path("operation").asText() + ":" + t.path("from") + "->" + t.path("to").asText()));
        assertThat(ts).containsExactly("acme.todo.ToDo.close:[\"DRAFT\"]->CLOSED", "acme.todo.ToDo.cancel:[\"DRAFT\"]->CANCELLED",
            "acme.todo.ToDo.reopen:[\"CLOSED\"]->DRAFT");
        // DocPerm 与 Workflow allowed → 权限点与角色
        assertThat(perm(ir, "acme:todo:todo:cancel").path("roles").toString()).isEqualTo("[\"System Manager\"]");
        assertThat(perm(ir, "crud:acme.todo.ToDo:remove").path("roles").toString()).isEqualTo("[\"All\"]");
        assertThat(perm(ir, "crud:acme.todo.ToDo:add").path("roles").toString()).isEqualTo("[\"All\",\"System Manager\"]");
        // Report Builder → 指标；Fixture → 种子
        assertThat(ir.path("metrics").get(0).path("code").asText()).isEqualTo("acme.todo.openToDos");
        assertThat(ir.path("seeds").get(0).path("rows")).hasSize(2);
        assertThat(ir.path("seeds").get(0).path("rows").get(0).path("priority").asText()).isEqualTo("HIGH");
        // 场景：登记 → 关闭
        assertThat(ir.path("tests").get(0).toString()).contains("acme.todo.ToDo.close").contains("\"status\":\"CLOSED\"");

        // 危险资产：Server Script / Client Script / Script Report 禁止自动转换；Patch 与 Python 需人工；Python 条件需人工
        JsonNode mr = ir.path("provenance").path("manualRequired");
        assertThat(kind(mr, "server_script/auto_close.json")).isEqualTo("BLOCKED");
        assertThat(kind(mr, "public/js/todo.js")).isEqualTo("BLOCKED");
        assertThat(kind(mr, "report/todo_aging/todo_aging.json")).isEqualTo("BLOCKED");
        assertThat(kind(mr, "patches/v1_0/set_default_priority.py")).isEqualTo("MANUAL");
        assertThat(kind(mr, "report/todo_aging/todo_aging.py")).isEqualTo("MANUAL");
        assertThat(mr.toString()).contains("doc.allocated_to == frappe.session.user").contains("Dynamic Link");
        mr.forEach(m -> assertThat(m.path("status").asText()).isEqualTo("OPEN"));

        // 来源：来源应用与版本、许可证（识别 LICENSE）、转换器版本；无模型
        JsonNode prov = ir.path("provenance");
        assertThat(prov.path("sourceType").asText()).isEqualTo("FRAPPE");
        assertThat(prov.path("source").path("version").asText()).isEqualTo("develop@5511749c");
        assertThat(prov.path("license").asText()).isEqualTo("MIT");
        assertThat(prov.path("converter").path("version").asText()).isEqualTo(FrappeImporter.VERSION);
        assertThat(prov.path("model").isNull()).isTrue();
    }

    @Test
    void 许可证未知或GPL时整体禁止自动转换() throws Exception {
        assertThat(kind(FrappeImporter.convert(APP, new FrappeImporter.Options("acme.todo", "x", null, "GPL-3.0")).path("provenance").path("manualRequired"),
            "/license")).isEqualTo("BLOCKED");
        Path noLicense = Files.createTempDirectory("frappe");
        Files.createDirectories(noLicense.resolve("doctype"));
        Files.copy(APP.resolve("desk/doctype/todo/todo.json"), noLicense.resolve("doctype/todo.json"));
        ObjectNode ir = FrappeImporter.convert(noLicense, new FrappeImporter.Options("acme.todo", "x", null, null));
        assertThat(ir.path("provenance").path("license").asText()).isEqualTo("UNKNOWN");
        assertThat(kind(ir.path("provenance").path("manualRequired"), "/license")).isEqualTo("BLOCKED");
    }

    @Test
    void 转换样例安装并通过场景测试_来源可查_未处理需人工项时ChangeSet不能发布() throws Exception {
        // 命令行转换
        Path work = Files.createTempDirectory("frappe-todo");
        Path irFile = work.resolve("todo.ir.json");
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        assertThat(LsMomCli.run(new String[]{"frappe-import", APP.toString(), "--namespace", "acme.todo", "--app", "frappe", "--version", "develop@5511749c",
            "--out", irFile.toString()}, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8)))
            .as(e.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(o.toString(StandardCharsets.UTF_8)).contains("禁止自动转换 3");

        // 生成 → 安装 → 场景测试
        assertThat(LsMomCli.run(new String[]{"generate", irFile.toString(), "--out", work.resolve("v1").toString()}, new PrintStream(o), new PrintStream(e))).isZero();
        PackFixture f = new PackFixture();
        assertThat(f.installer.install(PackSource.fromDirectory(work.resolve("v1/pack")), null).status()).isEqualTo("INSTALLED");
        PackTester.Report rep = f.tester.run("acme.todo");
        rep.regression().forEach(c -> assertThat(c.passed()).as(c.name() + "：" + c.message()).isTrue());
        assertThat(rep.passed()).isTrue();
        assertThat(f.core.jdbc.queryForObject("select count(*) from c_acme_todo_to_do where del_flag = '0'", Integer.class)).isGreaterThanOrEqualTo(2);

        // AI-03：查询转换生成的 Concept 的来源
        JsonNode p = PackProvenance.of(f.store, "acme.todo", "acme.todo.ToDo");
        assertThat(p.path("sourceType").asText()).isEqualTo("FRAPPE");
        assertThat(p.path("source").path("app").asText()).isEqualTo("frappe");
        assertThat(p.path("license").asText()).isEqualTo("MIT");
        assertThat(p.path("converter").path("name").asText()).isEqualTo(FrappeImporter.NAME);
        assertThat(p.path("concept").path("source").path("name").asText()).isEqualTo("ToDo");
        assertThat(p.path("concept").path("source").path("path").asText()).isEqualTo("desk/doctype/todo/todo.json");
        assertThat(p.path("concept").path("fields").path("allocatedTo").path("field").asText()).isEqualTo("allocated_to");
        assertThat(p.path("concept").path("operations").toString()).contains("acme.todo.ToDo.close").contains("Workflow Transition");
        assertThat(p.path("manualRequired").toString()).contains("Dynamic Link");
        assertThat(p.path("manualEdits")).isEmpty();
        assertThatThrownBy(() -> PackProvenance.of(f.store, "acme.todo", "acme.todo.Nope")).isInstanceOf(LsBizException.class);

        // AI-04：含 Server Script 的转换结果，测试全部通过、已批准，但需人工项未处理 → 不能发布
        ChangeSetPipeline.ChangeSet cs = submitAndApprove(f, work.resolve("v1/changeset/acme.todo-0.1.0.zip"));
        ChangeSetPipeline.Gate g = f.pipeline.gate(cs.id());
        assertThat(g.ready()).isFalse();
        assertThat(String.join("\n", g.problems())).contains("禁止自动转换项未处理：server_script/auto_close.json");
        assertThatThrownBy(() -> f.pipeline.release(cs.id(), human())).extracting(x -> ((LsBizException) x).getCode()).isEqualTo("CHANGESET_GATE_FAILED");

        // 人工处理：逐项关闭并记录人工修改，重新生成新版本后可以发布
        ObjectNode ir = (ObjectNode) LsIr.read(irFile);
        ((ObjectNode) ir.path("pack")).put("version", "0.1.1");
        Files.writeString(irFile, ir.toString());
        assertThat(LsMomCli.run(new String[]{"record-edit", irFile.toString(), "--by", "实施顾问", "--path", "concepts/to-do.yaml",
            "--reason", "referenceType 改为文本说明", "--lines", "2"}, new PrintStream(o), new PrintStream(e))).isZero();
        assertThat(LsMomCli.run(new String[]{"resolve", irFile.toString(), "--path", "server_script/auto_close.json", "--by", "实施顾问",
            "--resolution", "改用平台定时提醒，不迁移脚本"}, new PrintStream(o), new PrintStream(e))).isZero();
        ir = (ObjectNode) LsIr.read(irFile);
        List<String> rest = new ArrayList<>();
        ir.path("provenance").path("manualRequired").forEach(m -> { if (!"RESOLVED".equals(m.path("status").asText())) rest.add(m.path("path").asText()); });
        for (String path : rest) ProvenanceEdits.resolve(ir, path, "实施顾问", "已人工评审");
        Files.writeString(irFile, ir.toString());
        assertThat(LsMomCli.run(new String[]{"validate", irFile.toString()}, new PrintStream(o), new PrintStream(e))).isZero();
        assertThat(LsMomCli.run(new String[]{"generate", irFile.toString(), "--out", work.resolve("v2").toString()}, new PrintStream(o), new PrintStream(e))).isZero();
        JsonNode rpt = LsIr.JSON.readTree(Files.readString(work.resolve("v2/report.json")));
        assertThat(rpt.path("manualRequired").path("open").asInt()).isZero();

        ChangeSetPipeline.ChangeSet cs2 = submitAndApprove(f, work.resolve("v2/changeset/acme.todo-0.1.1.zip"));
        assertThat(f.pipeline.gate(cs2.id()).ready()).as(f.pipeline.gate(cs2.id()).problems().toString()).isTrue();
        assertThat(f.pipeline.release(cs2.id(), human()).version()).isEqualTo("0.1.1");
        JsonNode p2 = PackProvenance.of(f.store, "acme.todo", "acme.todo.ToDo");
        assertThat(p2.path("manualEdits").get(0).path("by").asText()).isEqualTo("实施顾问");
        assertThat(p2.path("manualEdits").get(0).path("reason").asText()).isEqualTo("referenceType 改为文本说明");
        JsonNode all = PackProvenance.of(f.store, "acme.todo", null);
        assertThat(all.path("manualRequired").toString()).contains("改用平台定时提醒，不迁移脚本").doesNotContain("\"OPEN\"");
    }

    static ChangeSetPipeline.ChangeSet submitAndApprove(PackFixture f, Path zip) throws Exception {
        ChangeSetPipeline.ChangeSet cs = f.pipeline.submit(PackSource.fromZip(Files.readAllBytes(zip)), null, "AI", null);
        assertThat(cs.status()).as(cs.stages().toString()).isEqualTo("DRAFT");
        for (ChangeSetPipeline.Stage s : ChangeSetPipeline.Stage.values()) {
            if (s != ChangeSetPipeline.Stage.VALIDATE) f.pipeline.recordStage(cs.id(), s, "PASSED", cs.artifactSha(), null, 1L);
        }
        return f.pipeline.approve(cs.id(), true, "同意", f.owner.id());
    }

    static ActorContext human() {
        return new ActorContext(ActorContext.ActorType.HUMAN, 1L, 1L, null, false, Set.of(), DataScope.unrestricted(), "发布人");
    }

    static JsonNode perm(JsonNode ir, String code) {
        for (JsonNode p : ir.path("permissions")) if (p.path("code").asText().equals(code)) return p;
        throw new AssertionError("没有权限点 " + code + "：" + ir.path("permissions"));
    }

    static String kind(JsonNode mr, String path) {
        for (JsonNode m : mr) if (m.path("path").asText().equals(path)) return m.path("kind").asText();
        throw new AssertionError("没有需人工项 " + path + "：" + mr);
    }
}
