package com.lsmom.ui.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.web.ActorResolver;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 表单自动带入：当前人员、组织、班次、时间与本人上次填写的值。 */
@RestController
@RequestMapping("/api/ui/form-context")
public class FormContextController {

    private final FormContextService service;
    private final ActorResolver actors;

    public FormContextController(FormContextService service, ActorResolver actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping
    public R<FormContextService.Context> get(@RequestParam(required = false) String form) {
        return R.ok(service.context(LoginHelper.getUserId(), actors.current().effectiveParty(), form));
    }

    @PutMapping("/remembered/{form}")
    public R<JsonNode> remember(@PathVariable String form, @RequestBody JsonNode values) {
        return R.ok(service.remember(LoginHelper.getUserId(), form, values));
    }
}
