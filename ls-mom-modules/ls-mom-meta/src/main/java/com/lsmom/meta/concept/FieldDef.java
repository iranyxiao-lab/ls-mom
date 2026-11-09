package com.lsmom.meta.concept;

import java.util.List;

/** Concept 字段定义。 */
public record FieldDef(String code, String displayName, DataType dataType, String columnName, boolean required,
                       String refConcept, List<String> options, boolean stateField, boolean systemField,
                       FieldStatus status, int sortOrder) {

    public enum FieldStatus { ACTIVE, DEPRECATED, REMOVED }

    public static FieldDef of(String code, String displayName, DataType type) {
        return new FieldDef(code, displayName, type, MetaNames.toColumn(code), false, null, null, false, false, FieldStatus.ACTIVE, 0);
    }

    public FieldDef asRequired() {
        return new FieldDef(code, displayName, dataType, columnName, true, refConcept, options, stateField, systemField, status, sortOrder);
    }

    public FieldDef asState() {
        return new FieldDef(code, displayName, dataType, columnName, required, refConcept, options, true, systemField, status, sortOrder);
    }

    public FieldDef withOptions(List<String> opts) {
        return new FieldDef(code, displayName, dataType, columnName, required, refConcept, opts, stateField, systemField, status, sortOrder);
    }

    public FieldDef ref(String concept) {
        return new FieldDef(code, displayName, DataType.REF, columnName, required, concept, options, stateField, systemField, status, sortOrder);
    }

    public FieldDef withDisplayName(String name) {
        return new FieldDef(code, name, dataType, columnName, required, refConcept, options, stateField, systemField, status, sortOrder);
    }
}
