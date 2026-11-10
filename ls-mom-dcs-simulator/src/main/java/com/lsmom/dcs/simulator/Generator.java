package com.lsmom.dcs.simulator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * 确定性数据生成：给定配置与时间段 [from, to)，生成点位采样与报警事件，并按场景决定每条记录的送达时间（received_at）。
 * 送达时间晚于 to 的记录（如断网后补传）由调用方在到时再写入；回放模式直接全部写入。
 */
public final class Generator {

    public static final String GOOD = "GOOD";
    public static final String BAD = "BAD";

    /** 采样：ts 为源时间戳（时钟偏差时已偏移），trueTs 为真实发生时间。 */
    public record Sample(String sourceSystem, String point, Instant ts, Instant trueTs, double value, String quality, Instant receivedAt) {
    }

    /** 报警事件：同一 dedupKey 的多次发生各有独立 sourceEventId；重复送达保持同一 sourceEventId。 */
    public record AlarmEvent(String sourceSystem, String sourceEventId, String point, String alarmType, String dedupKey, Instant occurredAt,
                             Instant receivedAt, String quality, String severity, double value, String message, String traceId) {
    }

    public record Batch(List<Sample> samples, List<AlarmEvent> alarms) {
    }

    private final SimConfig cfg;
    private final Instant origin;

    /**
     * @param origin 场景时间原点（断网起点等按它偏移）
     */
    public Generator(SimConfig cfg, Instant origin) {
        this.cfg = cfg;
        this.origin = origin;
    }

    public Batch generate(Instant from, Instant to) {
        SimConfig.Scenario sc = cfg.scenarioOrNone();
        List<Sample> samples = new ArrayList<>();
        List<AlarmEvent> alarms = new ArrayList<>();
        int step = cfg.interval();
        Map<String, Boolean> above = new HashMap<>();
        long n = 0;
        for (SimConfig.Point p : cfg.points()) {
            // 上一个周期的状态：判断上穿（避免分段生成时在边界重复报警）
            above.put(p.code(), wasAbove(p, from, step));
        }
        for (long t = align(from.getEpochSecond(), step); t < to.getEpochSecond(); t += step) {
            Instant real = Instant.ofEpochSecond(t);
            for (SimConfig.Point p : cfg.points()) {
                n++;
                boolean bad = isBad(p, real, sc);
                double v = bad ? sc.badQuality().spike() : value(p, real);
                String q = bad ? BAD : GOOD;
                Instant ts = skewed(p, real, sc);
                Instant recv = delivered(real, sc, 0);
                Sample s = new Sample(cfg.sourceSystem(), p.code(), ts, real, v, q, recv);
                samples.add(s);
                if (sc.duplicateEvery() > 0 && n % sc.duplicateEvery() == 0) samples.add(new Sample(s.sourceSystem(), s.point(), s.ts(), real, v, q,
                    recv.plusSeconds(1)));
                // 报警：越限上穿产生一次发生；坏值同样会被现场系统报出，但带坏质量位
                if (p.alarmHigh() != null) {
                    boolean nowAbove = v > p.alarmHigh();
                    if (nowAbove && !above.get(p.code())) {
                        String type = p.alarmType() == null ? "HI" : p.alarmType();
                        long delay = sc.reorderSeconds() > 0 ? rnd(p.code(), t, 7).nextInt(sc.reorderSeconds() + 1) : 0;
                        AlarmEvent e = new AlarmEvent(cfg.sourceSystem(), cfg.sourceSystem() + ":" + p.code() + ":" + type + ":" + t, p.code(), type,
                            cfg.sourceSystem() + ":" + p.code() + ":" + type, ts, delivered(real, sc, delay), q, p.severity() == null ? "MEDIUM" : p.severity(),
                            round(v), (p.name() == null ? p.code() : p.name()) + (bad ? " 坏值越限" : " 越上限 " + p.alarmHigh() + (p.unit() == null ? "" : p.unit())),
                            "sim-" + Long.toHexString(rnd(p.code(), t, 11).nextLong()));
                        alarms.add(e);
                        if (sc.duplicateEvery() > 0 && alarms.size() % sc.duplicateEvery() == 0) {
                            alarms.add(new AlarmEvent(e.sourceSystem(), e.sourceEventId(), e.point(), e.alarmType(), e.dedupKey(), e.occurredAt(),
                                e.receivedAt().plusSeconds(2), e.quality(), e.severity(), e.value(), e.message(), e.traceId()));
                        }
                    }
                    // 坏值不改变越限状态；恢复需低于"上限 - 死区"，避免在阈值附近抖动反复报警（与现场 DCS 一致）
                    if (!bad) {
                        if (nowAbove) above.put(p.code(), true);
                        else if (v < p.alarmHigh() - deadband(p)) above.put(p.code(), false);
                    }
                }
            }
        }
        samples.sort(Comparator.comparing(Sample::receivedAt));
        alarms.sort(Comparator.comparing(AlarmEvent::receivedAt));
        return new Batch(samples, alarms);
    }

