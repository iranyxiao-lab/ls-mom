package com.lsmom.action.rule;

import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 JSON / Java 对象转换为 CEL 值：整数 → int（Long），小数 → double，时间与枚举 → 字符串，null 保留。
 * 前端 TS 实现使用同一规则（整数值的数字视为 int），保证两端语义一致。
 */
public final class CelValues {

    private CelValues() {
    }

    public static Object fromJson(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        if (n.isBoolean()) return n.booleanValue();
        if (n.isIntegralNumber()) return n.longValue();
        if (n.isNumber()) {
            double d = n.doubleValue();
            return d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.007199254740992E15 ? (Object) (long) d : (Object) d;
        }
        if (n.isTextual()) return n.textValue();
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            n.forEach(x -> l.add(fromJson(x)));
            return l;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = n.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            m.put(e.getKey(), fromJson(e.getValue()));
        }
        return m;
    }

    public static Object fromJava(Object v) {
        if (v == null) return null;
        if (v instanceof JsonNode j) return fromJson(j);
        if (v instanceof Boolean || v instanceof String || v instanceof Long || v instanceof Double) return v;
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) return ((Number) v).longValue();
        if (v instanceof BigInteger b) return b.longValue();
        if (v instanceof BigDecimal b) {
            return b.stripTrailingZeros().scale() <= 0 ? (Object) b.longValue() : (Object) b.doubleValue();
        }
        if (v instanceof Float f) return f.doubleValue();
        if (v instanceof Enum<?> e) return e.name();
        if (v instanceof TemporalAccessor) return v.toString();
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), fromJava(x)));
            return out;
        }
        if (v instanceof Iterable<?> it) {
            List<Object> l = new ArrayList<>();
            it.forEach(x -> l.add(fromJava(x)));
            return l;
        }
        if (v.getClass().isRecord()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (RecordComponent c : v.getClass().getRecordComponents()) {
                try {
                    out.put(c.getName(), fromJava(c.getAccessor().invoke(v)));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            return out;
        }
        return v.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object v) {
        Object c = fromJava(v);
        return c instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }
}
