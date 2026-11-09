package com.lsmom.action.guard;

import com.lsmom.action.api.OperationContext;

/** 守卫：在状态迁移与写入前执行的约束检查。编码在 Operation/状态机定义中引用。 */
public interface Guard {

    String code();

    GuardResult check(OperationContext ctx);
}
