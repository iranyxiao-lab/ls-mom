package com.lsmom.dcs.simulator;

import com.lsmom.test.PgTestSupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 15.1：模拟器按配置输出各类场景（1Hz 点位值、越限报警、断网补传、重复、乱序、坏质量位、时钟偏差）。 */
class GeneratorTest {

    private static final Instant ORIGIN = Instant.parse("2026-11-10T00:00:00Z");

    private static SimConfig cfg(SimConfig.Scenario sc) {
        return new SimConfig("DCS-T", 7, 1, List.of(
            new SimConfig.Point("P.VIB", "振动", "mm/s", 3.2, 1.6, 30, 0.2, 4.5, "HI", "MEDIUM"),
            new SimConfig.Point("P.TEMP", "温度", "℃", 58, 6, 120, 0.5, 70.0, "HI", "HIGH"),
            new SimConfig.Point("P.PRESS", "压力", "MPa", 0.45, 0.03, 60, 0.005, null, null, null)), sc);
    }

    @Test
    void 默认配置可读() throws Exception {
        SimConfig d = SimConfig.defaults();
        assertThat(d.points()).extracting(SimConfig.Point::code).contains("P101.VIB", "P101.TEMP", "P101.PRESS");
        assertThat(d.scenario().outage().durationMinutes()).isEqualTo(10);
    }

    @Test
    void 无异常场景_1Hz每点每天86400条_越限上穿产生报警且结果可复现() {
        Generator g = new Generator(cfg(SimConfig.Scenario.NONE), ORIGIN);
        Generator.Batch b = g.generate(ORIGIN, ORIGIN.plus(Duration.ofHours(24)));
        Map<String, Long> perPoint = b.samples().stream().collect(Collectors.groupingBy(Generator.Sample::point, Collectors.counting()));
        assertThat(perPoint).containsEntry("P.VIB", 86_400L).containsEntry("P.TEMP", 86_400L).containsEntry("P.PRESS", 86_400L);
        assertThat(b.samples()).allMatch(s -> s.quality().equals(Generator.GOOD) && s.receivedAt().equals(s.trueTs()));
        // 振动 30 分钟一个周期：每周期上穿一次 → 同一 dedup_key 多次发生，各有独立事件编号
        List<Generator.AlarmEvent> vib = b.alarms().stream().filter(a -> a.point().equals("P.VIB")).toList();
        // 带死区：每个周期只报一次，不在阈值附近抖动
        assertThat(vib).hasSizeBetween(40, 50);
        assertThat(vib).extracting(Generator.AlarmEvent::dedupKey).containsOnly("DCS-T:P.VIB:HI");
        assertThat(vib.stream().map(Generator.AlarmEvent::sourceEventId).distinct().count()).isEqualTo(vib.size());
        assertThat(b.alarms()).noneMatch(a -> a.point().equals("P.PRESS"));
        assertThat(new Generator(cfg(SimConfig.Scenario.NONE), ORIGIN).generate(ORIGIN, ORIGIN.plusSeconds(600)).samples())
            .isEqualTo(g.generate(ORIGIN, ORIGIN.plusSeconds(600)).samples());
    }

    @Test
    void 分段生成与整段生成一致_边界不重复报警() {
        Generator g = new Generator(cfg(SimConfig.Scenario.NONE), ORIGIN);
        Generator.Batch whole = g.generate(ORIGIN, ORIGIN.plus(Duration.ofHours(2)));
        Generator.Batch a = g.generate(ORIGIN, ORIGIN.plus(Duration.ofHours(1)));
        Generator.Batch c = g.generate(ORIGIN.plus(Duration.ofHours(1)), ORIGIN.plus(Duration.ofHours(2)));
        assertThat(a.alarms().size() + c.alarms().size()).isEqualTo(whole.alarms().size());
    }

    @Test
    void 断网补传_重复_乱序_坏值_时钟偏差() {
        SimConfig.Scenario sc = new SimConfig.Scenario(new SimConfig.Outage(60, 10), 500, 90, new SimConfig.BadQuality("P.TEMP", 900, 999),
            new SimConfig.ClockSkew("P.PRESS", 600));
        Generator.Batch b = new Generator(cfg(sc), ORIGIN).generate(ORIGIN, ORIGIN.plus(Duration.ofHours(3)));
        Instant outStart = ORIGIN.plusSeconds(3600);
        Instant outEnd = outStart.plusSeconds(600);

        // 断网 10 分钟内的采样在恢复后才送达（补传）
        List<Generator.Sample> inOutage = b.samples().stream().filter(s -> !s.trueTs().isBefore(outStart) && s.trueTs().isBefore(outEnd)).toList();
        assertThat(inOutage).isNotEmpty().allMatch(s -> !s.receivedAt().isBefore(outEnd));
        // 重复送达：同一点位同一时间戳出现两次
        long distinct = b.samples().stream().map(s -> s.point() + "@" + s.ts()).distinct().count();
        assertThat(b.samples().size()).isGreaterThan((int) distinct);
        // 坏质量位：温度点周期性坏值，值越限
        assertThat(b.samples().stream().filter(s -> s.quality().equals(Generator.BAD))).isNotEmpty()
            .allMatch(s -> s.point().equals("P.TEMP") && s.value() == 999);
        assertThat(b.alarms()).anyMatch(a -> a.quality().equals(Generator.BAD) && a.point().equals("P.TEMP"));
        // 时钟偏差：压力点源时间比真实时间超前 600 秒
        assertThat(b.samples().stream().filter(s -> s.point().equals("P.PRESS"))).allMatch(s -> Duration.between(s.trueTs(), s.ts()).getSeconds() == 600);
        // 乱序：报警按送达时间排序后，发生时间不再单调
        List<Instant> occurred = b.alarms().stream().filter(a -> a.point().equals("P.VIB")).map(Generator.AlarmEvent::occurredAt).toList();
        boolean outOfOrder = false;
        for (int i = 1; i < occurred.size(); i++) if (occurred.get(i).isBefore(occurred.get(i - 1))) outOfOrder = true;
        assertThat(outOfOrder || b.alarms().stream().anyMatch(a -> !a.receivedAt().equals(a.occurredAt()))).isTrue();
    }

    @Test
    void 写入时序库() throws Exception {
        String schema = PgTestSupport.uniq("hist").toLowerCase().replaceAll("[^a-z0-9_]", "_");
        try (Connection c = PgTestSupport.dataSource().getConnection()) {
            HistorianWriter w = new HistorianWriter(c, schema);
            w.ensureSchema();
            Generator.Batch b = new Generator(cfg(SimConfig.Scenario.NONE), ORIGIN).generate(ORIGIN, ORIGIN.plusSeconds(3600));
            assertThat(w.writeSamples(b.samples())).isEqualTo(3 * 3600);
            w.writeAlarms(b.alarms());
        }
        assertThat(PgTestSupport.jdbc().queryForObject("select count(*) from " + schema + ".historian_point_value", Long.class)).isEqualTo(10_800L);
        assertThat(PgTestSupport.jdbc().queryForObject("select count(distinct dedup_key) from " + schema + ".historian_alarm_event", Long.class))
            .isGreaterThanOrEqualTo(1L);
    }
}
