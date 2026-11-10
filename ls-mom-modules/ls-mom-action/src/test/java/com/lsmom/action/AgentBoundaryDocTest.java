package com.lsmom.action;

import com.lsmom.action.gateway.AgentPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/agent-boundary.md 的永不暴露清单与运行时加载的 agent-denylist.txt 一致（18.6）。 */
class AgentBoundaryDocTest {

    @Test
    void 文档中的DENY清单与运行时清单一致() throws Exception {
        String doc = Files.readString(Path.of("../../docs/agent-boundary.md"));
        int from = doc.indexOf("<!-- deny-list:start -->");
        int to = doc.indexOf("<!-- deny-list:end -->");
        assertThat(from).as("文档缺少 deny-list 标记").isPositive();
        List<AgentPolicy.Entry> documented = new ArrayList<>();
        for (String line : doc.substring(from, to).split("\\R")) {
            String l = line.strip();
            if (!l.startsWith("|") || l.startsWith("| 类别") || l.startsWith("| ---")) continue;
            String[] cells = l.substring(1).split("\\|");
            documented.add(new AgentPolicy.Entry(cells[0].strip(), cells[1].strip().replace("`", "")));
        }
        assertThat(documented).containsExactlyInAnyOrderElementsOf(AgentPolicy.standard().entries());
        // 文档写明的每一类都有中文名称
        documented.forEach(e -> assertThat(AgentPolicy.label(e.category())).isNotEqualTo(e.category()));
    }
}
