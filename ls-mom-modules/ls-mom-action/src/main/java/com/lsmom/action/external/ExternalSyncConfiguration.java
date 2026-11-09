package com.lsmom.action.external;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * 外部 ERP 过账（配置 {@code lsmom.external.erp.url} 时启用）：提交的工作填报产出过账到 ERP。
 */
@Configuration(proxyBeanMethods = false)
public class ExternalSyncConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "lsmom.external.erp", name = "url")
    public HttpExternalDestination erpPosting(Environment env, ExternalSyncService sync) {
        return new HttpExternalDestination("erp", URI.create(env.getRequiredProperty("lsmom.external.erp.url")),
            Set.of("ls.core.WorkReport.submitted"), sync,
            Duration.ofMillis(env.getProperty("lsmom.external.erp.timeout-ms", Long.class, 5000L)),
            env.getProperty("lsmom.external.erp.max-attempts", Integer.class, 5));
    }
}
