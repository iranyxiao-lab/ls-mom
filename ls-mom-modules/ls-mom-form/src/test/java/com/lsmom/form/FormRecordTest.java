package com.lsmom.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.crud.CrudFixture;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 8.2：按冻结表单版本服务端校验（引用过滤、正则、范围、子表、只读、计算字段、规则）与动作只绑定已发布 Operation。 */
class FormRecordTest {

    private final FormFixture f = new FormFixture();
    private final ActorContext admin = ActionFixture.admin();
    private CrudFixture.Loan loan;
    private CoreFixture.Plant plant;
    private ObjectNode form;
    private String order;
    private long toolA;
    private long toolB;

    @BeforeEach
    void setUp() {
        loan = f.loan();
        order = loan.order().code();
        plant = f.crud.action.core.plant();
        form = FormFixture.sample("/forms/loan-order.form.json", loan.ns());
        assertThat(f.forms.saveDraft(form, 1L, null).issues()).isEmpty();
        assertThat(f.forms.publish(form.get("formCode").asText(), 1, 1L).status()).isEqualTo("APPLIED");
        toolA = id(f.crud.crud.create(loan.tool().code(), json("{\"name\":\"A 班扳手\",\"orgId\":" + plant.teamA().id() + "}"), admin, null));
        toolB = id(f.crud.crud.create(loan.tool().code(), json("{\"name\":\"B 班扳手\",\"orgId\":" + plant.teamB().id() + "}"), admin, null));
    }

    private static long id(Map<String, Object> r) {
        return ((Number) r.get("id")).longValue();
    }

    private JsonNode body(long org, long tool, String extra) {
        return json("{\"loanNo\":\"L-1\",\"borrower\":\"张三\",\"loanDate\":\"2026-11-01\",\"orgId\":" + org + ",\"toolRef\":" + tool
            + ",\"loanLine\":[{\"tool\":\"扳手\",\"qty\":2},{\"tool\":\"万用表\",\"qty\":1}]" + extra + "}");
    }

    @Test
    void 引用越过orgId过滤被拒并指出字段() {
        Party teamAUser = f.crud.action.core.employee("fa" + System.nanoTime(), plant.teamA());
        ActorContext a = f.crud.action.human(teamAUser, DataScopeLevel.ALL, "crud:" + order + ":add", "crud:" + order + ":edit");
        assertThatThrownBy(() -> f.crud.crud.create(order, body(plant.teamA().id(), toolB, ""), a, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED");
                assertThat(e.fieldErrors()).containsOnlyKeys("toolRef");
                assertThat(e.fieldErrors().get("toolRef")).contains("不在允许的引用范围内");
            });
        long id = id(f.crud.crud.create(order, body(plant.teamA().id(), toolA, ""), a, null));
        Map<String, Object> row = f.crud.crud.get(order, id, admin);
        assertThat(row).containsEntry("schemaVersion", 1L).containsEntry("qtyNote", "2 项");
    }

    @Test
    void 表单级正则范围子表行数与条件规则() {
        long org = plant.teamA().id();
        assertThatThrownBy(() -> f.crud.crud.create(order, json(body(org, toolA, "").toString().replace("\"L-1\"", "\"X-1\"")), admin, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.fieldErrors()).containsEntry("loanNo", "单号须以 L- 开头"));
        assertThatThrownBy(() -> f.crud.crud.create(order, json(body(org, toolA, "").toString().replace("\"qty\":2", "\"qty\":120")), admin, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.fieldErrors()).containsEntry("loanLine[0].qty", "第 1 行：数量不能大于 99");
                // 统一结构：子表单元格拆成 table / rowIndex / field，带稳定编码
                java.util.List<Map<String, Object>> errs = (java.util.List<Map<String, Object>>) com.lsmom.core.error.FieldErrors.complete(e.getDetails()).get("errors");
                assertThat(errs).anySatisfy(x -> assertThat(x).containsEntry("field", "loanLine.qty").containsEntry("table", "loanLine")
                    .containsEntry("rowIndex", 0).containsEntry("code", "MAX").containsEntry("message", "数量不能大于 99"));
            });
        assertThatThrownBy(() -> f.crud.crud.create(order, json(body(org, toolA, "").toString()
                .replace("[{\"tool\":\"扳手\",\"qty\":2},{\"tool\":\"万用表\",\"qty\":1}]", "[]")), admin, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.fieldErrors()).containsEntry("loanLine", "借出明细至少 1 行"));
        assertThatThrownBy(() -> f.crud.crud.create(order, body(org, toolA, ",\"purpose\":\"OTHER\""), admin, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.fieldErrors()).containsEntry("remark", "用途为其他时必须填写备注"));
        assertThat(f.crud.crud.create(order, body(org, toolA, ",\"purpose\":\"OTHER\",\"remark\":\"临时借用\""), admin, null)).containsKey("id");
    }

    @Test
    void 只读与计算字段不能由客户端写入() {
        long org = plant.teamA().id();
        assertThatThrownBy(() -> f.crud.crud.create(order, body(org, toolA, ",\"auditNote\":\"自批\""), admin, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.fieldErrors()).containsEntry("auditNote", "审核意见为只读，不能修改"));
        // 计算字段以服务端结果为准
        long id = id(f.crud.crud.create(order, body(org, toolA, ",\"qtyNote\":\"999 项\""), admin, null));
        assertThat(f.crud.crud.get(order, id, admin)).containsEntry("qtyNote", "2 项");
        // 更新时按当前记录合并后重新校验与重算
        f.crud.crud.update(order, id, 0, json("{\"loanLine\":[{\"tool\":\"扳手\",\"qty\":1}]}"), admin, null);
        assertThat(f.crud.crud.get(order, id, admin)).containsEntry("qtyNote", "1 项");
    }

    @Test
    void 动作绑定草稿Operation时发布失败() {
        f.crud.action.core.jdbc.update("""
            insert into meta_operation (code, concept_code, display_name, kind, risk, source, status)
            values (?, ?, '归档', 'HANDLER', 'C1', 'META', 'DRAFT')""", order + ".archive", order);
        form.put("version", 2);
        form.withArray("actions").add(json("{\"key\":\"archive\",\"label\":\"归档\",\"operation\":\"" + order + ".archive\"}"));
        form.withArray("actions").add(json("{\"key\":\"other\",\"label\":\"别处\",\"operation\":\"ls.core.Work.create\"}"));
        ViewSchemaValidator.Result draft = f.forms.saveDraft(form, 1L, null);
        assertThat(draft.issues()).extracting(ViewIssue::code).contains("OPERATION_NOT_PUBLISHED", "OPERATION_CONCEPT_MISMATCH");
        assertThatThrownBy(() -> f.forms.publish(form.get("formCode").asText(), 2, 1L))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("VIEW_INVALID");
                assertThat(e.getMessage()).contains("/actions/1/operation");
            });
        assertThat(f.forms.published(form.get("formCode").asText()).orElseThrow().version()).isEqualTo(1);
    }
}
