package com.lsmom.action.api;

import java.util.List;

/** Operation 执行结果。{@code replayed} 表示由幂等记录直接返回首次结果。 */
public record OperationResult(String operation, String targetId, Integer version, String status, Object data,
                              List<String> warnings, List<String> events, boolean replayed, boolean simulated) {

    public OperationResult asReplay() {
        return new OperationResult(operation, targetId, version, status, data, warnings, events, true, simulated);
    }
}
