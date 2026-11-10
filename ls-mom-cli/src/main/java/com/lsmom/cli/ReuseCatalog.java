package com.lsmom.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 语义复用检查用的已有对象目录：核心对象（人员、组织、工作、问题）＋ 指定目录下各 Pack 的 Concept。
 * 匹配顺序：显示名完全一致 → 同义词 → 显示名以该词结尾（如「设备」匹配「设备」而非「设备缺陷」）。
 */
public final class ReuseCatalog {

    public record Entry(String concept, String displayName, String pack, String packVersion, String labelField, List<String> synonyms) {
    }

    /** 核心对象与常见叫法。 */
    private static final List<Entry> CORE = List.of(
        new Entry("ls.core.Party", "人员", null, null, "name", List.of("人员", "员工", "人", "操作工", "负责人", "申请人", "检查人", "执行人", "责任人", "操作人")),
        new Entry("ls.core.OrgUnit", "组织", null, null, "name", List.of("组织", "部门", "车间", "班组", "工段", "单位")),
        new Entry("ls.core.Work", "工作", null, null, "title", List.of("工作", "工单", "任务", "作业任务")),
        new Entry("ls.core.Issue", "问题", null, null, "title", List.of("问题", "隐患", "异常")));

    /** Pack 对象的补充同义词（按显示名）。 */
    private static final Map<String, List<String>> SYNONYMS = Map.of(
        "设备", List.of("设备", "设施", "机台", "装置", "机器"),
        "物料记录", List.of("物料", "领料"),
        "质量记录", List.of("检验记录", "质检记录"));

    private final List<Entry> entries;

    private ReuseCatalog(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** 扫描目录（递归找 pack.yaml）。 */
    public static ReuseCatalog load(List<Path> roots) {
        List<Entry> out = new ArrayList<>(CORE);
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root, 8)) {
                for (Path manifest : s.filter(p -> p.getFileName().toString().equals("pack.yaml")).toList()) {
                    JsonNode m = LsIr.YAML.readTree(Files.readString(manifest));
                    Path concepts = manifest.getParent().resolve("concepts");
                    if (!Files.isDirectory(concepts)) continue;
                    try (Stream<Path> cs = Files.list(concepts)) {
                        for (Path c : cs.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                            JsonNode cn = LsIr.YAML.readTree(Files.readString(c));
                            if (!cn.hasNonNull("code")) continue;
                            String dn = cn.path("displayName").asText();
                            out.add(new Entry(cn.path("code").asText(), dn, m.path("code").asText(), m.path("version").asText(), labelField(cn),
                                SYNONYMS.getOrDefault(dn, List.of(dn))));
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new ReuseCatalog(out);
    }

    private static String labelField(JsonNode concept) {
        String first = null;
        for (JsonNode f : concept.path("fields")) {
            String code = f.path("code").asText();
            if (code.equals("name") || code.equals("title")) return code;
            if (first == null && "STRING".equals(f.path("dataType").asText())) first = code;
        }
        return first == null ? "id" : first;
    }

    public Optional<Entry> match(String term) {
        if (term == null || term.isBlank()) return Optional.empty();
        String t = term.trim();
        Map<Integer, Entry> best = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (e.displayName().equals(t)) return Optional.of(e);
            if (e.synonyms().contains(t)) best.putIfAbsent(1, e);
            else if (t.endsWith(e.displayName()) && t.length() - e.displayName().length() <= 2) best.putIfAbsent(2, e);
        }
        return best.isEmpty() ? Optional.empty() : Optional.of(best.entrySet().iterator().next().getValue());
    }
}
