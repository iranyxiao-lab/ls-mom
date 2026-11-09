package com.lsmom.form;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.core.error.LsBizException;
import com.lsmom.crud.CrudFixture;
import org.junit.jupiter.api.Test;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 8.3：服务端按节点策略拒绝修改只读字段（readonly:["*"] ＋ editable:["auditNote"]）。 */
class NodePolicyCheckTest {

    @Test
    void 审核节点只允许修改审核意见() {
        FormFixture f = new FormFixture();
        CrudFixture.Loan loan = f.loan();
        ObjectNode form = FormFixture.sample("/forms/loan-order.form.json", loan.ns());
        f.forms.saveDraft(form, 1L, null);
        f.forms.publish(form.get("formCode").asText(), 1, 1L);
        FormFieldPolicyCheck check = new FormFieldPolicyCheck(f.forms, null);
        NodePolicyResolver.Binding node = new NodePolicyResolver.Binding(form.get("formCode").asText(), 1, "deptAudit");
        assertThatCode(() -> check.enforce(node, json("{\"auditNote\":\"同意\"}"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> check.enforce(node, json("{\"auditNote\":\"同意\",\"borrower\":\"改人\"}")))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("FIELD_READONLY");
                assertThat(e.fieldErrors()).containsOnlyKeys("borrower");
            });
        assertThatThrownBy(() -> check.enforce(node, json("{}"))).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.fieldErrors()).containsOnlyKeys("auditNote"));
    }
}
