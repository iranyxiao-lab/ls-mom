package com.lsmom.dcs;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DCS 集成配置（前缀 {@code lsmom.dcs}）。
 *
 * @param enabled           是否启用 Historian 增量读取（关闭时只提供已有点位与快照查询）
 * @param historianUrl      Historian（独立时序库）JDBC 地址；以只读连接访问
 * @param historianSchema   Historian 表所在 schema
 * @param snapshotSeconds   快照时间窗（默认 60 秒）
 * @param clockSkewSeconds  源时间比接收时间超前超过该值视为时钟异常
 * @param pollMs            增量读取间隔
 * @param linkWindowMinutes 报警与人工缺陷关联建议的时间窗口
 * @param overlapSeconds    每次增量读取回看的时间，防止并发提交时漏读；重算按主键覆盖，不会重复
 */
@ConfigurationProperties(prefix = "lsmom.dcs")
public record DcsProperties(Boolean enabled, String historianUrl, String historianUser, String historianPassword, String historianSchema,
                            Integer snapshotSeconds, Integer clockSkewSeconds, Long pollMs, Integer linkWindowMinutes, Integer overlapSeconds) {

    public DcsProperties {
        enabled = enabled != null && enabled;
        historianSchema = historianSchema == null || historianSchema.isBlank() ? "public" : historianSchema;
        snapshotSeconds = snapshotSeconds == null ? 60 : snapshotSeconds;
        clockSkewSeconds = clockSkewSeconds == null ? 60 : clockSkewSeconds;
        pollMs = pollMs == null ? 10_000L : pollMs;
        linkWindowMinutes = linkWindowMinutes == null ? 30 : linkWindowMinutes;
        overlapSeconds = overlapSeconds == null ? 120 : overlapSeconds;
        if (!historianSchema.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("lsmom.dcs.historian-schema 非法：" + historianSchema);
        if (snapshotSeconds < 1 || 3600 % snapshotSeconds != 0) throw new IllegalArgumentException("lsmom.dcs.snapshot-seconds 须整除 3600");
    }

    public static DcsProperties defaults() {
        return new DcsProperties(null, null, null, null, null, null, null, null, null, null);
    }

    public DcsProperties withSchema(String schema) {
        return new DcsProperties(enabled, historianUrl, historianUser, historianPassword, schema, snapshotSeconds, clockSkewSeconds, pollMs,
            linkWindowMinutes, overlapSeconds);
    }
}
