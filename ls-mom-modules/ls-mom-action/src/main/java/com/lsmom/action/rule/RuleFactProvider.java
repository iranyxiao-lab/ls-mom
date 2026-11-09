package com.lsmom.action.rule;

import com.lsmom.action.api.OperationContext;

import java.util.Map;

/**
 * 跨对象事实提供者（L2 规则通过 {@code $context.<name>} 读取）。由 Java 实现、经评审随 Pack 发布，
 * 例如培训 Pack 提供 {@code qualification}（人员资质与有效期）。规则只能读取事实，不能触发写入或外部调用。
 */
public interface RuleFactProvider {

    String name();

    Object facts(OperationContext ctx, Map<String, Object> model);
}
