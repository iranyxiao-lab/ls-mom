package com.lsmom.flow.engine;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.util.CommandContextUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 引擎回调到 LS 运行时的唯一入口。生成的 BPMN 只引用 {@link LsServiceTask}、{@link LsEndListener} 两个白名单类，
 * 它们按引擎名找到对应的运行时（测试中可同时存在多个引擎）。
 */
public interface FlowHooks {

    /** 服务节点：RESOLVE（解析办理人）、ROUTE（条件分支取值）、OPERATION（执行已发布 Operation）、CC（抄送）、TIMEOUT（超时提醒/升级）。 */
    void serviceTask(String kind, String node, DelegateExecution execution);

    /** 流程到达结果结束事件（APPROVED / REJECTED）。 */
    void ended(String outcome, DelegateExecution execution);

    Map<String, FlowHooks> REGISTRY = new ConcurrentHashMap<>();

    static void register(String engineName, FlowHooks hooks) {
        REGISTRY.put(engineName, hooks);
    }

    static FlowHooks current() {
        String name = CommandContextUtil.getProcessEngineConfiguration().getEngineName();
        FlowHooks h = REGISTRY.get(name);
        if (h == null) throw new IllegalStateException("流程引擎 " + name + " 未注册运行时");
        return h;
    }
}
