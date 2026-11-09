package com.lsmom.crud.query;

import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.DataType;
import com.lsmom.meta.concept.FieldDef;
import com.lsmom.meta.concept.MetaNames;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 台账 Concept 的物理映射：字段 → 列（全部来自已发布元数据，经标识符白名单校验）。
 * 系统字段（id、orgId、status、createdBy、createdAt、updatedBy、updatedAt、version）只读。
 */
public final class LedgerModel {

    public static final Map<String, String> SYSTEM_MASTER = linked(
        "id", "id", "orgId", "org_id", "status", "status", "createdBy", "created_by", "createdAt", "created_at",
        "updatedBy", "updated_by", "updatedAt", "updated_at", "version", "version");
    public static final Map<String, String> SYSTEM_CHILD = linked("id", "id", "lineNo", "line_no");
    /** 默认允许筛选、排序的系统字段。 */
    public static final List<String> SYSTEM_QUERYABLE = List.of("id", "orgId", "status", "createdBy", "createdAt", "updatedAt");
    public static final List<String> SYSTEM_SORTABLE = List.of("id", "status", "createdAt", "updatedAt");

    private final ConceptDef concept;
    private final Map<String, FieldDef> fields = new LinkedHashMap<>();

    public LedgerModel(ConceptDef concept) {
        if (concept.storage() != ConceptDef.Storage.MANAGED_TABLE || !"PUBLISHED".equals(concept.status())) {
            throw new com.lsmom.core.error.LsBizException("CONCEPT_NOT_LEDGER", "不是已发布的台账 Concept：" + concept.code());
        }
        this.concept = concept;
        for (FieldDef f : concept.fields()) {
            if (f.status() == FieldDef.FieldStatus.ACTIVE && !f.systemField()) fields.put(f.code(), f);
        }
    }

    public ConceptDef concept() {
        return concept;
    }

    public String table() {
        return MetaNames.requireIdentifier(concept.tableName());
    }

    public boolean child() {
        return concept.isChild();
    }

    public Map<String, FieldDef> fields() {
        return fields;
    }

    public FieldDef field(String code) {
        return fields.get(code);
    }

    public Map<String, String> systemColumns() {
        return child() ? SYSTEM_CHILD : SYSTEM_MASTER;
    }

    /** 字段（含系统字段）对应的物理列；未知或 ext 字段返回 null。 */
    public String column(String fieldCode) {
        String sys = systemColumns().get(fieldCode);
        if (sys != null) return sys;
        FieldDef f = fields.get(fieldCode);
        if (f == null || f.ext()) return null;
        return MetaNames.requireIdentifier(f.columnName());
    }

    public DataType typeOf(String fieldCode) {
        if (fieldCode.equals("status")) return DataType.STRING;
        if (fieldCode.equals("createdAt") || fieldCode.equals("updatedAt")) return DataType.DATETIME;
        if (systemColumns().containsKey(fieldCode)) return DataType.INTEGER;
        return fields.get(fieldCode).dataType();
    }

    public boolean queryable(String fieldCode) {
        if (!child() && SYSTEM_QUERYABLE.contains(fieldCode)) return true;
        FieldDef f = fields.get(fieldCode);
        return f != null && !f.ext() && f.flag("queryable");
    }

    public boolean sortable(String fieldCode) {
        if (!child() && SYSTEM_SORTABLE.contains(fieldCode)) return true;
        FieldDef f = fields.get(fieldCode);
        return f != null && !f.ext() && f.flag("sortable");
    }

    /** 子表在详情/保存报文中的键：Concept 名首字母小写（如 LoanLine → loanLine）。 */
    public static String childKey(ConceptDef child) {
        String name = child.code().substring(child.code().lastIndexOf('.') + 1);
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static Map<String, String> linked(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }
}
