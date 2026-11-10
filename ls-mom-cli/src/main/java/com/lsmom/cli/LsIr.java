package com.lsmom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LS-IR 文档：加载（JSON 或 YAML）与校验。校验分两层：
 * <ol>
 *   <li>JSON Schema（{@code schema/ls-ir-1.0.json}）：结构、必填（含 provenance）、取值；</li>
 *   <li>语义：命名空间、引用对象可解析、视图字段存在、状态机迁移闭合、权限点已声明。</li>
 * </ol>
 */
public final class LsIr {

    public static final String VERSION = "1.0";
    public static final String SCHEMA = "schema/ls-ir-1.0.json";
    public static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final YAMLMapper YAML = new YAMLMapper();

    /** 平台内置、任何 Pack 都可引用的核心对象。 */
    public static final Set<String> CORE_CONCEPTS = Set.of("ls.core.Party", "ls.core.OrgUnit", "ls.core.Work", "ls.core.Issue");

    public record Problem(String path, String message) {
        @Override
        public String toString() {
            return path + "：" + message;
        }
    }

    private static volatile Schema schema;

    private LsIr() {
    }

    public static JsonNode read(Path file) {
        try {
            String text = Files.readString(file);
            return file.toString().endsWith(".json") ? JSON.readTree(text) : YAML.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 LS-IR 失败：" + file, e);
        }
    }

