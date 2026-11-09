package com.lsmom.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.registry.OperationDefinition;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.action.rule.CelRuleEngine;
import com.lsmom.core.error.LsBizException;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.FieldDef;
import com.lsmom.meta.concept.FieldUsageIndex.FieldRef;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 视图（FormSchema/ListSchema/DetailSchema/PrintSchema/MobileSchema/看板绑定）校验：
 * 1）JSON Schema 结构；2）拒绝脚本；3）语义——字段必须绑定 Concept 的有效字段、布局引用已定义且只放一次、子表是本 Concept 的子 Concept、
 * 引用目标与过滤字段存在、动作只绑定本 Concept 已发布的 Operation、规则与计算字段是合法 CEL、组件已注册且兼容、节点策略字段存在、看板不含 SQL。
 */
@Service
public class ViewSchemaValidator {

    public static final String SCHEMA_BASE = "https://lsmom.local/schema";
    /** 所有受控记录都有的系统字段，可直接出现在列表/详情中。 */
    public static final Set<String> SYSTEM_FIELDS = Set.of("id", "status", "version", "createdAt", "createdBy", "updatedAt", "updatedBy",
        "orgId", "schemaVersion");
    private static final Pattern SQL = Pattern.compile("(?is)\\b(select\\s+.+\\s+from|insert\\s+into|update\\s+\\S+\\s+set|delete\\s+from|drop\\s+table|union\\s+select)\\b");

    private final ConceptRegistry concepts;
    private final OperationRegistry operations;
    private final CelRuleEngine cel;
    private final ComponentRegistry components;
    private final Map<ViewType, Schema> schemas = new EnumMap<>(ViewType.class);

