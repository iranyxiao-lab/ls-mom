package com.lsmom.action.state;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 表驱动状态机定义（存于 meta_flow，kind = STATE_MACHINE）。
 * 每个迁移对应一个命名 Operation：校验当前状态 → 守卫 → 效果 → 写状态与版本 → 事件。
 */
public record StateMachineDefinition(String code, String concept, String displayName, List<String> states, String initial,
                                     List<Transition> transitions) {

    public record Transition(String operation, String name, List<String> from, String to, List<String> guards,
                             String permission, String risk, JsonNode inputSchema, String exposure) {
    }

    public List<Transition> allowedFrom(String status) {
        return transitions.stream().filter(t -> t.from().contains(status)).toList();
    }

    public Transition transition(String operation) {
        return transitions.stream().filter(t -> t.operation().equals(operation)).findFirst().orElse(null);
    }
}
