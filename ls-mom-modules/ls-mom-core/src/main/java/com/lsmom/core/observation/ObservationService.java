package com.lsmom.core.observation;

import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事实观察：人工读数、DCS 降采样快照、外部数据。每条是单个时刻的单个观察项，
 * 不接收波形或批量序列（高频数据留在时序库）。
 */
@Service
public class ObservationService {

    static final RowMapper<Observation> MAPPER = (rs, i) -> new Observation(rs.getLong("id"), rs.getString("object_type"),
        rs.getString("object_id"), rs.getString("item_code"), rs.getBigDecimal("value_num"), rs.getString("value_text"),
        rs.getString("unit"), rs.getObject("observed_at", LocalDateTime.class), Observation.Source.valueOf(rs.getString("source")),
        rs.getString("quality"), (Long) rs.getObject("org_unit_id"), (Long) rs.getObject("recorded_by"));

    private final JdbcTemplate jdbc;

    public ObservationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Observation record(Observation o) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (o.objectType() == null || o.objectId() == null) errors.put("object", "观察对象必填");
        if (o.itemCode() == null || o.itemCode().isBlank()) errors.put("itemCode", "观察项必填");
        if (o.valueNum() == null && (o.valueText() == null || o.valueText().isBlank())) errors.put("value", "观察值必填");
        if (o.valueText() != null && (o.valueText().length() > 500 || o.valueText().trim().startsWith("["))) {
            errors.put("valueText", "观察值只能是单个读数，波形与序列数据请写入时序库");
        }
        if (o.observedAt() == null) errors.put("observedAt", "观察时间必填");
        if (o.source() == null) errors.put("source", "来源必填");
        if (!errors.isEmpty()) throw LsBizException.validation(errors);
        Long id = jdbc.queryForObject("""
            insert into core_observation (object_type, object_id, item_code, value_num, value_text, unit, observed_at, source, quality,
              org_unit_id, recorded_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
            o.objectType(), o.objectId(), o.itemCode(), o.valueNum(), o.valueText(), o.unit(), o.observedAt(), o.source().name(),
            o.quality() == null ? "GOOD" : o.quality(), o.orgUnitId(), o.recordedBy());
        return jdbc.queryForObject("select * from core_observation where id = ?", MAPPER, id);
    }

    public List<Observation> listByObject(String objectType, String objectId, int limit) {
        return jdbc.query("select * from core_observation where object_type = ? and object_id = ? order by observed_at desc, id desc limit ?",
            MAPPER, objectType, objectId, Math.min(Math.max(limit, 1), 1000));
    }
}
