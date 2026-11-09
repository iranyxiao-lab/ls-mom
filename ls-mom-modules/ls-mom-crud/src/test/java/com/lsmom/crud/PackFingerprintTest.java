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
    }
}
