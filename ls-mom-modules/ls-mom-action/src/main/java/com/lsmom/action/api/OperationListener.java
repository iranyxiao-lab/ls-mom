package com.lsmom.action.api;

/**
 * Operation 成功后的扩展点：在同一事务内、事件写入之后调用（例如流程模块在"提交"成功后登记待启动的流程实例）。
 * 抛出异常会使整个 Operation 回滚；耗时或可重试的工作应登记后在事务提交后执行。
 */
public interface OperationListener {

    void afterSuccess(OperationContext ctx, OperationResult result);
}
