package com.lsmom.action.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.Exposure;
import com.lsmom.action.api.IdempotencyPolicy;
import com.lsmom.action.api.Risk;

import java.util.List;

/**
 * 已注册的 Operation 契约（来自 meta_operation）。
 * {@code undoOperation}：撤销本动作的补偿 Operation；为空表示不可撤销，界面须先确认再执行。
 */
public record OperationDefinition(String code, String concept, String name, Kind kind, Risk risk, Exposure exposure,
                                  String permission, JsonNode inputSchema, List<String> guards, IdempotencyPolicy idempotency,
                                  boolean requiresVersion, String source, String status, int version, String description,
                                  String undoOperation) {

    public enum Kind { HANDLER, TRANSITION, QUERY }

    public OperationDefinition(String code, String concept, String name, Kind kind, Risk risk, Exposure exposure,
                               String permission, JsonNode inputSchema, List<String> guards, IdempotencyPolicy idempotency,
                               boolean requiresVersion, String source, String status, int version, String description) {
        this(code, concept, name, kind, risk, exposure, permission, inputSchema, guards, idempotency, requiresVersion, source, status,
            version, description, null);
    }
}
