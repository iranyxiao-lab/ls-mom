package com.lsmom.action.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.core.error.LsBizException;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按 JSON Schema（2020-12）校验 Operation 输入，错误转为 字段 → 原因。 */
@Component
public class InputSchemaValidator {

    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final Map<String, Schema> cache = new ConcurrentHashMap<>();

    public void validate(JsonNode schema, JsonNode input) {
        Schema s = cache.computeIfAbsent(schema.toString(), k -> registry.getSchema(schema));
        List<Error> errors = s.validate(input);
        if (errors.isEmpty()) return;
        Map<String, String> fields = new LinkedHashMap<>();
        for (Error e : errors) {
            String loc = e.getInstanceLocation().toString().replaceFirst("^/", "").replace('/', '.');
            String field = e.getProperty() != null ? (loc.isEmpty() ? e.getProperty() : loc + "." + e.getProperty()) : (loc.isEmpty() ? "$" : loc);
            fields.putIfAbsent(field, describe(e, field));
        }
        throw LsBizException.validation(fields);
    }

    private static String describe(Error e, String field) {
        return switch (e.getKeyword()) {
            case "required" -> field + " 必填";
            case "type" -> field + " 类型不正确";
            case "enum" -> field + " 取值不在允许范围内";
            case "maxLength" -> field + " 过长";
            case "minLength" -> field + " 不能为空";
            case "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum" -> field + " 超出允许范围";
            case "additionalProperties" -> "不允许的字段 " + field;
            default -> field + "：" + e.getMessage();
        };
    }
}
