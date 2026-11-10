package com.lsmom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.ai.llm.HttpTransport;
import com.lsmom.ai.llm.LlmGateway;
import com.lsmom.ai.llm.RecordedTransport;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 构建期 Copilot：需求说明（经 LLM）/ 字段表格（CSV）/ 旧表结构（DDL）→ 需求规格 → 语义复用检查 → LS-IR。
 * 复用优先：需求中提到的已有对象（如「设备」→ ls.asset.Asset、「人员」→ ls.core.Party）只引用、不新建。
 * LLM 只参与第一步（需求 → 需求规格）；LS-IR 由规则确定性地组装，产物只是文件，须经 generate、测试与人工评审。
 */
public final class BuildCopilot {

    public static final String NAME = "ls-mom-cli copilot";
    public static final String VERSION = "1.0.0";

    static final String SYSTEM_PROMPT = """
        你是制造运营管理系统的需求分析助手。把用户的需求整理为 JSON 需求规格，只输出 JSON 对象，不要解释。格式：
        {"title":"需求标题","entities":[{"name":"中文名","code":"PascalCase 英文编码","description":"说明",
          "fields":[{"name":"中文名","code":"camelCase","type":"string|text|integer|decimal|boolean|date|datetime|enum|file|entity:<对象中文名>",
                     "required":true,"options":[{"code":"大写编码","label":"中文"}]}],
          "states":[{"code":"DRAFT","label":"草稿"}],
          "actions":[{"code":"submit","label":"提交","from":["DRAFT"],"to":"SUBMITTED","role":"角色"}]}],
         "roles":["角色"]}
        规则：引用其他业务对象（设备、人员、组织等）的字段用 type "entity:<对象中文名>"；状态与动作只在需求明确提到流转时给出。""";

    public record Result(JsonNode spec, ObjectNode ir, List<String> reused) {
    }

    /** LS-IR 来源信息（模型等）。 */
    public record Origin(String app, String path, String digest, LlmGateway.Completion completion, String endpoint) {
    }

    private BuildCopilot() {
    }

