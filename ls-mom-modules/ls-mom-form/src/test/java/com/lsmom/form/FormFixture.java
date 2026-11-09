package com.lsmom.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.rule.CelRuleEngine;
import com.lsmom.action.state.StateMachineDefinition;
import com.lsmom.crud.CrudFixture;
import com.lsmom.meta.concept.FieldUsageIndex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.lsmom.action.ActionFixture.json;

/** 表单测试装配：在 CrudFixture 之上装配组件注册表、视图校验器、视图版本库，并把表单记录校验接入通用 CRUD。 */
public class FormFixture {

    public final CrudFixture crud = new CrudFixture();
    public final FieldUsageIndex usages = new FieldUsageIndex(crud.action.core.jdbc);
    public final ComponentRegistry components = new ComponentRegistry(crud.action.core.jdbc);
    public final ViewSchemaValidator validator = new ViewSchemaValidator(crud.action.concepts, crud.action.operations, new CelRuleEngine(),
        components);
    public final FormRegistry forms = new FormRegistry(crud.action.core.jdbc, validator, crud.action.concepts, crud.action.operations, usages,
        crud.action.txm);
    public final FormRecordValidator records = new FormRecordValidator(forms, crud.action.concepts, new CelRuleEngine(), crud.action.core.jdbc);

    public FormFixture() {
        crud.crud.setRecordValidators(List.of(records));
    }

    /** 借还单台账＋状态机（submit 迁移），返回台账。 */
    public CrudFixture.Loan loan() {
        CrudFixture.Loan l = crud.loanLedger();
        String order = l.order().code();
        crud.action.machines.publish(new StateMachineDefinition(order + ".lifecycle", order, "借还单流程", List.of("DRAFT", "SUBMITTED"),
            "DRAFT", List.of(new StateMachineDefinition.Transition(order + ".submit", "提交", List.of("DRAFT"), "SUBMITTED", List.of(), null,
                "C1", json("{\"type\":\"object\"}"), "DRAFT"))));
        return l;
    }

    /** 读取测试资源中的视图样例并替换命名空间占位符。 */
    public static ObjectNode sample(String resource, String ns) {
        try (InputStream in = FormFixture.class.getResourceAsStream(resource)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("{{ns}}", ns);
            return (ObjectNode) json(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode at(JsonNode n, String pointer) {
        return n.at(pointer);
    }
}
