package com.lsmom.crud.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.crud.metric.MetricDefinition;
import com.lsmom.crud.metric.MetricEngine;
import com.lsmom.crud.metric.MetricRegistry;
import com.lsmom.crud.metric.NamedQueryRegistry;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 指标与命名 Query 接口。指标口径只接受结构化定义；命名 Query 只能调用研发登记的编码，不接受 SQL。 */
@RestController
@RequestMapping("/api")
public class MetricController {

    private final MetricRegistry registry;
    private final MetricEngine engine;
    private final NamedQueryRegistry queries;
    private final ActorResolver actors;

    public MetricController(MetricRegistry registry, MetricEngine engine, NamedQueryRegistry queries, ActorResolver actors) {
        this.registry = registry;
        this.engine = engine;
        this.queries = queries;
        this.actors = actors;
    }

    @GetMapping("/metrics")
    public R<List<Map<String, Object>>> list() {
        return R.ok(registry.list().stream().map(MetricController::summary).toList());
    }

    @GetMapping("/metrics/{code}")
    public R<MetricRegistry.Version> get(@PathVariable String code, @RequestParam(required = false) Integer version) {
        return R.ok(registry.require(code, version));
    }

    @GetMapping("/metrics/{code}/history")
    public R<List<Map<String, Object>>> history(@PathVariable String code) {
        return R.ok(registry.history(code).stream().map(MetricController::summary).toList());
    }

    @SaCheckPermission("lsmom:metric:design")
    @PostMapping("/metrics/validate")
    public R<List<MetricRegistry.Issue>> validate(@RequestBody JsonNode definition) {
        return R.ok(registry.validate(MetricDefinition.parse(definition)));
    }

    @SaCheckPermission("lsmom:metric:design")
    @PostMapping("/metrics/publish")
    public R<MetricRegistry.Version> publish(@RequestBody JsonNode definition) {
        return R.ok(registry.publish(MetricDefinition.parse(definition), actors.current().effectiveParty()));
    }

    @PostMapping("/metrics/{code}/query")
    public R<MetricEngine.MetricResult> query(@PathVariable String code, @RequestBody(required = false) MetricEngine.MetricQuery q) {
        return R.ok(engine.query(code, q == null ? new MetricEngine.MetricQuery(null, null, null, null, null, null) : q, actors.current()));
    }

    @PostMapping("/metrics/{code}/drill")
    public R<MetricEngine.DrillResult> drill(@PathVariable String code, @RequestBody(required = false) MetricEngine.DrillRequest r) {
        return R.ok(engine.drill(code, r == null ? new MetricEngine.DrillRequest(null, null, null, null, null, null, null, null, null, null) : r,
            actors.current()));
    }

    @GetMapping("/q")
    public R<List<NamedQueryRegistry.Info>> namedQueries() {
        return R.ok(queries.list());
    }

    @PostMapping("/q/{queryCode}")
    public R<NamedQueryRegistry.Result> run(@PathVariable String queryCode, @RequestBody(required = false) JsonNode params) {
        return R.ok(queries.execute(queryCode, params, actors.current()));
    }

    private static Map<String, Object> summary(MetricRegistry.Version v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", v.code());
        m.put("version", v.version());
        m.put("name", v.name());
        m.put("concept", v.concept());
        m.put("status", v.status());
        m.put("unit", v.definition().unit());
        m.put("fn", v.definition().measure().fn());
        m.put("dimensions", v.definition().dimensions());
        m.put("grains", v.definition().grains());
        m.put("description", v.definition().description());
        m.put("publishedAt", v.publishedAt());
        return m;
    }
}
