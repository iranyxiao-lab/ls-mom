package com.lsmom.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.action.registry.OperationDefinition;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.core.error.LsBizException;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.FieldUsageIndex;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 视图版本库（meta_view）：草稿可反复保存；发布经完整校验＋兼容性校验，并以变更集（meta_change_set）落地——
 * 移除字段等破坏性变更须由他人评审后应用。已发布版本不可修改，只能派生新版本；新版本发布后旧版本转为 RETIRED，仍可按版本读取用于历史记录只读渲染。
 */
@Service
public class FormRegistry {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final ViewSchemaValidator validator;
    private final ConceptRegistry concepts;
    private final OperationRegistry operations;
    private final FieldUsageIndex usages;
    private final TransactionTemplate tx;

    public FormRegistry(JdbcTemplate jdbc, ViewSchemaValidator validator, ConceptRegistry concepts, OperationRegistry operations,
                        FieldUsageIndex usages, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.validator = validator;
        this.concepts = concepts;
        this.operations = operations;
        this.usages = usages;
        this.tx = new TransactionTemplate(txm);
    }

    public record View(String code, int version, String concept, ViewType type, String displayName, String status, JsonNode schema,
                       String publishedAt, Long createdBy, String changeNote) {
    }

    /** 发布结果：APPLIED 表示已生效；REVIEWING 表示含破坏性变更，等待他人评审。 */
    public record PublishResult(long changeSetId, String status, boolean breaking, List<String> removedFields, List<String> addedFields) {
    }

