package com.lsmom.action.api;

import java.util.List;
import java.util.Map;

/**
 * Operation 执行结果。{@code replayed} 表示由幂等记录直接返回首次结果。
 * {@code undo} 不为空时表示可在短时间内用补偿 Operation 撤销本次执行（输入为执行前的值）。
 */
public record OperationResult(String operation, String targetId, Integer version, String status, Object data,
                              List<String> warnings, List<String> events, boolean replayed, boolean simulated, Undo undo) {

    /** 撤销方式：对同一对象以 input 执行补偿 Operation（版本取本结果的 version）。 */
    public record Undo(String operation, String targetId, Map<String, Object> input) {
    }

    public OperationResult(String operation, String targetId, Integer version, String status, Object data,
                           List<String> warnings, List<String> events, boolean replayed, boolean simulated) {
        this(operation, targetId, version, status, data, warnings, events, replayed, simulated, null);
    }

    public OperationResult asReplay() {
        return new OperationResult(operation, targetId, version, status, data, warnings, events, true, simulated, undo);
    }
}