    public ViewSchemaValidator(ConceptRegistry concepts, OperationRegistry operations, CelRuleEngine cel, ComponentRegistry components) {
        this.concepts = concepts;
        this.operations = operations;
        this.cel = cel;
        this.components = components;
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            b -> b.schemaIdResolvers(r -> r.mapPrefix(SCHEMA_BASE, "classpath:ls-schema")));
        for (ViewType t : ViewType.values()) {
            Schema s = registry.getSchema(SchemaLocation.of(t.schemaUri()));
            s.initializeValidators();
            schemas.put(t, s);
        }
    }

    /** 校验结果：问题列表（空为通过）与该视图引用的 Concept 字段（发布时写入字段使用索引）。 */
    public record Result(ViewType type, List<ViewIssue> issues, List<FieldRef> fieldRefs) {
        public boolean ok() {
            return issues.isEmpty();
        }
    }

    public static ViewType typeOf(JsonNode view) {
        String t = view == null ? null : view.path("viewType").asText(null);
        if (t == null) throw new LsBizException("VIEW_INVALID", "缺少 viewType", Map.of("issues", List.of(new ViewIssue("/viewType", "required", "缺少 viewType"))));
        try {
            return ViewType.valueOf(t);
        } catch (IllegalArgumentException e) {
            throw new LsBizException("VIEW_INVALID", "未知视图类型：" + t, Map.of("issues", List.of(new ViewIssue("/viewType", "enum", "未知视图类型：" + t))));
        }
    }

    public Result validate(JsonNode view) {
        ViewType type = typeOf(view);
        List<ViewIssue> issues = new ArrayList<>();
        ScriptGuard.scan(view, "", issues);
        for (Error e : schemas.get(type).validate(view)) {
            String loc = e.getInstanceLocation().toString();
            String path = e.getProperty() != null ? loc + "/" + e.getProperty() : loc;
            issues.add(new ViewIssue(path.isEmpty() ? "/" : path, "SCHEMA_" + e.getKeyword(), e.getMessage()));
        }
        List<FieldRef> refs = new ArrayList<>();
        // 结构不合法时不做语义检查（避免级联噪声），脚本问题照常报告
        if (issues.stream().noneMatch(i -> i.code().startsWith("SCHEMA_"))) {
            new Semantic(view, type, issues, refs).run();
        }
        return new Result(type, List.copyOf(issues), List.copyOf(refs));
    }

    /** 校验并在不通过时抛错：含脚本 → FORM_SCRIPT_FORBIDDEN，其余 → VIEW_INVALID；details.issues 为完整问题列表。 */
    public Result require(JsonNode view) {
        Result r = validate(view);
        if (r.ok()) return r;
        boolean script = r.issues().stream().anyMatch(i -> i.code().equals("FORM_SCRIPT_FORBIDDEN"));
        throw new LsBizException(script ? "FORM_SCRIPT_FORBIDDEN" : "VIEW_INVALID",
            (script ? "视图包含脚本内容，禁止发布：" : "视图校验未通过：") + r.issues().get(0).path() + " " + r.issues().get(0).message(),
            Map.of("issues", r.issues()));
    }

    private final class Semantic {
        final JsonNode view;
        final ViewType type;
        final List<ViewIssue> issues;
        final List<FieldRef> refs;
        ConceptDef concept;

        Semantic(JsonNode view, ViewType type, List<ViewIssue> issues, List<FieldRef> refs) {
            this.view = view;
            this.type = type;
            this.issues = issues;
            this.refs = refs;
        }

        void issue(String path, String code, String message) {
            issues.add(new ViewIssue(path, code, message));
        }

        void run() {
            String conceptCode = view.path("conceptCode").asText(null);
            if (conceptCode != null) {
                Optional<ConceptDef> c = concepts.find(conceptCode);
                if (c.isEmpty()) {
                    issue("/conceptCode", "CONCEPT_NOT_FOUND", "Concept 不存在：" + conceptCode);
                    return;
                }
                concept = c.get();
            } else if (type != ViewType.DASHBOARD) {
                issue("/conceptCode", "CONCEPT_NOT_FOUND", "缺少 conceptCode");
                return;
            }
            switch (type) {
                case FORM -> form();
                case LIST -> list();
                case DETAIL -> detail();
                case PRINT -> print();
                case MOBILE -> mobile();
                case DASHBOARD -> dashboard();
            }
        }

        // ---------- 字段与 Concept ----------

        FieldDef bind(ConceptDef c, String key, String path, boolean allowSystem) {
            if (allowSystem && SYSTEM_FIELDS.contains(key)) return null;
            FieldDef f = c.field(key);
            if (f == null || f.status() == FieldDef.FieldStatus.REMOVED) {
                issue(path, "FIELD_NOT_BOUND", "字段 " + key + " 不是 " + c.code() + " 的有效字段");
                return null;
            }
            refs.add(new FieldRef(c.code(), key, type.name() + ":" + path));
            return f;
        }

        void form() {
            Map<String, JsonNode> defined = new HashMap<>();
            JsonNode fields = view.path("fields");
            for (int i = 0; i < fields.size(); i++) {
                JsonNode f = fields.get(i);
                String key = f.path("key").asText();
                String p = "/fields/" + i;
                if (defined.put(key, f) != null) issue(p + "/key", "FIELD_DUPLICATE", "字段重复定义：" + key);
                fieldSpec(concept, f, p);
            }
            Set<String> subKeys = new HashSet<>();
            JsonNode subs = view.path("subTables");
            for (int i = 0; i < subs.size(); i++) {
                JsonNode s = subs.get(i);
                String p = "/subTables/" + i;
                String key = s.path("key").asText();
                if (!subKeys.add(key) || defined.containsKey(key)) issue(p + "/key", "FIELD_DUPLICATE", "子表键重复：" + key);
                String childCode = s.path("conceptCode").asText();
                Optional<ConceptDef> child = concepts.find(childCode);
                if (child.isEmpty() || !concept.code().equals(child.get().parentConcept())) {
                    issue(p + "/conceptCode", "SUBTABLE_INVALID", "子表 " + childCode + " 不是 " + concept.code() + " 的子 Concept");
                    continue;
                }
                String expectedKey = com.lsmom.crud.query.LedgerModel.childKey(child.get());
                if (!key.equals(expectedKey)) issue(p + "/key", "SUBTABLE_INVALID", "子表键须为 " + expectedKey + "（与台账接口一致）");
                Set<String> childKeys = new HashSet<>();
                JsonNode cf = s.path("fields");
                for (int j = 0; j < cf.size(); j++) {
                    if (!childKeys.add(cf.get(j).path("key").asText())) issue(p + "/fields/" + j + "/key", "FIELD_DUPLICATE", "子表字段重复");
                    fieldSpec(child.get(), cf.get(j), p + "/fields/" + j);
                }
            }
            Map<String, Integer> placed = new HashMap<>();
            layout(view.path("layout"), "/layout", defined.keySet(), subKeys, placed);
            for (String k : defined.keySet()) {
                if (!placed.containsKey(k) && !defined.get(k).path("hidden").asBoolean(false)) {
                    issue("/fields", "FIELD_NOT_PLACED", "字段 " + k + " 未放入布局（不显示的字段请设 hidden）");
                }
            }
            for (String k : subKeys) if (!placed.containsKey(k)) issue("/subTables", "FIELD_NOT_PLACED", "子表 " + k + " 未放入布局");
            rules(defined.keySet());
            actions(view.path("actions"), "/actions");
            policies(defined.keySet(), subKeys);
        }

        void fieldSpec(ConceptDef c, JsonNode f, String p) {
            String key = f.path("key").asText();
            String widget = f.path("widget").asText();
            boolean computed = widget.equals("computed") || f.has("computed");
            FieldDef def = bind(c, key, p + "/key", false);
            if (def != null && def.stateField()) issue(p + "/key", "FIELD_STATE_FORBIDDEN", "状态字段只能由 Operation 迁移，不能放进录入表单：" + key);
            if (f.has("pattern")) {
                try {
                    Pattern.compile(f.get("pattern").asText());
                } catch (PatternSyntaxException e) {
                    issue(p + "/pattern", "PATTERN_INVALID", "正则不合法：" + e.getDescription());
                }
            }
            if (f.has("min") && f.has("max") && f.get("min").asDouble() > f.get("max").asDouble()) {
                issue(p + "/min", "RANGE_INVALID", "min 大于 max");
            }
            if (computed) {
                String expr = f.path("computed").path("expression").asText(null);
                if (expr == null) issue(p + "/computed", "COMPUTED_INVALID", "计算字段缺少表达式");
                else celCheck(expr, false, p + "/computed/expression");
            }
            if (f.has("ref")) {
                String target = f.path("ref").path("concept").asText();
                Optional<ConceptDef> t = concepts.find(target);
                if (t.isEmpty()) {
                    issue(p + "/ref/concept", "REF_INVALID", "引用目标 Concept 不存在：" + target);
                } else {
                    if (def != null && def.refConcept() != null && !def.refConcept().equals(target)) {
                        issue(p + "/ref/concept", "REF_INVALID", "引用目标与字段定义不一致：" + def.refConcept());
                    }
                    JsonNode filters = f.path("ref").path("filter");
                    for (int i = 0; i < filters.size(); i++) {
                        String ff = filters.get(i).path("field").asText();
                        if (!SYSTEM_FIELDS.contains(ff) && t.get().field(ff) == null) {
                            issue(p + "/ref/filter/" + i + "/field", "REF_INVALID", "引用过滤字段不存在：" + target + "." + ff);
                        }
                        JsonNode v = filters.get(i).path("value");
                        if (v.isTextual() && v.asText().startsWith("$") && !v.asText().matches("^\\$(context\\.(orgId|userId|deptId)|model\\.[a-z][A-Za-z0-9]*)$")) {
                            issue(p + "/ref/filter/" + i + "/value", "REF_INVALID", "过滤取值只能引用 $context.orgId/userId/deptId 或 $model.<字段>");
                        }
                    }
                    String labelField = f.path("ref").path("labelField").asText(null);
                    if (labelField != null && t.get().field(labelField) == null) issue(p + "/ref/labelField", "REF_INVALID", "显示字段不存在：" + labelField);
                }
            } else if (widget.endsWith("Ref") && !widget.equals("sourceRef") && def != null && def.refConcept() == null) {
                issue(p + "/widget", "REF_INVALID", "引用控件需要引用型字段或 ref 配置：" + key);
            }
            if (widget.equals("registered") || f.has("component")) {
                String code = f.path("component").path("code").asText(null);
                String ver = f.path("component").path("version").asText(null);
                Optional<ComponentRegistry.Component> comp = code == null ? Optional.empty() : components.find(code, ver);
                if (comp.isEmpty()) issue(p + "/component", "COMPONENT_NOT_REGISTERED", "组件未注册：" + code + "@" + ver);
                else if (!"ACTIVE".equals(comp.get().status())) issue(p + "/component", "COMPONENT_NOT_REGISTERED", "组件已弃用：" + code + "@" + ver);
                else if (!comp.get().compatibleWith(ComponentRegistry.FORM_SCHEMA_VERSION)) {
                    issue(p + "/component", "COMPONENT_INCOMPATIBLE", "组件不兼容当前 FormSchema 版本：" + code + "@" + ver);
                }
            }
            if ((widget.equals("dict")) && !f.has("dictType") && (def == null || def.textProp("dictType") == null)) {
                issue(p + "/dictType", "DICT_MISSING", "字典控件需要 dictType");
            }
        }

        void layout(JsonNode nodes, String path, Set<String> fields, Set<String> subs, Map<String, Integer> placed) {
            for (int i = 0; i < nodes.size(); i++) {
                JsonNode n = nodes.get(i);
                String p = path + "/" + i;
                String t = n.path("type").asText();
                if (t.equals("field") || t.equals("subTable")) {
                    String key = n.path("key").asText(null);
                    Set<String> pool = t.equals("field") ? fields : subs;
                    if (key == null || !pool.contains(key)) issue(p + "/key", "LAYOUT_REF_INVALID", "布局引用了未定义的" + (t.equals("field") ? "字段" : "子表") + "：" + key);
                    else if (placed.merge(key, 1, Integer::sum) > 1) issue(p + "/key", "LAYOUT_DUPLICATE", "字段在布局中出现多次：" + key);
                }
                if (t.equals("relatedList") || t.equals("relatedSummary")) {
                    String rc = n.path("concept").asText(null);
                    Optional<ConceptDef> r = rc == null ? Optional.empty() : concepts.find(rc);
                    if (r.isEmpty()) issue(p + "/concept", "REF_INVALID", "关联 Concept 不存在：" + rc);
                    else if (n.has("via") && r.get().field(n.get("via").asText()) == null) issue(p + "/via", "REF_INVALID", "关联字段不存在：" + n.get("via").asText());
                }
                if (n.has("children")) layout(n.get("children"), p + "/children", fields, subs, placed);
            }
        }

        void rules(Set<String> fields) {
            JsonNode rules = view.path("rules");
            for (int i = 0; i < rules.size(); i++) {
                JsonNode r = rules.get(i);
                String p = "/rules/" + i;
                if (r.has("when")) celCheck(r.get("when").asText(), true, p + "/when");
                celCheck(r.path("assert").asText(), true, p + "/assert");
                if (r.has("field") && !fields.contains(r.get("field").asText())) issue(p + "/field", "FIELD_NOT_BOUND", "规则提示字段未在表单中：" + r.get("field").asText());
            }
        }

        void celCheck(String expr, boolean condition, String path) {
            for (Map<String, Object> e : cel.validate(expr, condition)) {
                issue(path, String.valueOf(e.get("errorCode")), String.valueOf(e.get("message")));
            }
        }

        void actions(JsonNode actions, String path) {
            Set<String> keys = new HashSet<>();
            for (int i = 0; i < actions.size(); i++) {
                JsonNode a = actions.get(i);
                String p = path + "/" + i;
                if (!keys.add(a.path("key").asText())) issue(p + "/key", "ACTION_DUPLICATE", "动作键重复");
                String code = a.path("operation").asText();
                Optional<OperationDefinition> op = operations.find(code);
                if (op.isEmpty()) issue(p + "/operation", "OPERATION_NOT_PUBLISHED", "动作绑定的 Operation 不存在：" + code);
                else if (!"PUBLISHED".equals(op.get().status())) issue(p + "/operation", "OPERATION_NOT_PUBLISHED", "动作只能绑定已发布的 Operation：" + code + "（" + op.get().status() + "）");
                else if (!op.get().concept().equals(concept.code())) issue(p + "/operation", "OPERATION_CONCEPT_MISMATCH", "Operation " + code + " 不属于 " + concept.code());
            }
        }

        void policies(Set<String> fields, Set<String> subs) {
            JsonNode np = view.path("nodePolicies");
            np.fieldNames().forEachRemaining(node -> {
                JsonNode pol = np.get(node);
                for (String kind : List.of("readonly", "editable", "hidden", "required")) {
                    JsonNode list = pol.path(kind);
                    for (int i = 0; i < list.size(); i++) {
                        String k = list.get(i).asText();
                        if (k.equals("*")) continue;
                        String base = k.contains(".") ? k.substring(0, k.indexOf('.')) : k;
                        if (!fields.contains(base) && !subs.contains(base)) {
                            issue("/nodePolicies/" + node + "/" + kind + "/" + i, "POLICY_FIELD_INVALID", "节点 " + node + " 的策略引用了表单中不存在的字段：" + k);
                        }
                    }
                }
            });
        }

        // ---------- 其他视图 ----------

        void list() {
            Set<String> seen = new LinkedHashSet<>();
            JsonNode cols = view.path("columns");
            for (int i = 0; i < cols.size(); i++) {
                String key = cols.get(i).isTextual() ? cols.get(i).asText() : cols.get(i).path("key").asText();
                if (!seen.add(key)) issue("/columns/" + i, "FIELD_DUPLICATE", "列重复：" + key);
                bind(concept, key, "/columns/" + i, true);
            }
            JsonNode filters = view.path("filters");
            for (int i = 0; i < filters.size(); i++) {
                FieldDef f = bind(concept, filters.get(i).asText(), "/filters/" + i, true);
                if (f != null && !f.flag("queryable")) issue("/filters/" + i, "FIELD_NOT_QUERYABLE", "字段未声明可查询：" + f.code());
            }
            JsonNode sorts = view.path("defaultSort");
            for (int i = 0; i < sorts.size(); i++) {
                FieldDef f = bind(concept, sorts.get(i).path("field").asText(), "/defaultSort/" + i + "/field", true);
                if (f != null && !f.flag("sortable")) issue("/defaultSort/" + i + "/field", "FIELD_NOT_SORTABLE", "字段未声明可排序：" + f.code());
            }
            actions(view.path("rowActions"), "/rowActions");
            actions(view.path("batchActions"), "/batchActions");
        }

        void detail() {
            JsonNode groups = view.path("groups");
            for (int g = 0; g < groups.size(); g++) {
                JsonNode fs = groups.get(g).path("fields");
                for (int i = 0; i < fs.size(); i++) bind(concept, fs.get(i).asText(), "/groups/" + g + "/fields/" + i, true);
            }
            JsonNode subs = view.path("subTables");
            for (int i = 0; i < subs.size(); i++) childOf(subs.get(i).asText(), "/subTables/" + i);
            JsonNode rel = view.path("relatedLists");
            for (int i = 0; i < rel.size(); i++) {
                String rc = rel.get(i).path("concept").asText();
                Optional<ConceptDef> r = concepts.find(rc);
                if (r.isEmpty()) issue("/relatedLists/" + i + "/concept", "REF_INVALID", "关联 Concept 不存在：" + rc);
                else {
                    bind(r.get(), rel.get(i).path("via").asText(), "/relatedLists/" + i + "/via", false);
                    JsonNode cols = rel.get(i).path("columns");
                    for (int j = 0; j < cols.size(); j++) bind(r.get(), cols.get(j).asText(), "/relatedLists/" + i + "/columns/" + j, true);
                }
            }
        }

        void childOf(String code, String path) {
            Optional<ConceptDef> c = concepts.find(code);
            if (c.isEmpty() || !concept.code().equals(c.get().parentConcept())) {
                issue(path, "SUBTABLE_INVALID", "子表 " + code + " 不是 " + concept.code() + " 的子 Concept");
            }
        }

        void print() {
            JsonNode secs = view.path("sections");
            for (int s = 0; s < secs.size(); s++) {
                JsonNode fs = secs.get(s).path("fields");
                for (int i = 0; i < fs.size(); i++) bind(concept, fs.get(i).asText(), "/sections/" + s + "/fields/" + i, true);
                if (secs.get(s).has("subTable")) childOf(secs.get(s).get("subTable").asText(), "/sections/" + s + "/subTable");
            }
        }

        void mobile() {
            JsonNode fs = view.path("fields");
            for (int i = 0; i < fs.size(); i++) bind(concept, fs.get(i).asText(), "/fields/" + i, true);
            if (view.path("photo").has("field")) bind(concept, view.path("photo").path("field").asText(), "/photo/field", false);
            actions(view.path("actions"), "/actions");
        }

        void dashboard() {
            JsonNode ws = view.path("widgets");
            for (int i = 0; i < ws.size(); i++) {
                JsonNode w = ws.get(i);
                if (!w.has("metric") && !w.has("query")) issue("/widgets/" + i, "DASHBOARD_BINDING_MISSING", "看板组件必须绑定 Metric 或命名查询");
                if (SQL.matcher(w.toString()).find()) issue("/widgets/" + i, "DASHBOARD_SQL_FORBIDDEN", "看板不允许直接写 SQL，请使用 Metric 或命名查询");
            }
        }
    }
}
