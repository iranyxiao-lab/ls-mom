package com.lsmom.biz.consultation;

import com.lsmom.action.api.OperationContext;
import com.lsmom.action.state.StateSnapshot;
import com.lsmom.action.state.TransitionEffect;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.BiFunction;

/** 记录值转换与迁移效果的小工具。 */
final class ConsultationSupport {

    private ConsultationSupport() {
    }

    static long num(Object v) {
        if (v == null) throw new IllegalStateException("缺少必填值");
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }

    static LocalDateTime time(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime t) return t;
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return LocalDateTime.parse(String.valueOf(v).replace(' ', 'T'));
    }

    static boolean yes(Object v) {
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    static TransitionEffect effect(String op, BiFunction<OperationContext, StateSnapshot, Map<String, Object>> fn) {
        return new TransitionEffect() {
            @Override
            public String operation() {
                return op;
            }

            @Override
            public Map<String, Object> apply(OperationContext ctx, StateSnapshot before) {
                return fn.apply(ctx, before);
            }
        };
    }
}
