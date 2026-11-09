package com.lsmom.flow.runtime;

import com.lsmom.form.NodePolicyResolver;

/**
 * 当前线程正在办理的流程节点（只在 {@link FlowRuntime} 办理待办、调用节点 Operation 期间存在）。
 * 节点来自服务端待办记录，不取客户端报文。
 */
public final class NodeContext {

    public record Current(String concept, String bizId, String taskId, NodePolicyResolver.Binding binding) {
    }

    private static final ThreadLocal<Current> CURRENT = new ThreadLocal<>();

    private NodeContext() {
    }

    public static Current get() {
        return CURRENT.get();
    }

    public static <T> T with(Current c, java.util.function.Supplier<T> body) {
        Current prev = CURRENT.get();
        CURRENT.set(c);
        try {
            return body.get();
        } finally {
            if (prev == null) CURRENT.remove();
            else CURRENT.set(prev);
        }
    }
}
