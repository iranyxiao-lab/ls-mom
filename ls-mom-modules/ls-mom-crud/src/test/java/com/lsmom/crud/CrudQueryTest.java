package com.lsmom.crud;

import com.lsmom.action.api.ActorContext;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.crud.query.QuerySpec;
import com.lsmom.crud.query.QuerySpec.Filter;
import com.lsmom.crud.query.QuerySpec.Sort;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 7.2：SafeSqlBuilder 与 QuerySpec——CRUD-03 注入测试集（排序注入、非白名单字段、联表越权）全部拒绝。 */
class CrudQueryTest {

    private final CrudFixture f = new CrudFixture();
    private CrudFixture.Loan loan;
    private CoreFixture.Plant plant;
    private ActorContext userA;
    private String order;
    private final ActorContext admin = com.lsmom.action.ActionFixture.admin();

    @BeforeEach
    void setUp() {
        loan = f.loanLedger();
        order = loan.order().code();
        plant = f.action.core.plant();
        Party a = f.action.core.employee("qa", plant.teamA());
        userA = f.action.human(a, DataScopeLevel.ORG, GenericCrudService.permission(order, "list"), GenericCrudService.permission(order, "add"));
        long toolA = tool("扭矩扳手", plant.teamA().id());
        long toolB = tool("绝缘手套", plant.teamB().id());
        create(admin, "L-A1", "张三", plant.teamA().id(), toolA, "MAINTENANCE");
        create(admin, "L-A2", "李四", plant.teamA().id(), toolB, "INSPECTION");
        create(admin, "L-B1", "王五", plant.teamB().id(), toolB, "MAINTENANCE");
    }

    private long tool(String name, long org) {
        return ((Number) f.crud.create(loan.tool().code(), json("{\"name\":\"" + name + "\",\"cost\":120.5,\"orgId\":" + org + "}"), admin, null)
            .get("id")).longValue();
    }

    private void create(ActorContext actor, String no, String borrower, long org, long toolId, String purpose) {
        f.crud.create(order, json("{\"loanNo\":\"" + no + "\",\"borrower\":\"" + borrower + "\",\"loanDate\":\"2026-11-01\",\"orgId\":" + org
            + ",\"toolRef\":" + toolId + ",\"purpose\":\"" + purpose + "\",\"deposit\":50,\"urgent\":\"Y\"}"), actor, null);
    }

    private static QuerySpec filters(Filter... fs) {
        return new QuerySpec(List.of(fs), List.of(), 1, 50, List.of(), List.of(), List.of());
    }

    private static QuerySpec sort(String field, String dir) {
        return new QuerySpec(List.of(), List.of(new Sort(field, dir)), 1, 50, List.of(), List.of(), List.of());
    }

