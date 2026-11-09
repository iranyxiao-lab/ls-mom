package com.lsmom.action.guard;

import com.lsmom.action.api.OperationContext;

/** 守卫：在状态迁移与写入前执行的约束检查。编码在 Operation/状态机定义中引用。 */
public interface Guard {

    String code();

    /** 约束级别；Java 守卫默认为 L3（跨对象、事务一致性或监管规则）。 */
    default ConstraintLevel level() {
        return ConstraintLevel.L3;
    }

    GuardResult check(OperationContext ctx);
}
