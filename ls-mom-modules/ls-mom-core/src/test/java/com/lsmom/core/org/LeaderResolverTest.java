package com.lsmom.core.org;

import com.lsmom.core.party.Party;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** spec core/party-org「负责人解析」。 */
class LeaderResolverTest {

    private final CoreFixture f = new CoreFixture();

    @Test
    void 车间未设置负责人时返回无人可办() {
        CoreFixture.Plant p = f.plant();
        LeaderResolution r = f.leaders.resolve(p.teamA().id(), LeaderKind.WORKSHOP);
        assertThat(r.resolved()).isFalse();
        assertThat(r.code()).isEqualTo("NO_ASSIGNEE");
        assertThat(r.orgUnitId()).isEqualTo(p.workshop().id());
        assertThat(r.reason()).contains(p.workshop().name()).contains("未设置负责人");
    }

    @Test
    void 按车间与上级解析负责人() {
        CoreFixture.Plant p = f.plant();
        Party director = f.employee("车间主任", p.workshop());
        Party teamLeader = f.employee("甲班班长", p.teamA());
        f.orgUnits.setLeader(p.workshop().id(), director.id());
        f.orgUnits.setLeader(p.teamA().id(), teamLeader.id());

        assertThat(f.leaders.resolve(p.teamA().id(), LeaderKind.SELF).partyId()).isEqualTo(teamLeader.id());
        assertThat(f.leaders.resolve(p.teamA().id(), LeaderKind.WORKSHOP).partyId()).isEqualTo(director.id());
        assertThat(f.leaders.resolve(p.teamA().id(), LeaderKind.SUPERIOR).partyId()).isEqualTo(director.id());
        // 公司没有上级
        assertThat(f.leaders.resolve(p.company().id(), LeaderKind.SUPERIOR).resolved()).isFalse();
        // 车间组织下没有部门
        assertThat(f.leaders.resolve(p.teamA().id(), LeaderKind.DEPARTMENT).reason()).contains("不属于任何部门");
    }

    @Test
    void 回退到RuoYi部门负责人() {
        CoreFixture.Plant p = f.plant();
        Party head = f.employee("乙班班长", p.teamB());
        f.jdbc.update("update sys_dept set leader = ? where dept_id = ?", head.userId(), p.teamB().deptId());
        assertThat(f.leaders.resolve(p.teamB().id(), LeaderKind.SELF).partyId()).isEqualTo(head.id());
    }
}
