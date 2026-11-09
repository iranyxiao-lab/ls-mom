package com.lsmom.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.crud.CrudFixture;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;

/** 8.10：docs/form-schema.md 与 docs/form-designer-adapter.md 中所有 ```json lsview 示例可直接通过校验器。 */
class DocExamplesTest {

    private static final Pattern BLOCK = Pattern.compile("```json lsview\\n(.*?)\\n```", Pattern.DOTALL);

    @Test
    void 文档示例全部通过校验() throws IOException {
        FormFixture f = new FormFixture();
        CrudFixture.Loan loan = f.loan();
        Path docs = docsDir();
        int total = 0;
        for (String name : List.of("form-schema.md", "form-designer-adapter.md")) {
            List<String> blocks = blocks(Files.readString(docs.resolve(name)));
            assertThat(blocks).as(name + " 中的示例").isNotEmpty();
            for (int i = 0; i < blocks.size(); i++) {
                // 示例使用 acme.tool 命名空间，替换为测试台账的命名空间
                JsonNode view = json(blocks.get(i).replace("acme.tool.", loan.ns() + "."));
                ViewSchemaValidator.Result r = f.validator.validate(view);
                assertThat(r.issues()).as("%s 第 %d 个示例（%s）", name, i + 1, view.path("viewType").asText()).isEmpty();
                total++;
            }
        }
        assertThat(total).isGreaterThanOrEqualTo(7);
    }

    private static List<String> blocks(String md) {
        List<String> out = new ArrayList<>();
        Matcher m = BLOCK.matcher(md);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static Path docsDir() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("docs/form-schema.md"))) p = p.getParent();
        assertThat(p).as("找不到 docs/form-schema.md").isNotNull();
        return p.resolve("docs");
    }
}
