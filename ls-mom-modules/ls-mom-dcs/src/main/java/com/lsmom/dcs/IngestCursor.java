package com.lsmom.dcs;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 增量读取游标（存业务库）：记录已处理到的 Historian 接收时间。 */
final class IngestCursor {

    private IngestCursor() {
    }

    static Instant get(JdbcTemplate jdbc, String name) {
        List<Timestamp> t = jdbc.queryForList("select received_at from dcs_ingest_cursor where name = ?", Timestamp.class, name);
        return t.isEmpty() ? Instant.EPOCH : t.get(0).toInstant();
    }

    static void set(JdbcTemplate jdbc, String name, Instant at) {
        jdbc.update("""
            insert into dcs_ingest_cursor (name, received_at) values (?, ?)
            on conflict (name) do update set received_at = greatest(dcs_ingest_cursor.received_at, excluded.received_at), updated_at = now()""",
            name, Timestamp.from(at));
    }
}
