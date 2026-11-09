package com.lsmom.form;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.core.error.LsBizException;
import com.lsmom.crud.CrudFixture;
import com.lsmom.meta.concept.FieldUsageIndex;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 8.8：版本不可变、旧单按旧版、旧草稿迁移策略、发布兼容性（节点策略/规则/打印模板/Operation 输入）、破坏性变更评审。 */
class FormVersionTest {

    private final FormFixture f = new FormFixture();
    private final ActorContext admin = ActionFixture.admin();
    private CrudFixture.Loan loan;
    private CoreFixture.Plant plant;
    private ObjectNode form;
    private String code;
    private String order;

    @BeforeEach
    void setUp() {
        loan = f.loan();
        order = loan.order().code();
        plant = f.crud.action.core.plant();
        form = FormFixture.sample("/forms/loan-order.form.json", loan.ns());
        code = form.get("formCode").asText();
        f.forms.saveDraft(form, 1L, null);
        f.forms.publish(code, 1, 1L);
    }

    private ObjectNode v2() {
        f.forms.derive(code, 2L);
        return (ObjectNode) f.forms.get(code, 2).schema().deepCopy();
    }

    private long createRecord(String no) {
        return ((Number) f.crud.crud.create(order, json("{\"loanNo\":\"" + no + "\",\"borrower\":\"张三\",\"loanDate\":\"2026-11-01\",\"orgId\":"
            + plant.teamA().id() + ",\"loanLine\":[{\"tool\":\"扳手\",\"qty\":2}]}"), admin, null).get("id")).longValue();
    }

