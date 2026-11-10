package com.lsmom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** spec ai/ls-ir-conversion「LS-IR 格式」：Schema 与语义校验，缺少 provenance 被拒。 */
class LsIrTest {

    static final Path SAMPLE = Path.of("src/test/resources/ir/permit.json");

    static JsonNode sample() {
        return LsIr.read(SAMPLE);
    }

    @Test
    void 样例通过校验() {
        assertThat(LsIr.validate(sample())).isEmpty();
    }

    @Test
    void 缺少provenance的LS_IR被拒() {
        ObjectNode ir = (ObjectNode) sample();
        ir.remove("provenance");
        List<LsIr.Problem> p = LsIr.validate(ir);
        assertThat(p).isNotEmpty();
        assertThat(p.toString()).contains("provenance");
    }

    @Test
    void 版本_命名空间_引用_状态_权限点的语义错误都被指出() {
        ObjectNode ir = (ObjectNode) sample();
        ir.put("irVersion", "0.9");
        assertThat(LsIr.validate(ir).toString()).contains("irVersion");

        ir = (ObjectNode) sample();
        ((ObjectNode) ir.path("concepts").get(0).path("fields").get(3)).put("refConcept", "ls.asset.Asset");
        assertThat(LsIr.validate(ir).toString()).contains("引用对象无法解析").contains("ls.asset.Asset");

        ir = (ObjectNode) sample();
        ((ObjectNode) ir.path("operations").get(0).path("transitions").get(0)).put("to", "NOWHERE");
        ((ObjectNode) ir.path("operations").get(0).path("transitions").get(1)).put("permission", "acme:permit:undeclared");
        assertThat(LsIr.validate(ir).toString()).contains("状态不存在：NOWHERE").contains("权限点未在 permissions 中声明");

        ir = (ObjectNode) sample();
        ((ObjectNode) ir.path("concepts").get(0)).put("code", "other.ns.Permit");
        assertThat(LsIr.validate(ir).toString()).contains("命名空间 acme.permit.");

        ir = (ObjectNode) sample();
        ((ObjectNode) ir.path("operations").get(0).path("transitions").get(1)).put("risk", "C2");
        assertThat(LsIr.validate(ir).toString()).contains("高风险对象 acme.permit.Permit");

        ir = (ObjectNode) sample();
        ((ObjectNode) ir.path("views").get(0).path("fields").get(0)).put("key", "nope");
        assertThat(LsIr.validate(ir).toString()).contains("视图字段不存在：nope");
    }

    @Test
    void 命令行validate返回码() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        assertThat(LsMomCli.run(new String[]{"validate", SAMPLE.toString()}, new PrintStream(out), new PrintStream(err))).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("通过");
        ObjectNode ir = (ObjectNode) sample();
        ir.remove("provenance");
        Path bad = Files.createTempFile("ir", ".json");
        Files.writeString(bad, ir.toString());
        assertThat(LsMomCli.run(new String[]{"validate", bad.toString()}, new PrintStream(out), new PrintStream(err))).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("缺少必填项 provenance");
    }
}
