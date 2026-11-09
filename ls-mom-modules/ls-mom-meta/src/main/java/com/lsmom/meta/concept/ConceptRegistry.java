package com.lsmom.meta.concept;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Concept 注册表。规则：
 * <ul>
 *   <li>Concept、字段编码一经发布不可修改、不可复用；显示名可随时调整；</li>
 *   <li>删除字段先 DEPRECATED，无已发布使用方后才能 REMOVED；</li>
 *   <li>{@code ls.core} 只能由核心自注册写入，且不得出现客户/扩展领域概念。</li>
 * </ul>
 */
@Service
public class ConceptRegistry {

    public static final String CORE_NAMESPACE = "ls.core";
    /** 受控表统一保留字段（DDL 生成器自动创建）。 */
    public static final Set<String> RESERVED_COLUMNS = Set.of("id", "org_id", "status", "created_by", "created_at", "updated_by",
        "updated_at", "version", "del_flag", "schema_version", "ext_attrs", "parent_id", "line_no");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final FieldUsageIndex usages;

    public ConceptRegistry(JdbcTemplate jdbc, FieldUsageIndex usages) {
        this.jdbc = jdbc;
        this.usages = usages;
    }

    private final RowMapper<FieldDef> fieldMapper = (rs, i) -> new FieldDef(rs.getString("field_code"), rs.getString("display_name"),
        DataType.valueOf(rs.getString("data_type")), rs.getString("column_name"), rs.getBoolean("required"),
        rs.getString("ref_concept"), readOptions(rs.getString("options")), rs.getBoolean("state_field"), rs.getBoolean("system_field"),
        FieldDef.FieldStatus.valueOf(rs.getString("status")), rs.getInt("sort_order"), readProps(rs.getString("props")));

    public Optional<ConceptDef> find(String code) {
        List<ConceptDef> list = jdbc.query("select * from meta_concept where code = ?", (rs, i) -> new ConceptDef(rs.getString("code"),
            rs.getString("namespace"), rs.getString("display_name"), ConceptDef.Storage.valueOf(rs.getString("storage")),
            rs.getString("table_name"), rs.getBoolean("editable"), rs.getString("owner_pack"), rs.getString("description"),
            fields(rs.getString("code"), true), rs.getString("status"), rs.getInt("version"), rs.getString("parent_concept")), code);
        return list.stream().findFirst();
    }

    public ConceptDef get(String code) {
        return find(code).orElseThrow(() -> new LsBizException("CONCEPT_NOT_FOUND", "Concept 不存在：" + code, Map.of("code", code)));
    }

    public List<String> listCodes(String namespacePrefix) {
        return jdbc.queryForList("select code from meta_concept where namespace like ? order by code", String.class,
            (namespacePrefix == null ? "" : namespacePrefix) + "%");
    }

    /** 字段（含已弃用；{@code includeRemoved} 决定是否包含已移除）。 */
    public List<FieldDef> fields(String conceptCode, boolean includeRemoved) {
        return jdbc.query("select * from meta_concept_field where concept_code = ?" + (includeRemoved ? "" : " and status <> 'REMOVED'")
            + " order by sort_order, field_code", fieldMapper, conceptCode);
    }

    /**
     * 核心自注册：核心显式表由代码维护，结构随核心迁移脚本变化；只增补字段、同步显示名与类型，
     * 结构中已不存在的字段标记弃用。始终不可经设计器编辑。
     */
    @Transactional
    public void registerCore(ConceptDef def) {
        if (!def.namespace().equals(CORE_NAMESPACE) || def.storage() != ConceptDef.Storage.CORE_TABLE) {
            throw new IllegalArgumentException("核心自注册只接受 ls.core 命名空间的核心显式表：" + def.code());
        }
        checkCorePollution(def);
        jdbc.update("""
            insert into meta_concept (code, namespace, display_name, storage, table_name, status, editable, description, published_at)
            values (?, ?, ?, 'CORE_TABLE', ?, 'PUBLISHED', false, ?, now())
            on conflict (code) do update set display_name = excluded.display_name, description = excluded.description, updated_at = now()""",
            def.code(), def.namespace(), def.displayName(), def.tableName(), def.description());
        Set<String> present = def.fields().stream().map(FieldDef::code).collect(Collectors.toSet());
        int order = 0;
        for (FieldDef f : def.fields()) {
            upsertField(def.code(), f, order++, def.version());
        }
        for (FieldDef existing : fields(def.code(), false)) {
            if (!present.contains(existing.code()) && existing.status() == FieldDef.FieldStatus.ACTIVE) {
                jdbc.update("update meta_concept_field set status = 'DEPRECATED', deprecated_at = now() where concept_code = ? and field_code = ?",
                    def.code(), existing.code());
            }
        }
    }