    private final RowMapper<View> mapper = (rs, i) -> {
        Timestamp pub = rs.getTimestamp("published_at");
        try {
            return new View(rs.getString("code"), rs.getInt("version"), rs.getString("concept_code"), ViewType.valueOf(rs.getString("view_type")),
                rs.getString("display_name"), rs.getString("status"), JSON.readTree(rs.getString("schema_json")),
                pub == null ? null : pub.toLocalDateTime().toString(), (Long) rs.getObject("created_by"), rs.getString("change_note"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    };

    // ---------- 读取 ----------

    public Optional<View> find(String code, int version) {
        return jdbc.query("select * from meta_view where code = ? and version = ?", mapper, code, version).stream().findFirst();
    }

    public View get(String code, int version) {
        return find(code, version).orElseThrow(() -> LsBizException.notFound("视图", code + "@v" + version));
    }

    public Optional<View> published(String code) {
        return jdbc.query("select * from meta_view where code = ? and status = 'PUBLISHED'", mapper, code).stream().findFirst();
    }

    /** 某 Concept 的默认视图：该类型下已发布视图中编码最小者。 */
    public Optional<View> defaultFor(String concept, ViewType type) {
        return jdbc.query("select * from meta_view where concept_code = ? and view_type = ? and status = 'PUBLISHED' order by code limit 1",
            mapper, concept, type.name()).stream().findFirst();
    }

    public List<Map<String, Object>> list(String concept, String type) {
        return jdbc.queryForList("""
            select code, view_type as "viewType", concept_code as "conceptCode", display_name as "displayName",
                   max(version) as "latestVersion",
                   max(case when status = 'PUBLISHED' then version end) as "publishedVersion",
                   bool_or(status = 'DRAFT') as "hasDraft"
            from meta_view where (?::text is null or concept_code = ?) and (?::text is null or view_type = ?)
            group by code, view_type, concept_code, display_name order by code""", concept, concept, type, type);
    }

    public List<Map<String, Object>> history(String code) {
        return jdbc.queryForList("""
            select code, version, status, display_name as "displayName", published_at as "publishedAt", created_at as "createdAt",
                   created_by as "createdBy", change_note as "changeNote"
            from meta_view where code = ? order by version desc""", code);
    }

    // ---------- 草稿 ----------

    /** 保存草稿并返回当前校验问题（草稿允许有问题，发布时必须清零）。已发布或已退役的版本不可覆盖。 */
    public ViewSchemaValidator.Result saveDraft(JsonNode schema, Long by, String note) {
        ViewType type = ViewSchemaValidator.typeOf(schema);
        String code = schema.path("formCode").asText(null);
        int version = schema.path("version").asInt(0);
        if (code == null || !code.matches("^[a-z][a-z0-9]*(\\.[A-Za-z][A-Za-z0-9]*)+$") || version < 1) {
            throw LsBizException.validation(Map.of("formCode", "视图编码或版本不合法"));
        }
        String concept = schema.path("conceptCode").asText(null);
        if (concept == null) {
            if (type != ViewType.DASHBOARD) throw LsBizException.validation(Map.of("conceptCode", "缺少 conceptCode"));
        } else {
            concepts.get(concept);
        }
        Optional<View> same = find(code, version);
        if (same.isPresent() && !"DRAFT".equals(same.get().status())) {
            throw immutable(code, version);
        }
        Integer maxFrozen = jdbc.queryForObject("select max(version) from meta_view where code = ? and status <> 'DRAFT'", Integer.class, code);
        if (maxFrozen != null && version <= maxFrozen) throw immutable(code, version);
        Optional<View> other = jdbc.query("select * from meta_view where code = ? limit 1", mapper, code).stream().findFirst();
        if (other.isPresent() && (other.get().type() != type || !java.util.Objects.equals(other.get().concept(), concept))) {
            throw LsBizException.validation(Map.of("formCode", "视图编码已被 " + other.get().type() + " / " + other.get().concept() + " 使用"));
        }
        ViewSchemaValidator.Result r = validator.validate(schema);
        String name = schema.path("title").asText(code);
        jdbc.update("""
            insert into meta_view (code, version, concept_code, view_type, display_name, status, schema_json, created_by, change_note)
            values (?, ?, ?, ?, ?, 'DRAFT', ?::jsonb, ?, ?)
            on conflict (code, version) do update set display_name = excluded.display_name, schema_json = excluded.schema_json,
                change_note = excluded.change_note, created_by = excluded.created_by""",
            code, version, concept, type.name(), name.length() > 100 ? name.substring(0, 100) : name,
            schema.toString(), by, note);
        return r;
    }

    /** 由最新发布版本派生下一版本草稿（已存在草稿时直接返回草稿）。 */
    public View derive(String code, Long by) {
        Optional<View> draft = jdbc.query("select * from meta_view where code = ? and status = 'DRAFT' order by version desc limit 1", mapper, code)
            .stream().findFirst();
        if (draft.isPresent()) return draft.get();
        View base = published(code).orElseThrow(() -> LsBizException.notFound("已发布视图", code));
        com.fasterxml.jackson.databind.node.ObjectNode next = base.schema().deepCopy();
        Integer max = jdbc.queryForObject("select max(version) from meta_view where code = ?", Integer.class, code);
        next.put("version", (max == null ? base.version() : max) + 1);
        saveDraft(next, by, "派生自 v" + base.version());
        return get(code, next.get("version").asInt());
    }

    public void discardDraft(String code, int version) {
        int n = jdbc.update("delete from meta_view where code = ? and version = ? and status = 'DRAFT'", code, version);
        if (n == 0) throw immutable(code, version);
    }

    // ---------- 校验与发布 ----------

    /** 完整校验：结构、脚本、语义，以及相对上一发布版本的兼容性。 */
    public ViewSchemaValidator.Result check(String code, int version) {
        View v = get(code, version);
        ViewSchemaValidator.Result r = validator.validate(v.schema());
        List<ViewIssue> all = new ArrayList<>(r.issues());
        if (r.ok()) all.addAll(compatibility(v, published(code).orElse(null)));
        return new ViewSchemaValidator.Result(r.type(), List.copyOf(all), r.fieldRefs());
    }

    /** 提交发布：校验不通过抛 VIEW_INVALID / FORM_SCRIPT_FORBIDDEN；无破坏性变更时直接生效，否则生成待评审变更集。 */
    public PublishResult publish(String code, int version, Long by) {
        View v = get(code, version);
        if (!"DRAFT".equals(v.status())) throw immutable(code, version);
        ViewSchemaValidator.Result r = requireValid(code, version);
        View prev = published(code).orElse(null);
        Set<String> before = prev == null ? Set.of() : fieldKeys(prev.schema());
        Set<String> after = fieldKeys(v.schema());
        List<String> removed = before.stream().filter(k -> !after.contains(k)).sorted().toList();
        List<String> added = after.stream().filter(k -> !before.contains(k)).sorted().toList();
        boolean breaking = !removed.isEmpty();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "VIEW_PUBLISH");
        item.put("code", code);
        item.put("version", version);
        item.put("viewType", v.type().name());
        item.put("from", prev == null ? null : prev.version());
        item.put("removedFields", removed);
        item.put("addedFields", added);
        String pack = v.concept() == null ? null : concepts.find(v.concept()).map(ConceptDef::ownerPack).orElse(null);
        return tx.execute(s -> {
            Long id = jdbc.queryForObject("""
                insert into meta_change_set (pack_code, title, status, breaking, items, source, created_by)
                values (?, ?, ?, ?, ?::jsonb, 'DESIGNER', ?) returning id""", Long.class, pack,
                "发布视图 " + code + " v" + version + (breaking ? "（移除字段 " + String.join("、", removed) + "）" : ""),
                breaking ? "REVIEWING" : "APPROVED", breaking, toJson(List.of(item)), by);
            if (breaking) return new PublishResult(id, "REVIEWING", true, removed, added);
            apply(id, v, r);
            return new PublishResult(id, "APPLIED", false, removed, added);
        });
    }

    public void review(long changeSetId, boolean approve, Long reviewer) {
        Map<String, Object> cs = changeSet(changeSetId);
        if (!"REVIEWING".equals(cs.get("status"))) throw new LsBizException("CHANGESET_STATE", "变更集不在评审中");
        if (reviewer != null && reviewer.equals(cs.get("created_by"))) {
            throw new LsBizException("CHANGESET_SELF_REVIEW", "提交人不能评审自己的破坏性变更");
        }
        jdbc.update("update meta_change_set set status = ?, reviewed_by = ? where id = ?", approve ? "APPROVED" : "REJECTED", reviewer, changeSetId);
    }

    /** 应用已批准的视图变更集（应用前重新校验，期间依赖可能已变化）。 */
    public void applyChangeSet(long changeSetId) {
        Map<String, Object> cs = changeSet(changeSetId);
        if (!"APPROVED".equals(cs.get("status"))) throw new LsBizException("CHANGESET_NOT_APPROVED", "变更集未经评审批准，不能执行");
        JsonNode item = readTree(String.valueOf(cs.get("items"))).path(0);
        if (!"VIEW_PUBLISH".equals(item.path("type").asText())) throw new LsBizException("CHANGESET_STATE", "不是视图发布变更集");
        String code = item.path("code").asText();
        int version = item.path("version").asInt();
        View v = get(code, version);
        if (!"DRAFT".equals(v.status())) throw immutable(code, version);
        ViewSchemaValidator.Result r = requireValid(code, version);
        tx.executeWithoutResult(s -> apply(changeSetId, v, r));
    }

    private ViewSchemaValidator.Result requireValid(String code, int version) {
        ViewSchemaValidator.Result r = check(code, version);
        if (r.ok()) return r;
        boolean script = r.issues().stream().anyMatch(i -> i.code().equals("FORM_SCRIPT_FORBIDDEN"));
        ViewIssue first = r.issues().get(0);
        throw new LsBizException(script ? "FORM_SCRIPT_FORBIDDEN" : "VIEW_INVALID",
            (script ? "视图包含脚本内容，禁止发布：" : "视图发布校验未通过：") + first.path() + " " + first.message(), Map.of("issues", r.issues()));
    }

    private void apply(long changeSetId, View v, ViewSchemaValidator.Result r) {
        Optional<View> prev = published(v.code());
        prev.ifPresent(p -> {
            jdbc.update("update meta_view set status = 'RETIRED' where code = ? and version = ?", p.code(), p.version());
            usages.retire(FieldUsageIndex.UserType.VIEW, p.code(), p.version());
        });
        jdbc.update("update meta_view set status = 'PUBLISHED', published_at = now() where code = ? and version = ?", v.code(), v.version());
        usages.replace(FieldUsageIndex.UserType.VIEW, v.code(), v.version(), r.fieldRefs());
        jdbc.update("update meta_change_set set status = 'APPLIED', applied_at = now() where id = ?", changeSetId);
    }

    // ---------- 兼容性 ----------

    /** 与 Flow 节点策略、Operation 输入、规则、打印模板的兼容性（相对上一发布版本被移除的字段）。 */
    List<ViewIssue> compatibility(View next, View prev) {
        List<ViewIssue> issues = new ArrayList<>();
        if (next.type() != ViewType.FORM) return issues;
        JsonNode s = next.schema();
        String concept = next.concept();
        Set<String> keys = fieldKeys(s);
        // Operation 输入：动作绑定的 Operation 要求的 Concept 字段必须由表单提供
        JsonNode actions = s.path("actions");
        for (int i = 0; i < actions.size(); i++) {
            Optional<OperationDefinition> op = operations.find(actions.get(i).path("operation").asText());
            if (op.isEmpty() || op.get().inputSchema() == null) continue;
            ConceptDef c = concepts.get(concept);
            for (JsonNode req : op.get().inputSchema().path("required")) {
                String f = req.asText();
                if (c.field(f) != null && !keys.contains(f)) {
                    issues.add(new ViewIssue("/actions/" + i + "/operation", "OPERATION_INPUT_INCOMPATIBLE",
                        "Operation " + op.get().code() + " 需要字段 " + f + "，表单未提供"));
                }
            }
        }
        if (prev == null) return issues;
        Set<String> removed = new LinkedHashSet<>(fieldKeys(prev.schema()));
        removed.removeAll(keys);
        if (removed.isEmpty()) return issues;
        for (String f : removed) {
            String base = f.contains(".") ? f.substring(f.indexOf('.') + 1) : f;
            String fieldConcept = f.contains(".") ? subConcept(prev.schema(), f.substring(0, f.indexOf('.'))) : concept;
            if (fieldConcept == null) continue;
            for (FieldUsageIndex.Usage u : usages.activeUsagesOf(fieldConcept, base)) {
                if (u.userType().equals("FLOW_NODE") || u.userType().equals("FLOW")) {
                    issues.add(new ViewIssue("/fields", "POLICY_FIELD_REMOVED",
                        "流程节点 " + u.userCode() + (u.detail() == null ? "" : "（" + u.detail() + "）") + " 的字段策略引用了被移除的字段 " + f));
                }
            }
            for (Map<String, Object> rule : jdbc.queryForList(
                "select code, definition::text as def from meta_rule where concept_code = ? and status = 'PUBLISHED'", fieldConcept)) {
                if (Pattern.compile("\\$?model\\." + Pattern.quote(base) + "\\b").matcher(String.valueOf(rule.get("def"))).find()) {
                    issues.add(new ViewIssue("/fields", "RULE_FIELD_REMOVED", "规则 " + rule.get("code") + " 使用了被移除的字段 " + f));
                }
            }
            if (!f.contains(".")) {
                for (Map<String, Object> pv : jdbc.queryForList("""
                    select code, version, schema_json::text as s from meta_view
                    where concept_code = ? and view_type = 'PRINT' and status = 'PUBLISHED'""", concept)) {
                    JsonNode print = readTree(String.valueOf(pv.get("s")));
                    for (JsonNode sec : print.path("sections")) {
                        for (JsonNode pf : sec.path("fields")) {
                            if (pf.asText().equals(f)) {
                                issues.add(new ViewIssue("/fields", "PRINT_FIELD_REMOVED", "打印模板 " + pv.get("code") + " v" + pv.get("version")
                                    + "（模板版本 " + print.path("templateVersion").asText() + "）使用了被移除的字段 " + f));
                            }
                        }
                    }
                }
            }
        }
        return issues;
    }

    private static String subConcept(JsonNode schema, String subKey) {
        for (JsonNode st : schema.path("subTables")) if (st.path("key").asText().equals(subKey)) return st.path("conceptCode").asText();
        return null;
    }

    /** 表单字段键：主表字段与 子表.列。 */
    static Set<String> fieldKeys(JsonNode schema) {
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode f : schema.path("fields")) keys.add(f.path("key").asText());
        for (JsonNode st : schema.path("subTables")) {
            for (JsonNode f : st.path("fields")) keys.add(st.path("key").asText() + "." + f.path("key").asText());
        }
        return keys;
    }

    // ---------- 旧单编辑 ----------

    /** 编辑按旧版本保存的记录时的版本选择：KEEP 保持原版本；UPGRADE 切到最新；PROMPT（默认）由界面提示用户选择。 */
    public Map<String, Object> resolveForEdit(String code, int recordVersion) {
        View latest = published(code).orElseThrow(() -> LsBizException.notFound("已发布视图", code));
        String strategy = latest.schema().path("migration").path("strategy").asText("PROMPT");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recordVersion", recordVersion);
        out.put("latestVersion", latest.version());
        out.put("strategy", strategy);
        boolean outdated = recordVersion < latest.version() && find(code, recordVersion).isPresent();
        out.put("outdated", outdated);
        out.put("useVersion", !outdated || strategy.equals("UPGRADE") ? latest.version() : recordVersion);
        out.put("prompt", outdated && strategy.equals("PROMPT"));
        out.put("note", latest.schema().path("migration").path("note").asText(null));
        return out;
    }

    // ---------- 工具 ----------

    private Map<String, Object> changeSet(long id) {
        return jdbc.queryForList("select id, status, items::text as items, created_by from meta_change_set where id = ?", id).stream().findFirst()
            .orElseThrow(() -> LsBizException.notFound("变更集", String.valueOf(id)));
    }

    private static LsBizException immutable(String code, int version) {
        return new LsBizException("FORM_VERSION_IMMUTABLE", "视图 " + code + " v" + version + " 已发布或不是草稿，已发布版本只能派生新版本",
            Map.of("code", code, "version", version));
    }

    private static JsonNode readTree(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
