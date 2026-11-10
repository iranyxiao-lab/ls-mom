package com.lsmom.pack.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.core.error.LsBizException;
import com.lsmom.pack.PackJson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 由台账 LS-IR 片段生成完整 Pack 草稿（只有 YAML/JSON）：Concept（含子表）、审批状态机、表单（含节点字段策略）、列表、审批流、规则与黄金场景。
 * 片段来自模板库或 Excel 推断，结构：
 * <pre>
 * entity: LoanOrder            Concept 名（大驼峰）
 * entityName: 借还单            显示名
 * fields: [ {code, displayName, dataType, required, refConcept, options, props} ]
 * children: [ {entity, displayName, minRows, fields} ]
 * flow: { name, nodes: [ {key, name, type, role, editable, required} ], initiatorEditable }
 * rules: [ {code, name, when, assert, field, message} ]
 * list: { columns, filters }
 * </pre>
 * 生成的状态机：草稿 → 审核中 → 已通过／已退回／已驳回，可撤回、退回后再提交；审批流节点按顺序办理，最后一个节点通过即"已通过"。
 */
public final class LedgerGenerator {

    private static final Pattern NS = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+$");
    private static final Pattern ENTITY = Pattern.compile("^[A-Z][A-Za-z0-9]{1,40}$");
    private static final Pattern CODE = Pattern.compile("^[a-z][A-Za-z0-9]{0,40}$");
    private static final Set<String> TYPES = Set.of("STRING", "TEXT", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "DATETIME", "ENUM", "REF", "FILE");
    /** 系统保留字段，片段里不能再声明 */
    private static final Set<String> RESERVED = Set.of("id", "status", "version", "orgId", "createdBy", "createdAt", "updatedBy", "updatedAt",
        "schemaVersion", "deleted");

    private LedgerGenerator() {
    }

    /** 业务负责人、需求提出人（Pack 清单必填）、复审日期与 Pack 版本（空为 1.0.0；同一编码已安装时由调用方给出下一版本） */
    public record Meta(String owner, String requester, java.time.LocalDate reviewDue, String version) {
        public Meta(String owner, String requester, java.time.LocalDate reviewDue) {
            this(owner, requester, reviewDue, null);
        }
    }

    /** 生成 Pack 文件（路径 → 内容）。ns 为 Pack 编码（如 acme.loan），title 为应用名称。 */
    public static Map<String, byte[]> generate(JsonNode fragment, String ns, String title, Meta meta) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (meta == null || meta.owner() == null || meta.owner().isBlank()) errors.put("owner", "请填写业务负责人");
        if (meta == null || meta.requester() == null || meta.requester().isBlank()) errors.put("requester", "请填写需求提出人");
        if (ns == null || !NS.matcher(ns).matches()) errors.put("ns", "编码须为小写字母、数字和点组成的命名空间，如 acme.loan");
        if (title == null || title.isBlank()) errors.put("title", "请填写应用名称");
        String entity = fragment.path("entity").asText("");
        if (!ENTITY.matcher(entity).matches()) errors.put("entity", "对象名须为英文大驼峰，如 LoanOrder");
        JsonNode fields = fragment.path("fields");
        if (!fields.isArray() || fields.isEmpty()) errors.put("fields", "至少需要一个字段");
        validateFields(fields, "fields", errors);
        Set<String> childKeys = new LinkedHashSet<>();
        for (int i = 0; i < fragment.path("children").size(); i++) {
            JsonNode c = fragment.path("children").get(i);
            if (!ENTITY.matcher(c.path("entity").asText("")).matches()) errors.put("children." + i + ".entity", "子表对象名须为英文大驼峰");
            if (!childKeys.add(lowerFirst(c.path("entity").asText("")))) errors.put("children." + i + ".entity", "子表重复");
            validateFields(c.path("fields"), "children." + i + ".fields", errors);
        }
        if (!errors.isEmpty()) throw LsBizException.validation(errors);

