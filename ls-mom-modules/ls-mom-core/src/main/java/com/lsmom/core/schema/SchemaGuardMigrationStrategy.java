package com.lsmom.core.schema;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;

import java.util.List;

/**
 * 核心迁移策略：先执行 Flyway 迁移，再做表结构漂移校验。
 * <ul>
 *   <li>本次执行了迁移，或从未记录过指纹：把当前结构记录为新基准；</li>
 *   <li>没有新迁移：比对当前结构与最近记录，有差异即为手工 DDL 漂移，
 *       按 {@code lsmom.schema.drift-mode} 记录警告或拒绝启动。</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class SchemaGuardMigrationStrategy implements FlywayMigrationStrategy {

    private static final int MAX_DIFF_LINES = 50;

    private final SchemaFingerprintService fingerprints;
    private final SchemaDriftProperties properties;

    @Override
    public void migrate(Flyway flyway) {
        MigrateResult result = flyway.migrate();
        String version = String.valueOf(flyway.info().current() == null ? "none" : flyway.info().current().getVersion());

        if (result.migrationsExecuted > 0 || fingerprints.latestRecorded().isEmpty()) {
            fingerprints.record(version, "core-migrate");
            log.info("[LS-MOM] 数据库迁移完成（执行 {} 个），已记录表结构指纹，当前版本 {}", result.migrationsExecuted, version);
            return;
        }

        List<String> diff = fingerprints.diffAgainstLatest();
        if (diff.isEmpty()) {
            log.info("[LS-MOM] 表结构与迁移记录一致，当前版本 {}", version);
            return;
        }

        String detail = String.join("\n", diff.subList(0, Math.min(diff.size(), MAX_DIFF_LINES)))
            + (diff.size() > MAX_DIFF_LINES ? "\n...（共 " + diff.size() + " 处差异）" : "");
        String message = "检测到未经迁移脚本的表结构变更（漂移），差异如下（- 迁移记录中有，+ 实际库中有）：\n" + detail;
        if (properties.getDriftMode() == SchemaDriftProperties.DriftMode.FAIL) {
            throw new SchemaDriftException(message + "\n请把变更写成迁移脚本，或从备份恢复后再启动。");
        }
        log.warn("[LS-MOM] {}", message);
    }
}
