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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ActorResolver(PartyService parties, DataScopeService scopes, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.parties = parties;
        this.scopes = scopes;
        this.jdbc = jdbc;
    }

    /**
     * 模拟某人办理（设计器沙箱试运行用）：按该人账号当前的角色、菜单权限与数据范围构造调用主体，不需要登录会话。
     * 显示名标"模拟"，便于在试运行轨迹中区分。
     */
    public ActorContext simulate(long partyId) {
        Party party = parties.get(partyId);
        if (party.userId() == null) throw new com.lsmom.core.error.LsBizException("PARTY_NO_ACCOUNT", party.name() + " 没有登录账号，不能模拟办理");
        long userId = party.userId();
        boolean admin = LoginHelper.isSuperAdmin(userId);
        List<RoleDTO> roles = jdbc.query("""
            select r.role_id, r.role_key, r.role_name, r.data_scope from sys_user_role ur join sys_role r on r.role_id = ur.role_id
            where ur.user_id = ? and r.status = '0' and r.del_flag = '0' order by r.role_sort""", (rs, i) -> {
            RoleDTO d = new RoleDTO();
            d.setRoleId(rs.getLong("role_id"));
            d.setRoleKey(rs.getString("role_key"));
            d.setRoleName(rs.getString("role_name"));
            d.setDataScope(rs.getString("data_scope"));
            return d;
        }, userId);
        Set<String> perms = new HashSet<>(jdbc.queryForList("""
            select distinct m.perms from sys_user_role ur join sys_role r on r.role_id = ur.role_id and r.status = '0' and r.del_flag = '0'
              join sys_role_menu rm on rm.role_id = r.role_id join sys_menu m on m.menu_id = rm.menu_id
            where ur.user_id = ? and m.status = '0' and m.perms is not null and m.perms <> ''""", String.class, userId));
        DataScope scope = admin ? DataScope.unrestricted() : scopeOf(party.id(), roles);
        String roleNames = String.join("、", roles.stream().map(RoleDTO::getRoleName).toList());
        return new ActorContext(ActorContext.ActorType.HUMAN, userId, party.id(), null, admin, Set.copyOf(perms), scope,
            "模拟·" + party.name() + (roleNames.isEmpty() ? "" : "（" + roleNames + "）"));
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
