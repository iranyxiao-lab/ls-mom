package com.lsmom.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.state.StateMachineDefinition;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.crud.CrudFixture;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * modernize-ui-ux 4.8：便利功能不绕过服务端——记忆值、扫码、粘贴带入的引用与手工选择一样复核，并说明原因：
 * 已删除、已停用（按状态机迁移名称）、不在调用者的数据范围内；修改记录时未改动的引用不复核。
 */
class RefRejectTest {

    private final FormFixture f = new FormFixture();
    private final ActorContext admin = ActionFixture.admin();
    private CrudFixture.Loan loan;
    private CoreFixture.Plant plant;
    private String order;
    private String tool;

    @BeforeEach
    void setUp() {
        loan = f.loan();
        order = loan.order().code();
        tool = loan.tool().code();
        plant = f.crud.action.core.plant();
        f.crud.action.machines.publish(new StateMachineDefinition(tool + ".lifecycle", tool, "工器具状态", List.of("DRAFT", "ACTIVE", "RETIRED"),
            "DRAFT", List.of(new StateMachineDefinition.Transition(tool + ".retire", "停用", List.of("ACTIVE"), "RETIRED", List.of(), null,
                "C1", json("{\"type\":\"object\"}"), "DRAFT"))));
        ObjectNode form = FormFixture.sample("/forms/loan-order.form.json", loan.ns());
        for (JsonNode fld : form.get("fields")) {
            if (fld.path("key").asText().equals("toolRef")) {
                ((ArrayNode) fld.get("ref").get("filter")).add(json("{\"field\":\"status\",\"op\":\"ne\",\"value\":\"RETIRED\"}"));
            }
        }
        assertThat(f.forms.saveDraft(form, 1L, null).issues()).isEmpty();
        assertThat(f.forms.publish(form.get("formCode").asText(), 1, 1L).status()).isEqualTo("APPLIED");
    }

    private long newTool(String name, long org) {
        return ((Number) f.crud.crud.create(tool, json("{\"name\":\"" + name + "\",\"orgId\":" + org + "}"), admin, null).get("id")).longValue();
    }

    private JsonNode body(long org, long toolId, String borrower) {
        return json("{\"loanNo\":\"L-1\",\"borrower\":\"" + borrower + "\",\"loanDate\":\"2026-11-01\",\"orgId\":" + org + ",\"toolRef\":" + toolId
            + ",\"loanLine\":[{\"tool\":\"扳手\",\"qty\":2}]}");
    }

    @SuppressWarnings("unchecked")
    private void rejected(Runnable call, String code, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(LsBizException.class, e -> {
            assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED");
            assertThat(e.fieldErrors()).containsOnlyKeys("toolRef");
            assertThat(e.fieldErrors().get("toolRef")).isEqualTo(message);
            // 引用字段的存在与数据范围由台账层先检查（无编码，记为 INVALID）；状态等表单级检查带编码
            if (code != null) assertThat((Map<String, String>) e.getDetails().get("codes")).containsEntry("toolRef", code);
        });
    }

    @Test
    void 已删除_已停用_无权使用分别说明原因() {
        long org = plant.teamA().id();
        var jdbc = f.crud.action.core.jdbc;
        String table = loan.tool().tableName();

        long gone = newTool("已删除的扳手", org);
        jdbc.update("update \"" + table + "\" set del_flag = '1' where id = ?", gone);
        rejected(() -> f.crud.crud.create(order, body(org, gone, "张三"), admin, null), null, "所选主要工器具已删除或不存在，请重新选择");

        long retired = newTool("停用的扳手", org);
        jdbc.update("update \"" + table + "\" set status = 'RETIRED' where id = ?", retired);
        rejected(() -> f.crud.crud.create(order, body(org, retired, "张三"), admin, null), "REF_INACTIVE", "该工器具已停用，请重新选择");

        // 只能看本人创建数据的用户：管理员建的工器具满足表单过滤，但不在其数据范围内
        Party p = f.crud.action.core.employee("rr" + System.nanoTime(), plant.teamA());
        ActorContext self = f.crud.action.human(p, DataScopeLevel.SELF, "crud:" + order + ":add");
        long others = newTool("别人的扳手", org);
        rejected(() -> f.crud.crud.create(order, body(org, others, "张三"), self, null), null, "你无权使用所选工器具，请重新选择");
    }

    @Test
    void 修改记录时未改动的引用不复核_改动则复核() {
        long org = plant.teamA().id();
        long a = newTool("在用扳手", org);
        Map<String, Object> rec = f.crud.crud.create(order, body(org, a, "张三"), admin, null);
        long id = ((Number) rec.get("id")).longValue();
        f.crud.action.core.jdbc.update("update \"" + loan.tool().tableName() + "\" set status = 'RETIRED' where id = ?", a);

        Map<String, Object> cur = f.crud.crud.get(order, id, admin);
        int version = ((Number) cur.get("version")).intValue();
        f.crud.crud.update(order, id, version, body(org, a, "李四"), admin, null);
        assertThat(f.crud.crud.get(order, id, admin)).containsEntry("borrower", "李四");

        long b = newTool("另一把扳手", org);
        f.crud.action.core.jdbc.update("update \"" + loan.tool().tableName() + "\" set status = 'RETIRED' where id = ?", b);
        int v2 = ((Number) f.crud.crud.get(order, id, admin).get("version")).intValue();
        rejected(() -> f.crud.crud.update(order, id, v2, body(org, b, "李四"), admin, null), "REF_INACTIVE", "该工器具已停用，请重新选择");
    }
}