    private static double deadband(SimConfig.Point p) {
        return Math.max(p.noise() * 3, Math.abs(p.alarmHigh()) * 0.02);
    }

    /** 时段开始前的越限状态：向前回溯到最近一次明确越限或明确恢复（分段生成与整段生成一致）。 */
    private boolean wasAbove(SimConfig.Point p, Instant from, int step) {
        if (p.alarmHigh() == null) return false;
        long limit = (long) (Math.max(p.periodMinutes(), 1) * 60 * 2);
        for (long back = step; back <= limit; back += step) {
            Instant t = from.minusSeconds(back);
            if (isBad(p, t, cfg.scenarioOrNone())) continue;
            double v = value(p, t);
            if (v > p.alarmHigh()) return true;
            if (v < p.alarmHigh() - deadband(p)) return false;
        }
        return false;
    }

    double value(SimConfig.Point p, Instant real) {
        double period = Math.max(p.periodMinutes(), 1) * 60;
        double wave = p.amplitude() * Math.sin(2 * Math.PI * (real.getEpochSecond() % (long) period) / period);
        double noise = p.noise() == 0 ? 0 : (rnd(p.code(), real.getEpochSecond(), 3).nextDouble() * 2 - 1) * p.noise();
        return round(p.base() + wave + noise);
    }

    private boolean isBad(SimConfig.Point p, Instant real, SimConfig.Scenario sc) {
        SimConfig.BadQuality b = sc.badQuality();
        return b != null && b.point().equals(p.code()) && b.every() > 0 && (real.getEpochSecond() / cfg.interval()) % b.every() == 0;
    }

    private static Instant skewed(SimConfig.Point p, Instant real, SimConfig.Scenario sc) {
        SimConfig.ClockSkew k = sc.clockSkew();
        return k != null && k.point().equals(p.code()) ? real.plusSeconds(k.seconds()) : real;
    }

    /** 送达时间：断网时段内的数据在恢复后补传；报警另有随机延迟（乱序）。 */
    private Instant delivered(Instant real, SimConfig.Scenario sc, long delaySeconds) {
        SimConfig.Outage o = sc.outage();
        if (o != null) {
            Instant start = origin.plusSeconds(o.startMinutes() * 60L);
            Instant end = start.plusSeconds(o.durationMinutes() * 60L);
            if (!real.isBefore(start) && real.isBefore(end)) return end.plusSeconds(5 + delaySeconds);
        }
        return real.plusSeconds(delaySeconds);
    }

    private SplittableRandom rnd(String point, long t, int salt) {
        return new SplittableRandom(cfg.seed() ^ (point.hashCode() * 31L + t) * 0x9E3779B97F4A7C15L ^ salt);
    }

    private static long align(long epoch, int step) {
        return epoch % step == 0 ? epoch : epoch + (step - epoch % step);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
