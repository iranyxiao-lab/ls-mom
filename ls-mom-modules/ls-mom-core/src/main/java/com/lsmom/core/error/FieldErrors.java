package com.lsmom.core.error;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一的字段错误结构 {@code {field, table, rowIndex, code, message, fix}}，随 VALIDATION_FAILED 放在 {@code details.errors}。
 * 子表单元格的键为 {@code 子表[行].字段}（行号从 0 开始），拆成 table、rowIndex 与 field（{@code 子表.字段}）。
 * 同时保留 {@code details.fields}（键 → 文字）兼容旧调用方。
 */
public final class FieldErrors {

    private static final Pattern CELL = Pattern.compile("^([A-Za-z][A-Za-z0-9]*)\\[(\\d+)]\\.([A-Za-z][A-Za-z0-9]*)$");
    private static final Pattern ROW_PREFIX = Pattern.compile("^第 \\d+ 行：");

    private FieldErrors() {
    }

    /** 字段错误：field 为字段键或 子表.字段；rowIndex 仅子表单元格有；code 为稳定编码；fix 为可选的修复提示。 */
    public record FieldError(String key, String field, String table, Integer rowIndex, String code, String message, String fix) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("field", field);
            if (table != null) m.put("table", table);
            if (rowIndex != null) m.put("rowIndex", rowIndex);
            m.put("code", code);
            m.put("message", message);
            if (fix != null) m.put("fix", fix);
            return m;
        }
    }

    public static FieldError of(String key, String code, String message, String fix) {
        Matcher m = CELL.matcher(key);
        if (m.matches()) {
            return new FieldError(key, m.group(1) + "." + m.group(3), m.group(1), Integer.parseInt(m.group(2)), code,
                ROW_PREFIX.matcher(message).replaceFirst(""), fix);
        }
        return new FieldError(key, key, null, null, code, message, fix);
    }

    /** 由 键 → 文字 及可选的 键 → 编码、键 → 修复提示 生成结构化列表；没有编码的记为 INVALID。 */
    public static List<Map<String, Object>> from(Map<?, ?> fields, Map<?, ?> codes, Map<?, ?> fixes) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (fields == null) return out;
        fields.forEach((k, v) -> {
            String key = String.valueOf(k);
            Object code = codes == null ? null : codes.get(key);
            Object fix = fixes == null ? null : fixes.get(key);
            out.add(of(key, code == null ? "INVALID" : String.valueOf(code), String.valueOf(v), fix == null ? null : String.valueOf(fix)).toMap());
        });
        return out;
    }

    /**
     * 补全 VALIDATION_FAILED 的细节：已有 errors 时原样返回；有 fields 时生成 errors（编码取 details.codes，修复提示取 details.fixes）。
     */
    public static Map<String, Object> complete(Map<String, Object> details) {
        if (details == null || details.containsKey("errors") || !(details.get("fields") instanceof Map<?, ?> fields)) return details;
        Map<String, Object> out = new LinkedHashMap<>(details);
        out.put("errors", from(fields, details.get("codes") instanceof Map<?, ?> c ? c : null, details.get("fixes") instanceof Map<?, ?> f ? f : null));
        return out;
    }
}
