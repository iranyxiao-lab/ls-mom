package com.lsmom.core.schema;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 执行演示种子。种子脚本由独立的 Flyway 实例与历史表管理：每个脚本只执行一次，
 * 脚本本身也用 {@code on conflict do nothing} 写成可重复执行，因此反复启动不会产生重复数据。
 */
@Slf4j
@RequiredArgsConstructor
public class DemoSeedRunner {

    private final DataSource dataSource;
    private final DemoSeedProperties properties;

    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        DemoSeedProperties.S3 s3 = properties.getS3();
        MigrateResult result = Flyway.configure()
            .dataSource(dataSource)
            .locations(properties.getLocation())
            .table(properties.getHistoryTable())
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .placeholders(Map.of(
                "s3Endpoint", s3.getEndpoint(),
                "s3AccessKey", s3.getAccessKey(),
                "s3SecretKey", s3.getSecretKey(),
                "s3Bucket", s3.getBucket()))
            .load()
            .migrate();
        log.info("[LS-MOM] 演示种子执行完成：本次执行 {} 个脚本", result.migrationsExecuted);
    }
}
