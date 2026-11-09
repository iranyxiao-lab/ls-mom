package com.lsmom.core.org;

import com.lsmom.core.party.PartyService;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 把"本班组/本车间"等口径解析为可行级过滤的组织集合。 */
@Service
public class DataScopeService {

    private final PartyService parties;
    private final OrgUnitService orgUnits;

    public DataScopeService(PartyService parties, OrgUnitService orgUnits) {
        this.parties = parties;
        this.orgUnits = orgUnits;
    }

    public DataScope resolve(long partyId, DataScopeLevel level) {
        if (level == DataScopeLevel.ALL) {
            return DataScope.unrestricted();
        }
        Long primary = parties.primaryOrgUnitId(partyId).orElse(null);
        if (primary == null) {
            return new DataScope(false, Set.of(), partyId, level);
        }
        Set<Long> ids = new HashSet<>();
        switch (level) {
            case SELF, ORG -> ids.add(primary);
            case TEAM, DEPT_AND_CHILD -> ids.addAll(orgUnits.selfAndDescendantIds(primary));
            case WORKSHOP -> {
                List<OrgUnit> chain = orgUnits.selfAndAncestors(primary);
                long root = chain.stream().filter(o -> o.type() == OrgUnitType.WORKSHOP).findFirst().orElse(chain.get(0)).id();
                ids.addAll(orgUnits.selfAndDescendantIds(root));
            }
            default -> throw new IllegalStateException(level.name());
        }
        return new DataScope(false, Set.copyOf(ids), partyId, level);
    }
}