    /** 发布（新建或升级）扩展 Concept。返回发布后的定义。 */
    @Transactional
    public ConceptDef publish(ConceptDef def) {
        List<String> problems = validateForPublish(def);
        if (!problems.isEmpty()) {
            throw new LsBizException("CONCEPT_PUBLISH_REJECTED", "发布校验失败：" + problems.get(0), Map.of("problems", problems));
        }
        Optional<ConceptDef> old = find(def.code());
        int version = old.map(o -> o.version() + (o.status().equals("PUBLISHED") ? 1 : 0)).orElse(1);
        jdbc.update("""
            insert into meta_concept (code, namespace, display_name, storage, table_name, status, version, editable, owner_pack, description,
              published_at, parent_concept)
            values (?, ?, ?, ?, ?, 'PUBLISHED', ?, true, ?, ?, now(), ?)
            on conflict (code) do update set display_name = excluded.display_name, status = 'PUBLISHED', version = excluded.version,
              description = excluded.description, published_at = now(), updated_at = now()""",
            def.code(), def.namespace(), def.displayName(), def.storage().name(), def.tableName(), version, def.ownerPack(), def.description(),
            def.parentConcept());
        int order = 0;
        for (FieldDef f : def.fields()) {
            upsertField(def.code(), f, order++, version);
        }
        return get(def.code());
    }

    /** 发布前校验，返回全部问题（为空表示可发布）。 */
    public List<String> validateForPublish(ConceptDef def) {
        List<String> problems = new ArrayList<>();
        if (def.code() == null || !MetaNames.CONCEPT_CODE.matcher(def.code()).matches()) {
            problems.add("Concept 编码不合法：" + def.code() + "（形如 acme.ledger.SafetyCheck）");
            return problems;
        }
        if (def.namespace().equals(CORE_NAMESPACE) || def.namespace().startsWith(CORE_NAMESPACE + ".")) {
            problems.add("ls.core 命名空间只能由核心维护，扩展对象请使用 Pack 或客户命名空间");
            try {
                checkCorePollution(def);
            } catch (LsBizException e) {
                problems.add(e.getMessage());
            }
        }
        Optional<ConceptDef> old = find(def.code());
        if (old.isPresent()) {
            ConceptDef o = old.get();
            if (!o.editable()) problems.add("「" + o.displayName() + "」是核心显式表，不能经设计器修改结构");
            if (o.storage() != def.storage()) problems.add("存储方式不能修改");
            if (!o.tableName().equals(def.tableName())) problems.add("物理表名不能修改");
            if (!java.util.Objects.equals(o.parentConcept(), def.parentConcept())) problems.add("主子表关系不能修改");
        }
        if (def.parentConcept() != null) {
            Optional<ConceptDef> parent = find(def.parentConcept());
            if (parent.isEmpty() || parent.get().storage() != ConceptDef.Storage.MANAGED_TABLE) {
                problems.add("子表所属的主表 Concept 不存在或不是受控表：" + def.parentConcept());
            } else if (parent.get().parentConcept() != null) {
                problems.add("子表不能再挂子表：" + def.parentConcept());
            }
        }
        Map<String, FieldDef> oldFields = new LinkedHashMap<>();
        old.ifPresent(o -> o.fields().forEach(f -> oldFields.put(f.code(), f)));
        Set<String> seen = new java.util.HashSet<>();
        for (FieldDef f : def.fields()) {
            if (!seen.add(f.code())) problems.add("字段编码重复：" + f.code());
            if (f.code() == null || !MetaNames.FIELD_CODE.matcher(f.code()).matches()) {
                problems.add("字段编码不合法：" + f.code());
                continue;
            }
            if (f.columnName() == null || !MetaNames.IDENTIFIER.matcher(f.columnName()).matches()) {
                problems.add("字段 " + f.code() + " 的列名不合法：" + f.columnName());
            }
            if (f.dataType() == DataType.REF && f.refConcept() == null) problems.add("引用字段 " + f.code() + " 必须指定引用的 Concept");
            if (f.ext() && (f.required() || f.flag("queryable") || f.flag("sortable") || f.flag("indexed") || f.flag("unique") || f.stateField())) {
                problems.add("字段 " + f.code() + " 存入 ext_attrs，只能是非关键补充信息（不能必填、筛选、排序、索引或作为状态）");
            }
            if (RESERVED_COLUMNS.contains(f.columnName()) && !f.systemField()) problems.add("列名 " + f.columnName() + " 是保留字段");
            FieldDef prev = oldFields.get(f.code());
            if (prev == null) continue;
            if (prev.status() != FieldDef.FieldStatus.ACTIVE) {
                problems.add("字段编码 " + f.code() + " 已" + (prev.status() == FieldDef.FieldStatus.DEPRECATED ? "弃用" : "移除") + "，不能复用");
            }
            if (prev.ext() != f.ext()) problems.add("字段 " + f.code() + " 的存储位置（列 / ext_attrs）不能直接修改，须经评审迁移");
            if (prev.dataType() != f.dataType()) problems.add("字段 " + f.code() + " 的类型不能从 " + prev.dataType() + " 改为 " + f.dataType() + "（破坏性变更须经评审迁移）");
            if (!prev.columnName().equals(f.columnName())) problems.add("字段 " + f.code() + " 的列名不能修改");
        }
        for (FieldDef prev : oldFields.values()) {
            if (prev.status() == FieldDef.FieldStatus.ACTIVE && !seen.contains(prev.code())) {
                problems.add("字段 " + prev.code() + " 不能直接删除，须先弃用");
            }
        }
        return problems;
    }

