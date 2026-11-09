package com.lsmom.core.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 业务时钟：服务统一从 Clock 取当前时间，测试可替换为固定时钟。 */
@Configuration(proxyBeanMethods = false)
public class CoreClockConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock lsClock() {
        return Clock.systemDefaultZone();
    }
}