    @Test
    void 已发布版本不可修改且只能派生新版本() {
        assertThatThrownBy(() -> f.forms.saveDraft(form, 1L, null)).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.getCode()).isEqualTo("FORM_VERSION_IMMUTABLE"));
        ObjectNode next = v2();
        assertThat(next.get("version").asInt()).isEqualTo(2);
        assertThat(f.forms.get(code, 2).status()).isEqualTo("DRAFT");
        assertThat(f.forms.derive(code, 2L).version()).isEqualTo(2); // 已有草稿时复用
    }

    @Test
    void 发布v2后旧单仍按v1渲染且编辑旧草稿按迁移策略() {
        long old = createRecord("L-OLD");
        ObjectNode next = v2();
        next.put("title", "工器具借还单（新版）");
        next.withArray("layout").remove(0);
        next.withArray("layout").insert(0, json("{\"type\":\"group\",\"title\":\"单据\",\"children\":[{\"type\":\"field\",\"key\":\"borrower\"},"
            + "{\"type\":\"field\",\"key\":\"loanNo\"}]}"));
        f.forms.saveDraft(next, 2L, "调整布局");
        assertThat(f.forms.publish(code, 2, 2L).status()).isEqualTo("APPLIED");

        Map<String, Object> row = f.crud.crud.get(order, old, admin);
        assertThat(row).containsEntry("schemaVersion", 1L);
        FormRegistry.View frozen = f.forms.get(code, 1);
        assertThat(frozen.status()).isEqualTo("RETIRED");
        assertThat(frozen.schema().path("layout").get(0).path("type").asText()).isEqualTo("row");
        assertThat(f.forms.published(code).orElseThrow().version()).isEqualTo(2);

        Map<String, Object> edit = f.forms.resolveForEdit(code, 1);
        assertThat(edit).containsEntry("strategy", "PROMPT").containsEntry("prompt", true).containsEntry("useVersion", 1).containsEntry("latestVersion", 2);
        // 用户选择保持 v3 → 继续按旧版保存；选择切换 → 记录升级到新版本；不能再降级
        f.crud.crud.update(order, old, 0, json("{\"borrower\":\"李四\"}"), admin, null);
        assertThat(f.crud.crud.get(order, old, admin)).containsEntry("schemaVersion", 1L);
        f.crud.crud.update(order, old, 1, json("{\"borrower\":\"王五\",\"_form\":{\"code\":\"" + code + "\",\"version\":2}}"), admin, null);
        assertThat(f.crud.crud.get(order, old, admin)).containsEntry("schemaVersion", 2L);
        assertThatThrownBy(() -> f.crud.crud.update(order, old, 2, json("{\"_form\":{\"code\":\"" + code + "\",\"version\":1}}"), admin, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.fieldErrors()).containsKey("_form"));
        // 新建记录按最新版本冻结
        assertThat(f.crud.crud.get(order, createRecord("L-NEW"), admin)).containsEntry("schemaVersion", 2L);
    }

    @Test
    void 新版本移除流程节点必填字段时发布失败并列出节点() {
        // 流程模块发布审批流时登记节点策略引用：节点 deptAudit 要求 remark
        f.usages.replace(FieldUsageIndex.UserType.FLOW_NODE, loan.ns() + ".loanFlow#deptAudit", 1,
            List.of(new FieldUsageIndex.FieldRef(order, "purpose", "required")));
        ObjectNode next = v2();
        removeField(next, "purpose");
        f.forms.saveDraft(next, 2L, null);
        assertThatThrownBy(() -> f.forms.publish(code, 2, 2L)).isInstanceOfSatisfying(LsBizException.class, e -> {
            assertThat(e.getCode()).isEqualTo("VIEW_INVALID");
            assertThat(e.getMessage()).contains(loan.ns() + ".loanFlow#deptAudit").contains("purpose");
        });
        // 表单自身的节点策略引用被删字段同样失败
        ObjectNode bad = (ObjectNode) f.forms.get(code, 2).schema().deepCopy();
        f.usages.retire(FieldUsageIndex.UserType.FLOW_NODE, loan.ns() + ".loanFlow#deptAudit", 1);
        removeField(bad, "auditNote");
        f.forms.saveDraft(bad, 2L, null);
        assertThat(f.forms.check(code, 2).issues()).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo("POLICY_FIELD_INVALID");
            assertThat(i.path()).startsWith("/nodePolicies/deptAudit");
        });
    }

    @Test
    void 规则与打印模板使用被删字段时发布失败() {
        f.crud.action.core.jdbc.update("""
            insert into meta_rule (code, version, concept_code, level, kind, display_name, message, definition, status)
            values (?, 1, ?, 'L2', 'CEL', '备注长度', '备注过长', ?::jsonb, 'PUBLISHED')""", loan.ns() + ".remarkLen", order,
            "{\"assert\":\"size(model.purpose) < 100\"}");
        f.forms.saveDraft(json("""
            {"viewType":"PRINT","formCode":"%s.loanPrint","version":1,"conceptCode":"%s","templateVersion":"2.1.0",
             "sections":[{"fields":["loanNo","purpose"]}]}""".formatted(loan.ns(), order)), 1L, null);
        f.forms.publish(loan.ns() + ".loanPrint", 1, 1L);
        ObjectNode next = v2();
        removeField(next, "purpose");
        f.forms.saveDraft(next, 2L, null);
        assertThat(f.forms.check(code, 2).issues()).extracting(ViewIssue::code).containsExactlyInAnyOrder("RULE_FIELD_REMOVED", "PRINT_FIELD_REMOVED");
    }

    @Test
    void 移除字段是破坏性变更需他人评审() {
        ObjectNode next = v2();
        removeField(next, "qtyNote");
        f.forms.saveDraft(next, 2L, null);
        FormRegistry.PublishResult r = f.forms.publish(code, 2, 2L);
        assertThat(r.status()).isEqualTo("REVIEWING");
        assertThat(r.removedFields()).containsExactly("qtyNote");
        assertThat(f.forms.published(code).orElseThrow().version()).isEqualTo(1);
        assertThatThrownBy(() -> f.forms.review(r.changeSetId(), true, 2L)).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.getCode()).isEqualTo("CHANGESET_SELF_REVIEW"));
        assertThatThrownBy(() -> f.forms.applyChangeSet(r.changeSetId())).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.getCode()).isEqualTo("CHANGESET_NOT_APPROVED"));
        f.forms.review(r.changeSetId(), true, 3L);
        f.forms.applyChangeSet(r.changeSetId());
        assertThat(f.forms.published(code).orElseThrow().version()).isEqualTo(2);
        assertThat(f.crud.action.count("select count(*) from meta_field_usage where user_code = ? and field_code = 'qtyNote'", code)).isZero();
    }

    @Test
    void 动作Operation输入需要的字段必须由表单提供() {
        f.crud.action.core.jdbc.update("""
            insert into meta_operation (code, concept_code, display_name, kind, risk, source, status, input_schema)
            values (?, ?, '登记', 'HANDLER', 'C1', 'META', 'PUBLISHED', ?::jsonb)""", order + ".register", order,
            "{\"type\":\"object\",\"required\":[\"deposit\",\"reason\"]}");
        ObjectNode next = v2();
        next.withArray("actions").add(json("{\"key\":\"register\",\"label\":\"登记\",\"operation\":\"" + order + ".register\"}"));
        f.forms.saveDraft(next, 2L, null);
        assertThat(f.forms.check(code, 2).issues()).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo("OPERATION_INPUT_INCOMPATIBLE");
            assertThat(i.message()).contains("deposit").doesNotContain("reason");
        });
    }

    private static void removeField(ObjectNode schema, String key) {
        var fields = schema.withArray("fields");
        for (int i = fields.size() - 1; i >= 0; i--) if (fields.get(i).path("key").asText().equals(key)) fields.remove(i);
        removeFromLayout(schema.withArray("layout"), key);
    }

    private static void removeFromLayout(com.fasterxml.jackson.databind.node.ArrayNode nodes, String key) {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            var n = nodes.get(i);
            if (n.path("type").asText().equals("field") && n.path("key").asText().equals(key)) nodes.remove(i);
            else if (n.has("children")) removeFromLayout((com.fasterxml.jackson.databind.node.ArrayNode) n.get("children"), key);
        }
    }
}
