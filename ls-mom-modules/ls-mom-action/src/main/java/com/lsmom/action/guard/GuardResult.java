package com.lsmom.action.guard;

import java.util.Map;

/** 守卫结果：PASS 通过；WARN 提示但不阻断（L0）；BLOCK 阻断并整体回滚。 */
public record GuardResult(Outcome outcome, String code, String message, Map<String, Object> details) {

    public enum Outcome { PASS, WARN, BLOCK }

    public static GuardResult pass() {
        return new GuardResult(Outcome.PASS, null, null, Map.of());
    }

    public static GuardResult warn(String message) {
        return new GuardResult(Outcome.WARN, null, message, Map.of());
    }

    public static GuardResult block(String code, String message) {
        return new GuardResult(Outcome.BLOCK, code, message, Map.of());
    }

    public static GuardResult block(String code, String message, Map<String, Object> details) {
        return new GuardResult(Outcome.BLOCK, code, message, details);
    }
}
