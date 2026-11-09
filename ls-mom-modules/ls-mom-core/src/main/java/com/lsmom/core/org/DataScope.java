package com.lsmom.core.org;

import java.util.Set;

/**
 * 解析后的数据域。{@code all} 为 true 时不过滤；否则按 {@code orgUnitIds} 行级过滤，
 * {@code partyId} 用于"仅本人"口径。
 */
public record DataScope(boolean all, Set<Long> orgUnitIds, Long partyId, DataScopeLevel level) {

    public static DataScope unrestricted() {
        return new DataScope(true, Set.of(), null, DataScopeLevel.ALL);
    }

    /** 多个角色时取并集（任一为全部则全部）。 */
    public DataScope union(DataScope other) {
        if (all || other.all) return unrestricted();
        java.util.Set<Long> ids = new java.util.HashSet<>(orgUnitIds);
        ids.addAll(other.orgUnitIds);
        DataScopeLevel lv = level == DataScopeLevel.SELF ? other.level : level;
        return new DataScope(false, Set.copyOf(ids), partyId != null ? partyId : other.partyId, lv);
    }

    public boolean allowsOrg(Long orgUnitId) {
        return all || (orgUnitId != null && orgUnitIds.contains(orgUnitId));
    }
}
