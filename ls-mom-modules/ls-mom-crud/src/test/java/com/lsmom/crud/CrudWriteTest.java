package com.lsmom.crud;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.state.StateMachineDefinition;
import com.lsmom.action.timeline.TimelineService;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 7.3 / 7.4 / 7.6：主子表单事务保存、详情、复制、逻辑删除、正式动作分流、服务端权限与校验、防重复提交、时间轴、Agent 拒绝。 */
class CrudWriteTest {

    private final CrudFixture f = new CrudFixture();
    private final ActorContext admin = ActionFixture.admin();
    private CrudFixture.Loan loan;
    private String order;
    private CoreFixture.Plant plant;
    private String line;

    @BeforeEach
    void setUp() {
        loan = f.loanLedger();
        order = loan.order().code();
        plant = f.action.core.plant();
        line = "loanLine";
    }

    private JsonNode body(String no, long org, String lines) {
        return json("{\"loanNo\":\"" + no + "\",\"borrower\":\"张三\",\"loanDate\":\"2026-11-01\",\"orgId\":" + org
            + ",\"purpose\":\"MAINTENANCE\",\"wechat\":\"zs-01\",\"" + line + "\":" + lines + "}");
    }

    private long create(String no) {
        return ((Number) f.crud.create(order, body(no, plant.teamA().id(), "[{\"tool\":\"扳手\",\"qty\":2},{\"tool\":\"万用表\",\"qty\":1}]"),
            admin, null).get("id")).longValue();
    }

    private int version(long id) {
        return ((Number) f.crud.get(order, id, admin).get("version")).intValue();
    }

