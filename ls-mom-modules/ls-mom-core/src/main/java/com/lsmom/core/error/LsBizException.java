package com.lsmom.core.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务规则拒绝。{@code code} 是稳定的机器可读编码（如 {@code WORK_INVALID_TRANSITION}），
 * {@code details} 给调用方可直接展示或据此引导下一步的结构化信息（如允许的动作、冲突的工作）。
 */
public class LsBizException extends RuntimeException {

    private final String code;
    private final Map<String, Object> details;

    public LsBizException(String code, String message) {
        this(code, message, Map.of());
    }

    public LsBizException(String code, String message, Map<String, ?> details) {
        super(message);
        this.code = code;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    /** 字段校验错误（字段 → 原因），非校验类错误返回空。 */
    @SuppressWarnings("unchecked")
    public Map<String, String> fieldErrors() {
        Object f = details.get("fields");
        return f instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
    }

    /** 字段校验失败：details.fields 为 字段 → 原因。 */
    public static LsBizException validation(Map<String, String> fieldErrors) {
        String first = fieldErrors.entrySet().iterator().next().getValue();
        return new LsBizException("VALIDATION_FAILED", first, Map.of("fields", fieldErrors));
    }

    public static LsBizException notFound(String what, Object id) {
        return new LsBizException("NOT_FOUND", what + "不存在：" + id, Map.of("id", String.valueOf(id)));
    }
}
