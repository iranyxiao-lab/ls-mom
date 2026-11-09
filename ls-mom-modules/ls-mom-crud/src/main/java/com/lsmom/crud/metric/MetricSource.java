package com.lsmom.crud.metric;

import com.lsmom.core.org.DataScope;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.DataType;
import com.lsmom.meta.concept.FieldDef;
import com.lsmom.meta.concept.MetaNames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标来源：把 Concept 元数据映射为表、列与数据域条件。台账（MANAGED_TABLE）用 org_id / created_by / del_flag；
 * 核心对象（CORE_TABLE）用 orgUnitId 字段的列，本人范围用 createdByParty / reporterParty。标识符只来自元数据并统一加引号。
 */
public final class MetricSource {

    private final ConceptDef concept;
    private final Map<String, FieldDef> fields = new LinkedHashMap<>();
    private final boolean managed;

    public MetricSource(ConceptDef concept) {
        this.concept = concept;
        this.managed = concept.storage() == ConceptDef.Storage.MANAGED_TABLE;
        for (FieldDef f : concept.fields()) if (!f.ext() && f.status() != FieldDef.FieldStatus.REMOVED) fields.put(f.code(), f);
        if (managed) {
            fields.putIfAbsent("id", FieldDef.of("id", "ID", DataType.INTEGER));
            fields.putIfAbsent("status", FieldDef.of("status", "状态", DataType.STRING));
            fields.putIfAbsent("orgId", FieldDef.of("orgId", "组织", DataType.REF));
            fields.putIfAbsent("createdAt", FieldDef.of("createdAt", "创建时间", DataType.DATETIME));
            fields.putIfAbsent("updatedAt", FieldDef.of("updatedAt", "更新时间", DataType.DATETIME));
            fields.putIfAbsent("createdBy", FieldDef.of("createdBy", "创建人", DataType.REF));
        }
    }

    public ConceptDef concept() {
        return concept;
    }

    public String table() {
        return q(concept.tableName());
    }

    public boolean has(String field) {
        return fields.containsKey(field);
    }

    public FieldDef field(String code) {
        return fields.get(code);
    }

    public DataType type(String field) {
        FieldDef f = fields.get(field);
        return f == null ? null : f.dataType();
    }

    public String column(String alias, String field) {
        FieldDef f = fields.get(field);
        if (f == null) throw new IllegalArgumentException("未知字段：" + field);
        String col = managed ? switch (field) {
            case "orgId" -> "org_id";
            case "createdAt" -> "created_at";
            case "updatedAt" -> "updated_at";
            case "createdBy" -> "created_by";
            case "id" -> "id";
            case "status" -> "status";
            default -> f.columnName();
        } : f.columnName();
        return alias + "." + q(col);
    }

    public String orgField() {
        if (managed) return "orgId";
        return fields.containsKey("orgUnitId") ? "orgUnitId" : null;
    }

    /** 基础条件：未删除 + 调用者数据域（人与 Agent 相同）。 */
    public String baseWhere(String alias, DataScope scope, List<Object> args) {
        List<String> parts = new ArrayList<>();
        if (managed) parts.add(alias + ".\"del_flag\" = '0'");
        if (scope != null && !scope.all()) {
            List<String> or = new ArrayList<>();
            String org = orgField();
            if (org != null && scope.level() != DataScopeLevel.SELF && !scope.orgUnitIds().isEmpty()) {
                or.add(column(alias, org) + " in (" + String.join(", ", java.util.Collections.nCopies(scope.orgUnitIds().size(), "?")) + ")");
                args.addAll(scope.orgUnitIds());
            }
            String self = managed ? "createdBy" : fields.containsKey("createdByParty") ? "createdByParty" : fields.containsKey("reporterParty") ? "reporterParty" : null;
            if (self != null && scope.partyId() != null) {
                or.add(column(alias, self) + " = ?");
                args.add(scope.partyId());
            }
            parts.add(or.isEmpty() ? "1 = 0" : "(" + String.join(" or ", or) + ")");
        }
        return parts.isEmpty() ? "1 = 1" : String.join(" and ", parts);
    }

    public List<String> fieldCodes() {
        return List.copyOf(fields.keySet());
    }

    static String q(String identifier) {
        return "\"" + MetaNames.requireIdentifier(identifier) + "\"";
    }
}