    public static Schema schema() {
        if (schema == null) {
            try (InputStream in = LsIr.class.getClassLoader().getResourceAsStream(SCHEMA)) {
                if (in == null) throw new IllegalStateException("类路径中没有 " + SCHEMA);
                schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(JSON.readTree(in));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return schema;
    }

    /** 全部问题；为空表示通过。 */
    public static List<Problem> validate(JsonNode ir) {
        List<Problem> out = new ArrayList<>();
        for (Error e : schema().validate(ir)) {
            String loc = e.getInstanceLocation().toString();
            String where = (loc.isEmpty() ? "$" : loc) + (e.getProperty() != null ? "/" + e.getProperty() : "");
            out.add(new Problem(where, describe(e)));
        }
        if (!out.isEmpty()) return dedupe(out);
        semantic(ir, out);
        return out;
    }

    private static String describe(Error e) {
        return switch (e.getKeyword()) {
            case "required" -> "缺少必填项 " + e.getProperty();
            case "additionalProperties" -> "不允许的属性 " + e.getProperty();
            case "const" -> "取值必须为 " + VERSION;
            default -> e.getMessage();
        };
    }

    private static List<Problem> dedupe(List<Problem> l) {
        return new ArrayList<>(new LinkedHashSet<>(l));
    }

    private static void semantic(JsonNode ir, List<Problem> out) {
        String ns = ir.path("namespace").asText() + ".";
        Set<String> own = new HashSet<>();
        Set<String> deps = new HashSet<>();
        ir.path("pack").path("dependsOn").forEach(d -> deps.add(d.path("code").asText() + "."));
        Set<String> reused = new HashSet<>();
        ir.path("reuse").forEach(r -> reused.add(r.path("concept").asText()));
        int i = 0;
        for (JsonNode c : ir.path("concepts")) {
            String p = "/concepts/" + i++;
            requireNs(c.path("code").asText(), ns, p + "/code", out);
            own.add(c.path("code").asText());
            int j = 0;
            for (JsonNode ch : c.path("children")) {
                requireNs(ch.path("code").asText(), ns, p + "/children/" + j++ + "/code", out);
                own.add(ch.path("code").asText());
            }
        }
        // 引用对象：本 Pack、语义复用清单、核心对象或依赖 Pack 中的对象
        i = 0;
        for (JsonNode c : ir.path("concepts")) {
            String p = "/concepts/" + i++;
            checkFields(c.path("fields"), p, own, reused, deps, out);
            int j = 0;
            for (JsonNode ch : c.path("children")) checkFields(ch.path("fields"), p + "/children/" + j++, own, reused, deps, out);
        }
        for (String r : reused) {
            if (!CORE_CONCEPTS.contains(r) && deps.stream().noneMatch(r::startsWith)) {
                out.add(new Problem("/reuse", "复用的 " + r + " 所在 Pack 须列入 pack.dependsOn"));
            }
        }
        // 视图
        i = 0;
        for (JsonNode v : ir.path("views")) {
            String p = "/views/" + i++;
            requireNs(v.path("code").asText(), ns, p + "/code", out);
            JsonNode concept = concept(ir, v.path("concept").asText());
            if (concept == null) {
                out.add(new Problem(p + "/concept", "视图对象不在本 Pack 中：" + v.path("concept").asText()));
                continue;
            }
            Set<String> codes = new HashSet<>();
            concept.path("fields").forEach(f -> codes.add(f.path("code").asText()));
            concept.path("children").forEach(ch -> codes.add(childKey(ch.path("code").asText())));
            int j = 0;
            for (JsonNode f : v.path("fields")) {
                if (!codes.contains(f.path("key").asText())) out.add(new Problem(p + "/fields/" + j, "视图字段不存在：" + f.path("key").asText()));
                j++;
            }
        }
        // 状态机与 Operation
        Set<String> perms = new HashSet<>();
        ir.path("permissions").forEach(x -> perms.add(x.path("code").asText()));
        Set<String> ops = new HashSet<>();
        i = 0;
        for (JsonNode o : ir.path("operations")) {
            String p = "/operations/" + i++;
            requireNs(o.path("code").asText(), ns, p + "/code", out);
            if (!own.contains(o.path("concept").asText())) out.add(new Problem(p + "/concept", "Operation 对象不在本 Pack 中：" + o.path("concept").asText()));
            if ("HANDLER".equals(o.path("kind").asText())) {
                ops.add(o.path("code").asText());
                if (!perms.contains(o.path("permission").asText())) out.add(new Problem(p + "/permission", "权限点未在 permissions 中声明：" + o.path("permission").asText()));
                continue;
            }
            Set<String> states = new HashSet<>();
            o.path("states").forEach(s -> states.add(s.asText()));
            if (!states.contains(o.path("initial").asText())) out.add(new Problem(p + "/initial", "初始状态不在 states 中"));
            int j = 0;
            for (JsonNode t : o.path("transitions")) {
                String tp = p + "/transitions/" + j++;
                requireNs(t.path("operation").asText(), ns, tp + "/operation", out);
                ops.add(t.path("operation").asText());
                t.path("from").forEach(s -> {
                    if (!states.contains(s.asText())) out.add(new Problem(tp + "/from", "状态不存在：" + s.asText()));
                });
                if (!states.contains(t.path("to").asText())) out.add(new Problem(tp + "/to", "状态不存在：" + t.path("to").asText()));
                if (!perms.contains(t.path("permission").asText())) out.add(new Problem(tp + "/permission", "权限点未在 permissions 中声明：" + t.path("permission").asText()));
            }
        }
        i = 0;
        for (JsonNode r : ir.path("rules")) {
            String p = "/rules/" + i++;
            requireNs(r.path("code").asText(), ns, p + "/code", out);
            r.path("appliesTo").forEach(a -> {
                if (!ops.contains(a.asText())) out.add(new Problem(p + "/appliesTo", "规则作用的 Operation 不存在：" + a.asText()));
            });
        }
        i = 0;
        for (JsonNode f : ir.path("flows")) {
            String p = "/flows/" + i++;
            requireNs(f.path("code").asText(), ns, p + "/code", out);
            if (!ops.contains(f.path("startOn").asText())) out.add(new Problem(p + "/startOn", "发起流程的 Operation 不存在：" + f.path("startOn").asText()));
            f.path("callbacks").forEach(cb -> {
                if (!ops.contains(cb.asText())) out.add(new Problem(p + "/callbacks", "流程回调的 Operation 不存在：" + cb.asText()));
            });
        }
        // 含 C2 及以上 Operation 的对象为高风险对象，平台不允许经通用台账接口写入：测试场景不能直接 create
        Set<String> highRisk = new HashSet<>();
        for (JsonNode o : ir.path("operations")) {
            if (high(o.path("risk").asText("C1"))) highRisk.add(o.path("concept").asText());
            o.path("transitions").forEach(t -> {
                if (high(t.path("risk").asText("C1"))) highRisk.add(o.path("concept").asText());
            });
        }
        i = 0;
        for (JsonNode t : ir.path("tests")) {
            String p = "/tests/" + i++;
            t.path("steps").forEach(st -> {
                String c = st.path("create").path("concept").asText("");
                if (highRisk.contains(c)) out.add(new Problem(p, "高风险对象 " + c + "（含 C2 及以上 Operation）不能经通用接口新增，场景中请改用新增 Operation"));
            });
        }
        i = 0;
        for (JsonNode m : ir.path("metrics")) requireNs(m.path("code").asText(), ns, "/metrics/" + i++ + "/code", out);
    }

    private static boolean high(String risk) {
        return "C2".equals(risk) || "C3".equals(risk);
    }

    private static void checkFields(JsonNode fields, String p, Set<String> own, Set<String> reused, Set<String> deps, List<Problem> out) {
        int k = 0;
        for (JsonNode f : fields) {
            String fp = p + "/fields/" + k++;
            String type = f.path("dataType").asText();
            if ("REF".equals(type)) {
                String ref = f.path("refConcept").asText("");
                if (ref.isEmpty()) out.add(new Problem(fp, "引用字段必须指定 refConcept"));
                else if (!own.contains(ref) && !reused.contains(ref) && !CORE_CONCEPTS.contains(ref) && deps.stream().noneMatch(ref::startsWith)) {
                    out.add(new Problem(fp + "/refConcept", "引用对象无法解析（不在本 Pack、复用清单、核心对象或依赖 Pack 中）：" + ref));
                }
            }
            if ("ENUM".equals(type) && (!f.has("options") || f.path("options").isEmpty())) out.add(new Problem(fp, "枚举字段必须有 options"));
        }
    }

    private static void requireNs(String code, String ns, String path, List<Problem> out) {
        if (!code.startsWith(ns)) out.add(new Problem(path, "编码必须以命名空间 " + ns + " 开头：" + code));
    }

    static JsonNode concept(JsonNode ir, String code) {
        for (JsonNode c : ir.path("concepts")) if (c.path("code").asText().equals(code)) return c;
        return null;
    }

    /** 子表在主表记录中的键：子表编码最后一段首字母小写（与通用台账一致）。 */
    static String childKey(String childCode) {
        String last = childCode.substring(childCode.lastIndexOf('.') + 1);
        return Character.toLowerCase(last.charAt(0)) + last.substring(1);
    }
}
