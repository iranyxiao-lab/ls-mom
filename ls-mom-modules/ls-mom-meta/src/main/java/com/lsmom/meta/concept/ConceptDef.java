package com.lsmom.meta.concept;

import java.util.List;

/** Concept 定义。 */
public record ConceptDef(String code, String namespace, String displayName, Storage storage, String tableName,
                         boolean editable, String ownerPack, String description, List<FieldDef> fields,
                         String status, int version) {

    public enum Storage { CORE_TABLE, MANAGED_TABLE }

    public static ConceptDef managed(String code, String displayName, List<FieldDef> fields, String ownerPack) {
        String ns = code.substring(0, code.lastIndexOf('.'));
        return new ConceptDef(code, ns, displayName, Storage.MANAGED_TABLE, MetaNames.managedTable(code), true, ownerPack, null,
            fields, "DRAFT", 1);
    }

    public FieldDef field(String fieldCode) {
        return fields.stream().filter(f -> f.code().equals(fieldCode)).findFirst().orElse(null);
    }

    public ConceptDef withFields(List<FieldDef> newFields) {
        return new ConceptDef(code, namespace, displayName, storage, tableName, editable, ownerPack, description, newFields, status, version);
    }
}
