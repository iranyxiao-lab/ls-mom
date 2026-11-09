package com.lsmom.meta.concept;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Concept 字段定义。props 为字段属性：queryable、sortable、indexed、unique、maxLength、precision、scale、dictType、
 * readPermission、writePermission、ext（非关键补充信息，存 ext_attrs 不建列）。
 */
public record FieldDef(String code, String displayName, DataType dataType, String columnName, boolean required,
                       String refConcept, List<String> options, boolean stateField, boolean systemField,
                       FieldStatus status, int sortOrder, Map<String, Object> props) {

    public enum FieldStatus { ACTIVE, DEPRECATED, REMOVED }

    public FieldDef {
        props = props == null ? Map.of() : Map.copyOf(props);
    }

    public FieldDef(String code, String displayName, DataType dataType, String columnName, boolean required, String refConcept,
                    List<String> options, boolean stateField, boolean systemField, FieldStatus status, int sortOrder) {
        this(code, displayName, dataType, columnName, required, refConcept, options, stateField, systemField, status, sortOrder, Map.of());
    }

    public boolean flag(String prop) {
        return Boolean.TRUE.equals(props.get(prop));
    }

    public Integer intProp(String prop) {
        Object v = props.get(prop);
        return v instanceof Number n ? n.intValue() : null;
    }

    public String textProp(String prop) {
        Object v = props.get(prop);
        return v == null ? null : String.valueOf(v);
    }

    /** 存入 ext_attrs 的非关键补充字段（不建物理列）。 */
    public boolean ext() {
        return flag("ext");
    }

    public FieldDef withProp(String key, Object value) {
        Map<String, Object> p = new LinkedHashMap<>(props);
        p.put(key, value);
        return new FieldDef(code, displayName, dataType, columnName, required, refConcept, options, stateField, systemField, status, sortOrder, p);
    }

    public FieldDef queryable() {
        return withProp("queryable", true);
    }

    public FieldDef sortable() {
        return withProp("sortable", true);
    }

    public FieldDef withType(DataType type) {
        return new FieldDef(code, displayName, type, columnName, required, refConcept, options, stateField, systemField, status, sortOrder, props);
    }

    public static FieldDef of(String code, String displayName, DataType type) {
        return new FieldDef(code, displayName, type, MetaNames.toColumn(code), false, null, null, false, false, FieldStatus.ACTIVE, 0, Map.of());
    }

    public FieldDef asRequired() {
        return new FieldDef(code, displayName, dataType, columnName, true, refConcept, options, stateField, systemField, status, sortOrder, props);
    }

    public FieldDef asState() {
        return new FieldDef(code, displayName, dataType, columnName, required, refConcept, options, true, systemField, status, sortOrder, props);
    }

    public FieldDef withOptions(List<String> opts) {
        return new FieldDef(code, displayName, dataType, columnName, required, refConcept, opts, stateField, systemField, status, sortOrder, props);
    }

    public FieldDef ref(String concept) {
        return new FieldDef(code, displayName, DataType.REF, columnName, required, concept, options, stateField, systemField, status, sortOrder, props);
    }

    public FieldDef withDisplayName(String name) {
        return new FieldDef(code, name, dataType, columnName, required, refConcept, options, stateField, systemField, status, sortOrder, props);
    }
}
