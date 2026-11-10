package com.lsmom.ai.knowledge.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.api.OperationResult;
import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.ai.knowledge.KnowledgeService;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 知识库维护（修订稿 → 真人发布）与问答（经 Operation 网关，与 Agent 同一入口、同样审计）。 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final KnowledgeService knowledge;
    private final OperationGateway gateway;
    private final ActorResolver actors;

    public KnowledgeController(KnowledgeService knowledge, OperationGateway gateway, ActorResolver actors) {
        this.knowledge = knowledge;
        this.gateway = gateway;
        this.actors = actors;
    }

    public record CreateBody(String code, String title, String category, Long orgUnitId, String classification, String content) {
    }

    public record DraftBody(String content, String note) {
    }

    public record AskBody(String question) {
    }

    @SaCheckPermission(value = {"lsmom:knowledge:manage", "lsmom:knowledge:ask"}, mode = SaMode.OR)
    @GetMapping("/documents")
    public R<List<Map<String, Object>>> list() {
        return R.ok(knowledge.list(actors.current()));
    }

    @SaCheckPermission(value = {"lsmom:knowledge:manage", "lsmom:knowledge:ask"}, mode = SaMode.OR)
    @GetMapping("/documents/{id}")
    public R<Map<String, Object>> get(@PathVariable long id) {
        return R.ok(knowledge.get(id, actors.current()));
    }

    @SaCheckPermission("lsmom:knowledge:manage")
    @PostMapping("/documents")
    public R<Map<String, Object>> create(@RequestBody CreateBody b) {
        long id = knowledge.create(b.code(), b.title(), b.category(), b.orgUnitId(), b.classification(), b.content(), actors.current());
        return R.ok(knowledge.get(id, actors.current()));
    }

    @SaCheckPermission("lsmom:knowledge:manage")
    @PutMapping("/documents/{id}/draft")
    public R<Map<String, Object>> draft(@PathVariable long id, @RequestBody DraftBody b) {
        knowledge.saveDraft(id, b.content(), b.note(), actors.current());
        return R.ok(knowledge.get(id, actors.current()));
    }

    @SaCheckPermission("lsmom:knowledge:publish")
    @PostMapping("/documents/{id}/publish")
    public R<Map<String, Object>> publish(@PathVariable long id) {
        knowledge.publish(id, actors.current());
        return R.ok(knowledge.get(id, actors.current()));
    }

    @SaCheckPermission("lsmom:knowledge:ask")
    @PostMapping("/ask")
    public R<Object> ask(@RequestBody AskBody b) {
        JsonNode in = JSON.createObjectNode().put("question", b.question() == null ? "" : b.question());
        OperationResult r = gateway.execute("ls.core.KnowledgeDocument.ask", OperationRequest.of(in), actors.current());
        return R.ok(r.data());
    }
}
