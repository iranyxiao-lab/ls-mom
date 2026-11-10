package com.lsmom.action.state;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 表驱动状态机定义（存于 meta_flow，kind = STATE_MACHINE）。
 * 每个迁移对应一个命名 Operation：校验当前状态 → 守卫 → 效果 → 写状态与版本 → 事件。
 */
public record StateMachineDefinition(String code, String concept, String displayName, List<String> states, String initial,
                                     List<Transition> transitions) {

    /** {@code undoOperation}：撤销该迁移的补偿 Operation（同一 Concept 的迁移），效果须以 {@code ctx.undoWith} 登记执行前的值。 */
    public record Transition(String operation, String name, List<String> from, String to, List<String> guards,
                             String permission, String risk, JsonNode inputSchema, String exposure, String undoOperation) {

        public Transition(String operation, String name, List<String> from, String to, List<String> guards,
                          String permission, String risk, JsonNode inputSchema, String exposure) {
            this(operation, name, from, to, guards, permission, risk, inputSchema, exposure, null);
        }
    }

    /** 终态：没有任何迁移可以离开的状态。 */
    public boolean terminal(String status) {
        return transitions.stream().noneMatch(t -> t.from().contains(status));
    }

    public List<Transition> allowedFrom(String status) {
        return transitions.stream().filter(t -> t.from().contains(status)).toList();
    }

    public Transition transition(String operation) {
        return transitions.stream().filter(t -> t.operation().equals(operation)).findFirst().orElse(null);
    }
}
