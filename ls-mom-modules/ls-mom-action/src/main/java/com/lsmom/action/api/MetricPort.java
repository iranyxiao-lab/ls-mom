package com.lsmom.action.api;

import java.util.List;
import java.util.Map;

/**
 * 指标查询门面：问数等能力只经已发布的指标取业务数字（按调用者数据域过滤），并能钻取到明细记录。
 * 由指标引擎所在模块实现；调用方不直接写 SQL。
 */
public interface MetricPort {

    record MetricInfo(String code, String name, String description, String unit, String concept, List<String> dimensions, boolean timed) {
    }

    /** filters：维度条件，形如 {field, op(eq/in), value}。from 含、to 不含（ISO 日期时间）。 */
    record Query(String code, String from, String to, List<Map<String, Object>> filters) {
    }

    record Value(String code, int version, String name, String unit, Object value) {
    }

    record Drill(String code, int version, String concept, long total, List<Map<String, Object>> rows) {
    }

    List<MetricInfo> metrics();

    Value value(Query q, ActorContext actor);

    Drill drill(Query q, int size, ActorContext actor);
}
