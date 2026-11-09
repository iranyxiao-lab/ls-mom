package com.lsmom.action.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个由 Java 实现的命名 Operation。启动时与 {@code meta_operation} 对账：
 * 元数据不存在则注册；版本相同但契约不一致则启动失败；提高 {@link #version()} 表示有意升级契约。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface LsOperation {

    /** 编码，形如 {@code ls.core.Work.create}，发布后不可修改或复用。 */
    String code();

    /** 目标 Concept。 */
    String concept();

    String name();

    Risk risk();

    Exposure exposure() default Exposure.NONE;

    /** 所需功能权限（Sa-Token perms），为空表示登录即可。 */
    String permission() default "";

    IdempotencyPolicy idempotency() default IdempotencyPolicy.OPTIONAL;

    /** 修改已有对象时要求携带版本号（乐观并发）。 */
    boolean requiresVersion() default false;

    /** 输入 JSON Schema（JSON 文本）。 */
    String inputSchema() default "{\"type\":\"object\"}";

    /** 守卫编码。 */
    String[] guards() default {};

    int version() default 1;

    String description() default "";
}
