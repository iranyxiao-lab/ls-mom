package com.lsmom.action.state;

import com.lsmom.action.api.OperationContext;

import java.util.Map;

/**
 * 迁移附带的业务效果（如分派写入分派记录、阻塞记录原因）。返回需要随状态一起写入的列。
 * 编码与迁移的 Operation 编码一致。
 */
public interface TransitionEffect {

    String operation();

    Map<String, Object> apply(OperationContext ctx, StateSnapshot before);
}
