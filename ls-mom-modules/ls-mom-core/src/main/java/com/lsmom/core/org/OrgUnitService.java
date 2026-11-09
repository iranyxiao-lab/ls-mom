package com.lsmom.core.org;

import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组织单元服务。新建组织会同时创建系统部门（由触发器生成组织单元），
 * 保证每个组织单元都有对应部门，RuoYi 数据权限可直接复用。
 */
@Service
public class OrgUnitService {

    static final String COLUMNS = "id, code, name, unit_type, parent_id, dept_id, leader_party_id, valid_from, valid_to, constraint_profile";
    static final RowMapper<OrgUnit> MAPPER = (rs, i) -> new OrgUnit(
        rs.getLong("id"), rs.getString("code"), rs.getString("name"), OrgUnitType.valueOf(rs.getString("unit_type")),
        (Long) rs.getObject("parent_id"), rs.getLong("dept_id"), (Long) rs.getObject("leader_party_id"),
        rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class), rs.getString("constraint_profile"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OrgUnitService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record CreateOrgUnit(String name, OrgUnitType type, Long parentId, LocalDate validFrom, LocalDate validTo) {
    }

    @Transactional
    public OrgUnit create(CreateOrgUnit cmd) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (cmd.name() == null || cmd.name().isBlank()) errors.put("name", "名称必填");
        if (cmd.type() == null) errors.put("type", "类型必填");
        if (cmd.parentId() == null && cmd.type() != OrgUnitType.COMPANY) errors.put("parentId", "非公司节点必须指定上级");
        if (!errors.isEmpty()) throw LsBizException.validation(errors);

        long parentDept = 0;
        String ancestors = "0";
        if (cmd.parentId() != null) {
            OrgUnit parent = requireActive(cmd.parentId(), today());
            parentDept = parent.deptId();
            ancestors = jdbc.queryForObject("select ancestors from sys_dept where dept_id = ?", String.class, parentDept) + "," + parentDept;
        }
        Long deptId = jdbc.queryForObject("select nextval('core_dept_id_seq')", Long.class);
        jdbc.update("""
            insert into sys_dept (dept_id, tenant_id, parent_id, ancestors, dept_name, dept_category, order_num, status, del_flag, create_time)
            values (?, '000000', ?, ?, ?, ?, 0, '0', '0', now())""", deptId, parentDept, ancestors, cmd.name(), cmd.type().name());
        jdbc.update("update core_org_unit set valid_from = coalesce(?, valid_from), valid_to = ? where dept_id = ?",
            cmd.validFrom(), cmd.validTo(), deptId);
        return jdbc.queryForObject("select " + COLUMNS + " from core_org_unit where dept_id = ?", MAPPER, deptId);
    }

    public OrgUnit get(long id) {
        List<OrgUnit> list = jdbc.query("select " + COLUMNS + " from core_org_unit where id = ?", MAPPER, id);
        if (list.isEmpty()) throw LsBizException.notFound("组织单元", id);
        return list.get(0);
    }

    public OrgUnit getByDept(long deptId) {
        List<OrgUnit> list = jdbc.query("select " + COLUMNS + " from core_org_unit where dept_id = ?", MAPPER, deptId);
        if (list.isEmpty()) throw LsBizException.notFound("部门对应的组织单元", deptId);
        return list.get(0);
    }

    /** 新业务引用组织前调用：失效或未生效的组织拒绝引用。 */
    public OrgUnit requireActive(long id, LocalDate day) {
        OrgUnit unit = get(id);
        if (!unit.activeOn(day)) {
            throw new LsBizException("ORG_INACTIVE", "组织「" + unit.name() + "」已失效或未生效，不能用于新业务",
                Map.of("orgUnitId", id, "validFrom", unit.validFrom().toString(),
                    "validTo", unit.validTo() == null ? "" : unit.validTo().toString()));
        }
        return unit;
    }

    /** 可供新业务选择的组织（当日有效）。 */
    public List<OrgUnit> listSelectable() {
        LocalDate day = today();
        return jdbc.query("select " + COLUMNS + " from core_org_unit where valid_from <= ? and (valid_to is null or valid_to >= ?) order by id",
            MAPPER, day, day);
    }

    @Transactional
    public void expire(long id, LocalDate validTo) {
        OrgUnit unit = get(id);
        if (validTo.isBefore(unit.validFrom())) {
            throw LsBizException.validation(Map.of("validTo", "失效日期不能早于生效日期"));
        }
        jdbc.update("update core_org_unit set valid_to = ?, updated_at = now(), version = version + 1 where id = ?", validTo, id);
    }

    @Transactional
    public void setLeader(long id, Long partyId) {
        get(id);
        jdbc.update("update core_org_unit set leader_party_id = ?, updated_at = now(), version = version + 1 where id = ?", partyId, id);
    }

    /** 自身及全部祖先，按由近到远排列（第一个是自身）。 */
    public List<OrgUnit> selfAndAncestors(long id) {
        return jdbc.query("""
            with recursive up as (
              select o.*, 0 as depth from core_org_unit o where o.id = ?
              union all
              select p.*, up.depth + 1 from core_org_unit p join up on p.id = up.parent_id
            ) select %s from up order by depth""".formatted(COLUMNS), MAPPER, id);
    }

    /** 自身及全部下级组织编号。 */
    public List<Long> selfAndDescendantIds(long id) {
        return jdbc.queryForList("""
            with recursive down as (
              select id from core_org_unit where id = ?
              union all
              select c.id from core_org_unit c join down on c.parent_id = down.id
            ) select id from down""", Long.class, id);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
