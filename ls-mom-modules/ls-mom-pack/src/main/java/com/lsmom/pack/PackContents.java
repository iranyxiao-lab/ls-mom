package com.lsmom.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.state.StateMachineDefinition;
import com.lsmom.core.error.LsBizException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pack 目录内容（按约定目录解析，不执行任何代码）：
 * <pre>
 * pack.yaml
 * concepts/*.yaml      台账对象（含子表）或 extends 为其他 Pack 的对象追加字段
 * operations/*.yaml    状态机（生成迁移 Operation）
 * rules/*.yaml         约束规则
 * views/*.json         表单 / 列表 / 看板等视图
 * metrics/*.yaml       指标口径
 * flows/*.json         审批流
 * migrations/V&lt;n&gt;__*.sql  补充迁移（独立 Flyway 历史表）
 * seed/*.yaml          种子数据
 * tests/scenarios/*.yaml  黄金场景（回归测试）
 * </pre>
 */
public record PackContents(PackManifest manifest, List<Item> concepts, List<Item> machines, List<Item> rules, List<Item> views,
                           List<Item> metrics, List<Item> flows, List<Migration> migrations, List<Item> seeds, List<Item> scenarios) {

    /** 一个定义文件：编码、文件路径、解析后的内容。 */
    public record Item(String code, String path, JsonNode body) {
    }

    public record Migration(String version, String description, String path, String sql) {
        public String filename() {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    public record Issue(String path, String code, String message) {
    }

    private static final Pattern MIGRATION = Pattern.compile("^V(\\d+(?:_\\d+)*)__([A-Za-z0-9_]+)\\.sql$");
    private static final Set<String> DIRS = Set.of("concepts/", "operations/", "rules/", "views/", "metrics/", "flows/", "migrations/", "seed/",
        "tests/scenarios/");

    public static PackContents parse(PackSource src) {
        PackManifest m = PackManifest.parse(src);
        List<Issue> issues = new ArrayList<>();
        for (String f : src.files().keySet()) {
            if (f.equals(PackSource.MANIFEST) || f.equals("README.md") || f.startsWith("docs/")) continue;
            String dir = f.contains("/") ? f.substring(0, f.lastIndexOf('/') + 1) : "";
            if (!DIRS.contains(dir)) issues.add(new Issue(f, "PACK_FILE_UNEXPECTED", "不在约定目录中的文件：" + f));
        }
        String ns = m.code() + ".";
        List<Item> concepts = items(src, "concepts/", issues, (b, p) -> b.hasNonNull("extends") ? b.path("extends").asText() : b.path("code").asText(null));
        List<Item> machines = items(src, "operations/", issues, (b, p) -> b.path("code").asText(null));
        List<Item> rules = items(src, "rules/", issues, (b, p) -> b.path("code").asText(null));
        List<Item> views = items(src, "views/", issues, (b, p) -> b.path("formCode").asText(null));
        List<Item> metrics = items(src, "metrics/", issues, (b, p) -> b.path("code").asText(null));
        List<Item> flows = items(src, "flows/", issues, (b, p) -> b.path("code").asText(null));
        List<Item> seeds = items(src, "seed/", issues, (b, p) -> b.hasNonNull("concept") ? b.path("concept").asText() + "@" + p : null);
        List<Item> scenarios = items(src, "tests/scenarios/", issues, (b, p) -> b.path("name").asText(null));

        List<Migration> migrations = new ArrayList<>();
        Set<String> versions = new java.util.HashSet<>();
        for (String p : src.list("migrations/")) {
            Matcher mm = MIGRATION.matcher(p.substring("migrations/".length()));
            if (!mm.matches()) {
                issues.add(new Issue(p, "MIGRATION_NAME_INVALID", "迁移文件名须为 V<版本>__<说明>.sql：" + p));
                continue;
            }
            if (!versions.add(mm.group(1))) issues.add(new Issue(p, "MIGRATION_DUPLICATE", "迁移版本重复：" + mm.group(1)));
            migrations.add(new Migration(mm.group(1), mm.group(2), p, src.text(p)));
        }

        // 命名空间：Pack 内定义都在 Pack 编码之下；extends 只能指向依赖 Pack 的对象
        Set<String> depCodes = new TreeSet<>();
        m.dependsOn().forEach(d -> depCodes.add(d.code() + "."));
        for (Item i : concepts) {
            if (i.body().hasNonNull("extends")) {
                if (depCodes.stream().noneMatch(i.code()::startsWith)) {
                    issues.add(new Issue(i.path(), "EXTENDS_NOT_DEPENDENCY", "只能为依赖 Pack 中的对象追加字段：" + i.code()));
                }
                if (i.body().hasNonNull("code") || i.body().hasNonNull("children")) {
                    issues.add(new Issue(i.path(), "EXTENDS_INVALID", "extends 文件只能包含 fields"));
                }
            } else {
                requireNs(i, ns, issues);
                i.body().path("children").forEach(c -> {
                    if (!c.path("code").asText("").startsWith(ns)) {
                        issues.add(new Issue(i.path(), "PACK_NAMESPACE", "子表编码必须以 " + ns + " 开头：" + c.path("code").asText()));
                    }
                });
            }
        }
        for (List<Item> l : List.of(machines, rules, views, metrics, flows)) l.forEach(i -> requireNs(i, ns, issues));
        for (Item sm : machines) {
            try {
                StateMachineDefinition d = PackJson.JSON.treeToValue(sm.body(), StateMachineDefinition.class);
                for (StateMachineDefinition.Transition t : d.transitions()) {
                    if (t.operation() == null || !t.operation().startsWith(ns)) {
                        issues.add(new Issue(sm.path(), "PACK_NAMESPACE", "Operation 编码必须以 " + ns + " 开头：" + t.operation()));
                    }
                    if (t.permission() == null || t.permission().isBlank()) {
                        issues.add(new Issue(sm.path(), "PACK_PERMISSION_REQUIRED", "Pack 中的 Operation 必须声明权限：" + t.operation()));
                    }
                }
            } catch (Exception e) {
                issues.add(new Issue(sm.path(), "STATE_MACHINE_INVALID", "状态机格式不正确：" + e.getMessage()));
            }
        }
        for (Item s : seeds) {
            if (!s.body().path("rows").isArray() || s.body().path("key").asText("").isEmpty()) {
                issues.add(new Issue(s.path(), "SEED_INVALID", "种子文件须包含 concept、key（判重字段）与 rows"));
            }
        }

        // 清单声明与目录一致
        checkDeclared(issues, "concepts", m.contents().concepts(), concepts.stream().filter(i -> !i.body().hasNonNull("extends")).map(Item::code).toList());
        checkDeclared(issues, "views", m.contents().views(), views.stream().map(Item::code).toList());
        checkDeclared(issues, "flows", m.contents().flows(), flows.stream().map(Item::code).toList());
        checkDeclared(issues, "rules", m.contents().rules(), rules.stream().map(Item::code).toList());
        checkDeclared(issues, "metrics", m.contents().metrics(), metrics.stream().map(Item::code).toList());
        List<String> ops = new ArrayList<>();
        machines.forEach(i -> i.body().path("transitions").forEach(t -> ops.add(t.path("operation").asText())));
        checkDeclared(issues, "operations", m.contents().operations(), ops);

        if (!issues.isEmpty()) {
            throw new LsBizException(issues.get(0).code(), "Pack 内容校验失败：" + issues.get(0).message(), Map.of("issues", issues));
        }
        return new PackContents(m, concepts, machines, rules, views, metrics, flows, migrations, seeds, scenarios);
    }

    private interface CodeOf {
        String of(JsonNode body, String path);
    }

    private static List<Item> items(PackSource src, String dir, List<Issue> issues, CodeOf code) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String p : src.list(dir, ".yaml", ".yml", ".json")) {
            JsonNode body;
            try {
                body = PackJson.read(p, src.file(p));
            } catch (LsBizException e) {
                issues.add(new Issue(p, "PACK_FILE_INVALID", e.getMessage()));
                continue;
            }
            String c = code.of(body, p);
            if (c == null || c.isBlank()) {
                issues.add(new Issue(p, "PACK_FILE_INVALID", "缺少编码：" + p));
                continue;
            }
            if (!seen.add(c)) issues.add(new Issue(p, "PACK_DUPLICATE_DEFINITION", "定义编码重复：" + c));
            out.add(new Item(c, p, body));
        }
        return out;
    }

    private static void requireNs(Item i, String ns, List<Issue> issues) {
        if (!i.code().startsWith(ns)) issues.add(new Issue(i.path(), "PACK_NAMESPACE", "编码必须以 " + ns + " 开头：" + i.code()));
    }

    private static void checkDeclared(List<Issue> issues, String kind, List<String> declared, List<String> actual) {
        Set<String> d = new TreeSet<>(declared);
        Set<String> a = new TreeSet<>(actual);
        if (d.equals(a)) return;
        Set<String> missing = new TreeSet<>(d);
        missing.removeAll(a);
        Set<String> extra = new TreeSet<>(a);
        extra.removeAll(d);
        issues.add(new Issue("/contents/" + kind, "PACK_CONTENTS_MISMATCH",
            "pack.yaml contents." + kind + " 与目录不一致" + (missing.isEmpty() ? "" : "，缺少文件：" + missing) + (extra.isEmpty() ? "" : "，未声明：" + extra)));
    }

    /** 全部 Pack 内 Concept（主表与子表）编码。 */
    public List<String> ownConcepts() {
        List<String> out = new ArrayList<>();
        for (Item i : concepts) {
            if (i.body().hasNonNull("extends")) continue;
            out.add(i.code());
            i.body().path("children").forEach(c -> out.add(c.path("code").asText()));
        }
        return out;
    }
}
