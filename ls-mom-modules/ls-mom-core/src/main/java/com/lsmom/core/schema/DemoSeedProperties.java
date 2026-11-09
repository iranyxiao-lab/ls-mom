package com.lsmom.core.schema;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 演示种子数据配置（前缀 {@code lsmom.seed.demo}）。只用于本地 Compose 与验收环境，生产环境保持关闭。
 */
@Data
@ConfigurationProperties(prefix = "lsmom.seed.demo")
public class DemoSeedProperties {

    /** 是否执行演示种子。 */
    private boolean enabled = false;

    /** 种子脚本位置。 */
    private String location = "classpath:db/seed/demo";

    /** 种子执行历史表，与核心迁移历史分开，避免版本号相互干扰。 */
    private String historyTable = "flyway_seed_history";

    /** 种子中 S3 对象存储配置的占位值。 */
    private S3 s3 = new S3();

    @Data
    public static class S3 {
        private String endpoint = "seaweedfs:8333";
        private String accessKey = "lsmom";
        private String secretKey = "lsmom-local-only";
        private String bucket = "lsmom";
    }
}
