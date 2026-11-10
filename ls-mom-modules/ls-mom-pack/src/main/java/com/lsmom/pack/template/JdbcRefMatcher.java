package com.lsmom.pack.template;

import com.lsmom.meta.concept.MetaNames;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 在现有数据里查 Excel 取值：设备按编码或名称，人员按姓名，组织按名称。 */
public final class JdbcRefMatcher implements ExcelInference.RefMatcher {

    private final JdbcTemplate jdbc;

    public JdbcRefMatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ExcelInference.Match match(String concept, Set<String> values) {
        if (values.isEmpty()) return new ExcelInference.Match(0, null);
        String join = switch (concept) {
            case "ls.asset.Asset" -> {
                String t = MetaNames.managedTable(concept);
                Boolean exists = jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, t);
                yield Boolean.TRUE.equals(exists) ? "\"" + t + "\" x on x.del_flag = '0' and (x.asset_code = v or x.name = v)" : null;
            }
            case "ls.core.Party" -> "core_party x on x.status = 'ACTIVE' and x.name = v";
            case "ls.core.OrgUnit" -> "core_org_unit x on (x.valid_to is null or x.valid_to >= current_date) and x.name = v";
            default -> null;
        };
        if (join == null) return new ExcelInference.Match(0, null);
        List<Map<String, Object>> rows = jdbc.queryForList("select v, min(x.id) as id from unnest(?::text[]) v join " + join + " group by v",
            (Object) values.toArray(String[]::new));
        Long sample = rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
        return new ExcelInference.Match(rows.size(), sample);
    }
}
