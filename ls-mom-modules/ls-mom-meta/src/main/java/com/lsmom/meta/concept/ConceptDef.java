package com.lsmom.meta.concept;

import java.util.List;

/** Concept 定义。 */
public record ConceptDef(String code, String namespace, String displayName, Storage storage, String tableName,
                         boolean editable, String ownerPack, String description, List<FieldDef> fields,
                         String status, int version, String parentConcept) {

    public enum Storage { CORE_TABLE, MANAGED_TABLE }

    public ConceptDef(String code, String namespace, String displayName, Storage storage, String tableName, boolean editable,
                      String ownerPack, String description, List<FieldDef> fields, String status, int version) {
        this(code, namespace, displayName, storage, tableName, editable, ownerPack, description, fields, status, version, null);
    }

    public static ConceptDef managed(String code, String displayName, List<FieldDef> fields, String ownerPack) {
        String ns = code.substring(0, code.lastIndexOf('.'));
        return new ConceptDef(code, ns, displayName, Storage.MANAGED_TABLE, MetaNames.managedTable(code), true, ownerPack, null,
            fields, "DRAFT", 1, null);
    }

    /** 子表 Concept：行随主表在同一事务保存，物理表含 parent_id 与 line_no。 */
    public static ConceptDef child(String code, String displayName, List<FieldDef> fields, String ownerPack, String parentConcept) {
        ConceptDef c = managed(code, displayName, fields, ownerPack);
        return new ConceptDef(c.code(), c.namespace(), displayName, Storage.MANAGED_TABLE, c.tableName(), true, ownerPack, null, fields,
            "DRAFT", 1, parentConcept);
    }

    public boolean isChild() {
        return parentConcept != null;
    }

    public FieldDef field(String fieldCode) {
        return fields.stream().filter(f -> f.code().equals(fieldCode)).findFirst().orElse(null);
    }

    public ConceptDef withFields(List<FieldDef> newFields) {
        return new ConceptDef(code, namespace, displayName, storage, tableName, editable, ownerPack, description, newFields, status, version,
            parentConcept);
    }
}