        String concept = ns + "." + entity;
        String perm = ns.replace('.', ':');
        String entityName = fragment.path("entityName").asText(title);
        String base = kebab(entity);
        String formCode = ns + "." + lowerFirst(entity) + "Form";
        String listCode = ns + "." + lowerFirst(entity) + "List";
        String flowCode = ns + "." + lowerFirst(entity) + "Approval";
        List<JsonNode> nodes = new ArrayList<>();
        fragment.path("flow").path("nodes").forEach(nodes::add);
        if (nodes.isEmpty()) {
            ObjectNode n = PackJson.JSON.createObjectNode();
            n.put("key", "audit").put("name", "审核").put("type", "AUDIT").put("role", "workshop_leader");
            nodes.add(n);
        }
        Map<String, byte[]> files = new LinkedHashMap<>();

        // —— Concept ——
        ObjectNode c = PackJson.JSON.createObjectNode();
        c.put("code", concept);
        c.put("displayName", entityName);
        c.put("description", fragment.path("description").asText(entityName + "登记、审批与查询"));
        c.set("fields", conceptFields(fields));
        if (fragment.path("children").size() > 0) {
            ArrayNode ch = c.putArray("children");
            for (JsonNode cs : fragment.path("children")) {
                ObjectNode o = ch.addObject();
                o.put("code", ns + "." + cs.path("entity").asText());
                o.put("displayName", cs.path("displayName").asText(cs.path("entity").asText()));
                o.set("fields", conceptFields(cs.path("fields")));
            }
        }
        files.put("concepts/" + base + ".yaml", yaml(c));

        // —— 状态机 ——
        ObjectNode sm = PackJson.JSON.createObjectNode();
        sm.put("code", concept + ".lifecycle");
        sm.put("concept", concept);
        sm.put("displayName", entityName + "状态");
        sm.set("states", array("DRAFT", "IN_REVIEW", "RETURNED", "APPROVED", "REJECTED"));
        sm.put("initial", "DRAFT");
        ArrayNode tr = sm.putArray("transitions");
        transition(tr, concept + ".submit", "提交", List.of("DRAFT"), "IN_REVIEW", perm + ":submit");
        transition(tr, concept + ".audit", "审核", List.of("IN_REVIEW"), "IN_REVIEW", perm + ":flow");
        transition(tr, concept + ".return", "退回", List.of("IN_REVIEW"), "RETURNED", perm + ":flow");
        transition(tr, concept + ".resubmit", "再提交", List.of("RETURNED"), "IN_REVIEW", perm + ":submit");
        transition(tr, concept + ".withdraw", "撤回", List.of("IN_REVIEW"), "DRAFT", perm + ":submit");
        transition(tr, concept + ".approve", "通过", List.of("IN_REVIEW"), "APPROVED", perm + ":flow");
        transition(tr, concept + ".reject", "驳回", List.of("IN_REVIEW"), "REJECTED", perm + ":flow");
        files.put("operations/" + base + "-lifecycle.yaml", yaml(sm));

        // —— 规则 ——
        List<String> ruleCodes = new ArrayList<>();
        ArrayNode formRules = PackJson.JSON.createArrayNode();
        for (JsonNode r : fragment.path("rules")) {
            String code = ns + "." + r.path("code").asText();
            ruleCodes.add(code);
            ObjectNode o = PackJson.JSON.createObjectNode();
            o.put("code", code);
            o.put("concept", concept);
            o.put("level", r.path("level").asText("L1"));
            o.put("kind", "CEL");
            o.put("name", r.path("name").asText(r.path("message").asText()));
            o.put("message", r.path("message").asText());
            ObjectNode d = o.putObject("definition");
            if (r.hasNonNull("when")) d.put("when", r.path("when").asText());
            d.put("assert", r.path("assert").asText());
            if (r.hasNonNull("field")) d.put("field", r.path("field").asText());
            d.set("appliesTo", array(concept + ".submit", concept + ".resubmit"));
            files.put("rules/" + kebab(r.path("code").asText()) + ".yaml", yaml(o));
            ObjectNode fr = formRules.addObject();
            fr.put("code", r.path("code").asText());
            fr.put("level", r.path("level").asText("L1"));
            if (r.hasNonNull("when")) fr.put("when", r.path("when").asText());
            fr.put("assert", r.path("assert").asText());
            if (r.hasNonNull("field")) fr.put("field", r.path("field").asText());
            fr.put("message", r.path("message").asText());
        }

