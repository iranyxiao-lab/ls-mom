package com.lsmom.dcs.simulator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

/**
 * 写入独立时序库（模拟现场 Historian）。表由模拟器维护；业务系统只读。
 * TimescaleDB 可用时建为 hypertable，否则为普通表（测试用 PostgreSQL）。
 */
public final class HistorianWriter {

    private final Connection conn;
    private final String schema;

    public HistorianWriter(Connection conn, String schema) {
        this.conn = conn;
        this.schema = schema == null || schema.isBlank() ? "public" : schema;
        if (!this.schema.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("非法 schema：" + schema);
    }

    public void ensureSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("create schema if not exists " + schema);
            st.execute("""
                create table if not exists %1$s.historian_point_value (
                    source_system varchar(64) not null,
                    point_code    varchar(64) not null,
                    ts            timestamptz not null,
                    value         double precision,
                    quality       varchar(16) not null default 'GOOD',
                    received_at   timestamptz not null default now()
                )""".formatted(schema));
            st.execute("create index if not exists idx_hpv_point_ts on %s.historian_point_value (point_code, ts)".formatted(schema));
            st.execute("create index if not exists idx_hpv_received on %s.historian_point_value (received_at)".formatted(schema));
            st.execute("""
                create table if not exists %1$s.historian_alarm_event (
                    source_system   varchar(64)  not null,
                    source_event_id varchar(128) not null,
                    point_code      varchar(64)  not null,
                    alarm_type      varchar(32)  not null,
                    dedup_key       varchar(200) not null,
                    occurred_at     timestamptz  not null,
                    received_at     timestamptz  not null default now(),
                    quality         varchar(16)  not null default 'GOOD',
                    severity        varchar(16),
                    value           double precision,
                    message         varchar(500),
                    trace_id        varchar(64)
                )""".formatted(schema));
            st.execute("create index if not exists idx_hae_received on %s.historian_alarm_event (received_at)".formatted(schema));
        }
        hypertable("historian_point_value", "ts");
    }

    /** TimescaleDB 扩展可用时转为 hypertable；不可用（普通 PostgreSQL）时跳过。 */
    private void hypertable(String table, String column) {
        try (Statement st = conn.createStatement()) {
            st.execute("create extension if not exists timescaledb");
            st.execute("select create_hypertable('%s.%s', '%s', if_not_exists => true, migrate_data => true)".formatted(schema, table, column));
        } catch (SQLException e) {
            // 非 TimescaleDB：保持普通表
            try {
                if (!conn.getAutoCommit()) conn.rollback();
            } catch (SQLException ignored) {
                // 连接已不可用时由后续写入报错
            }
        }
    }

    public boolean isEmpty() throws SQLException {
        try (Statement st = conn.createStatement(); var rs = st.executeQuery("select not exists (select 1 from " + schema + ".historian_point_value)")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    public int writeSamples(List<Generator.Sample> samples) throws SQLException {
        if (samples.isEmpty()) return 0;
        try (PreparedStatement ps = conn.prepareStatement("insert into " + schema
            + ".historian_point_value (source_system, point_code, ts, value, quality, received_at) values (?, ?, ?, ?, ?, ?)")) {
            int n = 0;
            for (Generator.Sample s : samples) {
                ps.setString(1, s.sourceSystem());
                ps.setString(2, s.point());
                ps.setTimestamp(3, Timestamp.from(s.ts()));
                ps.setDouble(4, s.value());
                ps.setString(5, s.quality());
                ps.setTimestamp(6, Timestamp.from(s.receivedAt()));
                ps.addBatch();
                if (++n % 5000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
            return n;
        }
    }

    public int writeAlarms(List<Generator.AlarmEvent> alarms) throws SQLException {
        if (alarms.isEmpty()) return 0;
        try (PreparedStatement ps = conn.prepareStatement("insert into " + schema + """
            .historian_alarm_event (source_system, source_event_id, point_code, alarm_type, dedup_key, occurred_at, received_at, quality,
                                    severity, value, message, trace_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            for (Generator.AlarmEvent a : alarms) {
                ps.setString(1, a.sourceSystem());
                ps.setString(2, a.sourceEventId());
                ps.setString(3, a.point());
                ps.setString(4, a.alarmType());
                ps.setString(5, a.dedupKey());
                ps.setTimestamp(6, Timestamp.from(a.occurredAt()));
                ps.setTimestamp(7, Timestamp.from(a.receivedAt()));
                ps.setString(8, a.quality());
                ps.setString(9, a.severity());
                ps.setDouble(10, a.value());
                ps.setString(11, a.message());
                ps.setString(12, a.traceId());
                ps.addBatch();
            }
            ps.executeBatch();
            return alarms.size();
        }
    }
}
