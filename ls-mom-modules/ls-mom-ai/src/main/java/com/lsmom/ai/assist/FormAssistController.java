package com.lsmom.ai.assist;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.JsonNode;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 表单设计器的自然语言助手：只返回补丁草稿，不修改表单。 */
@RestController
@RequestMapping("/api/form/assist")
public class FormAssistController {

    public record AssistBody(JsonNode schema, List<FormAssistService.ConceptField> conceptFields, String instruction) {
    }

    private final FormAssistService assist;
    private final LlmClients clients;

    public FormAssistController(FormAssistService assist, LlmClients clients) {
        this.assist = assist;
        this.clients = clients;
    }

    @SaCheckPermission("lsmom:form:design")
    @PostMapping
    public R<FormAssistService.Proposal> propose(@RequestBody AssistBody b) {
        return R.ok(assist.propose(b.schema(), b.conceptFields(), b.instruction()));
    }

    /** 助手是否接入真实模型（否则只回放录制的响应） */
    @SaCheckPermission("lsmom:form:design")
    @PostMapping("/status")
    public R<java.util.Map<String, Object>> status() {
        return R.ok(java.util.Map.of("replay", clients.replay()));
    }
}