        // —— 表单 ——
        ObjectNode form = PackJson.JSON.createObjectNode();
        form.put("viewType", "FORM");
        form.put("formCode", formCode);
        form.put("version", 1);
        form.put("conceptCode", concept);
        form.put("title", entityName);
        ArrayNode layout = form.putArray("layout");
        ArrayNode formFields = form.putArray("fields");
        for (JsonNode f : fields) {
            layout.addObject().put("type", "field").put("key", f.path("code").asText());
            formFields.add(formField(f));
        }
        if (fragment.path("children").size() > 0) {
            ArrayNode subs = form.putArray("subTables");
            for (JsonNode cs : fragment.path("children")) {
                String key = lowerFirst(cs.path("entity").asText());
                layout.addObject().put("type", "subTable").put("key", key);
                ObjectNode st = subs.addObject();
                st.put("key", key);
                st.put("label", cs.path("displayName").asText(key));
                st.put("conceptCode", ns + "." + cs.path("entity").asText());
                if (cs.has("minRows")) st.put("minRows", cs.path("minRows").asInt());
                ArrayNode sf = st.putArray("fields");
                cs.path("fields").forEach(f -> sf.add(formField(f)));
            }
        }
        if (!formRules.isEmpty()) form.set("rules", formRules);
        ObjectNode policies = form.putObject("nodePolicies");
        for (JsonNode n : nodes) {
            ObjectNode p = policies.putObject(n.path("key").asText());
            p.set("readonly", array("*"));
            if (n.path("editable").size() > 0) p.set("editable", n.path("editable").deepCopy());
            if (n.path("required").size() > 0) p.set("required", n.path("required").deepCopy());
        }
        ObjectNode initiator = policies.putObject("initiator");
        initiator.set("readonly", array("*"));
        if (fragment.path("flow").path("initiatorEditable").size() > 0) initiator.set("editable", fragment.path("flow").path("initiatorEditable").deepCopy());
        ArrayNode actions = form.putArray("actions");
        actions.addObject().put("key", "submit").put("label", "提交审核").put("operation", concept + ".submit");
        files.put("views/" + base + "-form.json", json(form));

        // —— 列表 ——
        ObjectNode list = PackJson.JSON.createObjectNode();
        list.put("viewType", "LIST");
        list.put("formCode", listCode);
        list.put("version", 1);
        list.put("conceptCode", concept);
        List<String> listable = new ArrayList<>();
        fields.forEach(f -> {
            String t = f.path("dataType").asText();
            if (!t.equals("TEXT") && !t.equals("FILE") && listable.size() < 6) listable.add(f.path("code").asText());
        });
        list.set("columns", fragment.path("list").path("columns").size() > 0 ? fragment.path("list").path("columns").deepCopy() : array(listable.toArray(String[]::new)));
        List<String> filters = new ArrayList<>();
        fields.forEach(f -> {
            if (f.path("props").path("queryable").asBoolean(false) && filters.size() < 5) filters.add(f.path("code").asText());
        });
        list.set("filters", fragment.path("list").path("filters").size() > 0 ? fragment.path("list").path("filters").deepCopy() : array(filters.toArray(String[]::new)));
        // 默认排序只能用声明了 sortable 的字段；都没有时按系统默认（新建时间倒序）
        for (JsonNode f : fields) {
            if (f.path("props").path("sortable").asBoolean(false)) {
                list.putArray("defaultSort").addObject().put("field", f.path("code").asText()).put("dir", "desc");
                break;
            }
        }
        ArrayNode rowActions = list.putArray("rowActions");
        rowActions.addObject().put("key", "submit").put("label", "提交审核").put("operation", concept + ".submit");
        // 撤回经单据的流程区域办理（同时结束审批人待办），列表不放直接撤回按钮
        files.put("views/" + base + "-list.json", json(list));

