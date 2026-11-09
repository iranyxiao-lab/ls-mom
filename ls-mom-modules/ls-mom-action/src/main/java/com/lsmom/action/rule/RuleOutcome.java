package com.lsmom.action.rule;

import com.lsmom.action.guard.ConstraintLevel;

import java.util.Map;

/**
 * 一条规则的求值结果。violated=true 表示约束未满足（或求值失败、超限，按规则失败处理）；
 * output 为决策表命中行的输出（ALL 策略时为 rows 列表）。
 */
public record RuleOutcome(String rule, ConstraintLevel level, boolean violated, String message, String field,
                          Map<String, Object> output, String error) {

    public static RuleOutcome pass(RuleDefinition r, Map<String, Object> output) {
        return new RuleOutcome(r.code(), r.level(), false, null, null, output, null);
    }
}
