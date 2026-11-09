package com.lsmom.action;

import com.lsmom.action.openapi.OperationOpenApiCustomizer;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 5.9：OpenAPI 中出现每个已发布 Operation，输入 Schema 与注册定义一致。 */
class OpenApiTest {

    private final ActionFixture f = new ActionFixture();

    @Test
    void 已发布Operation出现在文档且Schema一致() throws Exception {
        OpenAPI api = new OpenAPI().paths(new Paths().addPathItem("/api/op/{code}", new PathItem()));
        new OperationOpenApiCustomizer(f.operations).customise(api);
        String json = Json31.pretty(api);

        for (String code : new String[]{"ls.core.Work.create", "ls.core.Work.assign", "ls.core.Issue.verifyPass"}) {
            assertThat(api.getPaths()).containsKey("/api/op/" + code);
            var input = api.getPaths().get("/api/op/" + code).getPost().getRequestBody().getContent().get("application/json")
                .getSchema().getProperties().get("input");
            var inputJson = Json31.mapper().readTree(Json31.mapper().writeValueAsString(input));
            assertThat(normalize(inputJson)).isEqualTo(normalize(f.operations.require(code).inputSchema()));
        }
        assertThat(api.getPaths().get("/api/op/ls.core.Work.assign").getPost().getExtensions().get("x-ls-kind")).isEqualTo("TRANSITION");
        assertThat(json).contains("\"operationId\" : \"ls.core.Issue.raise\"");
        assertThat(Json31.mapper().readTree(json).has("paths")).isTrue();
    }

    /** required 是集合语义，比较前排序。 */
    static com.fasterxml.jackson.databind.JsonNode normalize(com.fasterxml.jackson.databind.JsonNode n) {
        if (n.isObject()) {
            var o = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            n.fields().forEachRemaining(e -> {
                if (e.getKey().equals("required") && e.getValue().isArray()) {
                    var a = o.putArray("required");
                    java.util.List<String> l = new java.util.ArrayList<>();
                    e.getValue().forEach(x -> l.add(x.asText()));
                    java.util.Collections.sort(l);
                    l.forEach(a::add);
                } else {
                    o.set(e.getKey(), normalize(e.getValue()));
                }
            });
            return o;
        }
        if (n.isArray()) {
            var a = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            n.forEach(x -> a.add(normalize(x)));
            return a;
        }
        return n;
    }
}
