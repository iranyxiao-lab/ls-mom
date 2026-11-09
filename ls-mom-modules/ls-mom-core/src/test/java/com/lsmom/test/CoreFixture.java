package com.lsmom.test;

import com.lsmom.core.org.DataScopeService;
import com.lsmom.core.org.LeaderResolver;
import com.lsmom.core.org.OrgUnit;
import com.lsmom.core.org.OrgUnitService;
import com.lsmom.core.org.OrgUnitType;
import com.lsmom.core.party.Party;
import com.lsmom.core.party.PartyService;
import com.lsmom.core.work.AssignmentService;
import com.lsmom.core.work.ConstraintProfileService;
import com.lsmom.core.work.WorkItemService;
import com.lsmom.core.work.WorkRelationService;
import com.lsmom.core.work.WorkReportService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/** 手工装配核心服务（不启动 Spring），并提供常用的组织/人员搭建方法。 */
public class CoreFixture {

    public final JdbcTemplate jdbc = PgTestSupport.jdbc();
    public final Clock clock;
    public final OrgUnitService orgUnits;
    public final PartyService parties;
    public final LeaderResolver leaders;
    public final DataScopeService dataScopes;
    public final ConstraintProfileService profiles;
    public final WorkItemService works;
    public final AssignmentService assignments;
    public final WorkReportService reports;
    public final WorkRelationService relations;

    public CoreFixture() {
        this(Clock.systemDefaultZone());
    }

    public CoreFixture(Clock clock) {
        this.clock = clock;
        orgUnits = new OrgUnitService(jdbc, clock);
        parties = new PartyService(jdbc);
        leaders = new LeaderResolver(orgUnits, jdbc);
        dataScopes = new DataScopeService(parties, orgUnits);
        profiles = new ConstraintProfileService(jdbc, orgUnits);
        works = new WorkItemService(jdbc, orgUnits, profiles, clock);
        assignments = new AssignmentService(jdbc, works, profiles);
        reports = new WorkReportService(jdbc, works, profiles, clock);
        relations = new WorkRelationService(jdbc, works);
    }

    /** 公司 → 车间 → 两个班组。 */
    public record Plant(OrgUnit company, OrgUnit workshop, OrgUnit teamA, OrgUnit teamB) {
    }

    public Plant plant() {
        OrgUnit company = org(PgTestSupport.uniq("公司"), OrgUnitType.COMPANY, null);
        OrgUnit workshop = org(PgTestSupport.uniq("车间"), OrgUnitType.WORKSHOP, company.id());
        return new Plant(company, workshop, org(PgTestSupport.uniq("甲班"), OrgUnitType.TEAM, workshop.id()),
            org(PgTestSupport.uniq("乙班"), OrgUnitType.TEAM, workshop.id()));
    }

    public OrgUnit org(String name, OrgUnitType type, Long parentId) {
        return orgUnits.create(new OrgUnitService.CreateOrgUnit(name, type, parentId, null, null));
    }

    /** 在组织下新建登录账号（与 RuoYi 新增用户相同的写法），返回其主体。 */
    public Party employee(String nick, OrgUnit org) {
        long userId = PgTestSupport.insertUser(PgTestSupport.uniq("u"), nick, org == null ? null : org.deptId());
        return parties.requireByUserId(userId);
    }
}
