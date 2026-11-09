package com.lsmom.crud.metric;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.core.error.LsBizException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 指标口径（不含 SQL）：来源 Concept、度量、过滤、时间字段与粒度、维度、权限。
 * <ul>
 *   <li>{@code measure.fn}：count / sum / avg / min / max / ratio（ratio = 满足 numerator 条件的记录数 ÷ 全部记录数）</li>
 *   <li>过滤条件 {@code {field, op, value}} 或字段间比较 {@code {field, op, valueField}}</li>
 * </ul>
 */
public record MetricDefinition(String code, String name, String concept, String description, String unit, Measure measure,
                               List<Cond> filters, String timeField, List<String> grains, List<String> dimensions, String permission,
                               List<String> drillColumns) {

    public static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public MetricDefinition {
        filters = filters == null ? List.of() : filters;
        grains = grains == null ? List.of() : grains;
        dimensions = dimensions == null ? List.of() : dimensions;
        drillColumns = drillColumns == null ? List.of() : drillColumns;
    }

    public record Measure(String fn, String field, List<Cond> numerator) {
        public Measure {
            numerator = numerator == null ? List.of() : numerator;
        }
    }

    public record Cond(String field, String op, Object value, String valueField) {
    }

    public static MetricDefinition parse(JsonNode json) {
        try {
            return JSON.treeToValue(json, MetricDefinition.class);
        } catch (Exception e) {
            throw new LsBizException("METRIC_INVALID", "指标定义格式错误：" + e.getMessage());
        }
    }

    public static MetricDefinition parse(String json) {
        try {
            return parse(JSON.readTree(json));
        } catch (LsBizException e) {
            throw e;
        } catch (Exception e) {
            throw new LsBizException("METRIC_INVALID", "指标定义格式错误：" + e.getMessage());
        }
    }

    public JsonNode toJson() {
        return JSON.valueToTree(this);
    }

    /** 口径哈希：只看「算什么」（来源、度量、过滤、时间字段），不看编码、名称、维度、展示属性。过滤条件顺序无关。 */
    public String semanticHash() {
        ObjectNode n = JSON.createObjectNode();
        n.put("concept", concept);
        n.set("measure", JSON.valueToTree(new Measure(measure.fn(), measure.field(), sorted(measure.numerator()))));
        n.set("filters", JSON.valueToTree(sorted(filters)));
        n.put("timeField", timeField);
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(n).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Cond> sorted(List<Cond> cs) {
        List<Cond> l = new ArrayList<>(cs);
        l.sort(Comparator.comparing(c -> {
            try {
                return JSON.writeValueAsString(c);
            } catch (Exception e) {
                return String.valueOf(c);
            }
        }));
        return l;
    }
}
