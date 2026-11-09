package com.lsmom.test;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 集成测试共用的 PostgreSQL 16：整个测试 JVM 只启动一个容器并执行一次核心迁移。
 * 各测试用 {@link #uniq(String)} 生成互不冲突的数据，不依赖执行顺序。
 * 其他模块通过 ls-mom-core 的 test-jar 复用。
 */
public final class PgTestSupport {

    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    private static final DataSource DATA_SOURCE;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 100000);
    private static final AtomicLong USER_ID = new AtomicLong(800_000_000L);

    static {
        PG.start();
        DriverManagerDataSource ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        DATA_SOURCE = ds;
        new JdbcTemplate(ds).execute("create extension if not exists vector; create schema if not exists flowable");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/core").outOfOrder(true).load().migrate();
    }

    private PgTestSupport() {
    }

    public static DataSource dataSource() {
        return DATA_SOURCE;
    }

    public static JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    public static TransactionTemplate tx() {
        return new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE));
    }

    public static String jdbcUrl() {
        return PG.getJdbcUrl();
    }

    public static String uniq(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }

    /** 像 RuoYi 新增用户一样直接写 sys_user，返回 user_id。 */
    public static long insertUser(String userName, String nickName, Long deptId) {
        long id = USER_ID.incrementAndGet();
        jdbc().update("""
            insert into sys_user (user_id, tenant_id, dept_id, user_name, nick_name, user_type, password, status, del_flag, create_time)
            values (?, '000000', ?, ?, ?, 'sys_user', '', '0', '0', now())""", id, deptId, userName, nickName);
        return id;
    }
}
