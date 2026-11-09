package com.lsmom.core.org;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 按组织解析负责人，供流程办理人策略与通知使用。
 * 负责人取组织单元的 leader_party_id，为空时回退到映射部门的 sys_dept.leader。
 */
@Service
public class LeaderResolver {

    private final OrgUnitService orgUnits;
    private final JdbcTemplate jdbc;

    public LeaderResolver(OrgUnitService orgUnits, JdbcTemplate jdbc) {
        this.orgUnits = orgUnits;
        this.jdbc = jdbc;
    }

    public LeaderResolution resolve(long orgUnitId, LeaderKind kind) {
        List<OrgUnit> chain = orgUnits.selfAndAncestors(orgUnitId);
        OrgUnit target = switch (kind) {
            case SELF -> chain.get(0);
            case SUPERIOR -> chain.size() > 1 ? chain.get(1) : null;
            case DEPARTMENT -> chain.stream().filter(o -> o.type() == OrgUnitType.DEPARTMENT).findFirst().orElse(null);
            case WORKSHOP -> chain.stream().filter(o -> o.type() == OrgUnitType.WORKSHOP).findFirst().orElse(null);
        };
        if (target == null) {
            return LeaderResolution.noAssignee(orgUnitId, switch (kind) {
                case SUPERIOR -> "该组织没有上级组织";
                case DEPARTMENT -> "该组织不属于任何部门";
                case WORKSHOP -> "该组织不属于任何车间";
                default -> "组织不存在";
            });
        }
        Long leader = leaderOf(target);
        return leader == null
            ? LeaderResolution.noAssignee(target.id(), "「" + target.name() + "」未设置负责人")
            : LeaderResolution.of(leader, target.id());
    }

    private Long leaderOf(OrgUnit unit) {
        if (unit.leaderPartyId() != null) {
            return unit.leaderPartyId();
        }
        return jdbc.queryForList("""
            select p.id from sys_dept d join core_party p on p.user_id = d.leader
            where d.dept_id = ? and p.status = 'ACTIVE'""", Long.class, unit.deptId()).stream().findFirst().orElse(null);
    }
}
