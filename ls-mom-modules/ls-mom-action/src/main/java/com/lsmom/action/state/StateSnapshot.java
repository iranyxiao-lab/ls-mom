package com.lsmom.action.state;

/** 状态机目标对象当前快照。 */
public record StateSnapshot(String id, String status, int version, Long orgUnitId, Long ownerParty) {
}
