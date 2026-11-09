package com.lsmom.flow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.LeaderKind;
import com.lsmom.core.org.LeaderResolution;
import com.lsmom.core.org.LeaderResolver;
import com.lsmom.core.org.OrgUnitService;
import com.lsmom.flow.model.FlowDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 命名办理人策略。只返回"可办"的主体：主体有效、登录账号未停用未删除，节点要求功能权限时还须具备该权限。
 * 解析为空时返回原因，由调用方生成异常待办（运行时）或阻断发布（发布校验）。
 */
@Component
public class AssigneeResolver {

    /** 解析上下文：发起人主体、业务对象所属组织、业务字段。 */
    public record Context(Long initiatorParty, Long bizOrgUnitId, Map<String, Object> attributes) {
    }

    public record Result(List<Long> parties, String reason) {
        public boolean empty() {
            return parties.isEmpty();
        }
    }

    private final JdbcTemplate jdbc;
    private final LeaderResolver leaders;
    private final OrgUnitService orgUnits;

    public AssigneeResolver(JdbcTemplate jdbc, LeaderResolver leaders, OrgUnitService orgUnits) {
        this.jdbc = jdbc;
        this.leaders = leaders;
        this.orgUnits = orgUnits;
    }

    public Result resolve(FlowDefinition.Assignee a, String permission, Context ctx) {
        if (a == null || a.strategy() == null) return new Result(List.of(), "节点未配置办理人策略");
        String s = a.strategy().toUpperCase();
        List<Long> raw = new ArrayList<>();
        String why;
        switch (s) {
            case "USER" -> {
                if (a.value() != null && a.value().isArray()) a.value().forEach(v -> raw.add(v.asLong()));
                else if (a.value() != null) raw.add(a.value().asLong());
                why = "指定人员";
            }
            case "INITIATOR" -> {
                if (ctx.initiatorParty() != null) raw.add(ctx.initiatorParty());
                why = "发起人";
            }
            case "ROLE" -> {
                String role = text(a.value());
                raw.addAll(jdbc.queryForList("""
                    select distinct p.id from sys_role r join sys_user_role ur on ur.role_id = r.role_id
                    join core_party p on p.user_id = ur.user_id
                    where r.role_key = ? and r.status = '0' and r.del_flag = '0'
                    order by p.id""", Long.class, role));
                if ("BIZ_ORG".equalsIgnoreCase(a.of()) && ctx.bizOrgUnitId() != null) raw.retainAll(membersOfSubtree(ctx.bizOrgUnitId()));
                why = "角色 " + role + ("BIZ_ORG".equalsIgnoreCase(a.of()) ? "（业务组织内）" : "");
            }
            case "DEPT", "TEAM" -> {
                long org = a.value() == null || a.value().isNull() ? requireOrg(ctx.bizOrgUnitId()) : a.value().asLong();
                raw.addAll(members(org));
                why = ("TEAM".equals(s) ? "班组 " : "部门 ") + org + " 成员";
            }
            case "FIELD" -> {
                String field = text(a.value());
                Object v = ctx.attributes() == null ? null : ctx.attributes().get(field);
                if (v instanceof Number n) raw.add(n.longValue());
                else if (v != null && String.valueOf(v).matches("\\d+")) raw.add(Long.parseLong(String.valueOf(v)));
                why = "业务字段 " + field;
            }
            case "ORG_LEADER", "DEPT_LEADER", "WORKSHOP_LEADER", "SUPERIOR_LEADER" -> {
                LeaderKind kind = switch (s) {
                    case "ORG_LEADER" -> LeaderKind.SELF;
                    case "DEPT_LEADER" -> LeaderKind.DEPARTMENT;
                    case "WORKSHOP_LEADER" -> LeaderKind.WORKSHOP;
                    default -> LeaderKind.SUPERIOR;
                };
                Long org = "BIZ_ORG".equalsIgnoreCase(a.of()) ? ctx.bizOrgUnitId() : primaryOrg(ctx.initiatorParty());
                if (org == null) return new Result(List.of(), "无法确定负责人所在组织");
                LeaderResolution r = leaders.resolve(org, kind);
                if (!r.resolved()) return new Result(List.of(), r.reason());
                raw.add(r.partyId());
                why = kind + " 负责人";
            }
            case "DATA_SCOPE" -> {
                // 具备该角色、且其数据范围覆盖业务组织（全部数据，或所在组织是业务组织本身或其上级）
                String role = text(a.value());
                long org = requireOrg(ctx.bizOrgUnitId());
                List<Long> ancestors = orgUnits.selfAndAncestors(org).stream().map(o -> o.id()).toList();
                raw.addAll(jdbc.queryForList("""
                    select distinct p.id from sys_role r join sys_user_role ur on ur.role_id = r.role_id
                    join core_party p on p.user_id = ur.user_id
                    left join core_party_org po on po.party_id = p.id and po.is_primary
                    where r.role_key = ? and r.status = '0' and r.del_flag = '0'
                      and (r.data_scope = '1' or po.org_unit_id = any(?))
                    order by p.id""", Long.class, role, ancestors.toArray(Long[]::new)));
                why = "数据范围覆盖业务组织的 " + role;
            }
            default -> throw new LsBizException("FLOW_INVALID", "未知办理人策略：" + a.strategy());
        }
        List<Long> usable = usable(new ArrayList<>(new LinkedHashSet<>(raw)), permission);
        if (usable.isEmpty()) {
            String reason = raw.isEmpty() ? "按「" + why + "」未解析到任何人员" : "按「" + why + "」解析到的人员均已离职、停用或无权限";
            return new Result(List.of(), reason);
        }
        return new Result(usable, null);
    }

