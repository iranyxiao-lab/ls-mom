package com.lsmom.action.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.registry.OperationDefinition;
import com.lsmom.action.registry.OperationRegistry;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 由 Operation 注册表生成 OpenAPI 3.1 描述：每个已发布 Operation 一条 {@code POST /api/op/<code>}，
 * 请求体中的 input 即注册的输入 JSON Schema（原样输出，保证与运行时校验一致）。
 */
@Component
@SuppressWarnings("unchecked")
public class OperationOpenApiCustomizer implements GlobalOpenApiCustomizer {

    public static final String PATH_PREFIX = "/api/op/";

    private final OperationRegistry registry;

    public OperationOpenApiCustomizer(OperationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void customise(OpenAPI openApi) {
        Paths paths = openApi.getPaths();
        if (paths == null) return;
        String generic = paths.keySet().stream().filter(k -> k.endsWith(PATH_PREFIX + "{code}")).findFirst().orElse(null);
        if (generic == null) return;
        String prefix = generic.substring(0, generic.length() - "{code}".length());
        for (OperationDefinition d : registry.listPublished()) {
            if (d.kind() == OperationDefinition.Kind.QUERY) continue;
            paths.addPathItem(prefix + d.code(), new PathItem().post(describe(d)));
        }
    }

    Operation describe(OperationDefinition d) {
        Schema<?> body = new JsonSchema().types(java.util.Set.of("object"))
            .addProperty("input", toSchema(d.inputSchema()))
            .addProperty("targetId", new JsonSchema().types(java.util.Set.of("string")).description("修改已有对象时的目标编号"))
            .addProperty("version", new JsonSchema().types(java.util.Set.of("integer")).description("对象当前版本（乐观并发）"))
            .addProperty("idempotencyKey", new JsonSchema().types(java.util.Set.of("string")).description("幂等键，24 小时内重复请求返回首次结果"))
            .addProperty("simulate", new JsonSchema().types(java.util.Set.of("boolean")).description("完整执行后回滚，用于预演"));
        if (d.kind() == OperationDefinition.Kind.TRANSITION || d.requiresVersion()) body.setRequired(List.of("targetId", "version"));
        Operation op = new Operation()
            .operationId(d.code())
            .summary(d.name())
            .description((d.description() == null ? "" : d.description() + "\n\n") + "风险级别 " + d.risk() + "，Agent 暴露 " + d.exposure()
                + (d.permission() == null ? "" : "，所需权限 `" + d.permission() + "`"))
            .addTagsItem("Operation · " + d.concept())
            .requestBody(new RequestBody().required(true).content(new Content().addMediaType("application/json", new MediaType().schema(body))))
            .addParametersItem(new HeaderParameter().name("Idempotency-Key").required(false).schema(new JsonSchema().types(java.util.Set.of("string"))))
            .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("RuoYi 响应：code=200 成功；400 校验失败、403 无权限、404 不存在、409 版本或幂等冲突、422 业务规则拒绝")));
        op.addExtension("x-ls-risk", d.risk().name());
        op.addExtension("x-ls-exposure", d.exposure().name());
        op.addExtension("x-ls-kind", d.kind().name());
        return op;
    }

    /** JSON Schema 2020-12 → OpenAPI 3.1 Schema（覆盖 Operation 契约允许使用的关键字）。 */
    static Schema<?> toSchema(JsonNode node) {
        JsonSchema s = new JsonSchema();
        JsonNode type = node.get("type");
        if (type != null) {
            java.util.Set<String> types = new java.util.LinkedHashSet<>();
            if (type.isArray()) type.forEach(t -> types.add(t.asText())); else types.add(type.asText());
            s.types(types);
        }
        if (node.has("properties")) {
            node.get("properties").fields().forEachRemaining(e -> s.addProperty(e.getKey(), toSchema(e.getValue())));
        }
        if (node.has("required")) {
            List<String> req = new java.util.ArrayList<>();
            node.get("required").forEach(r -> req.add(r.asText()));
            s.setRequired(req);
        }
        if (node.has("items")) s.setItems(toSchema(node.get("items")));
        if (node.has("enum")) {
            List<Object> values = new java.util.ArrayList<>();
            node.get("enum").forEach(v -> values.add(v.isNumber() ? v.numberValue() : v.asText()));
            ((Schema<Object>) (Schema<?>) s).setEnum(values);
        }
        if (node.has("minimum")) s.setMinimum(node.get("minimum").decimalValue());
        if (node.has("maximum")) s.setMaximum(node.get("maximum").decimalValue());
        if (node.has("minLength")) s.setMinLength(node.get("minLength").asInt());
        if (node.has("maxLength")) s.setMaxLength(node.get("maxLength").asInt());
        if (node.has("minItems")) s.setMinItems(node.get("minItems").asInt());
        if (node.has("maxItems")) s.setMaxItems(node.get("maxItems").asInt());
        if (node.has("description")) s.setDescription(node.get("description").asText());
        if (node.has("format")) s.setFormat(node.get("format").asText());
        if (node.has("additionalProperties") && node.get("additionalProperties").isBoolean()) {
            s.setAdditionalProperties(node.get("additionalProperties").asBoolean());
        }
        return s;
    }
}
