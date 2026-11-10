package com.lsmom.crud;

import com.lsmom.core.org.DataScope;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.org.OrgUnitService;
import com.lsmom.meta.concept.SharedReference;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 选用引用时的数据范围：声明为 {@link SharedReference} 的 Concept，"仅本人"用户可选用本组织及上级组织的记录
 * （例如操作工从本班组、本车间的设备登记缺陷）；其他情况与读取范围相同。
 */
public class ReferenceScopes {

    private final OrgUnitService orgUnits;
    private final Set<String> shared;

    public ReferenceScopes(OrgUnitService orgUnits, List<SharedReference> shared) {
        this.orgUnits = orgUnits;
        this.shared = shared == null ? Set.of() : Set.copyOf(shared.stream().map(SharedReference::concept).toList());
    }

    public DataScope forReference(DataScope scope, String targetConcept) {
        if (scope == null || scope.all() || scope.level() != DataScopeLevel.SELF || !shared.contains(targetConcept) || scope.orgUnitIds().isEmpty()) {
            return scope;
        }
        Set<Long> ids = new HashSet<>();
        for (Long o : scope.orgUnitIds()) orgUnits.selfAndAncestors(o).forEach(u -> ids.add(u.id()));
        return new DataScope(false, Set.copyOf(ids), scope.partyId(), DataScopeLevel.ORG);
    }

    /** 未配置时：引用范围与读取范围相同。 */
    public static DataScope same(ReferenceScopes r, DataScope scope, String targetConcept) {
        return r == null ? scope : r.forReference(scope, targetConcept);
    }
}
