package com.lsmom.crud.ddl;

import com.lsmom.core.error.LsBizException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Pack 迁移执行器：唯一执行动态表 DDL 的入口。脚本先登记到 meta_ddl_script，再由 Flyway（历史表 pack_schema_history）
 * 按版本执行；已执行脚本的校验和由 Flyway 校验，篡改会使迁移失败。设计器与通用接口没有 DDL 权限。
 */
@Component
public class PackMigrator {

    public static final String HISTORY_TABLE = "pack_schema_history";

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    private com.lsmom.core.schema.SchemaFingerprintService fingerprints;

    /** 迁移成功后记录新的表结构指纹，否则下次启动会把台账表当成手工 DDL 漂移而拒绝启动。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFingerprints(com.lsmom.core.schema.SchemaFingerprintService fingerprints) {
        this.fingerprints = fingerprints;
    }

    public PackMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.dataSource = jdbc.getDataSource();
    }

    /** 登记一份脚本（未执行）。 */
    public long register(DdlPlan plan, String packCode, Long changeSetId, Long createdBy) {
        Long id = jdbc.queryForObject("""
            insert into meta_ddl_script (concept_code, pack_code, description, script, checksum, breaking, change_set_id, created_by)
            values (?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
            plan.concept(), packCode, plan.description(), plan.script(), sha256(plan.script()), plan.breaking(), changeSetId, createdBy);
        return id;
    }

    /**
     * 执行全部已登记脚本（含之前失败的待执行脚本）。PostgreSQL 的 DDL 在事务中执行，失败整体回滚，脚本标记 FAILED。
     */
    public synchronized MigrateResult migrate() {
        List<DbMigrationResources.Script> scripts = jdbc.query("select id, description, script from meta_ddl_script where status <> 'FAILED' order by id",
            (rs, i) -> new DbMigrationResources.Script(rs.getLong(1), rs.getString(2), rs.getString(3)));
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .table(HISTORY_TABLE)
            .resourceProvider(new DbMigrationResources(scripts))
            .locations("db/pack-runtime-none")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .outOfOrder(true)
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .load();
        try {
            MigrateResult r = flyway.migrate();
            jdbc.update("""
                update meta_ddl_script s set status = 'APPLIED', applied_at = h.installed_on
                  from pack_schema_history h where h.version = s.id::text and h.success and s.status = 'PENDING'""");
            if (r.migrationsExecuted > 0 && fingerprints != null) fingerprints.record("pack-" + r.targetSchemaVersion, "pack-migrate");
            return r;
        } catch (RuntimeException e) {
            String msg = rootMessage(e);
            jdbc.update("""
                update meta_ddl_script set status = 'FAILED', error = ? where status = 'PENDING'
                  and id::text not in (select version from pack_schema_history where version is not null)""", truncate(msg));
            throw new LsBizException("DDL_MIGRATION_FAILED", "表结构迁移失败：" + msg, Map.of());
        }
    }

    public List<Map<String, Object>> history(String conceptCode) {
        return jdbc.queryForList("""
            select s.id, s.description, s.breaking, s.status, s.applied_at, h.checksum, h.installed_by
              from meta_ddl_script s left join pack_schema_history h on h.version = s.id::text
             where s.concept_code = ? order by s.id""", conceptCode);
    }

    private static String rootMessage(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null) x = x.getCause();
        return x.getMessage() == null ? t.getMessage() : x.getMessage();
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
