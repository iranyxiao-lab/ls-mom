package com.lsmom.dcs;

import com.lsmom.core.issue.IssueService;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.ManagedRecords;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** DCS 集成装配。Historian 连接只在 {@code lsmom.dcs.enabled=true} 时建立，且为只读连接池。 */
@Configuration
@EnableConfigurationProperties(DcsProperties.class)
public class DcsConfiguration {

    @Bean
    public AlarmService dcsAlarmService(JdbcTemplate jdbc, IssueService issues, DcsPointService points, ConceptRegistry concepts,
                                        ManagedRecords records, PlatformTransactionManager txm, DcsProperties props) {
        return new AlarmService(jdbc, issues, points, concepts, records, txm, props);
    }

    /**
     * Historian 只读连接池由读取器自己持有，不注册为 DataSource Bean（避免干扰业务库的默认数据源与 JdbcTemplate）。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "lsmom.dcs", name = "enabled", havingValue = "true")
    public HistorianReader dcsHistorianReader(DcsProperties props) {
        HikariConfig c = new HikariConfig();
        c.setPoolName("dcs-historian");
        c.setJdbcUrl(props.historianUrl());
        c.setUsername(props.historianUser());
        c.setPassword(props.historianPassword());
        c.setMaximumPoolSize(2);
        c.setReadOnly(true);
        // 数据库层面同样只读：任何写语句都会被拒绝
        c.setConnectionInitSql("set session characteristics as transaction read only");
        c.setInitializationFailTimeout(-1);
        return new HistorianReader(new HikariDataSource(c), props.historianSchema());
    }

    @Bean
    public DcsAlarmSummaryContext dcsAlarmSummaryContext(AlarmService alarms, SnapshotService snapshots) {
        return new DcsAlarmSummaryContext(alarms, snapshots);
    }

    @Bean
    public SnapshotService dcsSnapshotService(JdbcTemplate jdbc, ObjectProvider<HistorianReader> reader, DcsProperties props) {
        return new SnapshotService(jdbc, reader.getIfAvailable(), props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lsmom.dcs", name = "enabled", havingValue = "true")
    public DcsIngestJob dcsIngestJob(SnapshotService snapshots, AlarmService alarms, HistorianReader reader) {
        return new DcsIngestJob(snapshots, alarms, reader);
    }
}
