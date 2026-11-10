package com.lsmom.dcs.simulator;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 模拟器配置（YAML）。
 *
 * @param sourceSystem    来源系统编码（写入每条记录）
 * @param seed            随机种子：同一配置与时间段生成的数据完全一致，便于测试复现
 * @param intervalSeconds 采样周期（秒），默认 1（1Hz）
 * @param points          点位
 * @param scenario        异常场景
 */
public record SimConfig(String sourceSystem, long seed, int intervalSeconds, List<Point> points, Scenario scenario) {

    /**
     * 点位：值 = base + amplitude·sin(2πt/periodMinutes) + 噪声；超过 alarmHigh 的上穿时刻产生一次报警。
     */
    public record Point(String code, String name, String unit, double base, double amplitude, double periodMinutes, double noise,
                        Double alarmHigh, String alarmType, String severity) {
    }

    /**
     * @param outage       断网：从起点偏移 startMinutes 起持续 durationMinutes 的数据不实时送达，恢复后补传
     * @param duplicateEvery 每 N 条数据 / 报警重复送达一次（0 表示不重复）
     * @param reorderSeconds 报警送达的最大随机延迟（秒），大于报警间隔时会乱序到达
     * @param badQuality   坏质量位：指定点位每 every 个采样一个坏值，值为 spike（越限，用于验证坏值不参与报警判定）
     * @param clockSkew    时钟偏差：指定点位源时间戳比真实时间超前 seconds 秒
     */
    public record Scenario(Outage outage, int duplicateEvery, int reorderSeconds, BadQuality badQuality, ClockSkew clockSkew) {
        public static final Scenario NONE = new Scenario(null, 0, 0, null, null);
    }

    public record Outage(int startMinutes, int durationMinutes) {
    }

    public record BadQuality(String point, int every, double spike) {
    }

    public record ClockSkew(String point, int seconds) {
    }

    public int interval() {
        return intervalSeconds <= 0 ? 1 : intervalSeconds;
    }

    public Scenario scenarioOrNone() {
        return scenario == null ? Scenario.NONE : scenario;
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    public static SimConfig read(InputStream in) throws IOException {
        return YAML.readValue(in, SimConfig.class);
    }

    public static SimConfig defaults() throws IOException {
        try (InputStream in = SimConfig.class.getResourceAsStream("/simulator.yaml")) {
            return read(in);
        }
    }
}
