package com.lsmom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/ls-ir.md 与 docs/frappe-conversion.md 中的 JSON 示例都是合法的 LS-IR。 */
class DocExamplesTest {

    static final Pattern JSON_BLOCK = Pattern.compile("```json\\n(.*?)\\n```", Pattern.DOTALL);

    @Test
    void 文档中的示例LS_IR通过校验() throws Exception {
        List<JsonNode> examples = new ArrayList<>();
        for (String doc : List.of("../docs/ls-ir.md", "../docs/frappe-conversion.md")) {
            Matcher m = JSON_BLOCK.matcher(Files.readString(Path.of(doc)));
            while (m.find()) examples.add(LsIr.JSON.readTree(m.group(1)));
        }
        assertThat(examples).isNotEmpty();
        for (JsonNode ir : examples) {
            assertThat(LsIr.validate(ir)).as(ir.path("namespace").asText()).isEmpty();
            // 能生成
            assertThat(PackGenerator.generate(ir).pack()).containsKey("pack.yaml");
        }
    }

    @Test
    void 文档中列出的命令都已实现() throws Exception {
        String doc = Files.readString(Path.of("../docs/ls-ir.md"));
        for (String cmd : List.of("validate", "generate", "copilot", "frappe-import", "record-edit", "resolve", "record-run", "quality")) {
            assertThat(doc).contains("ls-mom-cli " + cmd + " ");
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            int code = LsMomCli.run(new String[]{cmd}, new java.io.PrintStream(o), new java.io.PrintStream(o));
            // 缺参数返回 2（用法错误），而不是"未知命令"
            assertThat(o.toString()).as(cmd).doesNotContain("未知命令");
            assertThat(code).as(cmd).isNotZero();
        }
    }
}
