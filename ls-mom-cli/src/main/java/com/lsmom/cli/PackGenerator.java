package com.lsmom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.pack.PackContents;
import com.lsmom.pack.PackSource;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 由 LS-IR 生成：
 * <pre>
 * &lt;out&gt;/pack/                 Pack 目录（pack.yaml、concepts、operations、rules、views、flows、metrics、migrations、seed、tests/scenarios、provenance.json、docs/permissions.yaml）
 * &lt;out&gt;/java/src/main/java/   代码 Operation 骨架（@LsOperation，待人工实现）
 * &lt;out&gt;/java/src/test/java/   JUnit：在临时数据库安装 Pack 并运行黄金场景
 * &lt;out&gt;/changeset/&lt;code&gt;-&lt;version&gt;.zip   ChangeSet 制品（含 MANIFEST.sha256）
 * &lt;out&gt;/report.json            生成质量指标（本次）
 * </pre>
 * 生成结果只进入文件与 ChangeSet，发布须经流水线测试与人工批准。
 */
public final class PackGenerator {

    public static final String NAME = "ls-mom-cli generate";
    public static final String VERSION = "1.0.0";

    public record Output(String code, String version, Map<String, String> pack, Map<String, String> java, byte[] changeSet, String digest,
                         ObjectNode report) {
    }

    private PackGenerator() {
    }

    static int cli(LsMomCli.Args a, PrintStream out, PrintStream err) {
        Path irFile = Path.of(a.positional(0, "LS-IR 文件"));
        Path dir = Path.of(a.require("out"));
        JsonNode ir = LsIr.read(irFile);
        List<LsIr.Problem> problems = LsIr.validate(ir);
        if (!problems.isEmpty()) {
            err.println("LS-IR 校验失败，未生成：");
            problems.forEach(p -> err.println("  " + p));
            return 1;
        }
        Output o = generate(ir);
        write(o, dir);
        out.println("已生成 " + o.code() + " " + o.version() + "：Pack 文件 " + o.pack().size() + " 个、代码 " + o.java().size() + " 个 → " + dir);
        out.println("ChangeSet 制品：" + dir.resolve("changeset").resolve(o.code() + "-" + o.version() + ".zip") + "（sha256 " + o.digest() + "）");
        JsonNode manual = o.report().path("manualRequired");
        if (manual.path("open").asInt() > 0) {
            out.println("注意：有 " + manual.path("open").asInt() + " 项需人工处理（其中禁止自动转换 " + manual.path("blocked").asInt() + " 项），处理前 ChangeSet 不能发布");
        }
        return 0;
    }

