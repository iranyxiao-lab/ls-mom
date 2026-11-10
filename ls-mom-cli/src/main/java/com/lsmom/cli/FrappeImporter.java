package com.lsmom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Frappe 资产静态转换为 LS-IR：只读取 JSON，不执行任何 Python、Server Script 或 Client Script。
 * <ul>
 *   <li>DocType → Concept（istable=1 的子 DocType → 子表；Table 字段 → children）；字段类型按映射表转换；</li>
 *   <li>Workflow → 状态机（状态、迁移、允许角色 → 权限点；初始状态映射为平台的 DRAFT）；迁移条件（Python 表达式）标记需人工改写；</li>
 *   <li>DocPerm → 通用台账权限点与角色；permlevel、if_owner 等无法确认语义的标记需人工；</li>
 *   <li>Report（Report Builder）→ 计数指标；Query / Script Report 需人工；Fixture → 种子；Patch 与其他 .py → 需人工；</li>
 *   <li>Server Script、Client Script → 禁止自动转换；许可证未知或不兼容（GPL 系）→ 整体禁止自动转换。</li>
 * </ul>
 * 每个元素记录来源（DocType 与字段名、文件路径）；整体记录来源应用与版本、许可证、转换器版本。
 */
public final class FrappeImporter {

    public static final String NAME = "ls-mom-cli frappe-import";
    public static final String VERSION = "1.0.0";
    /** 允许自动转换的许可证（SPDX）。 */
    static final Set<String> PERMISSIVE = Set.of("MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC");
    static final Set<String> RESERVED = Set.of("id", "status", "version", "orgId", "createdBy", "createdAt", "updatedBy", "updatedAt", "delFlag", "extAttrs");

    public record Options(String namespace, String app, String version, String license) {
    }

    private FrappeImporter() {
    }

