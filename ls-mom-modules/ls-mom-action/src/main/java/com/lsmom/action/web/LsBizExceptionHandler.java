package com.lsmom.action.web;

import com.lsmom.core.error.LsBizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务规则拒绝统一转为 RuoYi 响应格式：HTTP 200，{@code code} 为语义状态码（400/403/404/409/422），
 * {@code data.errorCode} 为稳定的机器可读编码，{@code data.details} 为结构化细节（字段错误、允许动作、冲突对象等）。
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LsBizExceptionHandler {

    @ExceptionHandler(LsBizException.class)
    public Map<String, Object> handle(LsBizException e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", e.getCode());
        data.put("details", e.getDetails());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status(e.getCode()));
        body.put("msg", e.getMessage());
        body.put("data", data);
        log.info("[LS-MOM] 业务拒绝 {}：{}", e.getCode(), e.getMessage());
        return body;
    }

    static int status(String code) {
        return switch (code) {
            case "VALIDATION_FAILED", "TARGET_REQUIRED", "QUERY_REJECTED" -> 400;
            case "UNAUTHENTICATED" -> 401;
            case "ACCESS_DENIED" -> 403;
            case "NOT_FOUND", "OPERATION_NOT_FOUND", "CONCEPT_NOT_FOUND" -> 404;
            case "VERSION_CONFLICT", "CONCURRENT_MODIFICATION", "IDEMPOTENCY_CONFLICT" -> 409;
            default -> 422;
        };
    }
}
