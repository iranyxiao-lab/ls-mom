package com.lsmom.core;

import com.lsmom.test.PgTestSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/core-model.md 的字段表与迁移脚本建出的实际结构保持一致。 */
class CoreModelDocTest {

    private static final Pattern TABLE_HEADING = Pattern.compile("^### `(core_[a-z_]+)`");
    private static final Pattern BACKTICK = Pattern.compile("`([a-z_]+)`");

    @Test
    void 文档字段与迁移脚本一致() throws Exception {
        Path doc = Path.of("../../docs/core-model.md");
        Map<String, Set<String>> documented = new LinkedHashMap<>();
        String current = null;
        for (String line : Files.readAllLines(doc)) {
            Matcher h = TABLE_HEADING.matcher(line);
            if (h.find()) {
                current = h.group(1);
                documented.put(current, new TreeSet<>());
            } else if (line.startsWith("## ")) {
                current = null;
            } else if (current != null && line.startsWith("| `")) {
                Matcher m = BACKTICK.matcher(line.split("\\|")[1]);
                while (m.find()) documented.get(current).add(m.group(1));
            }
        }

        List<Map<String, Object>> rows = PgTestSupport.jdbc().queryForList("""
            select table_name, column_name from information_schema.columns
            where table_schema = 'public' and table_name like 'core\\_%' order by table_name, ordinal_position""");
        Map<String, Set<String>> actual = new LinkedHashMap<>();
        rows.forEach(r -> actual.computeIfAbsent((String) r.get("table_name"), k -> new TreeSet<>()).add((String) r.get("column_name")));

        assertThat(documented.keySet()).as("文档中的核心表").containsExactlyInAnyOrderElementsOf(actual.keySet());
        for (String table : actual.keySet()) {
            Set<String> missing = new HashSet<>(actual.get(table));
            missing.removeAll(documented.get(table));
            Set<String> extra = new HashSet<>(documented.get(table));
            extra.removeAll(actual.get(table));
            assertThat(missing).as(table + " 未写入文档的字段").isEmpty();
            assertThat(extra).as(table + " 文档中多出（已不存在）的字段").isEmpty();
        }
    }
}
