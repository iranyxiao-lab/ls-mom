package com.lsmom.crud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.crud.query.LedgerModel;
import com.lsmom.meta.concept.FieldDef;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** 物理行 → 字段编码键的业务行（时间转 ISO 字符串、jsonb 解析、ext_attrs 展开为字段）。 */
final class LedgerRows {

    static final ObjectMapper JSON = new ObjectMapper();

    private LedgerRows() {
    }

    static Map<String, Object> toRow(LedgerModel m, Map<String, Object> raw, Predicate<String> readable) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.systemColumns().forEach((field, col) -> out.put(field, value(raw.get(col))));
        if (m.child()) out.put("parentId", raw.get("parent_id"));
        Map<String, Object> ext = asMap(value(raw.get("ext_attrs")));
        for (FieldDef f : m.fields().values()) {
            if (!readable.test(f.code())) continue;
            out.put(f.code(), f.ext() ? ext.get(f.code()) : value(raw.get(f.columnName())));
        }
        return out;
    }

    static Object value(Object v) {
        if (v instanceof Timestamp t) return t.toLocalDateTime().toString();
        if (v instanceof Date d) return d.toLocalDate().toString();
        if (v instanceof java.time.temporal.TemporalAccessor) return v.toString();
        if (v != null && v.getClass().getName().equals("org.postgresql.util.PGobject")) {
            String text = v.toString();
            try {
                return text == null ? null : JSON.readValue(text, Object.class);
            } catch (Exception e) {
                return text;
            }
        }
        if (v instanceof Integer i) return i.longValue();
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