    static int cli(LsMomCli.Args a, PrintStream out, PrintStream err) {
        Path dir = Path.of(a.positional(0, "Frappe 资产目录"));
        Options o = new Options(a.require("namespace"), a.option("app", null), a.option("version", null), a.option("license", null));
        ObjectNode ir = convert(dir, o);
        Path outFile = Path.of(a.require("out"));
        try {
            Files.createDirectories(outFile.toAbsolutePath().getParent());
            Files.writeString(outFile, LsIr.JSON.writeValueAsString(ir) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        JsonNode mr = ir.path("provenance").path("manualRequired");
        long blocked = 0;
        for (JsonNode m : mr) if ("BLOCKED".equals(m.path("kind").asText())) blocked++;
        out.println("已转换：" + outFile + "（对象 " + ir.path("concepts").size() + "，状态机 " + ir.path("operations").size() + "，需人工 " + mr.size()
            + "，其中禁止自动转换 " + blocked + "）");
        List<LsIr.Problem> problems = LsIr.validate(ir);
        if (!problems.isEmpty()) {
            err.println("LS-IR 校验未通过：");
            problems.forEach(p -> err.println("  " + p));
            return 1;
        }
        return 0;
    }

    public static ObjectNode convert(Path dir, Options opt) {
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ArrayNode manual = LsIr.JSON.createArrayNode();
        Map<String, JsonNode> doctypes = new LinkedHashMap<>();
        Map<String, String> doctypeFile = new LinkedHashMap<>();
        List<JsonNode> workflows = new ArrayList<>(), reports = new ArrayList<>(), fixtures = new ArrayList<>();
        Map<JsonNode, String> fileOf = new LinkedHashMap<>();
        for (Path f : files) {
            String rel = dir.relativize(f).toString().replace('\\', '/');
            String name = f.getFileName().toString();
            if (name.endsWith(".py")) {
                String kind = rel.contains("patches/") ? "Patch " : "Python 代码";
                manual.add(item(rel, "MANUAL", kind + "不执行、不自动转换，须人工评估并改写为 Operation / 规则"));
                continue;
            }
            if (name.endsWith(".js")) {
                manual.add(item(rel, "BLOCKED", "Client Script / 前端脚本禁止自动转换"));
                continue;
            }
            if (!name.endsWith(".json")) continue;
            JsonNode n;
            try {
                n = LsIr.JSON.readTree(Files.readString(f));
            } catch (IOException e) {
                manual.add(item(rel, "MANUAL", "不是有效的 JSON：" + e.getMessage()));
                continue;
            }
            if (n.isArray()) {
                if (n.size() > 0 && n.get(0).has("doctype")) {
                    fixtures.add(n);
                    fileOf.put(n, rel);
                }
                continue;
            }
            switch (n.path("doctype").asText()) {
                case "DocType" -> {
                    doctypes.put(n.path("name").asText(), n);
                    doctypeFile.put(n.path("name").asText(), rel);
                }
                case "Workflow" -> {
                    workflows.add(n);
                    fileOf.put(n, rel);
                }
                case "Report" -> {
                    reports.add(n);
                    fileOf.put(n, rel);
                }
                case "Server Script" -> manual.add(item(rel, "BLOCKED", "Server Script「" + n.path("name").asText() + "」禁止自动转换（含可执行 Python）"));
                case "Client Script" -> manual.add(item(rel, "BLOCKED", "Client Script「" + n.path("name").asText() + "」禁止自动转换"));
                default -> {
                }
            }
        }

        // 许可证：参数优先，否则识别 LICENSE 文件；未知或 GPL 系禁止自动转换
        String license = opt.license() != null ? opt.license() : detectLicense(dir);
        if (!PERMISSIVE.contains(license)) {
            manual.add(item("/license", "BLOCKED", "许可证 " + license + " 未知或与本产品不兼容，禁止自动转换"));
        }

        String ns = opt.namespace();
        ObjectNode ir = LsIr.JSON.createObjectNode();
        ir.put("irVersion", LsIr.VERSION);
        ir.put("namespace", ns);
        ObjectNode pack = ir.putObject("pack");
        pack.put("version", "0.1.0");
        String app = opt.app() != null ? opt.app() : dir.getFileName().toString();
        pack.put("name", "Frappe 转换：" + app);
        pack.put("description", "由 " + NAME + " 从 " + app + " 静态转换，待人工评审");
        pack.putObject("owner").put("name", "待指定负责人");
        pack.putObject("requester").put("name", "转换任务");
        pack.put("reviewDue", java.time.LocalDate.now().plusYears(1).toString());
        ArrayNode reuse = ir.putArray("reuse");
        ArrayNode concepts = ir.putArray("concepts");
        ArrayNode views = ir.putArray("views");
        ir.putArray("flows");
        ArrayNode operations = ir.putArray("operations");
        ir.putArray("rules");
        ArrayNode permissions = ir.putArray("permissions");
        ArrayNode metrics = ir.putArray("metrics");
        ir.putArray("migrations");
        ArrayNode seeds = ir.putArray("seeds");
        ArrayNode tests = ir.putArray("tests");
        Set<String> reused = new LinkedHashSet<>();

        Map<String, String> codeOf = new LinkedHashMap<>();
        doctypes.forEach((n, d) -> codeOf.put(n, ns + "." + BuildCopilot.pascal(n.replace(" ", "_"))));
        Map<String, JsonNode> workflowOf = new LinkedHashMap<>();
        workflows.forEach(w -> workflowOf.put(w.path("document_type").asText(), w));
        String permNs = ns.replace('.', ':');

        for (var en : doctypes.entrySet()) {
            JsonNode d = en.getValue();
            if (d.path("istable").asInt(0) == 1) continue;
            String dt = en.getKey();
            String code = codeOf.get(dt);
            String rel = doctypeFile.get(dt);
            JsonNode wf = workflowOf.get(dt);
            String stateField = wf == null ? null : wf.path("workflow_state_field").asText("workflow_state");
            ObjectNode c = concepts.addObject();
            c.put("code", code);
            c.put("displayName", dt);
            if (d.hasNonNull("description")) c.put("description", d.path("description").asText());
            c.putObject("source").put("type", "DocType").put("name", dt).put("path", rel);
            ArrayNode fields = c.putArray("fields");
            ArrayNode children = LsIr.JSON.createArrayNode();
            Map<String, JsonNode> sample = new LinkedHashMap<>();
            boolean requiredRef = false;
            for (JsonNode f : ordered(d)) {
                String fname = f.path("fieldname").asText();
                if (fname.equals(stateField)) continue;
                String ft = f.path("fieldtype").asText();
                if (ft.equals("Table")) {
                    JsonNode child = doctypes.get(f.path("options").asText());
                    if (child == null) {
                        manual.add(item(rel + "#" + fname, "MANUAL", "子表 DocType「" + f.path("options").asText() + "」不在转换包中"));
                        continue;
                    }
                    ObjectNode ch = children.addObject();
                    ch.put("code", ns + "." + BuildCopilot.pascal(f.path("options").asText().replace(" ", "_")));
                    ch.put("displayName", f.path("label").asText(f.path("options").asText()));
                    ch.putObject("source").put("type", "DocType").put("name", f.path("options").asText()).put("field", fname);
                    ArrayNode cf = ch.putArray("fields");
                    for (JsonNode x : ordered(child)) mapField(x, cf, doctypeFile.getOrDefault(child.path("name").asText(), rel), codeOf, reused, manual, null);
                    if (cf.isEmpty()) children.remove(children.size() - 1);
                    continue;
                }
                ObjectNode x = mapField(f, fields, rel, codeOf, reused, manual, null);
                if (x == null) continue;
                if (x.path("required").asBoolean(false)) {
                    if ("REF".equals(x.path("dataType").asText())) requiredRef = true;
                    else sample.put(x.path("code").asText(), sampleValue(x));
                }
            }
            if (fields.isEmpty()) fields.addObject().put("code", "title").put("displayName", "名称").put("dataType", "STRING");
            if (!children.isEmpty()) c.set("children", children);
            // 视图：按 field_order
            ObjectNode view = views.addObject();
            String simple = PackGenerator.last(code);
            view.put("code", ns + "." + Character.toLowerCase(simple.charAt(0)) + simple.substring(1) + "Form");
            view.put("concept", code);
            view.put("title", dt);
            ArrayNode vf = view.putArray("fields");
            fields.forEach(x -> vf.addObject().put("key", x.path("code").asText()));
            children.forEach(x -> vf.addObject().put("key", LsIr.childKey(x.path("code").asText())));

            // DocPerm → 通用台账权限点与角色
            Map<String, Set<String>> rolesBy = new LinkedHashMap<>();
            for (JsonNode p : d.path("permissions")) {
                String role = p.path("role").asText();
                if (p.path("permlevel").asInt(0) > 0 || p.path("if_owner").asInt(0) == 1) {
                    manual.add(item(rel + "#permissions/" + role, "MANUAL", "权限「" + role + "」含 permlevel / 仅本人（if_owner）语义，须人工映射到字段权限与数据域"));
                }
                for (String[] m : new String[][]{{"read", "list"}, {"read", "query"}, {"create", "add"}, {"write", "edit"}, {"delete", "remove"}}) {
                    if (p.path(m[0]).asInt(0) == 1) rolesBy.computeIfAbsent(m[1], k -> new LinkedHashSet<>()).add(role);
                }
            }
            rolesBy.forEach((act, roles) -> {
                ObjectNode pp = permissions.addObject().put("code", "crud:" + code + ":" + act).put("name", dt + " " + act).put("kind", "CRUD");
                ArrayNode rs = pp.putArray("roles");
                roles.forEach(rs::add);
            });

            // Workflow → 状态机
            List<String[]> path = new ArrayList<>();
            String initial = null;
            if (wf != null) {
                String wfRel = fileOf.get(wf);
                ObjectNode sm = operations.addObject();
                sm.put("kind", "STATE_MACHINE");
                sm.put("code", code + ".lifecycle");
                sm.put("concept", code);
                sm.put("displayName", wf.path("name").asText(dt + " 工作流"));
                sm.putObject("source").put("type", "Workflow").put("name", wf.path("name").asText()).put("path", wfRel);
                ArrayNode states = sm.putArray("states");
                Map<String, String> stateCode = new LinkedHashMap<>();
                for (JsonNode s : wf.path("states")) {
                    // 平台新建记录的状态固定为 DRAFT：Frappe 工作流的初始状态（第一个状态）映射为 DRAFT
                    String sc = states.isEmpty() ? "DRAFT" : stateCode(s.path("state").asText());
                    stateCode.put(s.path("state").asText(), sc);
                    states.add(sc);
                }
                initial = states.isEmpty() ? null : states.get(0).asText();
                sm.put("initial", initial);
                Map<String, ObjectNode> byAction = new LinkedHashMap<>();
                ArrayNode ts = sm.putArray("transitions");
                int ti = 0;
                for (JsonNode t : wf.path("transitions")) {
                    String action = t.path("action").asText();
                    String op = code + "." + BuildCopilot.camel(action);
                    String perm = permNs + ":" + simple.toLowerCase(Locale.ROOT) + ":" + BuildCopilot.camel(action).toLowerCase(Locale.ROOT);
                    ObjectNode tr = byAction.get(op + "->" + t.path("next_state").asText());
                    ObjectNode existing = byAction.values().stream().filter(v -> v.path("operation").asText().equals(op)).findFirst().orElse(null);
                    if (existing != null && !existing.path("to").asText().equals(stateCode.get(t.path("next_state").asText()))) {
                        manual.add(item(wfRel + "#transitions/" + ti, "MANUAL", "动作「" + action + "」在不同状态下去往不同状态，须人工拆分"));
                        ti++;
                        continue;
                    }
                    if (existing != null) tr = existing;
                    if (tr == null) {
                        tr = ts.addObject();
                        tr.put("operation", op);
                        tr.put("name", action);
                        tr.putArray("from");
                        tr.put("to", stateCode.get(t.path("next_state").asText()));
                        tr.put("permission", perm);
                        tr.put("risk", "C1");
                        tr.putObject("source").put("type", "Workflow Transition").put("name", action).put("path", wfRel);
                        byAction.put(op + "->" + t.path("next_state").asText(), tr);
                        if (!permissions.toString().contains("\"" + perm + "\"")) {
                            permissions.addObject().put("code", perm).put("name", dt + " " + action).put("kind", "OPERATION").putArray("roles");
                        }
                    }
                    ((ArrayNode) tr.get("from")).add(stateCode.get(t.path("state").asText()));
                    for (JsonNode p : permissions) {
                        if (p.path("code").asText().equals(perm) && !p.path("roles").toString().contains("\"" + t.path("allowed").asText() + "\"")) {
                            ((ArrayNode) p.path("roles")).add(t.path("allowed").asText());
                        }
                    }
                    if (t.hasNonNull("condition") && !t.path("condition").asText().isBlank()) {
                        manual.add(item(wfRel + "#transitions/" + ti, "MANUAL", "迁移「" + action + "」的条件是 Python 表达式（" + t.path("condition").asText()
                            + "），未执行，须人工改写为 CEL 规则"));
                        tr.put("conditional", true);
                    }
                    ti++;
                }
                // 场景路径：从初始状态沿无条件迁移走
                String cur = initial;
                Set<String> used = new LinkedHashSet<>();
                boolean moved = true;
                while (moved) {
                    moved = false;
                    for (JsonNode t : ts) {
                        if (t.path("conditional").asBoolean(false)) continue;
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
                ts.forEach(t -> ((ObjectNode) t).remove("conditional"));
            }
            // 场景测试
            if (requiredRef) {
                manual.add(item("/tests/" + code, "MANUAL", dt + " 的必填字段引用了其他对象，须人工补充场景测试数据"));
            } else {
                ObjectNode t = tests.addObject();
                t.put("name", "登记" + dt + (path.isEmpty() ? "" : "并按工作流流转"));
                ArrayNode steps = t.putArray("steps");
                ObjectNode values = LsIr.JSON.createObjectNode();
                sample.forEach(values::set);
                if (values.isEmpty()) {
                    for (JsonNode x : fields) {
                        if (!"REF".equals(x.path("dataType").asText()) && !"FILE".equals(x.path("dataType").asText())) {
                            values.set(x.path("code").asText(), sampleValue(x));
                            break;
                        }
                    }
                }
                steps.addObject().putObject("create").put("concept", code).put("as", "r").set("values", values);
                for (String[] p : path) steps.addObject().putObject("op").put("code", p[0]).put("target", "r");
                ObjectNode ex = steps.addObject().putObject("expect").put("concept", code).put("target", "r");
                if (!path.isEmpty()) ex.put("status", path.get(path.size() - 1)[1]);
            }
        }
        // 未被引用的子表 DocType 只作为子表
        for (JsonNode w : workflows) {
            if (!doctypes.containsKey(w.path("document_type").asText())) {
                manual.add(item(fileOf.get(w), "MANUAL", "Workflow 所属 DocType「" + w.path("document_type").asText() + "」不在转换包中"));
            }
        }
        // Report
        for (JsonNode r : reports) {
            String rel = fileOf.get(r);
            String type = r.path("report_type").asText();
            String ref = codeOf.get(r.path("ref_doctype").asText());
            if ("Report Builder".equals(type) && ref != null) {
                String mc = ns + "." + BuildCopilot.camel(r.path("name").asText().replace(" ", "_"));
                ObjectNode m = metrics.addObject();
                m.put("code", mc);
                m.put("name", r.path("name").asText());
                m.put("concept", ref);
                m.putObject("measure").put("fn", "count");
                m.put("timeField", "createdAt");
                m.putArray("grains").add("DAY");
                m.putArray("dimensions").add("status");
            } else if ("Script Report".equals(type)) {
                manual.add(item(rel, "BLOCKED", "Script Report「" + r.path("name").asText() + "」含 Python，禁止自动转换"));
            } else {
                manual.add(item(rel, "MANUAL", type + "「" + r.path("name").asText() + "」须人工改写为指标口径"));
            }
        }
        // Fixture → 种子
        for (JsonNode fx : fixtures) {
            String dt = fx.get(0).path("doctype").asText();
            String concept = codeOf.get(dt);
            if (concept == null) {
                manual.add(item(fileOf.get(fx), "MANUAL", "Fixture 的 DocType「" + dt + "」不在转换包中"));
                continue;
            }
            JsonNode d = doctypes.get(dt);
            Map<String, String> map = new LinkedHashMap<>();
            for (JsonNode f : d.path("fields")) map.put(f.path("fieldname").asText(), fieldCode(f.path("fieldname").asText()));
            JsonNode conceptNode = LsIr.concept(ir, concept);
            Map<String, JsonNode> mapped = new LinkedHashMap<>();
            conceptNode.path("fields").forEach(f -> mapped.put(f.path("code").asText(), f));
            ObjectNode seed = seeds.addObject();
            seed.put("concept", concept);
            String key = null;
            ArrayNode rows = seed.putArray("rows");
            for (JsonNode row : fx) {
                ObjectNode o = rows.addObject();
                row.fields().forEachRemaining(e -> {
                    String fc = map.get(e.getKey());
                    if (fc == null || !mapped.containsKey(fc) || "REF".equals(mapped.get(fc).path("dataType").asText())) return;
                    JsonNode f = mapped.get(fc);
                    if ("ENUM".equals(f.path("dataType").asText())) o.put(fc, optionCode(e.getValue().asText()));
                    else o.set(fc, e.getValue());
                });
            }
            for (JsonNode f : conceptNode.path("fields")) {
                if ("STRING".equals(f.path("dataType").asText()) || "TEXT".equals(f.path("dataType").asText())) {
                    key = f.path("code").asText();
                    if (f.path("required").asBoolean(false)) break;
                }
            }
            seed.put("key", key == null ? conceptNode.path("fields").get(0).path("code").asText() : key);
        }
        reused.forEach(r -> reuse.addObject().put("concept", r).put("reason", "Frappe User 映射为平台人员").put("term", "User").put("labelField", "name"));

        ObjectNode prov = ir.putObject("provenance");
        prov.put("sourceType", "FRAPPE");
        ObjectNode src = prov.putObject("source");
        src.put("app", app);
        if (opt.version() != null) src.put("version", opt.version());
        src.put("path", dir.getFileName().toString());
        prov.put("license", license);
        prov.putObject("converter").put("name", NAME).put("version", VERSION);
        prov.putNull("model");
        prov.put("generatedAt", Instant.now().toString());
        prov.putArray("manualEdits");
        prov.set("manualRequired", manual);
        return ir;
    }

    private static ObjectNode item(String path, String kind, String reason) {
        return LsIr.JSON.createObjectNode().put("path", path).put("kind", kind).put("reason", reason).put("status", "OPEN");
    }

    /** 按 field_order 排列字段（没有 field_order 时按文件顺序）。 */
    private static List<JsonNode> ordered(JsonNode d) {
        List<JsonNode> fields = new ArrayList<>();
        d.path("fields").forEach(fields::add);
        if (!d.has("field_order")) return fields;
        List<String> order = new ArrayList<>();
        d.path("field_order").forEach(x -> order.add(x.asText()));
        fields.sort((a, b) -> Integer.compare(idx(order, a), idx(order, b)));
        return fields;
    }

    private static int idx(List<String> order, JsonNode f) {
        int i = order.indexOf(f.path("fieldname").asText());
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    static String fieldCode(String fieldname) {
        String c = BuildCopilot.camel(fieldname);
        return RESERVED.contains(c) ? c + "Value" : c;
    }

    /** 字段映射；布局字段返回 null。 */
    private static ObjectNode mapField(JsonNode f, ArrayNode out, String rel, Map<String, String> codeOf, Set<String> reused, ArrayNode manual, Void unused) {
        String ft = f.path("fieldtype").asText();
        String fname = f.path("fieldname").asText();
        String dt;
        switch (ft) {
            case "Section Break", "Column Break", "Tab Break", "HTML", "Button", "Heading", "Image", "Fold", "Read Only" -> {
                return null;
            }
            case "Data", "Color", "Autocomplete", "Phone", "Dynamic Link" -> dt = "STRING";
            case "Small Text", "Text", "Long Text", "Text Editor", "Markdown Editor", "HTML Editor", "Code" -> dt = "TEXT";
            case "Int", "Duration" -> dt = "INTEGER";
            case "Float", "Currency", "Percent", "Rating" -> dt = "DECIMAL";
            case "Check" -> dt = "BOOLEAN";
            case "Date" -> dt = "DATE";
            case "Datetime" -> dt = "DATETIME";
            case "Select" -> dt = "ENUM";
            case "Attach", "Attach Image", "Signature" -> dt = "FILE";
            case "JSON", "Geolocation" -> dt = "JSON";
            case "Link" -> dt = "REF";
            case "Password" -> {
                manual.add(item(rel + "#" + fname, "BLOCKED", "密码字段「" + fname + "」禁止自动转换"));
                return null;
            }
            default -> {
                manual.add(item(rel + "#" + fname, "MANUAL", "字段类型 " + ft + "（" + fname + "）无对应映射，暂按文本保存"));
                dt = "STRING";
            }
        }
        ObjectNode x = out.addObject();
        x.put("code", fieldCode(fname));
        x.put("displayName", f.path("label").asText(fname));
        if (dt.equals("REF")) {
            String target = f.path("options").asText();
            String ref = "User".equals(target) ? "ls.core.Party" : codeOf.get(target);
            if (ref == null) {
                dt = "STRING";
                manual.add(item(rel + "#" + fname, "MANUAL", "Link 到「" + target + "」（不在转换包中），暂按文本保存，须人工映射"));
            } else {
                x.put("refConcept", ref);
                if (ref.startsWith("ls.core.")) reused.add(ref);
            }
        }
        if (ft.equals("Dynamic Link")) manual.add(item(rel + "#" + fname, "MANUAL", "Dynamic Link（" + fname + "）不支持，暂按文本保存"));
        x.put("dataType", dt);
        if ("ENUM".equals(dt)) {
            ArrayNode opts = x.putArray("options");
            ObjectNode labels = x.putObject("optionLabels");
            for (String o : f.path("options").asText("").split("\\R")) {
                if (o.isBlank()) continue;
                String oc = optionCode(o);
                if (!opts.toString().contains("\"" + oc + "\"")) {
                    opts.add(oc);
                    labels.put(oc, o.trim());
                }
            }
            if (opts.isEmpty()) {
                x.put("dataType", "STRING");
                x.remove("options");
                x.remove("optionLabels");
            }
        }
        if ("STRING".equals(x.path("dataType").asText())) x.put("maxLength", f.path("length").asInt(0) > 0 ? f.path("length").asInt() : 140);
        if (f.path("reqd").asInt(0) == 1) x.put("required", true);
        if (f.path("in_list_view").asInt(0) == 1 || f.path("in_standard_filter").asInt(0) == 1) x.put("queryable", true);
        if (f.path("unique").asInt(0) == 1) x.put("unique", true);
        x.putObject("source").put("type", "DocField").put("name", f.path("parent").asText(rel)).put("field", fname).put("path", rel);
        return x;
    }

    static String optionCode(String label) {
        String c = label.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        return c.isEmpty() ? "OPT" + Math.abs(label.hashCode()) : (Character.isDigit(c.charAt(0)) ? "V" + c : c);
    }

    static String stateCode(String state) {
        String c = optionCode(state);
        return Character.isLetter(c.charAt(0)) ? c : "S" + c;
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

    /** 识别 LICENSE / license.txt 文本；无法识别为 UNKNOWN。 */
    static String detectLicense(Path dir) {
        for (String n : List.of("LICENSE", "LICENSE.txt", "license.txt", "LICENSE.md")) {
            Path p = dir.resolve(n);
            if (!Files.exists(p)) continue;
            String t;
            try {
                t = Files.readString(p);
            } catch (IOException e) {
                return "UNKNOWN";
            }
            String head = t.length() > 600 ? t.substring(0, 600) : t;
            if (head.contains("GNU AFFERO GENERAL PUBLIC LICENSE")) return "AGPL-3.0";
            if (head.contains("GNU LESSER GENERAL PUBLIC LICENSE")) return "LGPL-3.0";
            if (head.contains("GNU GENERAL PUBLIC LICENSE")) return "GPL-3.0";
            if (head.contains("MIT License") || head.contains("Permission is hereby granted, free of charge")) return "MIT";
            if (head.contains("Apache License")) return "Apache-2.0";
            if (head.contains("Redistribution and use in source and binary forms")) return "BSD-3-Clause";
            return "UNKNOWN";
        }
        return "UNKNOWN";
    }

}
