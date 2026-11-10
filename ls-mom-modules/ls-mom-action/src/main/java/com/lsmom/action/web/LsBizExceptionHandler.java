package com.lsmom.action.web;

import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.lsmom.core.document.AccessAuditService;
import com.lsmom.core.error.LsBizException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 业务规则拒绝统一转为 RuoYi 响应格式：HTTP 200，{@code code} 为语义状态码（400/403/404/409/422），
 * {@code data.errorCode} 为稳定的机器可读编码，{@code data.details} 为结构化细节（字段错误、允许动作、冲突对象等）。
 * 安全类拒绝（缺菜单 / 接口权限、越出数据域、字段策略、非办理人、Agent 边界）另写一条受控访问审计（core_access_audit）。
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LsBizExceptionHandler {

    /** 计入访问审计的拒绝编码（Operation 网关另有 op_audit，此处记录的是请求层面的拒绝）。 */
    static final Set<String> SECURITY = Set.of("ACCESS_DENIED", "FIELD_READONLY", "TASK_NOT_ASSIGNED", "GENERIC_EDIT_FORBIDDEN",
        "CHANGESET_RELEASE_HUMAN_ONLY", "CHANGESET_SELF_REVIEW", "AGENT_TOOL_DENIED", "AGENT_SIMULATE_ONLY", "AGENT_APPROVAL_REQUIRED",
        "AGENT_APPROVAL_INVALID", "AGENT_APPROVAL_MISMATCH", "AGENT_APPROVAL_USED", "AGENT_APPROVAL_EXPIRED", "AGENT_OBO_REQUIRED",
        "AGENT_KEY_INVALID", "AGENT_KEY_EXPIRED", "AGENT_KEY_REVOKED", "AGENT_DISABLED");

    private final ObjectProvider<AccessAuditService> audit;
    private final ObjectProvider<JdbcTemplate> jdbc;

    public LsBizExceptionHandler(ObjectProvider<AccessAuditService> audit, ObjectProvider<JdbcTemplate> jdbc) {
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @ExceptionHandler(LsBizException.class)
    public Map<String, Object> handle(LsBizException e, HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", e.getCode());
        data.put("details", e.getDetails());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status(e.getCode()));
        body.put("msg", e.getMessage());
        body.put("data", data);
        log.info("[LS-MOM] 业务拒绝 {}：{}", e.getCode(), e.getMessage());
        if (SECURITY.contains(e.getCode())) record(e.getCode(), request, e.getMessage());
        return body;
    }

    /** 缺少菜单 / 接口权限：与 RuoYi 的响应一致，同时写访问审计。 */
    @ExceptionHandler(NotPermissionException.class)
    public R<Void> notPermission(NotPermissionException e, HttpServletRequest request) {
        log.warn("[LS-MOM] 权限码校验失败 {} {}：{}", request.getMethod(), request.getRequestURI(), e.getPermission());
        record("PERMISSION_DENIED", request, "缺少权限 " + e.getPermission());
        return R.fail(403, "没有访问权限，请联系管理员授权");
    }

    @ExceptionHandler(NotRoleException.class)
    public R<Void> notRole(NotRoleException e, HttpServletRequest request) {
        log.warn("[LS-MOM] 角色校验失败 {} {}：{}", request.getMethod(), request.getRequestURI(), e.getRole());
        record("ROLE_DENIED", request, "缺少角色 " + e.getRole());
        return R.fail(403, "没有访问权限，请联系管理员授权");
    }

    private void record(String action, HttpServletRequest request, String reason) {
        try {
            AccessAuditService a = audit.getIfAvailable();
            if (a == null) return;
            String path = request == null ? "" : request.getMethod() + " " + request.getRequestURI();
            a.record(party(), action, "API", path.length() > 64 ? path.substring(0, 64) : path, false,
                reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason);
        } catch (RuntimeException ex) {
            log.warn("[LS-MOM] 写访问审计失败：{}", ex.getMessage());
        }
    }

    private Long party() {
        try {
            Long user = LoginHelper.getUserId();
            JdbcTemplate j = jdbc.getIfAvailable();
            if (user == null || j == null) return null;
            return j.queryForList("select id from core_party where user_id = ?", Long.class, user).stream().findFirst().orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static int status(String code) {
        return switch (code) {
            case "VALIDATION_FAILED", "TARGET_REQUIRED", "QUERY_REJECTED" -> 400;
            case "UNAUTHENTICATED", "AGENT_KEY_INVALID", "AGENT_KEY_EXPIRED", "AGENT_KEY_REVOKED", "AGENT_DISABLED" -> 401;
            case "ACCESS_DENIED", "AGENT_TOOL_DENIED", "AGENT_SIMULATE_ONLY", "AGENT_APPROVAL_REQUIRED", "AGENT_APPROVAL_INVALID",
                 "AGENT_APPROVAL_MISMATCH", "AGENT_APPROVAL_USED", "AGENT_APPROVAL_EXPIRED", "AGENT_OBO_REQUIRED" -> 403;
            case "AGENT_RATE_LIMITED" -> 429;
            case "NOT_FOUND", "OPERATION_NOT_FOUND", "CONCEPT_NOT_FOUND" -> 404;
            case "VERSION_CONFLICT", "CONCURRENT_MODIFICATION", "IDEMPOTENCY_CONFLICT", "FORM_VERSION_IMMUTABLE" -> 409;
            default -> 422;
        };
    }
}
