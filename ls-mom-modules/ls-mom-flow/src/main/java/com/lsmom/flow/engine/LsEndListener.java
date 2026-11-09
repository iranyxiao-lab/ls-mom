package com.lsmom.flow.engine;

import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;

/** 结果结束事件的监听器：把流程结果交给 LS 运行时登记业务回调。 */
public class LsEndListener implements ExecutionListener {

    private Expression outcome;

    @Override
    public void notify(DelegateExecution execution) {
        FlowHooks.current().ended(LsServiceTask.text(outcome, execution), execution);
    }

    public void setOutcome(Expression outcome) {
        this.outcome = outcome;
    }
}
