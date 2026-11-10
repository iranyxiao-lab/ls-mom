package com.lsmom.dcs.simulator;

import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入口。
 * <pre>
 *   回放：java -jar ls-mom-dcs-simulator.jar --mode backfill --hours 24 [--config sim.yaml]
 *   实时：java -jar ls-mom-dcs-simulator.jar --mode live [--backfill-hours 6] [--config sim.yaml]
 * </pre>
 * 实时模式带 {@code --backfill-hours} 时，若 Historian 尚无数据，先回放最近 N 小时再转实时（演示环境首次启动即有历史曲线）。
 * 连接：HISTORIAN_URL / HISTORIAN_USER / HISTORIAN_PASSWORD / HISTORIAN_SCHEMA（默认 public）。
 */
public final class SimulatorMain {

    private SimulatorMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parse(args);
        SimConfig cfg;
        if (opt.containsKey("config")) {
            try (InputStream in = new FileInputStream(opt.get("config"))) {
                cfg = SimConfig.read(in);
            }
        } else {
            cfg = SimConfig.defaults();
        }
        String url = env("HISTORIAN_URL", "jdbc:postgresql://tsdb:5432/lsmom_ts");
        try (Connection c = DriverManager.getConnection(url, env("HISTORIAN_USER", "lsmom"), env("HISTORIAN_PASSWORD", ""))) {
            HistorianWriter w = new HistorianWriter(c, env("HISTORIAN_SCHEMA", "public"));
            w.ensureSchema();
            String mode = opt.getOrDefault("mode", "live");
            if ("backfill".equals(mode)) {
                backfill(cfg, w, Integer.parseInt(opt.getOrDefault("hours", "24")), Instant.now().truncatedTo(ChronoUnit.SECONDS));
            } else {
                int hours = Integer.parseInt(opt.getOrDefault("backfill-hours", "0"));
                if (hours > 0 && w.isEmpty()) backfill(cfg, w, hours, Instant.now().truncatedTo(ChronoUnit.SECONDS));
                live(cfg, w);
            }
        }
    }

    /** 回放 [to - hours, to)：按无异常场景生成（断网等场景只在实时模式按时间原点出现），按小时分段写入。 */
    private static void backfill(SimConfig cfg, HistorianWriter w, int hours, Instant to) throws Exception {
        Instant from = to.minus(Duration.ofHours(hours));
        SimConfig calm = new SimConfig(cfg.sourceSystem(), cfg.seed(), cfg.interval(), cfg.points(), SimConfig.Scenario.NONE);
        Generator g = new Generator(calm, from);
        for (Instant t = from; t.isBefore(to); t = t.plus(Duration.ofHours(1))) {
            Instant end = t.plus(Duration.ofHours(1)).isAfter(to) ? to : t.plus(Duration.ofHours(1));
            Generator.Batch b = g.generate(t, end);
            w.writeSamples(b.samples());
            w.writeAlarms(b.alarms());
            System.out.printf("回放 %s：%d 条采样，%d 条报警%n", t, b.samples().size(), b.alarms().size());
        }
    }

    /** 实时：每秒生成上一秒的数据；送达时间未到的记录（断网补传、延迟报警）暂存，到时再写。 */
    private static void live(SimConfig cfg, HistorianWriter w) throws Exception {
        Instant origin = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Generator g = new Generator(cfg, origin);
        List<Generator.Sample> pendingSamples = new ArrayList<>();
        List<Generator.AlarmEvent> pendingAlarms = new ArrayList<>();
        Instant cursor = origin;
        System.out.printf("实时模拟开始：%d 个点位，周期 %d 秒%n", cfg.points().size(), cfg.interval());
        while (!Thread.currentThread().isInterrupted()) {
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            if (now.isAfter(cursor)) {
                Generator.Batch b = g.generate(cursor, now);
                pendingSamples.addAll(b.samples());
                pendingAlarms.addAll(b.alarms());
                cursor = now;
            }
            Instant due = Instant.now();
            List<Generator.Sample> s = pendingSamples.stream().filter(x -> !x.receivedAt().isAfter(due)).sorted(Comparator.comparing(Generator.Sample::receivedAt)).toList();
            List<Generator.AlarmEvent> a = pendingAlarms.stream().filter(x -> !x.receivedAt().isAfter(due)).toList();
            w.writeSamples(s);
            w.writeAlarms(a);
            pendingSamples.removeAll(s);
            pendingAlarms.removeAll(a);
            Thread.sleep(1000);
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("参数格式：--名称 值");
            m.put(args[i].substring(2), args[i + 1]);
        }
        return m;
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }
}
