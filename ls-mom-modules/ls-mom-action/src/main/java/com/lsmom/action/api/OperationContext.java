package com.lsmom.action.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.registry.OperationDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次 Operation 执行的上下文，在网关、守卫、处理器之间传递。 */
public class OperationContext {

    private final OperationDefinition operation;
    private final ActorContext actor;
    private final OperationRequest request;
    private String targetId;
    private Integer targetVersion;
    private String targetStatus;
    private Long targetOrgUnitId;
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Object> eventPayload = new LinkedHashMap<>();
    private final List<PendingEvent> extraEvents = new ArrayList<>();
    private Map<String, Object> undoInput;

    public record PendingEvent(String type, String subjectConcept, String subjectId, Long orgUnitId, Map<String, Object> payload) {
    }

    public OperationContext(OperationDefinition operation, ActorContext actor, OperationRequest request) {
        this.operation = operation;
        this.actor = actor;
        this.request = request;
        this.targetId = request.targetId();
    }

    public OperationDefinition operation() { return operation; }
    public ActorContext actor() { return actor; }
    public OperationRequest request() { return request; }
    public JsonNode input() { return request.input(); }
    public String targetId() { return targetId; }
    public Integer targetVersion() { return targetVersion; }
    public String targetStatus() { return targetStatus; }
    public Long targetOrgUnitId() { return targetOrgUnitId; }
    public List<String> warnings() { return warnings; }
    public Map<String, Object> eventPayload() { return eventPayload; }
    public List<PendingEvent> extraEvents() { return extraEvents; }

    public long targetIdAsLong() {
        if (targetId == null) throw new com.lsmom.core.error.LsBizException("TARGET_REQUIRED", "该操作需要指定目标对象");
        return Long.parseLong(targetId);
    }

    /** 处理器创建或修改对象后登记结果目标。 */
    public void target(String id, Integer version, String status, Long orgUnitId) {
        this.targetId = id;
        this.targetVersion = version;
        this.targetStatus = status;
        this.targetOrgUnitId = orgUnitId;
    }

    /** 登记撤销输入（执行前的值）；Operation 注册了补偿 Operation 时结果会带上撤销方式。 */
    public void undoWith(Map<String, Object> input) {
        this.undoInput = input;
    }

    public Map<String, Object> undoInput() { return undoInput; }

    public void warn(String message) {
        warnings.add(message);
    }

    /** 主事件之外的附加事件（如通知整改负责人）。 */
    public void emit(String type, String subjectConcept, String subjectId, Long orgUnitId, Map<String, Object> payload) {
        extraEvents.add(new PendingEvent(type, subjectConcept, subjectId, orgUnitId, payload));
    }

    public String text(String field) {
        JsonNode n = input().get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    public Long longValue(String field) {
        JsonNode n = input().get(field);
        return n == null || n.isNull() ? null : n.asLong();
    }
}
