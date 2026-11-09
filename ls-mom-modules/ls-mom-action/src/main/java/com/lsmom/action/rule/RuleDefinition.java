package com.lsmom.action.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.guard.ConstraintLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则定义（meta_rule 一个版本）。definition 结构：
 * <pre>
 * CEL：            {"when": "前置条件，可选", "assert": "必须为真的条件", "field": "L1 时定位的字段，可选",
 *                   "appliesTo": ["Operation 编码"], "facts": ["事实提供者名称"]}
 * DECISION_TABLE： {"hitPolicy": "FIRST|ALL", "rows": [{"when": "条件", "output": {...}}], "default": {...},
 *                   "appliesTo": [...], "facts": [...]}；输出含 outcome（PASS/WARN/BLOCK）与 message 时可作守卫
 * JAVA：           {"guard": "已注册的代码守卫编码", "appliesTo": [...]}
 * </pre>
 */
public record RuleDefinition(String code, int version, String concept, ConstraintLevel level, RuleKind kind, String name,
                             String message, JsonNode definition, String status, String ownerPack, boolean standard,
                             String overrides) {

    public List<String> appliesTo() {
        return strings("appliesTo");
    }

    public List<String> facts() {
        return strings("facts");
    }

    public String text(String field) {
        JsonNode n = definition.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    public boolean disabled() {
        return definition.path("disabled").asBoolean(false);
    }

    private List<String> strings(String field) {
        List<String> out = new ArrayList<>();
        definition.path(field).forEach(n -> out.add(n.asText()));
        return out;
    }
}
