package com.lsmom.flow.engine;

import org.flowable.common.engine.impl.AbstractEngineConfiguration;
import org.flowable.common.engine.impl.runtime.Clock;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;

/**
 * 嵌入式 Flowable 7 引擎装配。
 * <ul>
 *   <li>引擎表只在独立 schema {@code flowable}：先用一条把 search_path 指向 flowable 的连接建表/升级，
 *       运行期再以 {@code flowable.} 表前缀与业务共用同一数据源和事务管理器（待办读模型与引擎同事务）。</li>
 *   <li>关闭 IDM 与事件注册引擎；不暴露任何 Spring Bean 给表达式（beans 为空），表达式由
 *       {@link RestrictedExpressionManager} 只允许白名单形式；脚本任务在部署前由扫描器拒绝。</li>
 * </ul>
 */
public final class FlowEngineFactory {

    public static final String SCHEMA = "flowable";

    private FlowEngineFactory() {
    }

    public record Options(boolean asyncExecutor, Clock clock, List<FlowableEventListener> listeners) {
    }

    public static ProcessEngine build(DataSource dataSource, PlatformTransactionManager txm, Options options) {
        bootstrapSchema(dataSource);
        SpringProcessEngineConfiguration cfg = new SpringProcessEngineConfiguration();
        cfg.setDataSource(dataSource);
        cfg.setTransactionManager(txm);
        // 运行期不设 databaseSchema：Flowable 会据此切换连接的 schema，而连接与业务事务共用；表名一律带 flowable. 前缀
        cfg.setDatabaseTablePrefix(SCHEMA + ".");
        cfg.setTablePrefixIsSchema(true);
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_FALSE);
        common(cfg);
        cfg.setAsyncExecutorActivate(options.asyncExecutor());
        if (options.clock() != null) cfg.setClock(options.clock());
        if (options.listeners() != null && !options.listeners().isEmpty()) cfg.setEventListeners(List.copyOf(options.listeners()));
        cfg.setBeans(new HashMap<>());
        cfg.setExpressionManager(new RestrictedExpressionManager());
        return cfg.buildProcessEngine();
    }

    /** 在 flowable schema 中建表或升级（幂等）；连接用毕恢复 search_path。 */
    static void bootstrapSchema(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            try (Statement st = c.createStatement()) {
                st.execute("create schema if not exists " + SCHEMA);
                st.execute("set search_path to " + SCHEMA);
            }
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(true);
            try {
                StandaloneProcessEngineConfiguration boot = new StandaloneProcessEngineConfiguration();
                boot.setDataSource(new SingleConnectionDataSource(c, true));
                boot.setDatabaseSchema(SCHEMA);
                boot.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
                common(boot);
                boot.setAsyncExecutorActivate(false);
                boot.buildProcessEngine().close();
            } finally {
                try (Statement st = c.createStatement()) {
                    st.execute("reset search_path");
                }
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 Flowable schema 失败", e);
        }
    }

    private static void common(AbstractEngineConfiguration cfg) {
        org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl p = (org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl) cfg;
        p.setDisableIdmEngine(true);
        p.setDisableEventRegistry(true);
        p.setHistoryLevel(org.flowable.common.engine.impl.history.HistoryLevel.AUDIT);
        p.setEngineName("ls-mom-" + System.identityHashCode(cfg));
    }

}
