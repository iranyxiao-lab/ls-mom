package com.lsmom.core.schema;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 迁移与漂移校验装配。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Flyway.class)
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({SchemaDriftProperties.class, DemoSeedProperties.class})
public class SchemaGuardConfiguration {

    @Bean
    public SchemaFingerprintService schemaFingerprintService(DataSource dataSource, SchemaDriftProperties properties) {
        return new SchemaFingerprintService(new JdbcTemplate(dataSource), properties);
    }

    @Bean
    public DemoSeedRunner demoSeedRunner(DataSource dataSource, DemoSeedProperties properties) {
        return new DemoSeedRunner(dataSource, properties);
    }

    @Bean
    public SchemaGuardMigrationStrategy schemaGuardMigrationStrategy(SchemaFingerprintService fingerprints,
                                                                     SchemaDriftProperties properties,
                                                                     DemoSeedRunner demoSeedRunner) {
        return new SchemaGuardMigrationStrategy(fingerprints, properties, demoSeedRunner);
    }
}
