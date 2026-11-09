package com.lsmom.core.org;

import java.time.LocalDate;

/** 组织单元。{@code validTo} 当日仍有效。 */
public record OrgUnit(Long id, String code, String name, OrgUnitType type, Long parentId, Long deptId,
                      Long leaderPartyId, LocalDate validFrom, LocalDate validTo, String constraintProfile) {

    public boolean activeOn(LocalDate day) {
        return !validFrom.isAfter(day) && (validTo == null || !validTo.isBefore(day));
    }
}