    private void rejected(ActorContext actor, QuerySpec spec, String fieldKey) {
        assertThatThrownBy(() -> f.crud.list(order, spec, actor))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("QUERY_REJECTED");
                assertThat(e.fieldErrors()).containsKey(fieldKey);
            });
    }

    @Test
    void 排序注入被拒绝为非法排序字段() {
        rejected(admin, sort("name; drop table x", "asc"), "sort");
        rejected(admin, sort("loanNo", "asc; drop table x"), "sort");
        rejected(admin, sort("borrower", "asc"), "sort"); // 可查询但未开放排序
        rejected(admin, sort("loan_no", "asc"), "sort"); // 物理列名不是字段编码
        assertThat(f.action.count("select count(*) from information_schema.tables where table_name = ?", loan.order().tableName())).isEqualTo(1);
        assertThat(f.crud.list(order, sort("loanNo", "desc"), admin).rows()).extracting(r -> r.get("loanNo")).containsExactly("L-B1", "L-A2", "L-A1");
    }

    @Test
    void 非白名单字段与操作符过滤被拒绝() {
        rejected(admin, filters(new Filter("remark", "eq", "x")), "remark");          // 未开放查询
        rejected(admin, filters(new Filter("wechat", "eq", "x")), "wechat");          // ext 字段
        rejected(admin, filters(new Filter("del_flag", "eq", "2")), "del_flag");      // 保留列
        rejected(admin, filters(new Filter("1=1 or loanNo", "eq", "x")), "1=1 or loanNo");
        rejected(admin, filters(new Filter("loanNo", "= 'x' or 1=1 --", "x")), "loanNo");
        rejected(admin, filters(new Filter("loanDate", "eq", "not-a-date")), "loanDate"); // 未开放查询（仅排序）
        rejected(admin, filters(new Filter("createdAt", "gt", "'; drop")), "createdAt");   // 类型转换失败
        // 值中的注入片段只作为参数绑定
        assertThat(f.crud.list(order, filters(new Filter("borrower", "eq", "' or '1'='1")), admin).total()).isZero();
        assertThat(f.crud.list(order, filters(new Filter("borrower", "like", "%")), admin).total()).isZero();
        assertThat(f.crud.list(order, filters(new Filter("purpose", "in", List.of("MAINTENANCE"))), admin).total()).isEqualTo(2);
        assertThat(f.crud.list(order, filters(new Filter("loanNo", "startsWith", "L-A")), admin).total()).isEqualTo(2);
    }

    @Test
    void 联表越权被拒绝且关联条件按被引用对象数据域过滤() {
        // 被引用对象未开放查询的字段
        rejected(admin, filters(new Filter("toolRef.cost", "gt", 1)), "toolRef.cost");
        // 多级路径
        rejected(admin, filters(new Filter("toolRef.name.x", "eq", "a")), "toolRef.name.x");
        // 没有被引用对象的查看权限
        rejected(userA, filters(new Filter("toolRef.name", "eq", "绝缘手套")), "toolRef.name");
        // 不能展开没有权限的引用
        QuerySpec expand = new QuerySpec(List.of(), List.of(), 1, 50, List.of("toolRef"), List.of(), List.of());
        assertThat(f.crud.list(order, expand, userA).rows()).allSatisfy(r ->
            assertThat(((Map<?, ?>) r.get("toolRefRef")).get("restricted")).isEqualTo(true));
        rejected(admin, new QuerySpec(List.of(), List.of(), 1, 50, List.of("borrower"), List.of(), List.of()), "expand");

        ActorContext withTool = f.action.human(f.action.core.employee("qa2", plant.teamA()), DataScopeLevel.ORG,
            GenericCrudService.permission(order, "list"), GenericCrudService.permission(loan.tool().code(), "list"));
        // A 班组用户：绝缘手套属于 B 班组，按被引用对象数据域过滤后不可见
        assertThat(f.crud.list(order, filters(new Filter("toolRef.name", "eq", "绝缘手套")), withTool).total()).isZero();
        assertThat(f.crud.list(order, filters(new Filter("toolRef.name", "eq", "扭矩扳手")), withTool).rows())
            .extracting(r -> r.get("loanNo")).containsExactly("L-A1");
        assertThat(f.crud.list(order, filters(new Filter("toolRef.name", "eq", "绝缘手套")), admin).total()).isEqualTo(2);
        assertThat(f.crud.list(order, expand, withTool).rows()).anySatisfy(r ->
            assertThat(((Map<?, ?>) r.get("toolRefRef")).get("label")).isEqualTo("扭矩扳手"));
    }

    @Test
    void 列表按数据域过滤且隐藏无读权限字段并翻译字典() {
        var page = f.crud.list(order, QuerySpec.all(), userA);
        assertThat(page.rows()).extracting(r -> r.get("loanNo")).containsExactlyInAnyOrder("L-A1", "L-A2");
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.rows().get(0)).doesNotContainKey("deposit").containsEntry("urgentLabel", "是");
        // 无读权限字段也不能作为条件
        rejected(userA, filters(new Filter("deposit", "gt", 1)), "deposit");
        assertThat(f.crud.list(order, QuerySpec.all(), admin).rows().get(0)).containsKey("deposit");
    }

    @Test
    void 分组聚合只接受白名单字段函数与别名() {
        QuerySpec agg = new QuerySpec(List.of(), List.of(), 1, 50, List.of(), List.of("purpose"),
            List.of(new QuerySpec.Aggregate("count", "*", "cnt"), new QuerySpec.Aggregate("sum", "deposit", "totalDeposit")));
        List<Map<String, Object>> rows = f.crud.aggregate(order, agg, admin);
        assertThat(rows).hasSize(2).anySatisfy(r -> {
            assertThat(r.get("purpose")).isEqualTo("MAINTENANCE");
            assertThat(((Number) r.get("cnt")).intValue()).isEqualTo(2);
        });
        assertThatThrownBy(() -> f.crud.aggregate(order, new QuerySpec(List.of(), List.of(), 1, 50, List.of(), List.of(),
            List.of(new QuerySpec.Aggregate("sum", "borrower", "x"))), admin)).extracting("code").isEqualTo("QUERY_REJECTED");
        assertThatThrownBy(() -> f.crud.aggregate(order, new QuerySpec(List.of(), List.of(), 1, 50, List.of(), List.of(),
            List.of(new QuerySpec.Aggregate("count", "*", "x\" from pg_user --"))), admin)).extracting("code").isEqualTo("QUERY_REJECTED");
        assertThatThrownBy(() -> f.crud.aggregate(order, new QuerySpec(List.of(), List.of(), 1, 50, List.of(), List.of(),
            List.of(new QuerySpec.Aggregate("pg_sleep", "deposit", "x"))), admin)).extracting("code").isEqualTo("QUERY_REJECTED");
        assertThatThrownBy(() -> f.crud.aggregate(order, new QuerySpec(List.of(), List.of(), 1, 50, List.of(), List.of("remark"),
            List.of(new QuerySpec.Aggregate("count", "*", "c"))), admin)).extracting("code").isEqualTo("QUERY_REJECTED");
    }
}
