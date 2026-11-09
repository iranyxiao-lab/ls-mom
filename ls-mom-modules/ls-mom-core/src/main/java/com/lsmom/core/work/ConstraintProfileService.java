package com.lsmom.core.work;

import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.OrgUnit;
import com.lsmom.core.org.OrgUnitService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 约束 Profile。组织启用的 Profile 对下级生效（最近的设置优先）；
 * 校验只在新的提交/分派时执行，历史已完成记录不会被重新判定。
 */
@Service
public class ConstraintProfileService {

    private static final RowMapper<ConstraintProfile> MAPPER = (rs, i) -> new ConstraintProfile(rs.getString("code"),
        rs.getString("name"), ConstraintProfile.ConflictMode.valueOf(rs.getString("conflict_mode")),
        rs.getBoolean("output_unit_required"), rs.getBoolean("material_required"), rs.getBoolean("verify_separation"));

    private final JdbcTemplate jdbc;
    private final OrgUnitService orgUnits;

    public ConstraintProfileService(JdbcTemplate jdbc, OrgUnitService orgUnits) {
        this.jdbc = jdbc;
        this.orgUnits = orgUnits;
    }

    public ConstraintProfile get(String code) {
        List<ConstraintProfile> list = jdbc.query("select * from core_constraint_profile where code = ?", MAPPER, code);
        if (list.isEmpty()) throw LsBizException.notFound("约束 Profile", code);
        return list.get(0);
    }

    public List<ConstraintProfile> list() {
        return jdbc.query("select * from core_constraint_profile order by builtin desc, code", MAPPER);
    }

    @Transactional
    public ConstraintProfile save(ConstraintProfile p) {
        jdbc.update("""
            insert into core_constraint_profile (code, name, conflict_mode, output_unit_required, material_required, verify_separation)
            values (?, ?, ?, ?, ?, ?)
            on conflict (code) do update set name = excluded.name, conflict_mode = excluded.conflict_mode,
              output_unit_required = excluded.output_unit_required, material_required = excluded.material_required,
              verify_separation = excluded.verify_separation, updated_at = now()
            where not core_constraint_profile.builtin""",
            p.code(), p.name(), p.conflictMode().name(), p.outputUnitRequired(), p.materialRequired(), p.verifySeparation());
        return get(p.code());
    }

    /** 为组织启用 Profile（传 null 表示改为继承上级）。 */
    @Transactional
    public void enableForOrg(long orgUnitId, String profileCode) {
        if (profileCode != null) get(profileCode);
        orgUnits.get(orgUnitId);
        jdbc.update("update core_org_unit set constraint_profile = ?, updated_at = now(), version = version + 1 where id = ?",
            profileCode, orgUnitId);
    }

    /** 组织当前生效的 Profile：自身或最近上级的设置，都没有则为默认宽松。 */
    public ConstraintProfile effectiveForOrg(long orgUnitId) {
        return orgUnits.selfAndAncestors(orgUnitId).stream()
            .map(OrgUnit::constraintProfile).filter(c -> c != null).findFirst()
            .map(this::get).orElseGet(() -> get(ConstraintProfile.DEFAULT));
    }
}
