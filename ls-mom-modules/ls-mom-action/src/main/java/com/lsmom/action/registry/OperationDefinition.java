package com.lsmom.action.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.Exposure;
import com.lsmom.action.api.IdempotencyPolicy;
import com.lsmom.action.api.Risk;

import java.util.List;

/** 已注册的 Operation 契约（来自 meta_operation）。 */
public record OperationDefinition(String code, String concept, String name, Kind kind, Risk risk, Exposure exposure,
                                  String permission, JsonNode inputSchema, List<String> guards, IdempotencyPolicy idempotency,
                                  boolean requiresVersion, String source, String status, int version, String description) {

    public enum Kind { HANDLER, TRANSITION, QUERY }
}
