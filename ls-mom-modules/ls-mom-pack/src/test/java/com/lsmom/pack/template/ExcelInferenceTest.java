package com.lsmom.pack.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.core.error.LsBizException;
import com.lsmom.pack.ChangeSetPipeline;
import com.lsmom.pack.PackFixture;
import com.lsmom.pack.PackSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Excel 生成台账：点检表样例推断出日期、设备引用和二值字典，生成的草稿通过校验。 */
class ExcelInferenceTest {

    /** 模拟现有数据：设备 P-01..P-05、人员 张三 / 李四 */
    private static final ExcelInference.RefMatcher REFS = (concept, values) -> {
        Set<String> known = switch (concept) {
            case "ls.asset.Asset" -> Set.of("P-01", "P-02", "P-03", "P-04", "P-05");
            case "ls.core.Party" -> Set.of("张三", "李四");
            default -> Set.of();
        };
        int hits = (int) values.stream().filter(known::contains).count();
        return new ExcelInference.Match(hits, hits > 0 ? 7L : null);
    };

    private static List<List<String>> rows() {
        List<List<String>> r = new ArrayList<>();
        String[] people = {"张三", "李四"};
        for (int i = 0; i < 60; i++) {
            r.add(List.of("2026-11-" + String.format("%02d", 1 + i % 28), "P-0" + (1 + i % 5), people[i % 2], i % 7 == 0 ? "异常" : "正常",
                String.valueOf(60 + i % 9) + ".5", String.valueOf(i % 3), i % 4 == 0 ? "" : "备注" + i, i % 2 == 0 ? "早班" : "中班"));
        }
        return r;
    }

    private static final List<String> HEADERS = List.of("点检日期", "设备编号", "点检人", "结果", "温度（℃）", "缺陷数", "备注", "班次");

    private static JsonNode field(JsonNode frag, String label) {
        for (JsonNode f : frag.path("fields")) if (f.path("displayName").asText().equals(label)) return f;
        throw new AssertionError("没有字段 " + label);
    }

    @Test
    void 点检表样例推断出日期_设备引用和二值字典() {
        ObjectNode frag = new ExcelInference(REFS).infer("点检表.xlsx", "设备点检", HEADERS, rows());
        assertThat(frag.path("name").asText()).isEqualTo("设备点检");
        assertThat(frag.path("provenance").path("sampleRows").asInt()).isEqualTo(50);

        JsonNode date = field(frag, "点检日期");
        assertThat(date.path("code").asText()).isEqualTo("checkDate");
        assertThat(date.path("dataType").asText()).isEqualTo("DATE");
        assertThat(date.path("required").asBoolean()).isTrue();

        JsonNode asset = field(frag, "设备编号");
        assertThat(asset.path("code").asText()).isEqualTo("asset");
        assertThat(asset.path("dataType").asText()).isEqualTo("REF");
        assertThat(asset.path("refConcept").asText()).isEqualTo("ls.asset.Asset");
        assertThat(asset.path("evidence").asText()).contains("现有设备");

        assertThat(field(frag, "点检人").path("refConcept").asText()).isEqualTo("ls.core.Party");

        JsonNode result = field(frag, "结果");
        assertThat(result.path("dataType").asText()).isEqualTo("ENUM");
        assertThat(result.path("options").findValuesAsText("value")).containsExactly("OK", "ABNORMAL");
        assertThat(result.path("options").findValuesAsText("label")).containsExactly("正常", "异常");
        assertThat(result.path("evidence").asText()).contains("二值字典");

        assertThat(field(frag, "温度（℃）").path("dataType").asText()).isEqualTo("DECIMAL");
        assertThat(field(frag, "温度（℃）").path("code").asText()).isEqualTo("temperature");
        assertThat(field(frag, "缺陷数").path("dataType").asText()).isEqualTo("INTEGER");
        JsonNode remark = field(frag, "备注");
        assertThat(remark.path("dataType").asText()).isEqualTo("STRING");
        assertThat(remark.has("required")).isFalse();
        JsonNode shift = field(frag, "班次");
        assertThat(shift.path("dataType").asText()).isEqualTo("ENUM");
        assertThat(shift.path("code").asText()).isEqualTo("f8");
        assertThat(frag.path("samples").path("asset").asLong()).isEqualTo(7L);
    }

    @Test
    void 没有表头时拒绝() {
        assertThatThrownBy(() -> new ExcelInference(REFS).infer("x.xlsx", "Sheet1", List.of("", " "), List.of()))
            .isInstanceOf(LsBizException.class).satisfies(e -> assertThat(((LsBizException) e).fieldErrors()).containsKey("headers"));
    }

    @Test
    void 推断结果生成的草稿通过校验且带来源记录() {
        PackFixture f = new PackFixture();
        // 测试库没有设备 Pack：去掉设备列；人员样例用真实人员
        List<String> headers = HEADERS.stream().filter(h -> !h.equals("设备编号")).toList();
        List<List<String>> rows = rows().stream().map(r -> {
            List<String> c = new ArrayList<>(r);
            c.remove(1);
            return (List<String>) c;
        }).toList();
        ObjectNode frag = new ExcelInference(REFS).infer("点检表.xlsx", "设备点检", headers, rows);
        frag.with("samples").put("checker", f.owner.id());
        f.flow.role("workshop_leader", f.owner);
        String ns = PackFixture.code("xl");
        PermissionGrants.apply(f.core.jdbc, "点检" + ns, PermissionGrants.plan(frag, ns, List.of("workshop_leader")));
        Map<String, byte[]> files = LedgerGenerator.generate(frag, ns, "设备点检", new LedgerGenerator.Meta("设备科王工", "一车间周主任", null));
        String prov = new String(files.get("provenance.json"), StandardCharsets.UTF_8);
        assertThat(prov).contains("\"sourceType\" : \"EXCEL\"", "点检表.xlsx", "第 1 列", "二值字典");
        ChangeSetPipeline.ChangeSet cs = f.pipeline.submit(PackSource.of(files), 1L, "EXCEL", null);
        assertThat(cs.status()).as(String.valueOf(cs.stages().get(0).detail())).isEqualTo("DRAFT");
        f.installer.install(PackSource.of(files), 1L);
        assertThat(f.tester.run(ns).passed()).isTrue();
    }
}
