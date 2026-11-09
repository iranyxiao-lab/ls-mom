package com.lsmom.core.schema;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 表结构漂移校验配置（前缀 {@code lsmom.schema}）。
 */
@Data
@ConfigurationProperties(prefix = "lsmom.schema")
public class SchemaDriftProperties {

    /** 发现漂移时的处理方式：WARN 只记录日志，FAIL 拒绝启动。生产环境为 FAIL。 */
    private DriftMode driftMode = DriftMode.WARN;

    /** 参与指纹计算的 schema。 */
    private List<String> schemas = List.of("public");

    public enum DriftMode {
        WARN, FAIL
    }
}
