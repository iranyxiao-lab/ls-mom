package com.lsmom.crud.metric;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.MetricPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 以指标引擎实现 {@link MetricPort}：只用已发布的最新版本，数据域与指标权限由引擎校验。 */
public class MetricPortAdapter implements MetricPort {

    private final MetricRegistry registry;
    private final MetricEngine engine;

    public MetricPortAdapter(MetricRegistry registry, MetricEngine engine) {
        this.registry = registry;
        this.engine = engine;
    }

    @Override
    public List<MetricInfo> metrics() {
        Map<String, MetricRegistry.Version> latest = new LinkedHashMap<>();
        for (MetricRegistry.Version v : registry.list()) {
            latest.merge(v.code(), v, (a, b) -> a.version() >= b.version() ? a : b);
        }
        return latest.values().stream().map(v -> new MetricInfo(v.code(), v.name(), v.definition().description(), v.definition().unit(),
            v.concept(), v.definition().dimensions(), v.definition().timeField() != null)).toList();
    }

    @Override
    public Value value(Query q, ActorContext actor) {
        MetricEngine.MetricResult r = engine.query(q.code(), new MetricEngine.MetricQuery(q.from(), q.to(), null, List.of(), conds(q), null), actor);
        Map<String, Object> row = r.rows().isEmpty() ? Map.of() : r.rows().get(0);
        return new Value(r.code(), r.version(), r.name(), r.unit(), row.get("value"));
    }

    @Override
    public Drill drill(Query q, int size, ActorContext actor) {
        MetricEngine.DrillResult r = engine.drill(q.code(), new MetricEngine.DrillRequest(q.from(), q.to(), null, null, Map.of(), conds(q), null, 1,
            size, null), actor);
        return new Drill(r.code(), r.version(), r.concept(), r.total(), r.rows());
    }

    private static List<MetricDefinition.Cond> conds(Query q) {
        List<MetricDefinition.Cond> out = new ArrayList<>();
        if (q.filters() != null) {
            for (Map<String, Object> f : q.filters()) {
                out.add(new MetricDefinition.Cond((String) f.get("field"), (String) f.getOrDefault("op", "eq"), f.get("value"), null));
            }
        }
        return out;
    }
}
