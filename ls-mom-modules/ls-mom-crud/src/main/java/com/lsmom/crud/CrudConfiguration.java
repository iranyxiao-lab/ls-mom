package com.lsmom.crud;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 通用台账配置：限额属性与导出线程池（有界，避免大量导出占满资源）。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CrudProperties.class)
public class CrudConfiguration {

    public static final String EXPORT_EXECUTOR = "lsmomExportExecutor";

    @Bean(name = EXPORT_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolExecutor lsmomExportExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20),
            r -> {
                Thread t = new Thread(r, "lsmom-export");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
