package com.lsmom.action.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Operation 请求。{@code targetId} 为修改已有对象时的目标；{@code version} 为调用方持有的对象版本；
 * {@code simulate} 为真时完整执行后整体回滚（Agent SIMULATE 等级）。
 */
public record OperationRequest(JsonNode input, String targetId, Integer version, String idempotencyKey, boolean simulate) {

    public static OperationRequest of(JsonNode input) {
        return new OperationRequest(input, null, null, null, false);
    }

    public static OperationRequest on(String targetId, int version, JsonNode input) {
        return new OperationRequest(input == null ? JsonNodeFactory.instance.objectNode() : input, targetId, version, null, false);
    }

    public OperationRequest withKey(String key) {
        return new OperationRequest(input, targetId, version, key, simulate);
    }

    public OperationRequest simulated() {
        return new OperationRequest(input, targetId, version, idempotencyKey, true);
    }
}