    /** 无人可办时的接收人：流程管理员角色成员；没有时回退到超级管理员。 */
    public List<Long> admins(String adminRole) {
        List<Long> list = usable(jdbc.queryForList("""
            select distinct p.id from sys_role r join sys_user_role ur on ur.role_id = r.role_id
            join core_party p on p.user_id = ur.user_id
            where r.role_key = ? and r.status = '0' and r.del_flag = '0' order by p.id""", Long.class, adminRole), null);
        if (!list.isEmpty()) return list;
        return jdbc.queryForList("select id from core_party where user_id = 1", Long.class);
    }

    /** 主体是否可办：有效主体、账号正常；需要权限时须具备（超级管理员角色视为具备）。 */
    public List<Long> usable(List<Long> parties, String permission) {
        if (parties.isEmpty()) return List.of();
        List<Long> active = jdbc.queryForList("""
            select p.id from core_party p join sys_user u on u.user_id = p.user_id
            where p.id = any(?) and p.status = 'ACTIVE' and u.status = '0' and u.del_flag = '0'""", Long.class,
            (Object) parties.toArray(Long[]::new));
        Set<Long> ok = new LinkedHashSet<>(active);
        if (permission != null && !permission.isBlank()) {
            ok.retainAll(jdbc.queryForList("""
                select distinct p.id from core_party p join sys_user_role ur on ur.user_id = p.user_id
                join sys_role r on r.role_id = ur.role_id and r.status = '0' and r.del_flag = '0'
                left join sys_role_menu rm on rm.role_id = r.role_id
                left join sys_menu m on m.menu_id = rm.menu_id
                where p.id = any(?) and (r.role_key = 'superadmin' or m.perms = ? or m.perms = '*:*:*')""", Long.class,
                parties.toArray(Long[]::new), permission));
        }
        return parties.stream().filter(ok::contains).toList();
    }

    private List<Long> members(long orgUnitId) {
        return jdbc.queryForList("""
            select distinct party_id from core_party_org where org_unit_id = ?
              and (valid_to is null or valid_to >= current_date) order by party_id""", Long.class, orgUnitId);
    }

    private Set<Long> membersOfSubtree(long orgUnitId) {
        Set<Long> s = new LinkedHashSet<>();
        for (Long id : orgUnits.selfAndDescendantIds(orgUnitId)) s.addAll(members(id));
        return s;
    }

    private Long primaryOrg(Long party) {
        if (party == null) return null;
        return jdbc.queryForList("select org_unit_id from core_party_org where party_id = ? and is_primary", Long.class, party)
            .stream().findFirst().orElse(null);
    }

    private static long requireOrg(Long org) {
        if (org == null) throw new LsBizException("FLOW_INVALID", "办理人策略需要业务组织");
        return org;
    }

    private static String text(JsonNode v) {
        return v == null || v.isNull() ? "" : v.asText();
    }
}