    /** 生成（调用方须先通过校验）。 */
    public static Output generate(JsonNode ir) {
        String ns = ir.path("namespace").asText();
        String version = ir.path("pack").path("version").asText();
        Map<String, String> pack = new TreeMap<>();
        pack.put("pack.yaml", yaml(manifest(ir)));
        for (JsonNode c : ir.path("concepts")) pack.put("concepts/" + kebab(last(c.path("code").asText())) + ".yaml", yaml(concept(c)));
        int reuse = ir.path("reuse").size();
        for (JsonNode o : ir.path("operations")) {
            if ("STATE_MACHINE".equals(o.path("kind").asText())) pack.put("operations/" + kebab(last(o.path("concept").asText())) + "-lifecycle.yaml", yaml(machine(o)));
        }
        for (JsonNode r : ir.path("rules")) pack.put("rules/" + last(r.path("code").asText()) + ".yaml", yaml(rule(r)));
        for (JsonNode v : ir.path("views")) pack.put("views/" + kebab(last(v.path("code").asText())) + ".json", json(view(ir, v)));
        for (JsonNode f : ir.path("flows")) pack.put("flows/" + kebab(last(f.path("code").asText())) + ".json", json(flow(f)));
        for (JsonNode m : ir.path("metrics")) pack.put("metrics/" + kebab(last(m.path("code").asText())) + ".yaml", yaml(m));
        for (JsonNode m : ir.path("migrations")) pack.put("migrations/V" + m.path("version").asText() + "__" + m.path("description").asText() + ".sql", m.path("sql").asText());
        int si = 0;
        for (JsonNode s : ir.path("seeds")) pack.put("seed/" + String.format("%02d", ++si) + "-" + kebab(last(s.path("concept").asText())) + ".yaml", yaml(s));
        int ti = 0;
        for (JsonNode t : ir.path("tests")) pack.put("tests/scenarios/" + String.format("%02d", ++ti) + ".yaml", yaml(t));
        pack.put(PackContents.PROVENANCE, json(provenance(ir)));
        pack.put("docs/permissions.yaml", yaml(permissions(ir)));

        Map<String, String> java = new TreeMap<>();
        String pkg = ns;
        for (JsonNode o : ir.path("operations")) {
            if (!"HANDLER".equals(o.path("kind").asText())) continue;
            String cls = pascal(last(o.path("code").asText())) + last(o.path("concept").asText()) + "Operation";
            java.put("src/main/java/" + pkg.replace('.', '/') + "/" + cls + ".java", handler(pkg, cls, o));
        }
        java.put("src/test/java/" + pkg.replace('.', '/') + "/GeneratedPackTest.java", junit(pkg, ns));

        Map<String, byte[]> files = new LinkedHashMap<>();
        pack.forEach((k, v) -> files.put(k, v.getBytes(StandardCharsets.UTF_8)));
        PackSource src = PackSource.of(files);
        ObjectNode report = LsIr.JSON.createObjectNode();
        report.put("pack", ns);
        report.put("version", version);
        report.put("generatedAt", Instant.now().toString());
        report.put("generator", NAME + " " + VERSION);
        report.put("validation", "PASSED");
        report.put("packFiles", pack.size());
        report.put("javaFiles", java.size());
        int own = ir.path("concepts").size();
        report.put("conceptsNew", own);
        report.put("conceptsReused", reuse);
        report.put("reuseRatio", own + reuse == 0 ? 0 : Math.round(reuse * 1000.0 / (own + reuse)) / 1000.0);
        JsonNode prov = ir.path("provenance");
        ArrayNode edits = report.putArray("manualEdits");
        prov.path("manualEdits").forEach(e -> edits.addObject().put("path", e.path("path").asText()).put("reason", e.path("reason").asText())
            .put("lines", e.path("lines").asInt(0)));
        int open = 0, blocked = 0;
        for (JsonNode m : prov.path("manualRequired")) {
            if (!"RESOLVED".equals(m.path("status").asText())) {
                open++;
                if ("BLOCKED".equals(m.path("kind").asText())) blocked++;
            }
        }
        report.putObject("manualRequired").put("open", open).put("blocked", blocked);
        report.put("sourceType", prov.path("sourceType").asText());
        report.put("model", prov.path("model").isObject() ? prov.path("model").path("name").asText() + " " + prov.path("model").path("version").asText() : null);
        JsonNode pm = prov.path("metrics");
        if (pm.has("requirementAt") && pm.has("uatAt")) {
            report.put("requirementToUatHours", Math.round(Duration.between(Instant.parse(pm.path("requirementAt").asText()),
                Instant.parse(pm.path("uatAt").asText())).toMinutes() / 6.0) / 10.0);
        }
        return new Output(ns, version, pack, java, src.toZip(), src.digest(), report);
    }

