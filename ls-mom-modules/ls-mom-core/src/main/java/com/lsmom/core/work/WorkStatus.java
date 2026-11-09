package com.lsmom.core.work;

/** 工作状态；迁移规则由状态机声明，只能经命名 Operation 推进。 */
public enum WorkStatus {
    DRAFT, READY, ASSIGNED, IN_PROGRESS, PENDING_CONFIRM, DONE, BLOCKED, CANCELLED;

    public boolean terminal() {
        return this == DONE || this == CANCELLED;
    }
}