    static int cli(LsMomCli.Args a, PrintStream out, PrintStream err) {
        Path in = Path.of(a.positional(0, "需求文件（.txt / .md / .csv / .sql）"));
        String ns = a.require("namespace");
        Path outFile = Path.of(a.require("out"));
        List<Path> roots = new ArrayList<>();
        a.all("catalog").forEach(c -> roots.add(Path.of(c)));
        if (roots.isEmpty()) roots.addAll(List.of(Path.of("packs"), Path.of("ls-mom-modules/ls-mom-biz")));
        ReuseCatalog catalog = ReuseCatalog.load(roots);
        String text = read(in);
        String name = in.getFileName().toString().toLowerCase(Locale.ROOT);
        Result r;
        if (name.endsWith(".csv")) {
            r = build(specFromCsv(text, a.option("title", stripExt(in.getFileName().toString()))), ns, catalog,
                new Origin("字段表格", in.toString(), sha256(text), null, null));
        } else if (name.endsWith(".sql")) {
            r = build(specFromDdl(text), ns, catalog, new Origin("旧表结构", in.toString(), sha256(text), null, null));
        } else {
            LlmGateway.Config cfg = LlmGateway.Config.fromEnv(System.getenv(), a.option("endpoint", a.option("replay", null) != null ? "http://replay.invalid" : null),
                a.option("model", null));
            LlmGateway.Transport t = a.option("replay", null) != null ? new RecordedTransport(Path.of(a.option("replay", null)))
                : a.option("record", null) != null ? new RecordedTransport(Path.of(a.option("record", null)), new HttpTransport()) : new HttpTransport();
            r = fromText(new LlmGateway(cfg, t), text, ns, catalog, in.toString());
        }
        List<LsIr.Problem> problems = LsIr.validate(r.ir());
        try {
            Files.createDirectories(outFile.toAbsolutePath().getParent());
            Files.writeString(outFile, LsIr.JSON.writeValueAsString(r.ir()) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.println("已生成 LS-IR：" + outFile + "（新建对象 " + r.ir().path("concepts").size() + " 个，复用 " + r.reused().size() + " 个："
            + String.join("、", r.reused()) + "）");
        int open = r.ir().path("provenance").path("manualRequired").size();
        if (open > 0) out.println("需人工补充 " + open + " 项（见 provenance.manualRequired）");
        if (!problems.isEmpty()) {
            err.println("LS-IR 校验未通过，请人工修正：");
            problems.forEach(p -> err.println("  " + p));
            return 1;
        }
        return 0;
    }

    /** 需求说明 → LLM → 需求规格 → LS-IR。 */
    public static Result fromText(LlmGateway g, String requirement, String ns, ReuseCatalog catalog, String path) {
        LlmGateway.Completion c = g.complete(List.of(LlmGateway.Message.system(SYSTEM_PROMPT), LlmGateway.Message.user(requirement)), true);
        JsonNode spec;
        try {
            spec = LsIr.JSON.readTree(stripFence(c.content()));
        } catch (IOException e) {
            throw new IllegalArgumentException("LLM 返回的需求规格不是有效 JSON：" + e.getMessage());
        }
        String host;
        try {
            host = URI.create(g.config().endpoint()).getHost();
        } catch (RuntimeException e) {
            host = null;
        }
        return build(spec, ns, catalog, new Origin("需求说明", path, sha256(requirement), c, host));
    }

    // —— 表格与旧表结构（不经 LLM） ——

    /** CSV：字段名,编码,类型,必填,选项（选项用 | 分隔，编码:名称）。首行为表头。 */
    public static ObjectNode specFromCsv(String csv, String title) {
        ObjectNode spec = LsIr.JSON.createObjectNode();
        spec.put("title", title);
        ObjectNode e = spec.putArray("entities").addObject();
        e.put("name", title);
        e.put("code", pascal(title.matches("[A-Za-z0-9_ ]+") ? title : "Record"));
        ArrayNode fields = e.putArray("fields");
        String[] lines = csv.strip().split("\\R");
        for (int i = 1; i < lines.length; i++) {
            String[] c = lines[i].split(",", -1);
            if (c.length < 3 || c[0].isBlank()) continue;
            ObjectNode f = fields.addObject();
            f.put("name", c[0].trim());
            f.put("code", camel(c[1].trim().isEmpty() ? "field" + i : c[1].trim()));
            f.put("type", c[2].trim());
            f.put("required", c.length > 3 && List.of("是", "y", "yes", "true", "1").contains(c[3].trim().toLowerCase(Locale.ROOT)));
            if (c.length > 4 && !c[4].isBlank()) {
                ArrayNode opts = f.putArray("options");
                for (String o : c[4].split("\\|")) {
                    String[] kv = o.split(":", 2);
                    opts.addObject().put("code", kv[0].trim().toUpperCase(Locale.ROOT)).put("label", kv.length > 1 ? kv[1].trim() : kv[0].trim());
                }
            }
        }
        return spec;
    }

    private static final Pattern TABLE = Pattern.compile("(?is)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?[\"`]?(\\w+)[\"`]?\\s*\\((.*)\\)\\s*;?");

    /** 旧表结构：create table 的列（类型映射，not null → 必填；comment on column / -- 注释作为中文名）。 */
    public static ObjectNode specFromDdl(String sql) {
        Matcher m = TABLE.matcher(sql);
        if (!m.find()) throw new IllegalArgumentException("没有找到 create table 语句");
        String table = m.group(1);
        ObjectNode spec = LsIr.JSON.createObjectNode();
        spec.put("title", table);
        ObjectNode e = spec.putArray("entities").addObject();
        e.put("name", table);
        e.put("code", pascal(table.replaceFirst("^(t_|tb_)", "")));
        ArrayNode fields = e.putArray("fields");
        List<String[]> defs = new ArrayList<>();
        for (String line : m.group(2).split("\\R")) {
            String l0 = line;
            String label = null;
            int cm = l0.indexOf("--");
            if (cm >= 0) {
                label = l0.substring(cm + 2).trim();
                l0 = l0.substring(0, cm);
            }
            List<String> parts = new ArrayList<>();
            for (String part : l0.split(",(?![^()]*\\))")) if (!part.isBlank()) parts.add(part.trim());
            // 行尾注释属于该行最后一列
            for (int k = 0; k < parts.size(); k++) defs.add(new String[]{parts.get(k), k == parts.size() - 1 ? label : null});
        }
        for (String[] def : defs) {
            String l = def[0];
            String label = def[1];
            Matcher col = Pattern.compile("(?i)^[\"`]?(\\w+)[\"`]?\\s+(\\w+)(\\s*\\([^)]*\\))?(.*)$").matcher(l);
            if (!col.matches() || l.toLowerCase(Locale.ROOT).matches("^(primary|unique|constraint|foreign|key|index|check)\\b.*")) continue;
            String name = col.group(1);
            if (Set.of("id", "created_at", "updated_at", "created_by", "updated_by", "del_flag", "version", "tenant_id").contains(name.toLowerCase(Locale.ROOT))) continue;
            String t = col.group(2).toLowerCase(Locale.ROOT);
            String type = switch (t) {
                case "int", "integer", "bigint", "smallint", "tinyint" -> "integer";
                case "numeric", "decimal", "float", "double", "real" -> "decimal";
                case "boolean", "bool", "bit" -> "boolean";
                case "date" -> "date";
                case "timestamp", "datetime", "timestamptz" -> "datetime";
                case "text", "clob", "longtext" -> "text";
                default -> "string";
            };
            ObjectNode f = fields.addObject();
            f.put("name", label == null || label.isBlank() ? name : label);
            f.put("code", camel(name));
            f.put("type", type);
            f.put("required", col.group(4).toLowerCase(Locale.ROOT).contains("not null"));
        }
        return spec;
    }

    // —— 需求规格 → LS-IR ——

    public static Result build(JsonNode spec, String ns, ReuseCatalog catalog, Origin origin) {
        ObjectNode ir = LsIr.JSON.createObjectNode();
        ir.put("irVersion", LsIr.VERSION);
        ir.put("namespace", ns);
        ObjectNode pack = ir.putObject("pack");
        pack.put("version", "0.1.0");
        pack.put("name", spec.path("title").asText(ns));
        pack.put("description", "由构建期 Copilot 根据" + origin.app() + "生成，待评审");
        pack.putObject("owner").put("name", "待指定负责人");
        pack.putObject("requester").put("name", "需求方");
        pack.put("reviewDue", java.time.LocalDate.now().plusYears(1).toString());
        ArrayNode reuse = ir.putArray("reuse");
        ArrayNode concepts = ir.putArray("concepts");
        ArrayNode views = ir.putArray("views");
        ArrayNode operations = ir.putArray("operations");
        ArrayNode permissions = ir.putArray("permissions");
        ArrayNode tests = ir.putArray("tests");
        ArrayNode manual = LsIr.JSON.createArrayNode();
        Map<String, ReuseCatalog.Entry> reused = new LinkedHashMap<>();
        Map<String, String> own = new LinkedHashMap<>();
        Set<String> deps = new LinkedHashSet<>();

        // 1. 语义复用：需求中的对象若已存在则引用，不新建
        List<JsonNode> creating = new ArrayList<>();
        for (JsonNode e : spec.path("entities")) {
            Optional<ReuseCatalog.Entry> hit = catalog.match(e.path("name").asText());
            if (hit.isPresent()) {
                reused.putIfAbsent(e.path("name").asText(), hit.get());
                if (e.path("fields").size() > 0 && hit.get().pack() != null) {
                    manual.addObject().put("path", "/reuse/" + hit.get().concept()).put("kind", "MANUAL")
                        .put("reason", "需求中的「" + e.path("name").asText() + "」复用已有对象 " + hit.get().concept() + "；如需补充字段，请在依赖该 Pack 的扩展中追加")
                        .put("status", "OPEN");
                }
            } else {
                creating.add(e);
                own.put(e.path("name").asText(), ns + "." + pascal(e.path("code").asText(e.path("name").asText())));
            }
        }
        String permNs = ns.replace('.', ':');
        // 2. 新建对象
        for (JsonNode e : creating) {
            String code = own.get(e.path("name").asText());
            String simple = PackGenerator.last(code);
            ObjectNode c = concepts.addObject();
            c.put("code", code);
            c.put("displayName", e.path("name").asText());
            if (e.has("description")) c.put("description", e.path("description").asText());
            ArrayNode fields = c.putArray("fields");
            boolean requiredRef = false;
            Map<String, JsonNode> sample = new LinkedHashMap<>();
            for (JsonNode f : e.path("fields")) {
                ObjectNode x = fields.addObject();
                x.put("code", camel(f.path("code").asText(f.path("name").asText())));
                x.put("displayName", f.path("name").asText());
                String type = f.path("type").asText("string");
                boolean req = f.path("required").asBoolean(false);
                if (type.startsWith("entity:")) {
                    String target = type.substring("entity:".length()).trim();
                    String ref = own.get(target);
                    if (ref == null) {
                        Optional<ReuseCatalog.Entry> hit = Optional.ofNullable(reused.get(target)).or(() -> catalog.match(target));
                        if (hit.isPresent()) {
                            reused.putIfAbsent(target, hit.get());
                            ref = hit.get().concept();
                        }
                    }
                    if (ref == null) {
                        x.put("dataType", "STRING");
                        manual.addObject().put("path", "/concepts/" + code + "/fields/" + x.path("code").asText()).put("kind", "MANUAL")
                            .put("reason", "无法确定「" + target + "」对应的对象，暂按文本保存").put("status", "OPEN");
                    } else {
                        x.put("dataType", "REF");
                        x.put("refConcept", ref);
                        if (req) requiredRef = true;
                    }
                } else {
                    x.put("dataType", switch (type) {
                        case "text" -> "TEXT";
                        case "integer", "int" -> "INTEGER";
                        case "decimal", "number", "float" -> "DECIMAL";
                        case "boolean", "bool" -> "BOOLEAN";
                        case "date" -> "DATE";
                        case "datetime" -> "DATETIME";
                        case "enum" -> "ENUM";
                        case "file" -> "FILE";
                        default -> "STRING";
                    });
                    if ("ENUM".equals(x.path("dataType").asText())) {
                        ArrayNode opts = x.putArray("options");
                        ObjectNode labels = x.putObject("optionLabels");
                        f.path("options").forEach(o -> {
                            opts.add(o.path("code").asText());
                            labels.put(o.path("code").asText(), o.path("label").asText(o.path("code").asText()));
                        });
                        if (opts.isEmpty()) {
                            x.put("dataType", "STRING");
                            x.remove("options");
                            x.remove("optionLabels");
                        }
                    }
                    if (req) sample.put(x.path("code").asText(), sampleValue(x));
                }
                if (req) x.put("required", true);
                if (fields.size() == 1) x.put("queryable", true);
            }
            if (fields.isEmpty()) fields.addObject().put("code", "title").put("displayName", "名称").put("dataType", "STRING").put("required", true);
            ObjectNode view = views.addObject();
            view.put("code", ns + "." + Character.toLowerCase(simple.charAt(0)) + simple.substring(1) + "Form");
            view.put("concept", code);
            view.put("title", e.path("name").asText());
            ArrayNode vf = view.putArray("fields");
            fields.forEach(f -> vf.addObject().put("key", f.path("code").asText()));
            // 状态与动作
            String initial = null;
            List<String[]> path = new ArrayList<>();
            if (e.path("states").size() > 0) {
                ObjectNode sm = operations.addObject();
                sm.put("kind", "STATE_MACHINE");
                sm.put("code", code + ".lifecycle");
                sm.put("concept", code);
                sm.put("displayName", e.path("name").asText() + "状态");
                ArrayNode states = sm.putArray("states");
                e.path("states").forEach(s -> states.add(s.path("code").asText()));
                initial = states.get(0).asText();
                sm.put("initial", initial);
                ArrayNode ts = sm.putArray("transitions");
                for (JsonNode a : e.path("actions")) {
                    String perm = permNs + ":" + camel(simple).toLowerCase(Locale.ROOT) + ":" + a.path("code").asText();
                    ObjectNode t = ts.addObject();
                    t.put("operation", code + "." + camel(a.path("code").asText()));
                    t.put("name", a.path("label").asText(a.path("code").asText()));
                    t.set("from", a.path("from"));
                    t.put("to", a.path("to").asText());
                    t.put("permission", perm);
                    t.put("risk", "C1");
                    if (!permissions.toString().contains("\"" + perm + "\"")) {
                        ObjectNode p = permissions.addObject().put("code", perm).put("name", e.path("name").asText() + " " + t.path("name").asText()).put("kind", "OPERATION");
                        if (a.hasNonNull("role")) p.putArray("roles").add(a.path("role").asText());
                    }
                }
                // 场景测试沿动作从初始状态走一遍
                String cur = initial;
                Set<String> used = new LinkedHashSet<>();
                boolean moved = true;
                while (moved) {
                    moved = false;
                    for (JsonNode t : ts) {
                        boolean from = false;
                        for (JsonNode s : t.path("from")) from |= s.asText().equals(cur);
                        if (from && used.add(t.path("operation").asText())) {
                            path.add(new String[]{t.path("operation").asText(), t.path("to").asText()});
                            cur = t.path("to").asText();
                            moved = true;
                            break;
                        }
                    }
                }
            }
            // 场景测试：必填引用了已有对象时无法自动造数，须人工补充
            if (requiredRef) {
                manual.addObject().put("path", "/tests/" + code).put("kind", "MANUAL")
                    .put("reason", e.path("name").asText() + "的必填字段引用了已有对象，请补充场景测试数据").put("status", "OPEN");
            } else {
                ObjectNode t = tests.addObject();
                t.put("name", "登记" + e.path("name").asText() + (path.isEmpty() ? "" : "并完成流转"));
                ArrayNode steps = t.putArray("steps");
                ObjectNode values = LsIr.JSON.createObjectNode();
                sample.forEach(values::set);
                if (values.isEmpty()) fields.forEach(f -> {
                    if (values.isEmpty() && !"REF".equals(f.path("dataType").asText())) values.set(f.path("code").asText(), sampleValue(f));
                });
                steps.addObject().putObject("create").put("concept", code).put("as", "r").set("values", values);
                for (String[] p : path) steps.addObject().putObject("op").put("code", p[0]).put("target", "r");
                ObjectNode ex = steps.addObject().putObject("expect").put("concept", code).put("target", "r");
                if (!path.isEmpty()) ex.put("status", path.get(path.size() - 1)[1]);
                else if (initial != null) ex.put("status", initial);
            }
        }
        // 3. 复用清单与依赖
        for (var en : reused.entrySet()) {
            ReuseCatalog.Entry h = en.getValue();
            if (reuse.toString().contains("\"" + h.concept() + "\"")) continue;
            ObjectNode r = reuse.addObject();
            r.put("concept", h.concept());
            r.put("reason", "需求中的「" + en.getKey() + "」对应已有对象「" + h.displayName() + "」" + (h.pack() == null ? "（核心）" : "（" + h.pack() + "）"));
            r.put("term", en.getKey());
            if (h.pack() != null) {
                r.put("pack", h.pack());
                deps.add(h.pack() + "@" + h.packVersion());
            }
            r.put("labelField", h.labelField());
        }
        if (!deps.isEmpty()) {
            ArrayNode d = pack.putArray("dependsOn");
            for (String x : deps) {
                String[] kv = x.split("@");
                int major = Integer.parseInt(kv[1].split("\\.")[0]);
                d.addObject().put("code", kv[0]).put("version", ">=" + kv[1] + " <" + (major + 1) + ".0.0");
            }
        }
        ir.putArray("flows");
        ir.putArray("rules");
        ir.putArray("metrics");
        ir.putArray("migrations");
        // 4. 来源
        ObjectNode prov = ir.putObject("provenance");
        prov.put("sourceType", origin.app().equals("旧表结构") ? "LEGACY_SCHEMA" : "REQUIREMENT");
        ObjectNode src = prov.putObject("source");
        src.put("app", origin.app());
        if (origin.path() != null) src.put("path", origin.path());
        src.put("digest", origin.digest());
        prov.put("license", "LicenseRef-Customer-Owned");
        prov.putObject("converter").put("name", NAME).put("version", VERSION);
        if (origin.completion() != null) {
            ObjectNode model = prov.putObject("model");
            model.put("name", origin.completion().model());
            model.put("version", origin.completion().modelVersion());
            if (origin.endpoint() != null) model.put("endpoint", origin.endpoint());
            model.put("promptDigest", origin.completion().requestDigest());
        } else {
            prov.putNull("model");
        }
        prov.put("generatedAt", Instant.now().toString());
        prov.putArray("manualEdits");
        prov.set("manualRequired", manual);
        prov.putObject("metrics").put("requirementAt", Instant.now().toString()).put("reused", reused.size()).put("created", creating.size());
        return new Result(spec, ir, reused.values().stream().map(ReuseCatalog.Entry::concept).distinct().toList());
    }

    private static JsonNode sampleValue(JsonNode f) {
        return switch (f.path("dataType").asText()) {
            case "INTEGER" -> LsIr.JSON.getNodeFactory().numberNode(1);
            case "DECIMAL" -> LsIr.JSON.getNodeFactory().numberNode(1.5);
            case "BOOLEAN" -> LsIr.JSON.getNodeFactory().booleanNode(true);
            case "DATE" -> LsIr.JSON.getNodeFactory().textNode("2026-01-01");
            case "DATETIME" -> LsIr.JSON.getNodeFactory().textNode("2026-01-01T08:00:00");
            case "ENUM" -> f.path("options").get(0);
            default -> LsIr.JSON.getNodeFactory().textNode("测试" + f.path("displayName").asText());
        };
    }

    static String stripFence(String s) {
        String t = s.strip();
        if (t.startsWith("```")) {
            t = t.substring(t.indexOf('\n') + 1);
            int end = t.lastIndexOf("```");
            if (end >= 0) t = t.substring(0, end);
        }
        return t.strip();
    }

    static String camel(String s) {
        String[] parts = s.replaceAll("[^A-Za-z0-9]+", "_").split("_");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            b.append(b.isEmpty() ? Character.toLowerCase(p.charAt(0)) + p.substring(1) : Character.toUpperCase(p.charAt(0)) + p.substring(1));
        }
        String r = b.isEmpty() ? "field" : b.toString();
        return Character.isDigit(r.charAt(0)) ? "f" + r : r;
    }

    static String pascal(String s) {
        String c = camel(s);
        return Character.toUpperCase(c.charAt(0)) + c.substring(1);
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
