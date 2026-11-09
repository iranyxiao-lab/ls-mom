package com.lsmom.action.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.core.error.LsBizException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** 6.2：packs/_shared/cel-conformance 一致性用例（前端 tests/unit/cel-conformance.spec.ts 同跑同一批文件）。 */
class CelConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final CelRuleEngine engine = new CelRuleEngine();

    static Path dir() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("packs/_shared/cel-conformance"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("找不到 packs/_shared/cel-conformance");
        return p.resolve("packs/_shared/cel-conformance");
    }

    /** "REPEAT:<片段>:<次数>:<结尾>" 展开为长表达式。 */
    static String expand(String expr) {
        if (!expr.startsWith("REPEAT:")) return expr;
        String[] p = expr.substring(7).split(":", 3);
        return p[0].repeat(Integer.parseInt(p[1])) + p[2];
    }

    @TestFactory
    List<DynamicTest> 前后端一致性用例() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir(), "*.json")) {
            for (Path f : files) {
                JsonNode doc = JSON.readTree(f.toFile());
                for (JsonNode c : doc.path("cases")) {
                    tests.add(DynamicTest.dynamicTest(f.getFileName() + " · " + c.path("name").asText(), () -> run(c)));
                }
            }
        }
        assertThat(tests).hasSizeGreaterThanOrEqualTo(40);
        return tests;
    }

    private void run(JsonNode c) {
        String expr = expand(c.path("expr").asText());
        JsonNode expect = c.path("expect");
        Map<String, Object> model = CelValues.asMap(c.path("model"));
        Map<String, Object> context = CelValues.asMap(c.path("context"));
        CelRuleEngine.Compiled compiled;
        try {
            compiled = engine.compileValue(expr);
        } catch (LsBizException e) {
            if (!expect.has("invalid")) fail("不应拒绝：" + e.getCode() + " " + e.getMessage());
            assertThat(e.getCode()).isEqualTo(expect.get("invalid").asText());
            if (expect.has("function")) assertThat(e.getDetails().get("function")).isEqualTo(expect.get("function").asText());
            return;
        }
        if (expect.has("invalid")) fail("应被发布校验拒绝：" + expect.get("invalid").asText());
        Object value;
        try {
            value = engine.evaluate(compiled, model, context);
        } catch (LsBizException e) {
            if (!expect.path("error").asBoolean(false)) fail("不应求值失败：" + e.getMessage());
            return;
        }
        if (expect.path("error").asBoolean(false)) fail("应求值失败，实际得到 " + value);
        assertThat(normalize(value)).isEqualTo(normalize(CelValues.fromJson(expect.get("value"))));
    }

    private static Object normalize(Object v) {
        if (v instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros();
        if (v instanceof com.google.common.primitives.UnsignedLong u) return new BigDecimal(u.bigIntegerValue());
        return v;
    }
}
