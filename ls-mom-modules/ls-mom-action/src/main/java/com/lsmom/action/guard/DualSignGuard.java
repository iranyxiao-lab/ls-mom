package com.lsmom.action.guard;

import com.lsmom.action.api.OperationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 示例代码守卫插件：双签互斥。同一人不能同时作为执行人和复核人完成双签。
 * 执行人取输入 {@code executorParty}；复核人取输入 {@code reviewerParty}，缺省为当前调用主体（代理执行时为被代理人）。
 * Pack 以 {@code {"kind":"JAVA","definition":{"guard":"ls.std.dualSign.exclusive","appliesTo":[...]}}} 引用。
 */
@Component
public class DualSignGuard implements Guard {

    public static final String CODE = "ls.std.dualSign.exclusive";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public ConstraintLevel level() {
        return ConstraintLevel.L3;
    }

    @Override
    public GuardResult check(OperationContext ctx) {
        Long executor = ctx.longValue("executorParty");
        Long reviewer = ctx.longValue("reviewerParty");
        if (reviewer == null) reviewer = ctx.actor().effectiveParty();
        if (executor == null) {
            return GuardResult.block("DUAL_SIGN_EXECUTOR_REQUIRED", "双签需要指定执行人");
        }
        if (executor.equals(reviewer)) {
            return GuardResult.block("DUAL_SIGN_SAME_PERSON", "执行人与复核人不能是同一人",
                Map.of("executorParty", executor, "reviewerParty", reviewer));
        }
        return GuardResult.pass();
    }
}
