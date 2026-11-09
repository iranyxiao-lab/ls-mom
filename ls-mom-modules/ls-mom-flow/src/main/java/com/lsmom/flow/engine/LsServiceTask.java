package com.lsmom.flow.engine;

import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;

/** 生成器使用的唯一服务任务类（字段 kind、node 以字符串常量注入）。 */
public class LsServiceTask implements JavaDelegate {

    private Expression kind;
    private Expression node;

    @Override
    public void execute(DelegateExecution execution) {
        FlowHooks.current().serviceTask(text(kind, execution), text(node, execution), execution);
    }

    static String text(Expression e, DelegateExecution execution) {
        return e == null ? null : String.valueOf(e.getValue(execution));
    }

    public void setKind(Expression kind) {
        this.kind = kind;
    }

    public void setNode(Expression node) {
        this.node = node;
    }
}
