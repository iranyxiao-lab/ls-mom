package com.lsmom.flow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.state.StateSnapshot;
import com.lsmom.action.state.TransitionEffect;
import com.lsmom.core.error.LsBizException;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.FieldDef;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流程节点 Operation（状态迁移类）的字段写入效果：把待办办理时提交的字段一并写入业务表。
 * 字段是否可写由表单节点策略在网关第 6 步校验；脱离待办直接调用该 Operation 时不接受任何字段输入。
 */
public class FieldWriteEffect implements TransitionEffect {

    private final String operation;
    private final String concept;
    private final ConceptRegistry concepts;

    public FieldWriteEffect(String operation, String concept, ConceptRegistry concepts) {
        this.operation = operation;
        this.concept = concept;
        this.concepts = concepts;
    }

    @Override
    public String operation() {
        return operation;
    }

    @Override
    public Map<String, Object> apply(OperationContext ctx, StateSnapshot before) {
        Map<String, Object> changes = new LinkedHashMap<>();
        JsonNode in = ctx.input();
        if (in == null || in.isEmpty()) return changes;
        Map<String, FieldDef> fields = new LinkedHashMap<>();
        concepts.get(concept).fields().forEach(f -> fields.put(f.code(), f));
        NodeContext.Current node = NodeContext.get();
        Iterator<Map.Entry<String, JsonNode>> it = in.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            FieldDef f = fields.get(e.getKey());
            if (f == null || f.systemField() || f.stateField() || f.ext()) continue; // 非业务列（如 comment）不写表
            if (node == null) {
                throw new LsBizException("FIELD_READONLY", "字段只能在流程待办中按节点策略提交：" + e.getKey(), Map.of("fields", Map.of(e.getKey(), "不允许直接修改")));
            }
            changes.put(f.code(), value(f, e.getValue()));
        }
        return changes;
    }

    private static Object value(FieldDef f, JsonNode v) {
        if (v == null || v.isNull()) return null;
        return switch (f.dataType()) {
            case INTEGER -> v.asLong();
            case DECIMAL -> new BigDecimal(v.asText());
            case BOOLEAN -> v.asBoolean();
            case DATE -> LocalDate.parse(v.asText());
            case DATETIME -> LocalDateTime.parse(v.asText());
            case REF -> v.isNumber() ? v.asLong() : Long.parseLong(v.asText());
            default -> v.asText();
        };
    }
}
