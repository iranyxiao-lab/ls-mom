package com.lsmom.core.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * LS-MOM 模块统一装配入口。
 * <p>
 * 上游 RuoYi 只扫描 {@code org.dromara} 包；LS-MOM 代码位于 {@code com.lsmom}，
 * 通过自动配置注册组件与 Mapper，避免修改上游启动类。
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.lsmom")
@MapperScan(basePackages = "com.lsmom.**.mapper")
public class LsMomAutoConfiguration {
}
