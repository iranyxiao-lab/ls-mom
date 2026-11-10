package com.lsmom.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.core.error.LsBizException;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** pack.yaml：编码、版本、依赖、包含内容、业务负责人、需求提出人与复审日期。 */
public record PackManifest(String code, String version, String name, String description, boolean standard, Person owner,
                           Person requester, LocalDate reviewDue, List<Dependency> dependsOn, Contents contents, List<Entry> entries) {

    public record Person(String name, String party) {
    }

    public record Dependency(String code, String version) {
    }

    public record Contents(List<String> concepts, List<String> operations, List<String> views, List<String> flows, List<String> rules,
                           List<String> metrics) {
        public Contents {
            concepts = concepts == null ? List.of() : List.copyOf(concepts);
            operations = operations == null ? List.of() : List.copyOf(operations);
            views = views == null ? List.of() : List.copyOf(views);
            flows = flows == null ? List.of() : List.copyOf(flows);
            rules = rules == null ? List.of() : List.copyOf(rules);
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
        }
    }

    /** Pack 入口；group 为菜单分组（work/ledger/analysis/config），省略时按路径推断。 */
    public record Entry(String name, String path, String group) {

        public Entry(String name, String path) {
            this(name, path, null);
        }

        public String effectiveGroup() {
            if (group != null && !group.isBlank()) return group;
            return path != null && path.startsWith("/lsmom/ledger/") ? "ledger" : "work";
        }
    }

    public record Issue(String path, String code, String message) {
    }

    public PackManifest {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        contents = contents == null ? new Contents(null, null, null, null, null, null) : contents;
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public SemVer semver() {
        return SemVer.parse(version);
    }

    private static final Schema SCHEMA;

    static {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            b -> b.schemaIdResolvers(r -> r.mapPrefix("https://lsmom.dev/schema", "classpath:ls-schema")));
        SCHEMA = registry.getSchema(SchemaLocation.of("https://lsmom.dev/schema/pack.schema.json"));
        SCHEMA.initializeValidators();
    }

    /** 校验 pack.yaml，返回全部问题（为空表示合格）。 */
    public static List<Issue> validate(JsonNode yaml) {
        List<Issue> issues = new ArrayList<>();
        for (Error e : SCHEMA.validate(yaml)) {
            String loc = e.getInstanceLocation().toString();
            String prop = e.getProperty();
            String path = prop != null ? loc + "/" + prop : loc;
            String code = "required".equals(e.getKeyword()) && ("owner".equals(prop) || "requester".equals(prop) || loc.endsWith("/owner")
                || loc.endsWith("/requester")) ? "PACK_OWNER_REQUIRED" : "MANIFEST_" + e.getKeyword().toUpperCase();
            String msg = "PACK_OWNER_REQUIRED".equals(code) ? ("requester".equals(prop) || loc.endsWith("/requester") ? "必须填写需求提出人" : "必须填写业务负责人")
                : e.getMessage();
            issues.add(new Issue(path.isEmpty() ? "/" : path, code, msg));
        }
        if (!issues.isEmpty()) return issues;
        String self = yaml.path("code").asText();
        JsonNode deps = yaml.path("dependsOn");
        for (int i = 0; i < deps.size(); i++) {
            JsonNode d = deps.get(i);
            if (self.equals(d.path("code").asText())) issues.add(new Issue("/dependsOn/" + i, "DEPENDENCY_SELF", "Pack 不能依赖自己"));
            try {
                SemVer.range(d.path("version").asText());
            } catch (IllegalArgumentException ex) {
                issues.add(new Issue("/dependsOn/" + i + "/version", "DEPENDENCY_RANGE_INVALID", ex.getMessage()));
            }
        }
        try {
            LocalDate.parse(yaml.path("reviewDue").asText());
        } catch (Exception ex) {
            issues.add(new Issue("/reviewDue", "MANIFEST_FORMAT", "复审日期不合法"));
        }
        return issues;
    }

    public static PackManifest parse(PackSource src) {
        JsonNode yaml = PackJson.read(PackSource.MANIFEST, src.file(PackSource.MANIFEST));
        List<Issue> issues = validate(yaml);
        if (!issues.isEmpty()) {
            Issue first = issues.get(0);
            throw new LsBizException(first.code().equals("PACK_OWNER_REQUIRED") ? "PACK_OWNER_REQUIRED" : "PACK_MANIFEST_INVALID",
                "pack.yaml 校验失败：" + first.message() + "（" + first.path() + "）", Map.of("issues", issues));
        }
        return PackJson.JSON.convertValue(yaml, PackManifest.class);
    }

    public JsonNode toJson() {
        return PackJson.JSON.valueToTree(this);
    }
}
