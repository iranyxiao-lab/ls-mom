package com.lsmom.dcs;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 降采样：按 Historian 接收时间增量找出新到达数据涉及的时间窗，对整窗重新聚合并按主键覆盖写入业务库。
 * 断网补传、重复送达、乱序都只会让某些窗被重算，业务库每点位每窗始终只有一行。
 */
public class SnapshotService {

    static final String CURSOR = "snapshot";

    private final JdbcTemplate jdbc;
    private final HistorianReader historian;
    private final DcsProperties props;

    /** historian 为空（未启用 Historian）时只提供快照查询。 */
    public SnapshotService(JdbcTemplate jdbc, HistorianReader historian, DcsProperties props) {
        this.jdbc = jdbc;
        this.historian = historian;
        this.props = props;
    }

    /** 处理一轮，返回重算的时间窗数。 */
    public int run() {
        if (historian == null) return 0;
        Instant upTo = historian.maxReceived();
        String key = CURSOR + ":" + historian.schema();
        Instant cursor = IngestCursor.get(jdbc, key);
        if (upTo == null || !upTo.isAfter(cursor)) return 0;
        Instant from = cursor.equals(Instant.EPOCH) ? cursor : cursor.minusSeconds(props.overlapSeconds());
        Set<String> enabled = jdbc.queryForList("select code from dcs_point where enabled", String.class).stream().collect(Collectors.toSet());
        int size = props.snapshotSeconds();
        int n = 0;
        for (HistorianReader.Touched t : historian.touched(from, upTo)) {
            if (!enabled.contains(t.pointCode())) continue;
            Instant start = floor(t.minTs(), size);
            Instant end = floor(t.maxTs(), size).plusSeconds(size);
            n += upsert(historian.aggregate(t.pointCode(), start, end, size, props.clockSkewSeconds()), size);
        }
        IngestCursor.set(jdbc, key, upTo);
        return n;
    }

    private int upsert(List<HistorianReader.Bucket> buckets, int size) {
        if (buckets.isEmpty()) return 0;
        jdbc.batchUpdate("""
            insert into dcs_telemetry_snapshot (point_code, bucket_start, bucket_seconds, avg_value, min_value, max_value, last_value,
                                                sample_count, bad_count, skew_count, computed_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            on conflict (point_code, bucket_seconds, bucket_start) do update set avg_value = excluded.avg_value, min_value = excluded.min_value,
              max_value = excluded.max_value, last_value = excluded.last_value, sample_count = excluded.sample_count,
              bad_count = excluded.bad_count, skew_count = excluded.skew_count, computed_at = now()""",
            buckets.stream().map(b -> new Object[]{b.pointCode(), Timestamp.from(b.bucketStart()), size, b.avg(), b.min(), b.max(), b.last(),
                b.samples(), b.bad(), b.skew()}).toList());
        return buckets.size();
    }

    static Instant floor(Instant t, int size) {
        long s = t.getEpochSecond();
        return Instant.ofEpochSecond(s - Math.floorMod(s, size));
    }

    /** 某点位在时间范围内的快照（业务库）。 */
    public List<java.util.Map<String, Object>> snapshots(String point, Instant from, Instant to) {
        return jdbc.queryForList("""
            select bucket_start, bucket_seconds, avg_value, min_value, max_value, last_value, sample_count, bad_count, skew_count
              from dcs_telemetry_snapshot where point_code = ? and bucket_start >= ? and bucket_start < ? order by bucket_start""",
            point, Timestamp.from(from), Timestamp.from(to));
    }
}
