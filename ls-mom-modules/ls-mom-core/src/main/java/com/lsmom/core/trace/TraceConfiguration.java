package com.lsmom.core.trace;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

/**
 * 让 Spring 管理的异步执行器（{@code @Async}、applicationTaskExecutor）继承调用方的 trace_id。
 * Spring Boot 会把容器中唯一的 {@link TaskDecorator} 自动应用到自动配置的执行器上。
 */
@Configuration(proxyBeanMethods = false)
public class TraceConfiguration {

    @Bean
    public TaskDecorator traceTaskDecorator() {
        return TraceContext::wrap;
    }
}
