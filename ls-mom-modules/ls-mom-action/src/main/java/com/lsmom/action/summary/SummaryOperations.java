package com.lsmom.action.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.Exposure;
import com.lsmom.action.api.LsOperation;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.api.OperationHandler;
import com.lsmom.action.api.Risk;
import com.lsmom.action.api.SummaryContextProvider;
import com.lsmom.action.state.TransitionEffect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 摘要的 Operation：取上下文（只读）、草拟（DRAFT）；发布 / 作废走状态机。 */
public final class SummaryOperations {

    private SummaryOperations() {
    }

    static final String KINDS = "{\"type\":\"string\",\"minLength\":1,\"maxLength\":32}";

    @Component
    @LsOperation(code = "ls.core.Summary.context", concept = SummaryService.CONCEPT, name = "摘要上下文", risk = Risk.C0, exposure = Exposure.QUERY,
        permission = "lsmom:summary:draft", inputSchema = """
        {"type":"object","required":["kind","subjectId"],"properties":{"kind":""" + KINDS + """
        ,"subjectId":{"type":"string","minLength":1,"maxLength":64}}}""")
    public static class Context implements OperationHandler {
        private final SummaryService summaries;

        public Context(SummaryService summaries) {
            this.summaries = summaries;
        }

        @Override
        public Object handle(OperationContext ctx) {
            SummaryContextProvider.Context c = summaries.context(ctx.input().get("kind").asText(), ctx.input().get("subjectId").asText(), ctx.actor());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subjectConcept", c.subjectConcept());
            m.put("subjectId", c.subjectId());
            m.put("title", c.title());
            m.put("facts", c.facts().stream().map(f -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("ref", Map.of("type", f.ref().type(), "id", f.ref().id(), "label", f.ref().label() == null ? "" : f.ref().label()));
                x.put("text", f.text());
                if (f.at() != null) x.put("at", f.at().toString());
                return x;
            }).toList());
            m.put("rule", "每个要点必须引用 facts 中至少一条记录（refs: [{type, id}]）；草稿须由真人确认后发布");
            return m;
        }
    }

    @Component
    @LsOperation(code = "ls.core.Summary.draft", concept = SummaryService.CONCEPT, name = "草拟摘要", risk = Risk.C0, exposure = Exposure.DRAFT,
        permission = "lsmom:summary:draft", inputSchema = """
        {"type":"object","required":["kind","subjectId","content","points"],"properties":{"kind":""" + KINDS + """
        ,"subjectId":{"type":"string","minLength":1,"maxLength":64},"content":{"type":"string","minLength":1,"maxLength":4000},
          "model":{"type":"string","maxLength":120},
          "points":{"type":"array","minItems":1,"maxItems":30,"items":{"type":"object","required":["text","refs"],"properties":{
            "text":{"type":"string","minLength":1,"maxLength":1000},
            "refs":{"type":"array","items":{"type":"object","required":["type","id"],"properties":{
              "type":{"type":"string"},"id":{"type":"string"},"label":{"type":"string"}}}}}}}}}""")
    public static class Draft implements OperationHandler {
        private final SummaryService summaries;

        public Draft(SummaryService summaries) {
            this.summaries = summaries;
        }

        @Override
        public Object handle(OperationContext ctx) {
            JsonNode in = ctx.input();
            List<SummaryService.Point> points = new ArrayList<>();
            for (JsonNode p : in.get("points")) {
                List<SummaryContextProvider.Ref> refs = new ArrayList<>();
                p.path("refs").forEach(r -> refs.add(new SummaryContextProvider.Ref(r.path("type").asText(), r.path("id").asText(), r.path("label").asText(null))));
                points.add(new SummaryService.Point(p.path("text").asText(), refs));
            }
            ActorContext actor = ctx.actor();
            long id = summaries.draft(in.get("kind").asText(), in.get("subjectId").asText(), in.get("content").asText(), points,
                in.hasNonNull("model") ? in.get("model").asText() : null, actor);
            Map<String, Object> s = summaries.find(id).orElseThrow();
            ctx.target(String.valueOf(id), ((Number) s.get("version")).intValue(), "DRAFT", s.get("orgUnitId") == null ? null : ((Number) s.get("orgUnitId")).longValue());
            ctx.eventPayload().put("kind", s.get("kind"));
            ctx.eventPayload().put("subject", s.get("subjectConcept") + ":" + s.get("subjectId"));
            return Map.of("id", id, "status", "DRAFT", "citations", s.get("citations"));
        }
    }

    /** 发布 / 作废的效果：记录发布人（被代理的人）与时间；作废原因。 */
    @Configuration(proxyBeanMethods = false)
    public static class Effects {
        @Bean
        public TransitionEffect summaryPublishEffect() {
            return new TransitionEffect() {
                @Override
                public String operation() {
                    return "ls.core.Summary.publish";
                }

                @Override
                public Map<String, Object> apply(OperationContext ctx, com.lsmom.action.state.StateSnapshot before) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("publishedBy", ctx.actor().effectiveParty());
                    m.put("publishedAt", LocalDateTime.now());
                    return m;
                }
            };
        }

        @Bean
        public TransitionEffect summaryDiscardEffect() {
            return new TransitionEffect() {
                @Override
                public String operation() {
                    return "ls.core.Summary.discard";
                }

                @Override
                public Map<String, Object> apply(OperationContext ctx, com.lsmom.action.state.StateSnapshot before) {
                    ctx.eventPayload().put("reason", ctx.text("reason"));
                    return Map.of("discardReason", ctx.text("reason"));
                }
            };
        }
    }
}
