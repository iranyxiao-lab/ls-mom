package com.lsmom.crud.metric;

import com.lsmom.action.gateway.InputSchemaValidator;
import com.lsmom.meta.concept.ConceptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 指标与命名 Query 装配；启动时登记全部 NamedQueryDef Bean 与内置指标（不合格的定义直接让启动失败）。 */
@Configuration(proxyBeanMethods = false)
public class MetricConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MetricConfiguration.class);

    @Bean
    public MetricRegistry metricRegistry(JdbcTemplate jdbc, ConceptRegistry concepts, PlatformTransactionManager txm) {
        return new MetricRegistry(jdbc, concepts, txm);
    }

    @Bean
    public MetricEngine metricEngine(JdbcTemplate jdbc, MetricRegistry registry) {
        return new MetricEngine(jdbc, registry);
    }

    @Bean
    public NamedQueryRegistry namedQueryRegistry(JdbcTemplate jdbc, InputSchemaValidator validator, PlatformTransactionManager txm) {
        return new NamedQueryRegistry(jdbc, validator, txm);
    }

    @Bean
    public SmartInitializingSingleton metricCatalogRegistrar(MetricRegistry metrics, NamedQueryRegistry queries, ObjectProvider<NamedQueryDef> defs) {
        return () -> {
            for (NamedQueryDef d : CoreMetrics.queries()) queries.register(d);
            defs.orderedStream().forEach(queries::register);
            for (MetricDefinition m : CoreMetrics.metrics()) metrics.publish(m, null);
            log.info("已登记命名 Query {} 个、内置指标 {} 个", queries.list().size(), CoreMetrics.metrics().size());
        };
    }
}
