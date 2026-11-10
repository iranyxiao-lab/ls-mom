package com.lsmom.crud;

import com.lsmom.core.schema.SchemaDriftProperties;
import com.lsmom.core.schema.SchemaFingerprintService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 台账发布（Pack 迁移）后记录新的表结构指纹：受控表是经评审的迁移，不应在下次启动时被判为手工漂移。 */
class PackFingerprintTest {

    @Test
    void 台账发布后表结构与最新指纹一致() {
        CrudFixture f = new CrudFixture();
        SchemaFingerprintService fp = new SchemaFingerprintService(f.action.core.jdbc, new SchemaDriftProperties());
        fp.record("test-base", "test");
        f.migrator.setFingerprints(fp);
        f.loanLedger();
        assertThat(fp.diffAgainstLatest()).isEmpty();
        // 未接入指纹时会产生漂移（回归保护：确认确实有新表）
        CrudFixture g = new CrudFixture();
        g.loanLedger();
        assertThat(fp.diffAgainstLatest()).isNotEmpty();
        // 迁移已提交但指纹未记（进程中途退出）：可识别为有记录的迁移，启动时补记基准
        assertThat(fp.packMigratedSinceLatest()).isTrue();
    }

    @Test
    void 手工DDL不被当作Pack迁移() throws Exception {
        CrudFixture f = new CrudFixture();
        SchemaFingerprintService fp = new SchemaFingerprintService(f.action.core.jdbc, new SchemaDriftProperties());
        fp.record("manual-base", "test");
        Thread.sleep(5);
        f.action.core.jdbc.execute("create table if not exists manual_drift_probe (id bigint)");
        try {
            assertThat(fp.diffAgainstLatest()).anyMatch(l -> l.contains("manual_drift_probe"));
            assertThat(fp.packMigratedSinceLatest()).isFalse();
        } finally {
            f.action.core.jdbc.execute("drop table manual_drift_probe");
        }
    }
}
