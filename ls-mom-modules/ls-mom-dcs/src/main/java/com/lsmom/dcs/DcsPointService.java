package com.lsmom.dcs;

import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 测点语义（编码、名称、单位、量程、来源系统）与测点-设备 / 车间 / 工艺映射、报警处置策略。 */
@Service
public class DcsPointService {

    public static final Set<String> POLICIES = Set.of("AUTO_DRAFT", "NOTIFY", "IGNORE");

    public record Point(Long id, String code, String name, String unit, BigDecimal rangeLow, BigDecimal rangeHigh, String sourceSystem,
                        Long assetId, Long orgUnitId, String processName, String alarmPolicy, Long ownerParty, boolean enabled) {
    }

    private final JdbcTemplate jdbc;

    public DcsPointService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按编码新增或更新测点。 */
    public Point save(Point p, Long by) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (p.code() == null || !p.code().matches("[A-Za-z0-9_.\\-]{1,64}")) errors.put("code", "编码必填，限字母、数字、点、下划线、横线");
        if (p.name() == null || p.name().isBlank()) errors.put("name", "名称必填");
        if (p.sourceSystem() == null || p.sourceSystem().isBlank()) errors.put("sourceSystem", "来源系统必填");
        String policy = p.alarmPolicy() == null ? "NOTIFY" : p.alarmPolicy();
        if (!POLICIES.contains(policy)) errors.put("alarmPolicy", "处置策略取 AUTO_DRAFT / NOTIFY / IGNORE");
        if (p.rangeLow() != null && p.rangeHigh() != null && p.rangeLow().compareTo(p.rangeHigh()) >= 0) errors.put("rangeHigh", "量程上限须大于下限");
        if ("AUTO_DRAFT".equals(policy) && p.orgUnitId() == null && p.assetId() == null) errors.put("orgUnitId", "自动草稿需要映射设备或组织");
        if (!errors.isEmpty()) throw LsBizException.validation(errors);
        jdbc.update("""
            insert into dcs_point (code, name, unit, range_low, range_high, source_system, asset_id, org_unit_id, process_name, alarm_policy,
                                   owner_party, enabled, created_by)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (code) do update set name = excluded.name, unit = excluded.unit, range_low = excluded.range_low,
              range_high = excluded.range_high, source_system = excluded.source_system, asset_id = excluded.asset_id,
              org_unit_id = excluded.org_unit_id, process_name = excluded.process_name, alarm_policy = excluded.alarm_policy,
              owner_party = excluded.owner_party, enabled = excluded.enabled, updated_at = now()""",
            p.code(), p.name().trim(), p.unit(), p.rangeLow(), p.rangeHigh(), p.sourceSystem(), p.assetId(), p.orgUnitId(), p.processName(),
            policy, p.ownerParty(), p.enabled(), by);
        return find(p.code()).orElseThrow();
    }

    public Optional<Point> find(String code) {
        return jdbc.query("select * from dcs_point where code = ?", (rs, i) -> new Point(rs.getLong("id"), rs.getString("code"),
            rs.getString("name"), rs.getString("unit"), rs.getBigDecimal("range_low"), rs.getBigDecimal("range_high"),
            rs.getString("source_system"), (Long) rs.getObject("asset_id"), (Long) rs.getObject("org_unit_id"), rs.getString("process_name"),
            rs.getString("alarm_policy"), (Long) rs.getObject("owner_party"), rs.getBoolean("enabled")), code).stream().findFirst();
    }

    /** 测点列表（附最近快照）。orgUnitIds 为空表示不限组织。 */
    public List<Map<String, Object>> list(Long assetId, String keyword, List<Long> orgUnitIds) {
        StringBuilder sql = new StringBuilder("""
            select p.*, o.name as org_name, s.bucket_start as last_bucket, s.avg_value as last_avg, s.last_value as last_value,
                   s.max_value as last_max, s.bad_count as last_bad, s.skew_count as last_skew,
                   (select count(*) from dcs_alarm a where a.point_code = p.code and a.status in ('NEW', 'DRAFTED', 'NOTIFIED', 'SUGGESTED')) as open_alarms
              from dcs_point p left join core_org_unit o on o.id = p.org_unit_id
              left join lateral (select * from dcs_telemetry_snapshot t where t.point_code = p.code order by t.bucket_start desc limit 1) s on true
             where 1 = 1""");
        List<Object> args = new ArrayList<>();
        if (assetId != null) {
            sql.append(" and p.asset_id = ?");
            args.add(assetId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and (p.code ilike ? or p.name ilike ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        if (orgUnitIds != null) {
            if (orgUnitIds.isEmpty()) return List.of();
            sql.append(" and p.org_unit_id in (").append(String.join(",", orgUnitIds.stream().map(x -> "?").toList())).append(")");
            args.addAll(orgUnitIds);
        }
        sql.append(" order by p.code");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }
}
