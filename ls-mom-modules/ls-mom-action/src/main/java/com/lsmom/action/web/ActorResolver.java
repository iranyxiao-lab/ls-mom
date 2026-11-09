package com.lsmom.action.web;

import com.lsmom.action.api.ActorContext;
import com.lsmom.core.org.DataScope;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.org.DataScopeService;
import com.lsmom.core.party.Party;
import com.lsmom.core.party.PartyService;
import org.dromara.common.core.domain.dto.RoleDTO;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 由当前登录会话（Sa-Token）构造调用主体：功能权限取菜单权限；数据域取各角色数据范围的并集。
 * RuoYi 数据范围映射：1 全部→ALL，2 自定义/4 本部门及以下/6 部门及以下或本人→DEPT_AND_CHILD，3 本部门→ORG，5 仅本人→SELF。
 */
@Component
public class ActorResolver {

    private final PartyService parties;
    private final DataScopeService scopes;

    public ActorResolver(PartyService parties, DataScopeService scopes) {
        this.parties = parties;
        this.scopes = scopes;
    }

    public ActorContext current() {
        LoginUser user = LoginHelper.getLoginUser();
        if (user == null) {
            throw new com.lsmom.core.error.LsBizException("UNAUTHENTICATED", "请先登录");
        }
        Party party = parties.requireByUserId(user.getUserId());
        boolean admin = LoginHelper.isSuperAdmin(user.getUserId());
        Set<String> perms = new HashSet<>(user.getMenuPermission() == null ? Set.of() : user.getMenuPermission());
        DataScope scope = admin ? DataScope.unrestricted() : scopeOf(party.id(), user.getRoles());
        return new ActorContext(ActorContext.ActorType.HUMAN, user.getUserId(), party.id(), null, admin, Set.copyOf(perms), scope,
            user.getNickname());
    }

    DataScope scopeOf(long partyId, List<RoleDTO> roles) {
        DataScope result = null;
        for (RoleDTO r : roles == null ? List.<RoleDTO>of() : roles) {
            DataScopeLevel lv = switch (r.getDataScope() == null ? "5" : r.getDataScope()) {
                case "1" -> DataScopeLevel.ALL;
                case "3" -> DataScopeLevel.ORG;
                case "5" -> DataScopeLevel.SELF;
                default -> DataScopeLevel.DEPT_AND_CHILD;
            };
            DataScope s = scopes.resolve(partyId, lv);
            result = result == null ? s : result.union(s);
        }
        return result == null ? scopes.resolve(partyId, DataScopeLevel.SELF) : result;
    }
}
