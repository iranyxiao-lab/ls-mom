package com.lsmom.dcs;

import com.lsmom.dcs.simulator.Generator;
import com.lsmom.dcs.simulator.HistorianWriter;
import com.lsmom.dcs.simulator.SimConfig;
import com.lsmom.test.PgTestSupport;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Properties;

/** 测试用 Historian：共享测试库中的独立 schema，由模拟器写入；业务侧以只读连接读取。 */
final class DcsTestSupport {

    private DcsTestSupport() {
    }

    static String schema() {
        return PgTestSupport.uniq("hist_").toLowerCase();
    }

    /** 每次取新连接并设为只读事务（与生产 Historian 连接池一致）。 */
    static DataSource readOnly() {
        DriverManagerDataSource ds = new DriverManagerDataSource(PgTestSupport.jdbcUrl());
        Properties p = new Properties();
        p.setProperty("user", PgTestSupport.username());
        p.setProperty("password", PgTestSupport.password());
        p.setProperty("options", "-c default_transaction_read_only=on");
        ds.setConnectionProperties(p);
        return ds;
    }

    static void write(String schema, List<Generator.Sample> samples, List<Generator.AlarmEvent> alarms) {
        try (Connection c = PgTestSupport.dataSource().getConnection()) {
            HistorianWriter w = new HistorianWriter(c, schema);
            w.ensureSchema();
            w.writeSamples(samples);
            w.writeAlarms(alarms);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 唯一编码的三点位配置（共享测试库）。 */
    static SimConfig config(String prefix, SimConfig.Scenario sc) {
        return new SimConfig("DCS-" + prefix, 11, 1, List.of(
            new SimConfig.Point(prefix + ".VIB", "振动", "mm/s", 3.2, 1.6, 30, 0.2, 4.5, "HI", "MEDIUM"),
            new SimConfig.Point(prefix + ".TEMP", "温度", "℃", 58, 6, 120, 0.5, 70.0, "HI", "HIGH"),
            new SimConfig.Point(prefix + ".PRESS", "压力", "MPa", 0.45, 0.03, 60, 0.005, null, null, null)), sc);
    }
}
