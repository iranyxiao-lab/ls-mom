package com.lsmom.form;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.core.error.LsBizException;
import com.lsmom.crud.CrudFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 8.7 / FORM-08：任何属性中的脚本或函数体都被拒绝且无法发布；自定义组件只能来自注册表。 */
class ComponentAndScriptTest {

    private final FormFixture f = new FormFixture();
    private CrudFixture.Loan loan;
    private ObjectNode form;
    private String code;

    @BeforeEach
    void setUp() {
        loan = f.loan();
        form = FormFixture.sample("/forms/loan-order.form.json", loan.ns());
        code = form.get("formCode").asText();
    }

    @Test
    void 粘贴脚本无法发布() {
        String[] payloads = {"<script>alert(1)</script>", "function () { return 1 }", "x => x + 1", "javascript:alert(1)",
            "<img src=x onerror=alert(1)>", "eval('1')", "new Function('a')"};
        for (String p : payloads) {
            ObjectNode s = form.deepCopy();
            ((ObjectNode) s.withArray("fields").get(0)).put("help", p);
            ViewSchemaValidator.Result r = f.validator.validate(s);
            assertThat(r.issues()).as(p).anySatisfy(i -> {
                assertThat(i.code()).isEqualTo("FORM_SCRIPT_FORBIDDEN");
                assertThat(i.path()).isEqualTo("/fields/0/help");
            });
        }
        ((ObjectNode) form.withArray("fields").get(0)).put("placeholder", "<script>fetch('/x')</script>");
        f.forms.saveDraft(form, 1L, null);
        assertThatThrownBy(() -> f.forms.publish(code, 1, 1L)).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.getCode()).isEqualTo("FORM_SCRIPT_FORBIDDEN"));
        assertThat(f.forms.published(code)).isEmpty();
        // 脚本属性名（onClick 等）同样被拒
        ObjectNode s = (ObjectNode) json(form.toString().replace("<script>fetch('/x')</script>", "x"));
        ((ObjectNode) s.withArray("layout").get(0)).put("onClick", "x");
        assertThat(f.validator.validate(s).issues()).extracting(ViewIssue::code).contains("FORM_SCRIPT_FORBIDDEN");
    }

    @Test
    void 自定义组件必须已注册且兼容() {
        ObjectNode s = form.deepCopy();
        ObjectNode remark = (ObjectNode) s.withArray("fields").get(5);
        remark.put("widget", "registered");
        remark.set("component", json("{\"code\":\"acme.widget.signaturePad\",\"version\":\"1.0.0\"}"));
        assertThat(f.validator.validate(s).issues()).extracting(ViewIssue::code).contains("COMPONENT_NOT_REGISTERED");

        f.components.register(new ComponentRegistry.Component("acme.widget.signaturePad", "1.0.0", "签名板", "手写签名",
            json("[{\"name\":\"penColor\",\"type\":\"string\"}]"), json("[{\"name\":\"value\",\"type\":\"string\"}]"), null, 1, null,
            "plus-ui/tests/unit/components/signature-pad.spec.ts", "ACTIVE"));
        assertThat(f.validator.validate(s).issues()).isEmpty();
        f.components.register(new ComponentRegistry.Component("acme.widget.signaturePad", "2.0.0", "签名板", null, null, null, null, 2, null,
            "plus-ui/tests/unit/components/signature-pad.spec.ts", "ACTIVE"));
        remark.set("component", json("{\"code\":\"acme.widget.signaturePad\",\"version\":\"2.0.0\"}"));
        assertThat(f.validator.validate(s).issues()).extracting(ViewIssue::code).contains("COMPONENT_INCOMPATIBLE");
    }

    @Test
    void 组件注册拒绝脚本与缺少测试() {
        assertThatThrownBy(() -> f.components.register(new ComponentRegistry.Component("acme.widget.evil", "1.0.0", "<script>x</script>",
            null, null, null, null, 1, null, "t.spec.ts", "ACTIVE"))).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.getCode()).isEqualTo("COMPONENT_REJECTED"));
        assertThatThrownBy(() -> f.components.register(new ComponentRegistry.Component("acme.widget.noTest", "1.0.0", "无测试",
            null, null, null, null, 1, null, " ", "ACTIVE"))).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.getCode()).isEqualTo("COMPONENT_REJECTED"));
    }
}
