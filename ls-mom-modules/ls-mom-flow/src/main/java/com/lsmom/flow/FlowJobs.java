package com.lsmom.flow;

import com.lsmom.flow.runtime.FlowReconciler;
import com.lsmom.flow.runtime.FlowRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时作业：每分钟重试启动失败的实例与失败的回调；每 10 分钟对账（只读，差异写日志，修复经接口人工触发）。 */
@Component
@ConditionalOnProperty(name = "lsmom.flow.jobs", havingValue = "true", matchIfMissing = true)
public class FlowJobs {

    private static final Logger log = LoggerFactory.getLogger(FlowJobs.class);

    private final FlowRuntime runtime;
    private final FlowReconciler reconciler;

    public FlowJobs(FlowRuntime runtime, FlowReconciler reconciler) {
        this.runtime = runtime;
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${lsmom.flow.retry-interval-ms:60000}", initialDelay = 30000)
    public void retry() {
        int n = runtime.retryPending();
        if (n > 0) log.info("流程同步重试成功 {} 项", n);
    }

    /** FlowReconcileJob：比对引擎运行时与待办读模型、回调日志。 */
    @Scheduled(fixedDelayString = "${lsmom.flow.reconcile-interval-ms:600000}", initialDelay = 120000)
    public void reconcile() {
        FlowReconciler.Report r = reconciler.check();
        if (!r.diffs().isEmpty()) log.warn("流程对账发现 {} 项差异：{}", r.diffs().size(), r.diffs().stream().limit(20).toList());
    }
}
