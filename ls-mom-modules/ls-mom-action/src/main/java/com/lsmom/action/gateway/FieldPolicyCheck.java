package com.lsmom.action.gateway;

import com.lsmom.action.api.OperationContext;

/** 字段策略检查（表单节点策略：只读、隐藏、必填等），由表单模块实现。不通过时抛 LsBizException。 */
public interface FieldPolicyCheck {

    void check(OperationContext ctx);
}
