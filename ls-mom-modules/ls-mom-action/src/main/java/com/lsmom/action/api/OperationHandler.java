package com.lsmom.action.api;

/**
 * Java 实现的 Operation。实现类加 {@link LsOperation} 注解并注册为 Spring Bean。
 * 在网关的事务中执行；抛出异常即整体回滚。返回值作为结果 data。
 */
public interface OperationHandler {

    Object handle(OperationContext ctx);
}
