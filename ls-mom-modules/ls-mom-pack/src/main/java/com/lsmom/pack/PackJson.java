package com.lsmom.pack;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** Pack 文件读写：YAML/JSON 解析、规范化（键排序）JSON 与哈希、定义差异。 */
public final class PackJson {

    public static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
        .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)).registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private PackJson() {
    }

    /** 按扩展名解析（.yaml/.yml 为 YAML，其余为 JSON）。 */
    public static JsonNode read(String path, byte[] content) {
        try {
            ObjectMapper m = path.endsWith(".yaml") || path.endsWith(".yml") ? YAML : JSON;
            JsonNode n = m.readTree(content);
            if (n == null || n.isMissingNode()) throw PackSource.invalid(path + " 为空");
            return n;
        } catch (java.io.IOException e) {
            throw PackSource.invalid(path + " 解析失败：" + originalMessage(e));
        }
    }

    private static String originalMessage(java.io.IOException e) {
        return e instanceof JsonProcessingException j ? j.getOriginalMessage() : e.getMessage();
    }

    /** 按扩展名写出（YAML 或缩进 JSON），用于漂移回灌生成新的 Pack 文件。 */
    public static byte[] write(String path, JsonNode node) {
        try {
            if (path.endsWith(".yaml") || path.endsWith(".yml")) return YAML.writeValueAsBytes(node);
            return (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 规范化：对象键排序、去掉 null 值；数组保持顺序。 */
    public static JsonNode canonical(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return JsonNodeFactory.instance.nullNode();
        if (n.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            n.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) sorted.put(e.getKey(), canonical(e.getValue()));
            });
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            sorted.forEach(o::set);
            return o;
        }
        if (n.isArray()) {
            ArrayNode a = JsonNodeFactory.instance.arrayNode();
            n.forEach(x -> a.add(canonical(x)));
            return a;
        }
        return n;
    }

    public static String hash(JsonNode n) {
        return PackSource.sha256(canonical(n).toString());
    }

    public record Diff(String path, JsonNode expected, JsonNode actual) {
    }

    /** 两个定义的差异（JSON Pointer 路径；数组按下标比较）。 */
    public static List<Diff> diff(JsonNode expected, JsonNode actual) {
        List<Diff> out = new ArrayList<>();
        diff("", canonical(expected), canonical(actual), out);
        return out;
    }

    private static void diff(String path, JsonNode a, JsonNode b, List<Diff> out) {
        if (Objects.equals(a, b)) return;
        if (a != null && b != null && a.isObject() && b.isObject()) {
            TreeMap<String, Boolean> keys = new TreeMap<>();
            a.fieldNames().forEachRemaining(k -> keys.put(k, true));
            b.fieldNames().forEachRemaining(k -> keys.put(k, true));
            for (String k : keys.keySet()) diff(path + "/" + k.replace("~", "~0").replace("/", "~1"), a.get(k), b.get(k), out);
            return;
        }
        if (a != null && b != null && a.isArray() && b.isArray() && a.size() == b.size()) {
            Iterator<JsonNode> ia = a.elements();
            Iterator<JsonNode> ib = b.elements();
            for (int i = 0; ia.hasNext(); i++) diff(path + "/" + i, ia.next(), ib.next(), out);
            return;
        }
        out.add(new Diff(path.isEmpty() ? "/" : path, a, b));
    }
}