    /** 调整显示名：只改展示，不影响编码与历史语义。 */
    @Transactional
    public void renameField(String conceptCode, String fieldCode, String displayName) {
        int n = jdbc.update("update meta_concept_field set display_name = ? where concept_code = ? and field_code = ?",
            displayName, conceptCode, fieldCode);
        if (n == 0) throw LsBizException.notFound("字段", conceptCode + "." + fieldCode);
    }

    @Transactional
    public void deprecateField(String conceptCode, String fieldCode) {
        int n = jdbc.update("""
            update meta_concept_field set status = 'DEPRECATED', deprecated_at = now()
            where concept_code = ? and field_code = ? and status = 'ACTIVE' and not system_field""", conceptCode, fieldCode);
        if (n == 0) throw new LsBizException("FIELD_NOT_DEPRECATABLE", "字段不存在、已弃用或是系统字段：" + fieldCode);
    }

    /**
     * 移除已弃用字段（物理迁移由 Pack 安装器执行）。仍被已发布表单、在途流程、指标或 Operation 引用时拒绝并列出引用方。
     */
    @Transactional
    public void removeField(String conceptCode, String fieldCode) {
        FieldDef f = fields(conceptCode, true).stream().filter(x -> x.code().equals(fieldCode)).findFirst()
            .orElseThrow(() -> LsBizException.notFound("字段", conceptCode + "." + fieldCode));
        if (f.status() != FieldDef.FieldStatus.DEPRECATED) {
            throw new LsBizException("FIELD_NOT_DEPRECATED", "字段须先弃用才能移除：" + fieldCode);
        }
        List<FieldUsageIndex.Usage> users = usages.activeUsagesOf(conceptCode, fieldCode);
        if (!users.isEmpty()) {
            throw new LsBizException("FIELD_IN_USE", "字段仍被引用，不能移除：" + users.stream().map(FieldUsageIndex.Usage::label)
                .collect(Collectors.joining("、")), Map.of("users", users));
        }
        jdbc.update("update meta_concept_field set status = 'REMOVED', removed_at = now() where concept_code = ? and field_code = ?",
            conceptCode, fieldCode);
    }

    private void checkCorePollution(ConceptDef def) {
        List<String> bad = new ArrayList<>();
        if (MetaNames.CORE_FORBIDDEN.matcher(def.code()).find()) bad.add(def.code());
        def.fields().stream().map(FieldDef::code).filter(c -> MetaNames.CORE_FORBIDDEN.matcher(c).find()).forEach(bad::add);
        if (!bad.isEmpty()) {
            throw new LsBizException("CORE_NAMESPACE_POLLUTED", "ls.core 不能包含扩展领域概念或字段：" + String.join("、", bad),
                Map.of("items", bad));
        }
    }

    private void upsertField(String concept, FieldDef f, int order, int version) {
        jdbc.update("""
            insert into meta_concept_field (concept_code, field_code, display_name, data_type, column_name, required, ref_concept, options,
              state_field, system_field, sort_order, since_version, props)
            values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb)
            on conflict (concept_code, field_code) do update set display_name = excluded.display_name, data_type = excluded.data_type,
              required = excluded.required, ref_concept = excluded.ref_concept, options = excluded.options,
              state_field = excluded.state_field, system_field = excluded.system_field, sort_order = excluded.sort_order, props = excluded.props,
              status = case when meta_concept_field.status = 'REMOVED' then 'REMOVED' else 'ACTIVE' end,
              deprecated_at = case when meta_concept_field.status = 'REMOVED' then meta_concept_field.deprecated_at end""",
            concept, f.code(), f.displayName(), f.dataType().name(), f.columnName(), f.required(), f.refConcept(),
            writeOptions(f.options()), f.stateField(), f.systemField(), f.sortOrder() != 0 ? f.sortOrder() : order, version, writeProps(f.props()));
    }

    private static String writeOptions(List<String> options) {
        try {
            return options == null ? null : JSON.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String writeProps(Map<String, Object> props) {
        try {
            return JSON.writeValueAsString(props == null ? Map.of() : props);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readProps(String json) {
        try {
            return json == null ? Map.of() : JSON.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 经评审的破坏性变更（改类型等）完成物理迁移后同步字段定义。只供 DDL 迁移执行器调用。
     */
    @Transactional
    public void applyReviewedFieldChange(String conceptCode, FieldDef field) {
        int n = jdbc.update("update meta_concept_field set data_type = ?, props = ?::jsonb where concept_code = ? and field_code = ?",
            field.dataType().name(), writeProps(field.props()), conceptCode, field.code());
        if (n == 0) throw LsBizException.notFound("字段", conceptCode + "." + field.code());
        jdbc.update("update meta_concept set version = version + 1, updated_at = now() where code = ?", conceptCode);
    }

    private static List<String> readOptions(String json) {
        try {
            return json == null ? null : JSON.readValue(json, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
