package com.lsmom.flow.runtime;

import com.lsmom.action.api.OperationContext;
import com.lsmom.form.NodePolicyResolver;

import java.util.Optional;

/** 表单节点策略的流程实现：Operation 在待办办理中执行时，按该待办所在节点（及实例冻结的表单版本）校验字段。 */
public class FlowNodePolicyResolver implements NodePolicyResolver {

    @Override
    public Optional<Binding> resolve(OperationContext ctx) {
        NodeContext.Current c = NodeContext.get();
        if (c == null || c.binding() == null) return Optional.empty();
        if (!c.concept().equals(ctx.operation().concept()) || !c.bizId().equals(ctx.targetId())) return Optional.empty();
        return Optional.of(c.binding());
    }
}