    public static void write(Output o, Path dir) {
        try {
            for (var e : o.pack().entrySet()) put(dir.resolve("pack").resolve(e.getKey()), e.getValue());
            for (var e : o.java().entrySet()) put(dir.resolve("java").resolve(e.getKey()), e.getValue());
            Path zip = dir.resolve("changeset").resolve(o.code() + "-" + o.version() + ".zip");
            Files.createDirectories(zip.getParent());
            Files.write(zip, o.changeSet());
            put(dir.resolve("report.json"), json(o.report()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void put(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    // —— Pack 文件 ——

    private static ObjectNode manifest(JsonNode ir) {
        JsonNode p = ir.path("pack");
        ObjectNode m = LsIr.JSON.createObjectNode();
        m.put("code", ir.path("namespace").asText());
        m.put("version", p.path("version").asText());
        m.put("name", p.path("name").asText());
        if (p.has("description")) m.put("description", p.path("description").asText());
        m.set("owner", p.path("owner"));
        m.set("requester", p.path("requester"));
        m.put("reviewDue", p.path("reviewDue").asText());
        if (p.path("dependsOn").size() > 0) m.set("dependsOn", p.path("dependsOn"));
        ObjectNode c = m.putObject("contents");
        ArrayNode concepts = c.putArray("concepts");
        ir.path("concepts").forEach(x -> concepts.add(x.path("code").asText()));
        ArrayNode ops = c.putArray("operations");
        ir.path("operations").forEach(o -> o.path("transitions").forEach(t -> ops.add(t.path("operation").asText())));
        ArrayNode rules = c.putArray("rules");
        ir.path("rules").forEach(x -> rules.add(x.path("code").asText()));
        ArrayNode views = c.putArray("views");
        ir.path("views").forEach(x -> views.add(x.path("code").asText()));
        ArrayNode flows = c.putArray("flows");
        ir.path("flows").forEach(x -> flows.add(x.path("code").asText()));
        ArrayNode metrics = c.putArray("metrics");
        ir.path("metrics").forEach(x -> metrics.add(x.path("code").asText()));
        ArrayNode entries = m.putArray("entries");
        if (p.path("entries").size() > 0) p.path("entries").forEach(entries::add);
        else ir.path("concepts").forEach(x -> entries.addObject().put("name", x.path("displayName").asText()).put("path", "/lsmom/ledger/" + x.path("code").asText()));
        return m;
    }

    private static ObjectNode concept(JsonNode c) {
        ObjectNode o = LsIr.JSON.createObjectNode();
        o.put("code", c.path("code").asText());
        o.put("displayName", c.path("displayName").asText());
        if (c.has("description")) o.put("description", c.path("description").asText());
        o.set("fields", fields(c.path("fields")));
        if (c.path("children").size() > 0) {
            ArrayNode ch = o.putArray("children");
            c.path("children").forEach(x -> {
                ObjectNode y = ch.addObject();
                y.put("code", x.path("code").asText());
                y.put("displayName", x.path("displayName").asText());
                y.set("fields", fields(x.path("fields")));
            });
        }
        return o;
    }

    private static ArrayNode fields(JsonNode in) {
        ArrayNode out = LsIr.JSON.createArrayNode();
        for (JsonNode f : in) {
            ObjectNode o = out.addObject();
            o.put("code", f.path("code").asText());
            o.put("displayName", f.path("displayName").asText());
            o.put("dataType", f.path("dataType").asText());
            if (f.path("required").asBoolean(false)) o.put("required", true);
            if (f.has("refConcept")) o.put("refConcept", f.path("refConcept").asText());
            if (f.has("options")) o.set("options", f.path("options"));
            ObjectNode props = LsIr.JSON.createObjectNode();
            for (String k : List.of("queryable", "sortable", "unique")) if (f.path(k).asBoolean(false)) props.put(k, true);
            if (f.has("maxLength")) props.put("maxLength", f.path("maxLength").asInt());
            if (f.has("optionLabels")) props.set("optionLabels", f.path("optionLabels"));
            if (!props.isEmpty()) o.set("props", props);
        }
        return out;
    }

    private static ObjectNode machine(JsonNode o) {
        ObjectNode m = LsIr.JSON.createObjectNode();
        m.put("code", o.path("code").asText());
        m.put("concept", o.path("concept").asText());
        m.put("displayName", o.path("displayName").asText(last(o.path("concept").asText()) + " 状态"));
        m.set("states", o.path("states"));
        m.put("initial", o.path("initial").asText());
        ArrayNode ts = m.putArray("transitions");
        for (JsonNode t : o.path("transitions")) {
            ObjectNode x = ts.addObject();
            x.put("operation", t.path("operation").asText());
            x.put("name", t.path("name").asText());
            x.set("from", t.path("from"));
            x.put("to", t.path("to").asText());
            x.put("permission", t.path("permission").asText());
            x.put("risk", t.path("risk").asText("C1"));
            x.set("inputSchema", t.has("inputSchema") ? t.path("inputSchema") : LsIr.JSON.createObjectNode().put("type", "object"));
        }
        return m;
    }

    private static ObjectNode rule(JsonNode r) {
        ObjectNode o = LsIr.JSON.createObjectNode();
        o.put("code", r.path("code").asText());
        o.put("concept", r.path("concept").asText());
        o.put("level", r.path("level").asText("L2"));
        o.put("kind", "CEL");
        o.put("name", r.path("name").asText());
        o.put("message", r.path("message").asText(r.path("name").asText()));
        ObjectNode d = o.putObject("definition");
        d.put("assert", r.path("assert").asText());
        d.set("appliesTo", r.path("appliesTo"));
        return o;
    }

    private static ObjectNode view(JsonNode ir, JsonNode v) {
        JsonNode concept = LsIr.concept(ir, v.path("concept").asText());
        Map<String, JsonNode> byCode = new LinkedHashMap<>();
        concept.path("fields").forEach(f -> byCode.put(f.path("code").asText(), f));
        Map<String, JsonNode> children = new LinkedHashMap<>();
        concept.path("children").forEach(c -> children.put(LsIr.childKey(c.path("code").asText()), c));
        ObjectNode o = LsIr.JSON.createObjectNode();
        o.put("viewType", "FORM");
        o.put("formCode", v.path("code").asText());
        o.put("version", 1);
        o.put("conceptCode", concept.path("code").asText());
        o.put("title", v.path("title").asText(concept.path("displayName").asText()));
        ArrayNode layout = o.putArray("layout");
        ArrayNode fields = o.putArray("fields");
        for (JsonNode vf : v.path("fields")) {
            String key = vf.path("key").asText();
            if (children.containsKey(key)) {
                layout.addObject().put("type", "subTable").put("key", key);
                JsonNode ch = children.get(key);
                ObjectNode st = (o.has("subTables") ? (ArrayNode) o.get("subTables") : o.putArray("subTables")).addObject();
                st.put("key", key);
                st.put("label", vf.path("label").asText(ch.path("displayName").asText()));
                st.put("conceptCode", ch.path("code").asText());
                ArrayNode sf = st.putArray("fields");
                for (JsonNode cf : ch.path("fields")) fieldWidget(ir, sf.addObject(), cf.path("code").asText(), cf.path("displayName").asText(), cf, false);
                continue;
            }
            layout.addObject().put("type", "field").put("key", key);
            JsonNode f = byCode.get(key);
            ObjectNode x = fields.addObject();
            fieldWidget(ir, x, key, vf.path("label").asText(f.path("displayName").asText()), f, vf.path("required").asBoolean(f.path("required").asBoolean(false)));
            if (vf.has("widget")) x.put("widget", vf.path("widget").asText());
        }
        if (v.path("actions").size() > 0) o.set("actions", v.path("actions"));
        return o;
    }

    private static void fieldWidget(JsonNode ir, ObjectNode x, String key, String label, JsonNode f, boolean required) {
        x.put("key", key);
        x.put("label", label);
        x.put("widget", widget(f));
        if (required || f.path("required").asBoolean(false)) x.put("required", true);
        if ("ENUM".equals(f.path("dataType").asText())) {
            ArrayNode opts = x.putArray("options");
            f.path("options").forEach(op -> opts.addObject().put("label", f.path("optionLabels").path(op.asText()).asText(op.asText())).put("value", op.asText()));
        }
        if ("conceptRef".equals(x.path("widget").asText())) {
            String ref = f.path("refConcept").asText();
            x.putObject("ref").put("concept", ref).put("labelField", labelField(ir, ref));
        }
    }

    static String widget(JsonNode f) {
        return switch (f.path("dataType").asText()) {
            case "TEXT" -> "textarea";
            case "INTEGER", "DECIMAL" -> "number";
            case "BOOLEAN" -> "switch";
            case "DATE" -> "date";
            case "DATETIME" -> "datetime";
            case "ENUM" -> "select";
            case "FILE" -> "attachment";
            case "REF" -> switch (f.path("refConcept").asText()) {
                case "ls.core.Party" -> "userRef";
                case "ls.core.OrgUnit" -> "deptRef";
                case "ls.core.Work" -> "workRef";
                default -> "conceptRef";
            };
            default -> "input";
        };
    }

    private static String labelField(JsonNode ir, String ref) {
        for (JsonNode r : ir.path("reuse")) if (r.path("concept").asText().equals(ref) && r.has("labelField")) return r.path("labelField").asText();
        JsonNode c = LsIr.concept(ir, ref);
        if (c != null) for (JsonNode f : c.path("fields")) if ("STRING".equals(f.path("dataType").asText())) return f.path("code").asText();
        return "name";
    }

    private static ObjectNode flow(JsonNode f) {
        ObjectNode o = LsIr.JSON.createObjectNode();
        o.put("code", f.path("code").asText());
        o.put("name", f.path("name").asText());
        o.put("concept", f.path("concept").asText());
        o.put("startOn", f.path("startOn").asText());
        o.set("callbacks", f.path("callbacks"));
        ArrayNode nodes = o.putArray("nodes");
        for (JsonNode n : f.path("nodes")) {
            ObjectNode x = nodes.addObject();
            x.put("key", n.path("key").asText());
            x.put("type", "AUDIT");
            x.put("name", n.path("name").asText());
            x.set("assignee", n.path("assignee"));
            if (n.has("permission")) x.put("permission", n.path("permission").asText());
        }
        if (f.has("form")) o.set("form", f.path("form"));
        return o;
    }

    /** Pack 中的来源记录：IR provenance ＋ 各元素（Concept、字段、子表、状态机与迁移）的来源索引，供按 Concept 查询。 */
    static ObjectNode provenance(JsonNode ir) {
        ObjectNode p = ir.path("provenance").deepCopy();
        ObjectNode el = p.putObject("elements");
        for (JsonNode c : ir.path("concepts")) {
            ObjectNode e = el.putObject(c.path("code").asText());
            e.put("kind", "CONCEPT");
            if (c.has("source")) e.set("source", c.path("source"));
            ObjectNode fs = e.putObject("fields");
            c.path("fields").forEach(f -> { if (f.has("source")) fs.set(f.path("code").asText(), f.path("source")); });
            for (JsonNode ch : c.path("children")) {
                ObjectNode ce = el.putObject(ch.path("code").asText());
                ce.put("kind", "CHILD").put("parent", c.path("code").asText());
                if (ch.has("source")) ce.set("source", ch.path("source"));
                ObjectNode cf = ce.putObject("fields");
                ch.path("fields").forEach(f -> { if (f.has("source")) cf.set(f.path("code").asText(), f.path("source")); });
            }
        }
        for (JsonNode o : ir.path("operations")) {
            ObjectNode e = el.putObject(o.path("code").asText());
            e.put("kind", o.path("kind").asText()).put("concept", o.path("concept").asText());
            if (o.has("source")) e.set("source", o.path("source"));
            for (JsonNode t : o.path("transitions")) {
                ObjectNode te = el.putObject(t.path("operation").asText());
                te.put("kind", "TRANSITION").put("concept", o.path("concept").asText());
                if (t.has("source")) te.set("source", t.path("source"));
            }
        }
        return p;
    }

    private static ObjectNode permissions(JsonNode ir) {
        ObjectNode o = LsIr.JSON.createObjectNode();
        o.put("pack", ir.path("namespace").asText());
        ArrayNode list = o.putArray("permissions");
        java.util.Set<String> declared = new java.util.HashSet<>();
        ir.path("permissions").forEach(p -> {
            list.add(p);
            declared.add(p.path("code").asText());
        });
        // 通用台账权限点（由平台按对象提供；IR 已声明的保留其角色）
        for (JsonNode c : ir.path("concepts")) {
            for (String act : List.of("list", "query", "add", "edit", "remove")) {
                if (declared.contains("crud:" + c.path("code").asText() + ":" + act)) continue;
                list.addObject().put("code", "crud:" + c.path("code").asText() + ":" + act).put("name", c.path("displayName").asText() + " " + act).put("kind", "CRUD");
            }
        }
        return o;
    }

    // —— 代码 ——

    private static String handler(String pkg, String cls, JsonNode o) {
        String schema = o.has("inputSchema") ? o.path("inputSchema").toString() : "{\"type\":\"object\"}";
        return """
            package %s;

            import com.lsmom.action.api.Exposure;
            import com.lsmom.action.api.LsOperation;
            import com.lsmom.action.api.OperationContext;
            import com.lsmom.action.api.OperationHandler;
            import com.lsmom.action.api.Risk;
            import com.lsmom.core.error.LsBizException;

            /**
             * %s（由 %s %s 生成的骨架，须人工实现并评审后才能发布）。
             * %s
             */
            @LsOperation(code = "%s", concept = "%s", name = "%s", risk = Risk.%s, exposure = Exposure.%s, permission = "%s", inputSchema = %s)
            public class %s implements OperationHandler {

                @Override
                public Object handle(OperationContext ctx) {
                    // TODO 实现业务逻辑；写入一律经 Operation / ManagedRecords，不直接改核心表
                    throw new LsBizException("NOT_IMPLEMENTED", "%s 尚未实现");
                }
            }
            """.formatted(pkg, esc(o.path("name").asText()), NAME, VERSION, esc(o.path("description").asText("")), o.path("code").asText(),
            o.path("concept").asText(), esc(o.path("name").asText()), o.path("risk").asText("C1"), o.path("exposure").asText("DENY"),
            o.path("permission").asText(), "\"" + esc(schema) + "\"", cls, esc(o.path("name").asText()));
    }

    private static String junit(String pkg, String code) {
        return """
            package %s;

            import com.lsmom.pack.PackFixture;
            import com.lsmom.pack.PackInstaller;
            import com.lsmom.pack.PackSource;
            import com.lsmom.pack.PackTester;
            import org.junit.jupiter.api.Test;

            import java.nio.file.Path;

            import static org.assertj.core.api.Assertions.assertThat;

            /** 由 %s %s 生成：在临时数据库安装 Pack %s 并运行其黄金场景与越权测试。 */
            class GeneratedPackTest {

                @Test
                void 安装并通过生成的测试() {
                    PackFixture f = new PackFixture();
                    Path dir = Path.of(System.getProperty("lsmom.generated.pack", "../pack"));
                    PackInstaller.Result r = f.installer.install(PackSource.fromDirectory(dir), null);
                    assertThat(r.status()).isIn("INSTALLED", "UPGRADED", "UNCHANGED");
                    PackTester.Report rep = f.tester.run("%s");
                    rep.regression().forEach(c -> assertThat(c.passed()).as(c.name() + "：" + c.message()).isTrue());
                    rep.privilege().forEach(c -> assertThat(c.passed()).as(c.name() + "：" + c.message()).isTrue());
                    assertThat(rep.passed()).isTrue();
                }
            }
            """.formatted(pkg, NAME, VERSION, code, code);
    }

    // —— 工具 ——

    static String yaml(JsonNode n) {
        try {
            return LsIr.YAML.writeValueAsString(n).replaceFirst("^---\\n", "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String json(JsonNode n) {
        try {
            return LsIr.JSON.writeValueAsString(n) + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String last(String code) {
        return code.substring(code.lastIndexOf('.') + 1);
    }

    static String kebab(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }

    static String pascal(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
