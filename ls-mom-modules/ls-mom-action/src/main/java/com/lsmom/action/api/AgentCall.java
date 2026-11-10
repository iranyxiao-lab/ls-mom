package com.lsmom.action.api;

import java.util.function.Supplier;

/**
 * Agent 调用的附加上下文（当前线程）：批量草稿的 batch_id、已核验的审批单 ID。
 * 由 Agent 门面在调用 Operation 网关前设置，网关据此做暴露等级检查并写入审计。
 */
public record AgentCall(String batchId, String approvalId) {

    private static final ThreadLocal<AgentCall> CURRENT = new ThreadLocal<>();

    public static AgentCall current() {
        return CURRENT.get();
    }

    public static <T> T with(AgentCall call, Supplier<T> body) {
        AgentCall prev = CURRENT.get();
        CURRENT.set(call);
        try {
            return body.get();
        } finally {
            if (prev == null) CURRENT.remove();
            else CURRENT.set(prev);
        }
    }
}
