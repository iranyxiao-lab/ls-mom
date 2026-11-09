package com.lsmom.core.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 在真实 PostgreSQL 16 上走完迁移与漂移校验闭环：
 * 空库迁移 → 记录指纹 → 重启一致 → 手工 DDL → dev 警告 / prod 拒绝启动。
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaDriftPostgresTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static DataSource dataSource;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
    }

    private static Flyway flyway() {
        return Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/migration/core")
            .baselineOnMigrate(true).baselineVersion("1")
            .load();
    }

    private static SchemaGuardMigrationStrategy strategy(SchemaDriftProperties.DriftMode mode) {
        SchemaDriftProperties props = new SchemaDriftProperties();
        props.setDriftMode(mode);
        return new SchemaGuardMigrationStrategy(new SchemaFingerprintService(jdbc, props), props, null);
    }

    @Test
    @Order(1)
    void freshDatabaseMigratesAndRecordsFingerprint() {
        strategy(SchemaDriftProperties.DriftMode.FAIL).migrate(flyway());
        assertThat(jdbc.queryForObject("select count(*) from sys_user", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from ls_schema_fingerprint", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_name like 'flow\\_%'", Integer.class)).isZero();
    }

    @Test
    @Order(2)
    void restartWithoutChangesPasses() {
        assertThatCode(() -> strategy(SchemaDriftProperties.DriftMode.FAIL).migrate(flyway())).doesNotThrowAnyException();
    }

    @Test
    @Order(3)
    void manualDdlIsDetected() {
        jdbc.execute("alter table sys_user add column hacked varchar(10)");
        assertThatCode(() -> strategy(SchemaDriftProperties.DriftMode.WARN).migrate(flyway())).doesNotThrowAnyException();
        assertThatThrownBy(() -> strategy(SchemaDriftProperties.DriftMode.FAIL).migrate(flyway()))
            .isInstanceOf(SchemaDriftException.class)
            .hasMessageContaining("sys_user")
            .hasMessageContaining("hacked");
    }
}
