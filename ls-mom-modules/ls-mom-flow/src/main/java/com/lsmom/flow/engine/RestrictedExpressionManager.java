package com.lsmom.flow.engine;

import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.impl.el.ProcessExpressionManager;

import java.util.Map;

/** 受限表达式上下文：不暴露任何 Bean，且只编译 {@link ExpressionWhitelist} 允许的表达式。 */
public class RestrictedExpressionManager extends ProcessExpressionManager {

    public RestrictedExpressionManager() {
        super(Map.of());
    }

    @Override
    public Expression createExpression(String expression) {
        if (!ExpressionWhitelist.allowed(expression)) {
            throw new FlowableIllegalArgumentException("流程表达式不在白名单内：" + expression);
        }
        return super.createExpression(expression);
    }
}
