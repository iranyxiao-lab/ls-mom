package com.lsmom.core.schema;

/**
 * 表结构与迁移记录不一致，且配置为拒绝启动。
 */
public class SchemaDriftException extends IllegalStateException {

    public SchemaDriftException(String message) {
        super(message);
    }
}
