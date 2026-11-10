package com.lsmom.ui.search;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScope;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkItemService;
import org.dromara.common.core.domain.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令面板直达的对象详情（只读）：工作、人员。台账记录直接打开台账页详情。
 * 数据域与全局搜索一致；不在数据域内的对象按"不存在"处理，不暴露其是否存在。
 */
@RestController
@RequestMapping("/api/ui/objects")
public class ObjectController {

    private final WorkItemService works;
    private final ActorResolver actors;
    private final JdbcTemplate jdbc;

    public ObjectController(WorkItemService works, ActorResolver actors, JdbcTemplate jdbc) {
        this.works = works;
        this.actors = actors;
        this.jdbc = jdbc;
    }

    /** 与命名 Query 数据域占位相同的判断：全部数据域，或组织在数据域内（本人级除外），或本人创建。 */
    static boolean visible(DataScope scope, Long orgUnitId, Long creatorParty) {
        if (scope == null || scope.all()) return true;
        if (scope.level() != DataScopeLevel.SELF && orgUnitId != null && scope.orgUnitIds().contains(orgUnitId)) return true;
        return creatorParty != null && creatorParty.equals(scope.partyId());
    }

    @GetMapping("/work/{id}")
    public R<Map<String, Object>> work(@PathVariable long id) {
        ActorContext actor = actors.current();
        WorkItem w = works.get(id);
        if (!visible(actor.dataScope(), w.orgUnitId(), w.createdByParty())) throw LsBizException.notFound("工作", id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.id());
        m.put("code", w.code());
        m.put("title", w.title());
        m.put("type", w.type());
        m.put("status", w.status());
        m.put("priority", w.priority());
        m.put("orgUnitName", w.orgUnitName());
        m.put("description", w.description());
        m.put("plannedStart", w.plannedStart());
        m.put("plannedEnd", w.plannedEnd());
        m.put("actualStart", w.actualStart());
        m.put("actualEnd", w.actualEnd());
        m.put("progress", w.progress());
        m.put("blockReason", w.blockReason());
        m.put("createdAt", w.createdAt());
        m.put("assignees", jdbc.queryForList("""
            select p.name from core_work_assignment a join core_party p on a.resource_type = 'PARTY' and p.id::text = a.resource_ref
             where a.work_item_id = ? and a.status = 'ACTIVE' and a.role in ('RESPONSIBLE', 'ASSIST') order by a.id""", String.class, id));
        return R.ok(m);
    }

    @GetMapping("/party/{id}")
    public R<Map<String, Object>> party(@PathVariable long id) {
        ActorContext actor = actors.current();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select p.id, p.code, p.name, p.party_type as "partyType", p.org_name as "company"
              from core_party p where p.id = ? and p.status = 'ACTIVE' and p.party_type in ('EMPLOYEE', 'OUTSOURCE')""", id);
        if (rows.isEmpty()) throw LsBizException.notFound("人员", id);
        List<Map<String, Object>> orgs = jdbc.queryForList("""
            select o.id, o.name, po.role, po.is_primary as "primary" from core_party_org po join core_org_unit o on o.id = po.org_unit_id
             where po.party_id = ? and (po.valid_to is null or po.valid_to >= current_date) order by po.is_primary desc, po.id""", id);
        Long primaryOrg = orgs.isEmpty() ? null : ((Number) orgs.get(0).get("id")).longValue();
        if (!visible(actor.dataScope(), primaryOrg, null)) throw LsBizException.notFound("人员", id);
        Map<String, Object> m = new LinkedHashMap<>(rows.get(0));
        m.put("orgs", orgs);
        return R.ok(m);
    }
}
