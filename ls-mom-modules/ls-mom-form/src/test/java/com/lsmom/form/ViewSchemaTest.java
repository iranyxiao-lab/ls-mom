package com.lsmom.form;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.core.error.LsBizException;
import com.lsmom.crud.CrudFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 8.1：六种视图的 JSON Schema 与语义校验；示例通过、未绑定字段被拒、未知控件定位到字段、看板 SQL 被拒。 */
class ViewSchemaTest {

    private final FormFixture f = new FormFixture();
    private CrudFixture.Loan loan;
    private ObjectNode form;

    @BeforeEach
    void setUp() {
        loan = f.loan();
        form = FormFixture.sample("/forms/loan-order.form.json", loan.ns());
    }

    @Test
    void 示例表单通过校验并登记字段引用() {
        ViewSchemaValidator.Result r = f.validator.validate(form);
        assertThat(r.issues()).isEmpty();
        assertThat(r.fieldRefs()).extracting(x -> x.fieldCode()).contains("loanNo", "toolRef", "tool", "qty");
    }

    @Test
    void 字段未绑定Concept时被拒() {
        ((ObjectNode) form.withArray("fields").get(1)).put("key", "borrowerName");
        ViewSchemaValidator.Result r = f.validator.validate(form);
        assertThat(r.issues()).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo("FIELD_NOT_BOUND");
            assertThat(i.path()).isEqualTo("/fields/1/key");
        });
        assertThatThrownBy(() -> f.validator.require(form)).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.getCode()).isEqualTo("VIEW_INVALID"));
    }

    @Test
    void 未知控件类型定位到字段() {
        ((ObjectNode) form.withArray("fields").get(2)).put("widget", "fancyDate");
        ViewSchemaValidator.Result r = f.validator.validate(form);
        assertThat(r.issues()).anySatisfy(i -> assertThat(i.path()).startsWith("/fields/2/widget"));
    }

    @Test
    void 布局引用未定义字段或重复放置被拒() {
        ArrayNode layout = form.withArray("layout");
        layout.add(json("{\"type\":\"field\",\"key\":\"loanNo\"}"));
        layout.add(json("{\"type\":\"field\",\"key\":\"ghost\"}"));
        ViewSchemaValidator.Result r = f.validator.validate(form);
        assertThat(r.issues()).extracting(ViewIssue::code).contains("LAYOUT_DUPLICATE", "LAYOUT_REF_INVALID");
    }

    @Test
    void 子表必须是本Concept的子Concept且状态字段不能录入() {
        ((ObjectNode) form.withArray("subTables").get(0)).put("conceptCode", loan.tool().code());
        form.withArray("fields").add(json("{\"key\":\"status\",\"widget\":\"input\",\"hidden\":true}"));
        ViewSchemaValidator.Result r = f.validator.validate(form);
        assertThat(r.issues()).extracting(ViewIssue::code).contains("SUBTABLE_INVALID", "FIELD_NOT_BOUND");
    }

    @Test
    void 规则与计算字段必须是合法受限CEL() {
        ((ObjectNode) form.withArray("rules").get(0)).put("assert", "exec('rm -rf /')");
        ((ObjectNode) form.withArray("fields").get(6).get("computed")).put("expression", "model.loanLine.map(x, x.qty)");
        ViewSchemaValidator.Result r = f.validator.validate(form);
        assertThat(r.issues()).extracting(ViewIssue::path).contains("/rules/0/assert", "/fields/6/computed/expression");
    }

    @Test
    void 列表详情打印移动与看板视图() {
        String ns = loan.ns();
        String order = loan.order().code();
        assertThat(f.validator.validate(json("""
            {"viewType":"LIST","formCode":"%s.loanList","version":1,"conceptCode":"%s",
             "columns":["loanNo",{"key":"borrower","width":120},"loanDate","status"],"filters":["loanNo","purpose"],
             "defaultSort":[{"field":"loanDate","dir":"desc"}],"rowActions":[{"key":"submit","label":"提交","operation":"%s.submit"}],
             "export":{"enabled":true}}""".formatted(ns, order, order))).issues()).isEmpty();
        assertThat(f.validator.validate(json("""
            {"viewType":"LIST","formCode":"%s.loanList","version":1,"conceptCode":"%s","columns":["loanNo"],"filters":["remark"],
             "defaultSort":[{"field":"borrower"}]}""".formatted(ns, order))).issues())
            .extracting(ViewIssue::code).containsExactlyInAnyOrder("FIELD_NOT_QUERYABLE", "FIELD_NOT_SORTABLE");
        assertThat(f.validator.validate(json("""
            {"viewType":"DETAIL","formCode":"%s.loanDetail","version":1,"conceptCode":"%s",
             "groups":[{"title":"基本信息","columns":2,"fields":["loanNo","borrower","loanDate","status"]}],
             "subTables":["%s"],"timeline":true,"attachments":true}""".formatted(ns, order, loan.line().code()))).issues()).isEmpty();
        assertThat(f.validator.validate(json("""
            {"viewType":"PRINT","formCode":"%s.loanPrint","version":1,"conceptCode":"%s","templateVersion":"1.0.0","paper":"A4",
             "header":{"title":"工器具借还单"},"sections":[{"fields":["loanNo","borrower"],"columns":2},{"fields":[],"subTable":"%s"}],
             "signatures":[{"label":"借用人"},{"label":"审核人","role":"dept_leader"}]}""".formatted(ns, order, loan.line().code()))).issues()).isEmpty();
        assertThat(f.validator.validate(json("""
            {"viewType":"MOBILE","formCode":"%s.loanMobile","version":1,"conceptCode":"%s","fields":["loanNo","borrower","loanDate"],
             "offlineDraft":true,"actions":[{"key":"submit","label":"提交","operation":"%s.submit"}]}""".formatted(ns, order, order))).issues()).isEmpty();
        assertThat(f.validator.validate(json("""
            {"viewType":"DASHBOARD","formCode":"%s.board","version":1,"widgets":[{"type":"metricCard","title":"借出","metric":"acme.tool.loanCount"}]}"""
            .formatted(ns))).issues()).isEmpty();
    }

    @Test
    void 看板写SQL被拒() {
        ViewSchemaValidator.Result r = f.validator.validate(json("""
            {"viewType":"DASHBOARD","formCode":"%s.board","version":1,
             "widgets":[{"type":"table","title":"明细","query":"acme.tool.loans","dimensions":["select * from sys_user"]}]}"""
            .formatted(loan.ns())));
        assertThat(r.issues()).extracting(ViewIssue::code).contains("DASHBOARD_SQL_FORBIDDEN");
    }
}
