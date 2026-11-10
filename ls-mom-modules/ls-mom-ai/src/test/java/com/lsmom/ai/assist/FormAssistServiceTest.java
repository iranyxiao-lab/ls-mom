package com.lsmom.ai.assist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.rule.CelRuleEngine;
import com.lsmom.ai.llm.LlmGateway;
import com.lsmom.core.error.LsBizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 自然语言生成规则：条件必填描述生成规则草稿（录制响应），补丁逐条校验，不修改表单。 */
class FormAssistServiceTest {

    @TempDir
    Path dir;

    private static final String SCHEMA = """
        {"viewType":"FORM","formCode":"x.defectForm","version":3,"conceptCode":"ls.asset.Defect","title":"设备缺陷",
         "layout":[],"fields":[
           {"key":"asset","label":"设备","widget":"assetRef","required":true},
           {"key":"title","label":"缺陷描述","widget":"input","required":true},
           {"key":"symptom","label":"现象","widget":"textarea"},
           {"key":"severity","label":"严重度","widget":"select","required":true,
            "options":[{"value":"LOW","label":"低"},{"value":"MEDIUM","label":"中"},{"value":"HIGH","label":"高"},{"value":"CRITICAL","label":"严重"}]}],
         "rules":[{"code":"symptomRequired","message":"旧规则"}]}""";
    private static final List<FormAssistService.ConceptField> CONCEPT = List.of(new FormAssistService.ConceptField("location", "位置", "STRING"));
    private static final String ASK = "严重度为高或严重时，必须填写现象";

    private void record(JsonNode schema, String instruction, String content) throws Exception {
        ObjectNode body = FormAssistService.JSON.createObjectNode();
        body.put("model", LlmClients.RECORDED_MODEL);
        var msgs = body.putArray("messages");
        msgs.addObject().put("role", "system").put("content", FormAssistService.SYSTEM);
        msgs.addObject().put("role", "user").put("content", FormAssistService.prompt(schema, CONCEPT, instruction));
        body.putObject("response_format").put("type", "json_object");
        ObjectNode resp = FormAssistService.JSON.createObjectNode();
        resp.put("model", "qwen2.5-72b-instruct");
        resp.put("system_fingerprint", "fp_rec");
        resp.putArray("choices").addObject().putObject("message").put("role", "assistant").put("content", content);
        resp.putObject("usage").put("prompt_tokens", 600).put("completion_tokens", 120);
        Files.writeString(dir.resolve(LlmGateway.digest(body) + ".json"), resp.toPrettyString());
    }

    private FormAssistService service() {
        return new FormAssistService(new LlmClients("", null, null, Duration.ofSeconds(5), dir, false), new CelRuleEngine());
    }

    @Test
    void 条件必填描述生成规则草稿_逐条校验() throws Exception {
        JsonNode schema = FormAssistService.JSON.readTree(SCHEMA);
        record(schema, ASK, """
            {"summary":"严重度为高或严重时现象必填，并加上位置字段",
             "ops":[
              {"op":"addRule","rule":{"code":"symptomRequired","level":"L1","when":"$model.severity == 'HIGH' || $model.severity == 'CRITICAL'",
               "assert":"has($model.symptom) && $model.symptom != null && size($model.symptom) > 0","field":"symptom","message":"严重度为高或严重时必须填写现象"}},
              {"op":"addField","field":{"key":"location","label":"位置","widget":"input"}},
              {"op":"updateField","key":"symptom","set":{"help":"高、严重级别必填"}},
              {"op":"updateField","key":"nothere","set":{"required":true}},
              {"op":"addRule","rule":{"code":"bad","message":"x","assert":"$model.symptom ==="}},
              {"op":"addRule","rule":{"code":"ghost","message":"x","assert":"$model.ghost > 1"}},
              {"op":"updateField","key":"title","set":{"widget":"textarea"}}
             ]}""");
        FormAssistService.Proposal p = service().propose(schema, CONCEPT, ASK);
        assertThat(p.summary()).contains("现象必填");
        assertThat(p.modelVersion()).isEqualTo("qwen2.5-72b-instruct@fp_rec");
        JsonNode ops = p.ops();
        assertThat(ops).hasSize(7);
        // 条件必填 → 规则草稿；编码与已有规则重复时自动加序号
        assertThat(ops.get(0).path("valid").asBoolean()).isTrue();
        assertThat(ops.get(0).path("rule").path("code").asText()).isEqualTo("symptomRequired2");
        assertThat(ops.get(0).path("rule").path("when").asText()).contains("HIGH");
        assertThat(ops.get(1).path("valid").asBoolean()).isTrue();
        assertThat(ops.get(2).path("valid").asBoolean()).isTrue();
        assertThat(ops.get(3).path("issue").asText()).contains("不在表单上");
        assertThat(ops.get(4).path("issue").asText()).contains("表达式不合法");
        assertThat(ops.get(5).path("issue").asText()).contains("ghost");
        assertThat(ops.get(6).path("issue").asText()).contains("widget");
        assertThat(p.issues()).hasSize(4);
        // 不修改传入的表单
        assertThat(schema.path("rules")).hasSize(1);
        assertThat(schema.path("fields")).hasSize(4);
    }

    @Test
    void 提示词不含编码与版本_没有录制时明确报错() {
        JsonNode schema = assertDoesNotThrowRead(SCHEMA);
        String prompt = FormAssistService.prompt(schema, CONCEPT, ASK);
        assertThat(prompt).doesNotContain("x.defectForm").doesNotContain("version").contains("severity｜严重度｜select｜必填｜选项 LOW=低");
        assertThat(prompt).contains("可添加字段：\n- location｜位置｜STRING");
        assertThatThrownBy(() -> service().propose(schema, CONCEPT, "别的需求")).isInstanceOf(LsBizException.class)
            .hasMessageContaining("没有录制的响应");
        assertThatThrownBy(() -> service().propose(schema, CONCEPT, " ")).isInstanceOf(LsBizException.class);
    }

    private static JsonNode assertDoesNotThrowRead(String s) {
        try {
            return FormAssistService.JSON.readTree(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
