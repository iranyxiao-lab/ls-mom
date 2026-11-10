package com.lsmom.dcs;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Historian（独立时序库）只读访问。业务系统从不写入 Historian：连接本身设为只读事务，误写会被数据库拒绝。
 * 原始高频值只在这里；业务库只保存降采样快照与报警引用。
 */
public class HistorianReader implements AutoCloseable {

    /** 原始采样的降采样聚合结果（一个点位一个时间窗）。 */
    public record Bucket(String pointCode, Instant bucketStart, Double avg, Double min, Double max, Double last, int samples, int bad, int skew) {
    }

    /** 原始报警事件。 */
    public record RawAlarm(String sourceSystem, String sourceEventId, String pointCode, String alarmType, String dedupKey, Instant occurredAt,
                           Instant receivedAt, String quality, String severity, Double value, String message, String traceId) {
    }

    /** 本轮新到达数据涉及的点位与时间范围。 */
    public record Touched(String pointCode, Instant minTs, Instant maxTs) {
    }

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final String schema;

    public HistorianReader(DataSource readOnlyDataSource, String schema) {
        this.dataSource = readOnlyDataSource;
        this.jdbc = new JdbcTemplate(readOnlyDataSource);
        if (!schema.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("非法 schema：" + schema);
        this.schema = schema;
    }

    @Override
    public void close() throws Exception {
        if (dataSource instanceof AutoCloseable c) c.close();
    }

    public String schema() {
        return schema;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** 接收时间在 (after, upTo] 内的采样涉及的点位及源时间范围。 */
    public List<Touched> touched(Instant after, Instant upTo) {
        return jdbc.query("""
            select point_code, min(ts) as min_ts, max(ts) as max_ts from %s.historian_point_value
             where received_at > ? and received_at <= ? group by point_code order by point_code""".formatted(schema),
            (rs, i) -> new Touched(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()),
            Timestamp.from(after), Timestamp.from(upTo));
    }

    public Instant maxReceived() {
        Timestamp t = jdbc.queryForObject("select max(received_at) from %s.historian_point_value".formatted(schema), Timestamp.class);
        return t == null ? null : t.toInstant();
    }

    public Instant maxAlarmReceived() {
        Timestamp t = jdbc.queryForObject("select max(received_at) from %s.historian_alarm_event".formatted(schema), Timestamp.class);
        return t == null ? null : t.toInstant();
    }

    /**
     * 按时间窗聚合 [from, to) 内的原始采样：同一时间戳重复送达只取一次；坏质量位与时钟异常的采样计数但不参与数值统计。
     */
    public List<Bucket> aggregate(String point, Instant from, Instant to, int bucketSeconds, int skewSeconds) {
        return jdbc.query("""
            with s as (
              select distinct on (ts) ts, value, quality, received_at from %s.historian_point_value
               where point_code = ? and ts >= ? and ts < ? order by ts, received_at
            ), x as (
              select date_bin(make_interval(secs => ?), ts, timestamptz '2000-01-01 00:00:00+00') as b, ts, value,
                     quality <> 'GOOD' as bad, ts - received_at > make_interval(secs => ?) as skew from s
            )
            select b, avg(value) filter (where not bad and not skew) as avg_v, min(value) filter (where not bad and not skew) as min_v,
                   max(value) filter (where not bad and not skew) as max_v,
                   (array_agg(value order by ts desc) filter (where not bad and not skew))[1] as last_v,
                   count(*) as n, count(*) filter (where bad) as bad_n, count(*) filter (where skew) as skew_n
              from x group by b order by b""".formatted(schema),
            (rs, i) -> new Bucket(point, rs.getTimestamp("b").toInstant(), (Double) rs.getObject("avg_v"), (Double) rs.getObject("min_v"),
                (Double) rs.getObject("max_v"), (Double) rs.getObject("last_v"), rs.getInt("n"), rs.getInt("bad_n"), rs.getInt("skew_n")),
            point, Timestamp.from(from), Timestamp.from(to), bucketSeconds, skewSeconds);
    }

    /** 接收时间在 (after, upTo] 内的报警事件，按接收时间排序。 */
    public List<RawAlarm> alarms(Instant after, Instant upTo, int limit) {
        return jdbc.query("""
            select * from %s.historian_alarm_event where received_at > ? and received_at <= ? order by received_at, source_event_id limit ?"""
                .formatted(schema),
            (rs, i) -> new RawAlarm(rs.getString("source_system"), rs.getString("source_event_id"), rs.getString("point_code"),
                rs.getString("alarm_type"), rs.getString("dedup_key"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("received_at").toInstant(), rs.getString("quality"), rs.getString("severity"), (Double) rs.getObject("value"),
                rs.getString("message"), rs.getString("trace_id")),
            Timestamp.from(after), Timestamp.from(upTo), limit);
    }

    /** 原始采样窗口（页面"原始数据引用"只给计数与时间范围，不搬运原始值）。 */
    public long rawCount(String point, Instant from, Instant to) {
        Long n = jdbc.queryForObject("select count(*) from %s.historian_point_value where point_code = ? and ts >= ? and ts < ?".formatted(schema),
            Long.class, point, Timestamp.from(from), Timestamp.from(to));
        return n == null ? 0 : n;
    }

    static Instant toInstant(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp t) return t.toInstant();
        if (v instanceof OffsetDateTime o) return o.toInstant();
        return (Instant) v;
    }
}
