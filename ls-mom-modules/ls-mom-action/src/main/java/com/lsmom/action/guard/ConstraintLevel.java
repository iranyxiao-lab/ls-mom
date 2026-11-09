package com.lsmom.action.guard;

/**
 * 约束级别：L0 提示（不阻断）、L1 Schema/字段校验、L2 声明式守卫（CEL、决策表）、L3 代码守卫插件、L4 外部权威（人工审批或外部系统）。
 * L1 及以上在服务端阻断；L2 及以上只在服务端裁决（前端结论仅作提示）。
 */
public enum ConstraintLevel {
    L0, L1, L2, L3, L4;

    public boolean blocking() {
        return this != L0;
    }

    /** 严格程度：提示 0，其余阻断级别同为 1（L1～L4 表示约束来源与执行方式，不比较强弱）。 */
    public int strictness() {
        return blocking() ? 1 : 0;
    }
}
