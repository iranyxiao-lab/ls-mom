package com.lsmom.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.gateway.FieldPolicyCheck;
import com.lsmom.core.error.LsBizException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关管道第 6 步：Operation 在流程节点上执行时，按 {@link FieldPolicy} 计算的节点策略校验输入——
 * 不可编辑字段出现在输入中则拒绝（FIELD_READONLY），节点要求的必填字段缺失则拒绝。
 */
@Component
public class FormFieldPolicyCheck implements FieldPolicyCheck {

    private final FormRegistry forms;
    private final ObjectProvider<NodePolicyResolver> resolvers;

    public FormFieldPolicyCheck(FormRegistry forms, ObjectProvider<NodePolicyResolver> resolvers) {
        this.forms = forms;
        this.resolvers = resolvers;
    }

    @Override
    public void check(OperationContext ctx) {
        List<NodePolicyResolver> list = resolvers.orderedStream().toList();
        for (NodePolicyResolver r : list) {
            r.resolve(ctx).ifPresent(b -> enforce(b, ctx.input()));
        }
    }

    public void enforce(NodePolicyResolver.Binding b, JsonNode input) {
        JsonNode form = forms.get(b.formCode(), b.formVersion()).schema();
        JsonNode policy = b.policy() != null && !b.policy().isNull() ? b.policy() : form.path("nodePolicies").get(b.node());
        Map<String, FieldPolicy.Access> access = FieldPolicy.resolve(form, policy);
        Map<String, String> errors = new LinkedHashMap<>();
        if (input != null) {
            Iterator<String> names = input.fieldNames();
            while (names.hasNext()) {
                String k = names.next();
                FieldPolicy.Access a = access.get(k);
                if (a != null && !a.editable()) errors.put(k, "节点「" + b.node() + "」不允许修改该字段");
            }
        }
        access.forEach((k, a) -> {
            if (!a.required() || !a.editable() || k.contains(".")) return;
            JsonNode v = input == null ? null : input.get(k);
            if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) errors.putIfAbsent(k, "节点「" + b.node() + "」要求填写该字段");
        });
        if (errors.isEmpty()) return;
        boolean readonly = errors.values().stream().anyMatch(m -> m.contains("不允许修改"));
        throw new LsBizException(readonly ? "FIELD_READONLY" : "VALIDATION_FAILED", "字段策略校验失败：" + errors.values().iterator().next(),
            Map.of("fields", errors, "node", b.node()));
    }
}