        // —— 审批流 ——
        ObjectNode flow = PackJson.JSON.createObjectNode();
        flow.put("code", flowCode);
        flow.put("name", fragment.path("flow").path("name").asText(entityName + "审批"));
        flow.put("concept", concept);
        flow.put("startOn", concept + ".submit");
        flow.putObject("form").put("code", formCode).put("version", 1);
        ObjectNode cb = flow.putObject("callbacks");
        cb.put("approved", concept + ".approve");
        cb.put("rejected", concept + ".reject");
        cb.put("returned", concept + ".return");
        cb.put("resubmitted", concept + ".resubmit");
        cb.put("withdrawn", concept + ".withdraw");
        ArrayNode fn = flow.putArray("nodes");
        for (int i = 0; i < nodes.size(); i++) {
            JsonNode n = nodes.get(i);
            ObjectNode o = fn.addObject();
            o.put("key", n.path("key").asText());
            o.put("type", n.path("type").asText(i == nodes.size() - 1 && nodes.size() > 1 ? "CONFIRM" : "AUDIT"));
            o.put("name", n.path("name").asText(n.path("key").asText()));
            // 按角色且数据范围覆盖业务组织：办理人一定能看到并办理这张单据
            o.putObject("assignee").put("strategy", "DATA_SCOPE").put("value", n.path("role").asText("workshop_leader"));
            // 中间节点办理时写入节点字段（如审核意见）；最后一个节点通过即回调"通过"
            if (i < nodes.size() - 1 || nodes.size() == 1) o.put("operation", concept + ".audit");
            o.put("permission", perm + ":flow");
        }
        files.put("flows/" + base + "-approval.json", json(flow));

        // —— 黄金场景：新建一条记录为草稿 ——
        ObjectNode sc = PackJson.JSON.createObjectNode();
        sc.put("name", "新建" + entityName + "为草稿");
        ArrayNode steps = sc.putArray("steps");
        ObjectNode create = steps.addObject().putObject("create");
        create.put("concept", concept).put("as", "r");
        ObjectNode values = create.putObject("values");
        fields.forEach(f -> {
            if (f.path("required").asBoolean(false)) putSample(values, f, fragment);
        });
        for (JsonNode cs : fragment.path("children")) {
            if (cs.path("minRows").asInt(0) < 1) continue;
            ObjectNode row = PackJson.JSON.createObjectNode();
            cs.path("fields").forEach(f -> {
                if (f.path("required").asBoolean(false)) putSample(row, f, fragment);
            });
            values.putArray(lowerFirst(cs.path("entity").asText())).add(row);
        }
        steps.addObject().putObject("expect").put("concept", concept).put("target", "r").put("status", "DRAFT");
        files.put("tests/scenarios/" + base + "-create.yaml", yaml(sc));

