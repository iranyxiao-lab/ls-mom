package com.lsmom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.ai.llm.LlmGateway;
import com.lsmom.ai.llm.RecordedTransport;
import com.lsmom.pack.PackFixture;
import com.lsmom.pack.PackSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** spec ai/ls-ir-conversion「构建期需求生成」：先做语义复用检查——需求中的「设备」复用 ls.asset.Asset 而非新建（LLM 用录制响应）。 */
class BuildCopilotTest {

    static final Path RES = Path.of("src/test/resources");
    static final Path ASSET_PACK = Path.of("../ls-mom-modules/ls-mom-biz/pack-asset/src/main/resources/lsmom-packs/ls.asset");
    static final ReuseCatalog CATALOG = ReuseCatalog.load(List.of(Path.of("../ls-mom-modules/ls-mom-biz")));

    LlmGateway replay() {
        return new LlmGateway(new LlmGateway.Config("http://replay.invalid/v1", null, "qwen2.5-72b-instruct", Duration.ofSeconds(5), 5, 100_000),
            new RecordedTransport(RES.resolve("llm")));
    }

    @Test
    void 需求中的设备复用已有ls_asset_Asset_不新建设备对象() throws Exception {
        String req = Files.readString(RES.resolve("requirements/lubrication.txt"));
        BuildCopilot.Result r = BuildCopilot.fromText(replay(), req, "acme.lube", CATALOG, "requirements/lubrication.txt");
        JsonNode ir = r.ir();
        // LLM 把「设备」也列成了对象：复用检查后不新建
        assertThat(r.spec().path("entities").toString()).contains("\"设备\"");
        assertThat(ir.path("concepts")).hasSize(1);
        ir.path("concepts").forEach(c -> assertThat(c.path("displayName").asText()).isNotEqualTo("设备"));
        JsonNode rec = ir.path("concepts").get(0);
        assertThat(rec.path("code").asText()).isEqualTo("acme.lube.LubricationRecord");
        JsonNode equipment = field(rec, "equipment");
        assertThat(equipment.path("dataType").asText()).isEqualTo("REF");
        assertThat(equipment.path("refConcept").asText()).isEqualTo("ls.asset.Asset");
        assertThat(field(rec, "lubricator").path("refConcept").asText()).isEqualTo("ls.core.Party");
        assertThat(ir.path("reuse").toString()).contains("ls.asset.Asset").contains("ls.core.Party");
        assertThat(ir.path("pack").path("dependsOn").get(0).path("code").asText()).isEqualTo("ls.asset");
        // 状态流转与权限点
        assertThat(ir.path("operations").get(0).path("transitions")).hasSize(2);
        assertThat(ir.path("permissions").toString()).contains("acme:lube:lubricationrecord:confirm");
        // 来源：模型与版本、提示摘要；必填引用已有对象的场景测试须人工补充
        JsonNode prov = ir.path("provenance");
        assertThat(prov.path("sourceType").asText()).isEqualTo("REQUIREMENT");
        assertThat(prov.path("model").path("version").asText()).isEqualTo("qwen2.5-72b-instruct@fp_lube01");
        assertThat(prov.path("model").path("promptDigest").asText()).hasSize(64);
        assertThat(prov.path("manualRequired").toString()).contains("请补充场景测试数据");
        assertThat(LsIr.validate(ir)).isEmpty();

        // 生成并在临时数据库安装（先装依赖的设备 Pack）
        PackGenerator.Output o = PackGenerator.generate(ir);
        assertThat(o.report().path("conceptsReused").asInt()).isEqualTo(2);
        assertThat(o.report().path("reuseRatio").asDouble()).isEqualTo(0.667);
        PackFixture f = new PackFixture();
        f.installer.install(PackSource.fromDirectory(ASSET_PACK), null);
        Path dir = Files.createTempDirectory("copilot");
        PackGenerator.write(o, dir);
        assertThat(f.installer.install(PackSource.fromDirectory(dir.resolve("pack")), null).status()).isEqualTo("INSTALLED");
        assertThat(f.action.concepts.get("acme.lube.LubricationRecord").fields()).anySatisfy(x -> {
            assertThat(x.code()).isEqualTo("equipment");
            assertThat(x.refConcept()).isEqualTo("ls.asset.Asset");
        });
    }

    @Test
    void 字段表格与旧表结构不经LLM也能生成并复用() {
        JsonNode spec = BuildCopilot.specFromCsv(BuildCopilot.read(RES.resolve("requirements/lubrication-fields.csv")), "LubeLog");
        BuildCopilot.Result r = BuildCopilot.build(spec, "acme.lubecsv", CATALOG, new BuildCopilot.Origin("字段表格", "x.csv", "d", null, null));
        assertThat(field(r.ir().path("concepts").get(0), "equipment").path("refConcept").asText()).isEqualTo("ls.asset.Asset");
        assertThat(field(r.ir().path("concepts").get(0), "oil").path("options")).hasSize(2);
        assertThat(r.ir().path("provenance").path("model").isNull()).isTrue();
        assertThat(LsIr.validate(r.ir())).isEmpty();

        JsonNode ddl = BuildCopilot.specFromDdl(BuildCopilot.read(RES.resolve("requirements/legacy_lube.sql")));
        BuildCopilot.Result d = BuildCopilot.build(ddl, "acme.legacy", CATALOG, new BuildCopilot.Origin("旧表结构", "legacy.sql", "d", null, null));
        JsonNode c = d.ir().path("concepts").get(0);
        assertThat(c.path("code").asText()).isEqualTo("acme.legacy.LubeLog");
        List<String> codes = new ArrayList<>();
        c.path("fields").forEach(x -> codes.add(x.path("code").asText() + ":" + x.path("dataType").asText() + ":" + x.path("required").asBoolean()));
        assertThat(codes).containsExactly("equipNo:STRING:true", "oilType:STRING:true", "volume:DECIMAL:false", "lubedAt:DATETIME:true");
        assertThat(field(c, "lubedAt").path("displayName").asText()).isEqualTo("润滑时间");
        assertThat(d.ir().path("provenance").path("sourceType").asText()).isEqualTo("LEGACY_SCHEMA");
        assertThat(LsIr.validate(d.ir())).isEmpty();
        // 没有必填引用：自动生成了场景测试
        assertThat(d.ir().path("tests")).hasSize(1);
    }

    static JsonNode field(JsonNode concept, String code) {
        for (JsonNode f : concept.path("fields")) if (f.path("code").asText().equals(code)) return f;
        throw new AssertionError("没有字段 " + code);
    }
}
