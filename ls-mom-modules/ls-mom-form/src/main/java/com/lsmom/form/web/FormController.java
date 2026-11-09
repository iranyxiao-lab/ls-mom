package com.lsmom.form.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.rule.CelValues;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.core.error.LsBizException;
import com.lsmom.form.ComponentRegistry;
import com.lsmom.form.FieldPolicy;
import com.lsmom.form.FormRegistry;
import com.lsmom.form.RefSearchService;
import com.lsmom.form.ViewSchemaValidator;
import com.lsmom.form.ViewType;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/**
 * 视图（表单/列表/详情/打印/移动/看板）接口。运行时读取已发布或指定版本（历史记录按冻结版本只读渲染）；
 * 设计器只能保存草稿、校验、提交发布（经变更集），没有跳过校验的入口。
 */
@RestController
@RequestMapping("/api/form")
public class FormController {

    private final FormRegistry forms;
    private final ViewSchemaValidator validator;
    private final ComponentRegistry components;
    private final RefSearchService refs;
    private final ActorResolver actors;

    public FormController(FormRegistry forms, ViewSchemaValidator validator, ComponentRegistry components, RefSearchService refs,
                          ActorResolver actors) {
        this.forms = forms;
        this.validator = validator;
        this.components = components;
        this.refs = refs;
        this.actors = actors;
    }

    // ---------- 运行时 ----------

    @GetMapping("/views")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String concept, @RequestParam(required = false) String type) {
        return R.ok(forms.list(concept, type));
    }

    /** 已发布版本；带 version 时返回该冻结版本（含已退役，用于旧单只读渲染）。 */
    @GetMapping("/views/{code}")
    public R<FormRegistry.View> get(@PathVariable String code, @RequestParam(required = false) Integer version) {
        FormRegistry.View v = version == null ? forms.published(code).orElseThrow(() -> LsBizException.notFound("已发布视图", code))
            : forms.get(code, version);
        if ("DRAFT".equals(v.status()) && !actors.current().hasPermission("lsmom:form:design")) {
            throw new LsBizException("ACCESS_DENIED", "草稿视图仅设计人员可见");
        }
        return R.ok(v);
    }

    @GetMapping("/concept/{concept}/default")
    public R<FormRegistry.View> defaultView(@PathVariable String concept, @RequestParam(defaultValue = "FORM") ViewType type) {
        return R.ok(forms.defaultFor(concept, type).orElse(null));
    }

    @GetMapping("/views/{code}/history")
    public R<List<Map<String, Object>>> history(@PathVariable String code) {
        return R.ok(forms.history(code));
    }

    @GetMapping("/views/{code}/edit-version")
    public R<Map<String, Object>> editVersion(@PathVariable String code, @RequestParam int recordVersion) {
        return R.ok(forms.resolveForEdit(code, recordVersion));
    }

    /** 节点字段策略（与前端同一算法），供流程待办与设计器权限模拟使用。 */
    @GetMapping("/views/{code}/{version}/policy")
    public R<Map<String, FieldPolicy.Access>> policy(@PathVariable String code, @PathVariable int version,
                                                     @RequestParam(required = false) String node) {
        JsonNode s = forms.get(code, version).schema();
        return R.ok(FieldPolicy.resolve(s, node == null ? null : s.path("nodePolicies").get(node)));
    }

    public record RefSearch(JsonNode ref, String keyword, List<Long> ids, Long orgId, JsonNode model) {
    }

    /** 引用控件远程搜索：过滤与提交校验共用，结果受目标台账列表权限与数据域限制。 */
    @PostMapping("/ref/search")
    public R<List<RefSearchService.Option>> refSearch(@RequestBody RefSearch b) {
        if (b.ref() == null || !b.ref().hasNonNull("concept")) throw LsBizException.validation(Map.of("ref", "缺少引用目标"));
        return R.ok(refs.search(actors.current(), b.ref(), b.keyword(), b.ids(), b.orgId(),
            b.model() == null ? Map.of() : CelValues.asMap(b.model())));
    }

    @GetMapping("/components")
    public R<List<Map<String, Object>>> components() {
        return R.ok(components.list());
    }

    // ---------- 设计器 ----------

    public record Draft(JsonNode schema, String note) {
    }

    @SaCheckPermission("lsmom:form:design")
    @PostMapping("/drafts")
    public R<ViewSchemaValidator.Result> saveDraft(@RequestBody Draft b) {
        return R.ok(forms.saveDraft(b.schema(), actors.current().effectiveParty(), b.note()));
    }

    @SaCheckPermission("lsmom:form:design")
    @PostMapping("/validate")
    public R<ViewSchemaValidator.Result> validate(@RequestBody JsonNode schema) {
        return R.ok(validator.validate(schema));
    }

    @SaCheckPermission("lsmom:form:design")
    @PostMapping("/views/{code}/derive")
    public R<FormRegistry.View> derive(@PathVariable String code) {
        return R.ok(forms.derive(code, actors.current().effectiveParty()));
    }

    @SaCheckPermission("lsmom:form:design")
    @DeleteMapping("/views/{code}/{version}")
    public R<Void> discard(@PathVariable String code, @PathVariable int version) {
        forms.discardDraft(code, version);
        return R.ok();
    }

    @SaCheckPermission("lsmom:form:design")
    @GetMapping("/views/{code}/{version}/check")
    public R<ViewSchemaValidator.Result> check(@PathVariable String code, @PathVariable int version) {
        return R.ok(forms.check(code, version));
    }

    @SaCheckPermission("lsmom:form:publish")
    @PostMapping("/views/{code}/{version}/publish")
    public R<FormRegistry.PublishResult> publish(@PathVariable String code, @PathVariable int version) {
        return R.ok(forms.publish(code, version, actors.current().effectiveParty()));
    }

    public record Review(boolean approve) {
    }

    @SaCheckPermission("lsmom:form:review")
    @PostMapping("/changesets/{id}/review")
    public R<Void> review(@PathVariable long id, @RequestBody Review b) {
        forms.review(id, b.approve(), actors.current().effectiveParty());
        return R.ok();
    }

    @SaCheckPermission("lsmom:form:publish")
    @PostMapping("/changesets/{id}/apply")
    public R<Map<String, Object>> apply(@PathVariable long id) {
        forms.applyChangeSet(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changeSetId", id);
        out.put("status", "APPLIED");
        return R.ok(out);
    }

    @SaCheckPermission("lsmom:form:design")
    @PostMapping("/components")
    public R<Void> registerComponent(@RequestBody ComponentRegistry.Component c) {
        components.register(c);
        return R.ok();
    }
}
