package com.lsmom.flow;

import com.lsmom.flow.model.FlowDefinition;
import com.lsmom.flow.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 9.12：docs/flow-integration.md 的时间线事件表与实现一致，示例配置可解析。 */
class FlowDocTest {

    @Test
    void 文档事件名与实现一致() throws IOException {
        String doc = Files.readString(doc());
        String section = doc.substring(doc.indexOf("## 时间线事件"), doc.indexOf("## 版本冻结"));
        Set<String> documented = new TreeSet<>();
        Matcher m = Pattern.compile("^\\| `(ls\\.flow\\.[a-zA-Z.]+)` \\|", Pattern.MULTILINE).matcher(section);
        while (m.find()) documented.add(m.group(1));
        assertThat(documented).isEqualTo(new TreeSet<>(FlowRuntime.TIMELINE_EVENTS));
    }

    @Test
    void 文档示例配置可解析() throws IOException {
        Matcher m = Pattern.compile("```json lsflow\\n(.*?)\\n```", Pattern.DOTALL).matcher(Files.readString(doc()));
        int n = 0;
        while (m.find()) {
            FlowDefinition d = FlowDefinition.parse(m.group(1));
            assertThat(d.callbacks().approved()).isNotBlank();
            assertThat(d.nodes()).isNotEmpty();
            n++;
        }
        assertThat(n).isGreaterThanOrEqualTo(1);
    }

    private static Path doc() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("docs/flow-integration.md"))) p = p.getParent();
        assertThat(p).as("找不到 docs/flow-integration.md").isNotNull();
        return p.resolve("docs/flow-integration.md");
    }
}
