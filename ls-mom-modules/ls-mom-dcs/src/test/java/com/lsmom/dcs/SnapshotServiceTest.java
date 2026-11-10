package com.lsmom.dcs;

import com.lsmom.action.ActionFixture;
import com.lsmom.dcs.simulator.Generator;
import com.lsmom.dcs.simulator.SimConfig;
import com.lsmom.test.PgTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 15.2：点位语义与映射、只读时序访问、降采样快照（默认 1 分钟）；原始高频值不进业务库。 */
class SnapshotServiceTest {

    static final Instant ORIGIN = Instant.parse("2026-11-09T00:00:00Z");

    final ActionFixture f = new ActionFixture();
    final JdbcTemplate jdbc = f.core.jdbc;
    final DcsPointService points = new DcsPointService(jdbc);
    final long workshop = f.core.plant().workshop().id();

    void register(SimConfig cfg) {
        for (SimConfig.Point p : cfg.points()) {
            points.save(new DcsPointService.Point(null, p.code(), p.name(), p.unit(), null, null, cfg.sourceSystem(), 900_000_001L, workshop,
                "循环水", "NOTIFY", null, true), null);
        }
    }

    long rows(String point) {
        return jdbc.queryForObject("select count(*) from dcs_telemetry_snapshot where point_code = ?", Long.class, point);
    }

    @Test
    void 连续24小时1Hz推送后业务库只有分钟级快照() {
        String px = PgTestSupport.uniq("S");
        SimConfig cfg = DcsTestSupport.config(px, SimConfig.Scenario.NONE);
        String schema = DcsTestSupport.schema();
        Generator g = new Generator(cfg, ORIGIN);
        for (int h = 0; h < 24; h++) {
            Generator.Batch b = g.generate(ORIGIN.plus(Duration.ofHours(h)), ORIGIN.plus(Duration.ofHours(h + 1)));
            DcsTestSupport.write(schema, b.samples(), b.alarms());
        }
        register(cfg);
        HistorianReader reader = new HistorianReader(DcsTestSupport.readOnly(), schema);
        SnapshotService s = new SnapshotService(jdbc, reader, DcsProperties.defaults());

        assertThat(jdbc.queryForObject("select count(*) from " + schema + ".historian_point_value", Long.class)).isEqualTo(3 * 86_400L);
        assertThat(s.run()).isEqualTo(3 * 1440);
        for (SimConfig.Point p : cfg.points()) assertThat(rows(p.code())).isEqualTo(1440L);
        assertThat(jdbc.queryForObject("select min(sample_count) || '/' || max(sample_count) from dcs_telemetry_snapshot where point_code = ?",
            String.class, px + ".VIB")).isEqualTo("60/60");
        // 业务库（public）中没有原始值表
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = 'public' and table_name like 'historian%'",
            Long.class)).isZero();
        // 再跑一轮没有新数据：不重算
        assertThat(s.run()).isZero();
        // 快照查询与点位列表带最近快照
        assertThat(s.snapshots(px + ".VIB", ORIGIN, ORIGIN.plus(Duration.ofHours(1)))).hasSize(60);
        assertThat(points.list(900_000_001L, px, null)).hasSize(3).allSatisfy(m -> assertThat(m.get("last_bucket")).isNotNull());
        // Historian 连接只读
        assertThatThrownBy(() -> reader.jdbc().update("delete from " + schema + ".historian_point_value")).hasMessageContaining("read-only");
    }

    @Test
    void 断网补传与重复送达后快照无重复_坏值与时钟偏差计数不参与统计() {
        String px = PgTestSupport.uniq("O");
        SimConfig.Scenario sc = new SimConfig.Scenario(new SimConfig.Outage(60, 10), 500, 90, new SimConfig.BadQuality(px + ".TEMP", 900, 999),
            new SimConfig.ClockSkew(px + ".PRESS", 600));
        SimConfig cfg = DcsTestSupport.config(px, sc);
        String schema = DcsTestSupport.schema();
        Generator.Batch b = new Generator(cfg, ORIGIN).generate(ORIGIN, ORIGIN.plus(Duration.ofHours(3)));
        Instant outEnd = ORIGIN.plus(Duration.ofMinutes(70));
        List<Generator.Sample> before = new ArrayList<>(), after = new ArrayList<>();
        for (Generator.Sample x : b.samples()) (x.receivedAt().isBefore(outEnd) ? before : after).add(x);
        register(cfg);
        SnapshotService s = new SnapshotService(jdbc, new HistorianReader(DcsTestSupport.readOnly(), schema), DcsProperties.defaults());

        // 断网期间：只到达断网前的数据
        DcsTestSupport.write(schema, before, List.of());
        s.run();
        long outageBucket = jdbc.queryForObject("select count(*) from dcs_telemetry_snapshot where point_code = ? and bucket_start >= ? and bucket_start < ?",
            Long.class, px + ".VIB", java.sql.Timestamp.from(ORIGIN.plus(Duration.ofMinutes(60))), java.sql.Timestamp.from(outEnd));
        assertThat(outageBucket).isZero();
        // 恢复后补传
        DcsTestSupport.write(schema, after, List.of());
        s.run();
        assertThat(rows(px + ".VIB")).isEqualTo(180L);
        assertThat(rows(px + ".TEMP")).isEqualTo(180L);
        // 重复送达的采样只算一次
        assertThat(jdbc.queryForObject("select max(sample_count) from dcs_telemetry_snapshot where point_code = ?", Integer.class, px + ".VIB"))
            .isEqualTo(60);
        // 坏质量位：计数，不进入最大值
        assertThat(jdbc.queryForObject("select sum(bad_count) from dcs_telemetry_snapshot where point_code = ?", Long.class, px + ".TEMP")).isPositive();
        assertThat(jdbc.queryForObject("select max(max_value) from dcs_telemetry_snapshot where point_code = ?", Double.class, px + ".TEMP"))
            .isLessThan(999);
        // 时钟偏差：计数并排除
        assertThat(jdbc.queryForObject("select sum(skew_count) from dcs_telemetry_snapshot where point_code = ?", Long.class, px + ".PRESS")).isPositive();
        // 重放全部数据（再次补传）：快照行数不变
        DcsTestSupport.write(schema, b.samples(), List.of());
        s.run();
        assertThat(rows(px + ".VIB")).isEqualTo(180L);
    }

    @Test
    void 点位校验() {
        assertThatThrownBy(() -> points.save(new DcsPointService.Point(null, "bad code", "", null, java.math.BigDecimal.TEN, java.math.BigDecimal.ONE,
            "", null, null, null, "WRITE", null, true), null))
            .isInstanceOfSatisfying(com.lsmom.core.error.LsBizException.class, e -> assertThat(((java.util.Map<?, ?>) e.getDetails().get("fields")).keySet().stream().map(String::valueOf).toList())
                .contains("code", "name", "sourceSystem", "alarmPolicy", "rangeHigh"));
    }
}