        // —— 清单 ——
        ObjectNode m = PackJson.JSON.createObjectNode();
        m.put("code", ns);
        m.put("version", meta.version() == null || meta.version().isBlank() ? "1.0.0" : meta.version());
        m.put("name", title);
        m.put("description", fragment.path("description").asText(entityName + "登记、审批与查询"));
        m.putObject("owner").put("name", meta.owner().trim());
        m.putObject("requester").put("name", meta.requester().trim());
        m.put("reviewDue", (meta.reviewDue() != null ? meta.reviewDue() : java.time.LocalDate.now().plusYears(1)).toString());
        Set<String> deps = new LinkedHashSet<>();
        refConcepts(fields, deps);
        fragment.path("children").forEach(cs -> refConcepts(cs.path("fields"), deps));
        if (!deps.isEmpty()) {
            ArrayNode d = m.putArray("dependsOn");
            deps.forEach(p -> d.addObject().put("code", p).put("version", ">=1.0.0"));
        }
        ObjectNode contents = m.putObject("contents");
        contents.set("concepts", array(concept));
        ArrayNode ops = contents.putArray("operations");
        tr.forEach(t -> ops.add(t.path("operation").asText()));
        if (!ruleCodes.isEmpty()) contents.set("rules", array(ruleCodes.toArray(String[]::new)));
        contents.set("views", array(formCode, listCode));
        contents.set("flows", array(flowCode));
        m.putArray("entries").addObject().put("name", title).put("path", "/lsmom/ledger/" + concept);
        if (fragment.hasNonNull("provenance")) files.put("provenance.json", json(provenance(fragment, concept)));
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put("pack.yaml", yaml(m));
        out.putAll(files);
        return out;
    }

    /** 来源记录（协同应用中心"来源"页）：Excel 文件与工作表、每个字段对应的列与推断依据 */
    private static ObjectNode provenance(JsonNode fragment, String concept) {
        JsonNode p = fragment.path("provenance");
        ObjectNode out = PackJson.JSON.createObjectNode();
        out.put("sourceType", p.path("source").asText("TEMPLATE"));
        out.putObject("source").put("app", p.path("file").asText("")).put("path", p.path("sheet").asText(""));
        out.putObject("converter").put("name", "ls-mom 台账生成").put("version", "1");
        out.put("generatedAt", java.time.LocalDateTime.now().withNano(0).toString());
        out.putArray("manualEdits");
        out.putArray("manualRequired");
        ObjectNode el = out.putObject("elements").putObject(concept);
        el.put("kind", "CONCEPT");
        el.putObject("source").put("type", "EXCEL_SHEET").put("name", p.path("file").asText("")).put("path", p.path("sheet").asText(""));
        ObjectNode fs = el.putObject("fields");
        for (JsonNode f : fragment.path("fields")) {
            ObjectNode src = fs.putObject(f.path("code").asText());
            src.put("type", "EXCEL_COLUMN");
            src.put("name", f.path("displayName").asText());
            if (f.has("column")) src.put("path", "第 " + (f.path("column").asInt() + 1) + " 列");
            if (f.hasNonNull("evidence")) src.put("field", f.path("evidence").asText());
        }
        return out;
    }

    private static void validateFields(JsonNode fields, String at, Map<String, String> errors) {
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            JsonNode f = fields.get(i);
            String code = f.path("code").asText("");
            String p = at + "." + i;
            if (!CODE.matcher(code).matches()) errors.put(p + ".code", "字段编码须为英文小驼峰：" + code);
            else if (RESERVED.contains(code)) errors.put(p + ".code", "字段编码 " + code + " 是系统保留字段");
            else if (!seen.add(code)) errors.put(p + ".code", "字段编码重复：" + code);
            if (f.path("displayName").asText("").isBlank()) errors.put(p + ".displayName", "请填写字段名称");
            String t = f.path("dataType").asText("");
            if (!TYPES.contains(t)) errors.put(p + ".dataType", "不支持的类型：" + t);
            if (t.equals("REF") && f.path("refConcept").asText("").isBlank()) errors.put(p + ".refConcept", "引用字段须指定引用对象");
            if (t.equals("ENUM") && f.path("options").size() == 0) errors.put(p + ".options", "选项字段至少需要一个选项");
        }
    }

    /** LS-IR 字段 → Concept 字段：ENUM 的 options 为 [{value,label}] 时拆成取值与显示名 */
    private static ArrayNode conceptFields(JsonNode fields) {
        ArrayNode out = PackJson.JSON.createArrayNode();
        for (JsonNode f : fields) {
            ObjectNode o = out.addObject();
            o.put("code", f.path("code").asText());
            o.put("displayName", f.path("displayName").asText());
            o.put("dataType", f.path("dataType").asText());
            if (f.path("required").asBoolean(false)) o.put("required", true);
            if (f.hasNonNull("refConcept")) o.put("refConcept", f.path("refConcept").asText());
            ObjectNode props = f.path("props").isObject() ? ((ObjectNode) f.path("props")).deepCopy() : PackJson.JSON.createObjectNode();
            if (f.path("dataType").asText().equals("ENUM")) {
                ArrayNode opts = o.putArray("options");
                ObjectNode labels = props.with("optionLabels");
                for (JsonNode op : f.path("options")) {
                    String v = op.isObject() ? op.path("value").asText() : op.asText();
                    opts.add(v);
                    if (op.isObject() && op.hasNonNull("label")) labels.put(v, op.path("label").asText());
                }
            }
            if (!props.isEmpty()) o.set("props", props);
        }
        return out;
    }

    private static ObjectNode formField(JsonNode f) {
        ObjectNode o = PackJson.JSON.createObjectNode();
        String code = f.path("code").asText();
        o.put("key", code);
        o.put("label", f.path("displayName").asText());
        String t = f.path("dataType").asText();
        String ref = f.path("refConcept").asText("");
        String widget = switch (t) {
            case "TEXT" -> "textarea";
            case "INTEGER", "DECIMAL" -> "number";
            case "DATE" -> "date";
            case "DATETIME" -> "datetime";
            case "BOOLEAN" -> "switch";
            case "ENUM" -> f.path("options").size() <= 3 ? "radio" : "select";
            case "FILE" -> "attachment";
            case "REF" -> switch (ref) {
                case "ls.core.Party" -> "userRef";
                case "ls.asset.Asset" -> "assetRef";
                case "ls.core.OrgUnit" -> "deptRef";
                case "ls.core.Work" -> "workRef";
                default -> "conceptRef";
            };
            default -> "input";
        };
        o.put("widget", widget);
        if (f.path("required").asBoolean(false)) o.put("required", true);
        if (f.path("props").hasNonNull("maxLength") && (t.equals("STRING") || t.equals("TEXT"))) o.put("maxLength", f.path("props").path("maxLength").asInt());
        if (f.path("props").hasNonNull("minimum")) o.put("min", f.path("props").path("minimum").asDouble());
        if (t.equals("INTEGER")) o.put("precision", 0);
        if (t.equals("ENUM")) {
            ArrayNode opts = o.putArray("options");
            for (JsonNode op : f.path("options")) {
                String v = op.isObject() ? op.path("value").asText() : op.asText();
                String label = op.isObject() ? op.path("label").asText(v) : f.path("props").path("optionLabels").path(v).asText(v);
                opts.addObject().put("label", label).put("value", v);
            }
        }
        if (t.equals("REF") && !ref.equals("ls.core.Party")) o.putObject("ref").put("concept", ref).put("labelField", "name");
        if (f.hasNonNull("help")) o.put("help", f.path("help").asText());
        if (f.path("readonly").asBoolean(false)) o.put("readonly", true);
        return o;
    }

    private static void putSample(ObjectNode values, JsonNode f, JsonNode fragment) {
        String code = f.path("code").asText();
        JsonNode sample = fragment.path("samples").path(code);
        if (!sample.isMissingNode()) {
            values.set(code, sample.deepCopy());
            return;
        }
        switch (f.path("dataType").asText()) {
            case "INTEGER" -> values.put(code, 1);
            case "DECIMAL" -> values.put(code, 1.5);
            case "BOOLEAN" -> values.put(code, true);
            case "DATE" -> values.put(code, "2026-11-10");
            case "DATETIME" -> values.put(code, "2026-11-10T08:00:00");
            case "ENUM" -> {
                JsonNode op = f.path("options").get(0);
                values.put(code, op.isObject() ? op.path("value").asText() : op.asText());
            }
            case "REF" -> values.put(code, 1);
            default -> values.put(code, "T-0001");
        }
    }

    private static void refConcepts(JsonNode fields, Set<String> packs) {
        for (JsonNode f : fields) {
            String ref = f.path("refConcept").asText("");
            if (ref.startsWith("ls.asset.")) packs.add("ls.asset");
            else if (ref.startsWith("ls.quality.")) packs.add("ls.quality");
            else if (ref.startsWith("ls.production.")) packs.add("ls.production");
        }
    }

    private static void transition(ArrayNode tr, String op, String name, List<String> from, String to, String perm) {
        ObjectNode t = tr.addObject();
        t.put("operation", op);
        t.put("name", name);
        t.set("from", array(from.toArray(String[]::new)));
        t.put("to", to);
        t.put("permission", perm);
        t.put("risk", "C1");
        t.putObject("inputSchema").put("type", "object");
    }

    private static ArrayNode array(String... values) {
        ArrayNode a = PackJson.JSON.createArrayNode();
        for (String v : values) a.add(v);
        return a;
    }

    static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    static String kebab(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }

    private static byte[] yaml(JsonNode n) {
        try {
            return PackJson.YAML.writeValueAsString(n).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] json(JsonNode n) {
        try {
            return PackJson.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