    @Test
    void 子表第三行缺少必填工器具时整单失败并定位到行() {
        int before = f.action.count("select count(*) from \"" + loan.order().tableName() + "\"");
        assertThatThrownBy(() -> f.crud.create(order, body("L-ERR", plant.teamA().id(),
            "[{\"tool\":\"扳手\",\"qty\":2},{\"tool\":\"万用表\",\"qty\":1},{\"qty\":3}]"), admin, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED");
                assertThat(e.fieldErrors()).containsEntry(line + "[2].tool", "第 3 行：工器具必填").hasSize(1);
            });
        assertThat(f.action.count("select count(*) from \"" + loan.order().tableName() + "\"")).isEqualTo(before);
        // 多处错误一次返回：主表与子表
        assertThatThrownBy(() -> f.crud.create(order, json("{\"borrower\":\"" + "长".repeat(300) + "\",\"loanDate\":\"11/01\",\"orgId\":"
            + plant.teamA().id() + ",\"purpose\":\"BAD\",\"" + line + "\":[{\"tool\":\"x\",\"qty\":0}]}"), admin, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.fieldErrors()).containsKeys("loanNo", "borrower", "loanDate", "purpose");
                assertThat(e.fieldErrors()).doesNotContainKey(line + "[0].qty"); // 主表失败时先返回主表错误
            });
        assertThatThrownBy(() -> f.crud.create(order, body("L-Q", plant.teamA().id(), "[{\"tool\":\"x\",\"qty\":0}]"), admin, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.fieldErrors()).containsEntry(line + "[0].qty", "第 1 行：数量不能小于 1"));
    }

    @Test
    void 新增详情编辑复制删除与字段级时间轴() {
        long id = create("L-100");
        Map<String, Object> d = f.crud.get(order, id, admin);
        assertThat(d).containsEntry("loanNo", "L-100").containsEntry("status", "DRAFT").containsEntry("wechat", "zs-01")
            .containsEntry("loanDate", "2026-11-01");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) d.get(line);
        assertThat(lines).extracting(l -> l.get("tool")).containsExactly("扳手", "万用表");
        assertThat(lines).extracting(l -> l.get("lineNo")).containsExactly(1L, 2L);

        f.crud.update(order, id, 0, json("{\"borrower\":\"李四\",\"remark\":\"夜班借用\",\"wechat\":\"ls-02\",\"" + line
            + "\":[{\"tool\":\"扳手\",\"qty\":3}]}"), admin, null);
        Map<String, Object> d2 = f.crud.get(order, id, admin);
        assertThat(d2).containsEntry("borrower", "李四").containsEntry("version", 1L).containsEntry("wechat", "ls-02");
        assertThat((List<?>) d2.get(line)).hasSize(1);
        // 乐观锁
        assertThatThrownBy(() -> f.crud.update(order, id, 0, json("{\"remark\":\"x\"}"), admin, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("VERSION_CONFLICT"));
        // 字段级变更时间轴
        List<TimelineService.Entry> tl = new TimelineService(f.action.core.jdbc).of(order, String.valueOf(id));
        assertThat(tl).extracting(TimelineService.Entry::type).containsExactly("crud.created", "crud.updated");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) tl.get(1).payload().get("changes");
        assertThat(changes).anySatisfy(c -> assertThat(c).containsEntry("field", "borrower").containsEntry("from", "张三").containsEntry("to", "李四"));
        assertThat(changes).anySatisfy(c -> assertThat(c).containsEntry("field", line).containsEntry("from", "2 行").containsEntry("to", "1 行"));

        // 复制：新草稿、唯一字段清空、子表行一并复制
        long copy = ((Number) f.crud.copy(order, id, admin, null).get("id")).longValue();
        Map<String, Object> c = f.crud.get(order, copy, admin);
        assertThat(c).containsEntry("borrower", "李四").containsEntry("status", "DRAFT").containsEntry("version", 0L);
        assertThat(c.get("loanNo")).isNull();
        assertThat((List<?>) c.get(line)).hasSize(1);

        // 逻辑删除
        f.crud.delete(order, copy, 0, admin);
        assertThatThrownBy(() -> f.crud.get(order, copy, admin)).extracting("code").isEqualTo("NOT_FOUND");
        assertThat(f.action.count("select count(*) from \"" + loan.order().tableName() + "\" where id = ? and del_flag = '2'", copy)).isEqualTo(1);
        // 唯一约束冲突按字段返回
        assertThatThrownBy(() -> create("L-100")).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.fieldErrors()).containsEntry("loanNo", "借用单号已存在"));
    }

    @Test
    void 已提交记录禁止通用编辑_正式动作只能经Operation() {
        f.action.machines.publish(new StateMachineDefinition(order + ".lifecycle", order, "借还单流程", List.of("DRAFT", "SUBMITTED", "RETURNED"),
            "DRAFT", List.of(
            new StateMachineDefinition.Transition(order + ".submit", "提交", List.of("DRAFT"), "SUBMITTED", List.of(), null, "C1",
                json("{\"type\":\"object\"}"), "DRAFT"),
            new StateMachineDefinition.Transition(order + ".giveBack", "归还", List.of("SUBMITTED"), "RETURNED", List.of(), null, "C1",
                json("{\"type\":\"object\"}"), "DRAFT"))));
        long id = create("L-200");
        var r = f.action.on(order + ".submit", admin, String.valueOf(id), 0, "{}");
        assertThat(r.status()).isEqualTo("SUBMITTED");
        assertThat(f.crud.get(order, id, admin)).containsEntry("status", "SUBMITTED")
            .satisfies(d -> assertThat((List<?>) d.get("_allowedOperations")).hasSize(2));

        assertThatThrownBy(() -> f.crud.update(order, id, 1, json("{\"qtyNote\":\"改数量\"}"), admin, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("GENERIC_EDIT_FORBIDDEN");
                assertThat(e.getMessage()).contains(order + ".submit");
            });
        assertThatThrownBy(() -> f.crud.delete(order, id, 1, admin)).extracting("code").isEqualTo("GENERIC_EDIT_FORBIDDEN");
        // 草稿态也不能经通用接口改状态
        long draft = create("L-201");
        assertThatThrownBy(() -> f.crud.update(order, draft, 0, json("{\"status\":\"RETURNED\"}"), admin, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.fieldErrors()).containsKey("status"));
        assertThat(f.crud.get(order, draft, admin)).containsEntry("status", "DRAFT");
    }

    @Test
    void 绕过前端直接调用时服务端权限与校验依然生效() {
        long id = create("L-300");
        Party b = f.action.core.employee("b-user", plant.teamB());
        Set<String> perms = Set.of("list", "query", "add", "edit", "remove");
        ActorContext userB = f.action.human(b, DataScopeLevel.ORG, perms.stream().map(p -> GenericCrudService.permission(order, p)).toArray(String[]::new));
        // 越域读取、编辑、删除
        assertThatThrownBy(() -> f.crud.get(order, id, userB))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("ACCESS_DENIED"));
        assertThatThrownBy(() -> f.crud.update(order, id, 0, json("{\"remark\":\"x\"}"), userB, null)).extracting("code").isEqualTo("ACCESS_DENIED");
        assertThatThrownBy(() -> f.crud.delete(order, id, 0, userB)).extracting("code").isEqualTo("ACCESS_DENIED");
        // 不能把记录建到数据域外的组织
        assertThatThrownBy(() -> f.crud.create(order, body("L-301", plant.teamA().id(), "[]"), userB, null)).extracting("code").isEqualTo("ACCESS_DENIED");
        // 缺少功能权限
        ActorContext readOnly = f.action.human(b, DataScopeLevel.ORG, GenericCrudService.permission(order, "list"));
        assertThatThrownBy(() -> f.crud.create(order, body("L-302", plant.teamB().id(), "[]"), readOnly, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getDetails()).containsEntry("permission", GenericCrudService.permission(order, "add")));
        // 字段写权限：没有审核权限不能写审核意见
        long mine = ((Number) f.crud.create(order, body("L-303", plant.teamB().id(), "[]"), userB, null).get("id")).longValue();
        assertThatThrownBy(() -> f.crud.update(order, mine, 0, json("{\"auditNote\":\"同意\"}"), userB, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.fieldErrors()).containsKey("auditNote"));
        // 字段读权限：无权限时详情不返回押金，也不能写入
        assertThat(f.crud.get(order, mine, userB)).doesNotContainKey("deposit");
        assertThatThrownBy(() -> f.crud.update(order, mine, 0, json("{\"deposit\":10}"), userB, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.fieldErrors()).containsKey("deposit"));
        // Schema 校验：未知字段、类型、引用越权
        assertThatThrownBy(() -> f.crud.update(order, mine, 0, json("{\"hack\":1,\"deposit\":\"abc\"}"), admin, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.fieldErrors()).containsKeys("hack", "deposit"));
        long toolA = ((Number) f.crud.create(loan.tool().code(), json("{\"name\":\"A 组扳手\",\"orgId\":" + plant.teamA().id() + "}"), admin, null)
            .get("id")).longValue();
        assertThatThrownBy(() -> f.crud.update(order, mine, 0, json("{\"toolRef\":" + toolA + "}"), userB, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.fieldErrors()).containsEntry("toolRef", "你无权使用所选工器具，请重新选择"));
        // 子表只能随主表访问
        assertThatThrownBy(() -> f.crud.get(loan.line().code(), 1, admin)).extracting("code").isEqualTo("CONCEPT_NOT_LEDGER");
    }

    @Test
    void 双击保存只写一次() {
        JsonNode b = body("L-400", plant.teamA().id(), "[{\"tool\":\"扳手\",\"qty\":1}]");
        Map<String, Object> first = f.crud.create(order, b, admin, "form-abc-1");
        Map<String, Object> second = f.crud.create(order, b, admin, "form-abc-1");
        assertThat(second).containsEntry("id", first.get("id")).containsEntry("replayed", true);
        assertThat(f.action.count("select count(*) from \"" + loan.order().tableName() + "\" where loan_no = 'L-400'")).isEqualTo(1);
        assertThat(f.action.count("select count(*) from \"" + loan.line().tableName() + "\" where parent_id = ?", ((Number) first.get("id")).longValue()))
            .isEqualTo(1);
        long id = ((Number) first.get("id")).longValue();
        f.crud.update(order, id, 0, json("{\"remark\":\"一次\"}"), admin, "form-abc-2");
        Map<String, Object> again = f.crud.update(order, id, 0, json("{\"remark\":\"一次\"}"), admin, "form-abc-2");
        assertThat(again).containsEntry("replayed", true);
        assertThat(version(id)).isEqualTo(1);
    }

    @Test
    void Agent身份调用通用接口被拒绝并审计() {
        Party bot = f.action.core.employee("agent", plant.teamA());
        ActorContext agent = new ActorContext(ActorContext.ActorType.AGENT, bot.userId(), bot.id(), null, true, Set.of(),
            com.lsmom.core.org.DataScope.unrestricted(), "巡检助手");
        assertThatThrownBy(() -> f.crud.create(order, body("L-500", plant.teamA().id(), "[]"), agent, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("ACCESS_DENIED");
                assertThat(e.getDetails()).containsEntry("reason", "AGENT_FORBIDDEN");
            });
        assertThatThrownBy(() -> f.crud.list(order, com.lsmom.crud.query.QuerySpec.all(), agent)).extracting("code").isEqualTo("ACCESS_DENIED");
        assertThat(f.action.count("select count(*) from op_audit where op_code = ? and result = 'FAILED' and error_code = 'AGENT_FORBIDDEN'",
            GenericCrudService.permission(order, "add"))).isEqualTo(1);
        assertThat(f.action.count("select count(*) from \"" + loan.order().tableName() + "\" where loan_no = 'L-500'")).isZero();
    }
}
