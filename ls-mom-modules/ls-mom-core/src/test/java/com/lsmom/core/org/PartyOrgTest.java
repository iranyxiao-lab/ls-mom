package com.lsmom.core.org;

import com.lsmom.core.error.LsBizException;
import com.lsmom.core.party.Party;
import com.lsmom.core.party.PartyService;
import com.lsmom.core.party.PartyType;
import com.lsmom.core.work.AssignmentService;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkItemService;
import com.lsmom.core.work.WorkType;
import com.lsmom.test.CoreFixture;
import com.lsmom.test.PgTestSupport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** spec core/party-org 全部场景。 */
class PartyOrgTest {

    private final CoreFixture f = new CoreFixture();

    @Test
    void 用户创建时生成Party() {
        CoreFixture.Plant p = f.plant();
        long userId = PgTestSupport.insertUser(PgTestSupport.uniq("emp"), "新员工", p.teamA().deptId());

        Party party = f.parties.findByUserId(userId).orElseThrow();
        assertThat(party.type()).isEqualTo(PartyType.EMPLOYEE);
        assertThat(party.name()).isEqualTo("新员工");
        assertThat(party.canLogin()).isTrue();
        assertThat(f.parties.primaryOrgUnitId(party.id())).contains(p.teamA().id());

        // 调岗：主组织随部门变化
        f.jdbc.update("update sys_user set dept_id = ? where user_id = ?", p.teamB().deptId(), userId);
        assertThat(f.parties.primaryOrgUnitId(party.id())).contains(p.teamB().id());
        // 删除账号：主体保留但停用
        f.jdbc.update("update sys_user set del_flag = '2' where user_id = ?", userId);
        assertThat(f.parties.get(party.id()).canLogin()).isFalse();
    }

    @Test
    void 外部协作方无登录但可被分派引用() {
        CoreFixture.Plant p = f.plant();
        Party ext = f.parties.register(new PartyService.RegisterParty(PartyType.EXTERNAL, "李工", "13800000000", "某维修公司", null));
        assertThat(ext.userId()).isNull();
        assertThat(ext.canLogin()).isFalse();
        assertThat(ext.code()).startsWith("X");

        WorkItem w = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.MAINTENANCE, "外委维修", "", p.workshop().id()));
        f.assignments.assign(w.id(), java.util.List.of(AssignmentService.AssignTarget.party(ext.id(), null, null)), null);
        assertThat(f.assignments.listActive(w.id())).extracting(a -> a.resourceRef()).containsExactly(String.valueOf(ext.id()));

        // 数据库层也保证外部主体不能关联账号
        assertThatThrownBy(() -> f.jdbc.update("update core_party set user_id = 1 where id = ?", ext.id()))
            .hasMessageContaining("core_party_check");
        assertThatThrownBy(() -> f.parties.register(new PartyService.RegisterParty(PartyType.EMPLOYEE, "张三", null, null, null)))
            .isInstanceOf(LsBizException.class);
    }

    @Test
    void 每个组织单元映射到系统部门() {
        CoreFixture.Plant p = f.plant();
        String ancestors = f.jdbc.queryForObject("select ancestors from sys_dept where dept_id = ?", String.class, p.teamA().deptId());
        assertThat(ancestors).endsWith("," + p.company().deptId() + "," + p.workshop().deptId());
        assertThat(p.teamA().type()).isEqualTo(OrgUnitType.TEAM);
        assertThat(p.teamA().parentId()).isEqualTo(p.workshop().id());

        // RuoYi 界面新增部门（dept_category 为空）同样生成组织单元
        f.jdbc.update("insert into sys_dept (dept_id, parent_id, ancestors, dept_name, del_flag) values (?, ?, ?, '界面新建', '0')",
            777_000_000L + System.nanoTime() % 1000000, p.workshop().deptId(), ancestors);
        assertThat(f.jdbc.queryForObject("select count(*) from core_org_unit where name = '界面新建' and unit_type = 'DEPARTMENT' and parent_id = ?",
            Integer.class, p.workshop().id())).isPositive();
    }

    @Test
    void 班组撤销后不能用于新工作但历史可读() {
        CoreFixture.Plant p = f.plant();
        WorkItem old = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, "撤销前的工作", "", p.teamB().id()));

        f.orgUnits.expire(p.teamB().id(), LocalDate.now().minusDays(1));

        assertThat(f.orgUnits.listSelectable()).extracting(OrgUnit::id).doesNotContain(p.teamB().id()).contains(p.teamA().id());
        assertThatThrownBy(() -> f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, "新工作", "", p.teamB().id())))
            .isInstanceOf(LsBizException.class).extracting("code").isEqualTo("ORG_INACTIVE");
        assertThat(f.works.get(old.id()).orgUnitName()).isEqualTo(p.teamB().name());
    }

    @Test
    void 失效日期当天仍有效() {
        CoreFixture.Plant p = f.plant();
        f.orgUnits.expire(p.teamA().id(), LocalDate.of(2030, 1, 31));
        CoreFixture onLastDay = new CoreFixture(Clock.fixed(LocalDate.of(2030, 1, 31).atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
        CoreFixture nextDay = new CoreFixture(Clock.fixed(LocalDate.of(2030, 2, 1).atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
        assertThat(onLastDay.orgUnits.requireActive(p.teamA().id(), LocalDate.of(2030, 1, 31))).isNotNull();
        assertThatThrownBy(() -> nextDay.works.create(WorkItemService.CreateWorkItem.of(WorkType.OTHER, "x", "", p.teamA().id())))
            .extracting("code").isEqualTo("ORG_INACTIVE");
    }

    @Test
    void 班组数据域只返回所属班组的工作() {
        CoreFixture.Plant p = f.plant();
        Party member = f.employee("甲班组员", p.teamA());
        WorkItem a = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, "甲班工作", "", p.teamA().id()));
        WorkItem b = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, "乙班工作", "", p.teamB().id()));
        WorkItem w = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, "车间工作", "", p.workshop().id()));

        DataScope team = f.dataScopes.resolve(member.id(), DataScopeLevel.TEAM);
        assertThat(f.works.list(WorkItemService.WorkQuery.all(), team)).extracting(WorkItem::id).containsExactly(a.id());

        DataScope workshop = f.dataScopes.resolve(member.id(), DataScopeLevel.WORKSHOP);
        assertThat(f.works.list(WorkItemService.WorkQuery.all(), workshop)).extracting(WorkItem::id)
            .containsExactlyInAnyOrder(a.id(), b.id(), w.id());

        // 仅本人：自己创建或分派给自己的
        DataScope self = f.dataScopes.resolve(member.id(), DataScopeLevel.SELF);
        assertThat(f.works.list(WorkItemService.WorkQuery.all(), self)).isEmpty();
        f.assignments.assign(b.id(), java.util.List.of(AssignmentService.AssignTarget.party(member.id(), null, null)), null);
        assertThat(f.works.list(WorkItemService.WorkQuery.all(), self)).extracting(WorkItem::id).containsExactly(b.id());
    }
}
