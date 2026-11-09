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

    public boolean allowsOrg(Long orgUnitId) {
        return all || (orgUnitId != null && orgUnitIds.contains(orgUnitId));
    }
}
