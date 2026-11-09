package com.lsmom.core.org;

/**
 * 负责人解析结果。解析不到时 {@link #resolved()} 为 false，调用方必须据此阻断发布或生成异常待办，
 * 不能当作"无需处理"。
 */
public record LeaderResolution(boolean resolved, Long partyId, Long orgUnitId, String reason) {

    public static LeaderResolution of(long partyId, long orgUnitId) {
        return new LeaderResolution(true, partyId, orgUnitId, null);
    }

    public static LeaderResolution noAssignee(Long orgUnitId, String reason) {
        return new LeaderResolution(false, null, orgUnitId, reason);
    }

    public String code() {
        return resolved ? "RESOLVED" : "NO_ASSIGNEE";
    }
}
