package com.lsmom.form;

import com.lsmom.action.api.OperationContext;

import java.util.Optional;

/**
 * 当前 Operation 所处的表单节点（由流程模块按待办任务实现：任务 → 表单编码/版本 → 节点键）。
 * 节点来自服务端任务记录，不取客户端报文，避免绕过节点策略。
 */
public interface NodePolicyResolver {

    record Binding(String formCode, int formVersion, String node) {
    }

    Optional<Binding> resolve(OperationContext ctx);
}
