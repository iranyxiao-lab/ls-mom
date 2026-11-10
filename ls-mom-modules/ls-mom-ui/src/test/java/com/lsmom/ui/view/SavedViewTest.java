package com.lsmom.ui.view;

import com.lsmom.action.ActionFixture;
import com.lsmom.core.error.LsBizException;
import com.lsmom.crud.CrudFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** modernize-ui-ux 5.2：保存视图——个人与角色共享、默认视图、字段校验、只有创建者能改删。 */
class SavedViewTest {

    private final CrudFixture f = new CrudFixture();
    private final SavedViewService views = new SavedViewService(f.action.core.jdbc, f.action.concepts);
    private final CrudFixture.Loan loan = f.loanLedger();
    private final String concept = loan.order().code();
    private final long me = 920_000_000L + System.nanoTime() % 1_000_000;
    private final long other = me + 1;
    private final String role = "lv" + Long.toString(System.nanoTime(), 36);

    private static final String DEF = """
        {"filters":[{"field":"status","op":"ne","value":"DONE"},{"field":"orgId","op":"eq","value":"$context.orgId"}],
         "columns":[{"code":"borrower","visible":true,"width":180},{"code":"loanNo","visible":false,"fixed":"left"}],
         "sort":[{"field":"loanDate","dir":"desc"}],"groupBy":"status","junk":1}""";

    @Test
    void 个人视图_默认视图_规整定义() {
        long id = views.create(me, concept, " 本班组未完成 ", json(DEF), null, false);
        SavedViewService.Views mine = views.list(me, List.of(), concept);
        assertThat(mine.views()).singleElement().satisfies(v -> {
            assertThat(v.name()).isEqualTo("本班组未完成");
            assertThat(v.mine()).isTrue();
            assertThat(v.definition().has("junk")).isFalse();
            assertThat(v.definition().at("/filters/1/value").asText()).isEqualTo("$context.orgId");
            assertThat(v.definition().at("/columns/0/width").asInt()).isEqualTo(180);
            assertThat(v.definition().path("groupBy").asText()).isEqualTo("status");
        });
        assertThat(mine.defaultId()).isNull();
        views.setDefault(me, List.of(), concept, id);
        assertThat(views.list(me, List.of(), concept).defaultId()).isEqualTo(id);
        // 别人看不到个人视图，也不能设为默认
        assertThat(views.list(other, List.of(), concept).views()).isEmpty();
        assertThatThrownBy(() -> views.setDefault(other, List.of(), concept, id)).isInstanceOf(LsBizException.class);
        views.setDefault(me, List.of(), concept, null);
        assertThat(views.list(me, List.of(), concept).defaultId()).isNull();
    }

    @Test
    void 共享给角色_只有创建者能改删_字段必须存在() {
        f.action.core.jdbc.update("""
            insert into sys_role (role_id, role_name, role_key, role_sort, data_scope, status, del_flag, create_time)
            values ((select coalesce(max(role_id), 0) + 1 from sys_role), '视图测试', ?, 99, '1', '0', '0', now())""", role);
        assertThatThrownBy(() -> views.create(me, concept, "共享", json(DEF), role, false))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("ACCESS_DENIED"));
        long id = views.create(me, concept, "共享", json(DEF), role, true);
        assertThat(views.list(other, List.of(role), concept).views()).singleElement().satisfies(v -> {
            assertThat(v.mine()).isFalse();
            assertThat(v.sharedRole()).isEqualTo(role);
        });
        views.setDefault(other, List.of(role), concept, id);
        assertThat(views.list(other, List.of(role), concept).defaultId()).isEqualTo(id);
        assertThatThrownBy(() -> views.update(other, id, "改名", json(DEF), role, true))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("ACCESS_DENIED"));
        assertThatThrownBy(() -> views.delete(other, id)).isInstanceOf(LsBizException.class);
        assertThatThrownBy(() -> views.create(me, concept, "坏字段", json("{\"filters\":[{\"field\":\"nope\",\"op\":\"eq\",\"value\":1}]}"), null, false))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.fieldErrors().get("definition")).contains("字段不存在：nope"));
        assertThatThrownBy(() -> views.create(me, concept, "", json("{}"), null, false)).isInstanceOf(LsBizException.class);
        // 删除后默认视图随之清除
        views.delete(me, id);
        assertThat(views.list(other, List.of(role), concept).defaultId()).isNull();
    }
}
