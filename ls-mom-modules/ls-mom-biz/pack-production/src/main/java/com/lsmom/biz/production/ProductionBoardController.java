package com.lsmom.biz.production;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.core.org.DataScope;
import com.lsmom.core.work.WorkItemService;
import org.dromara.common.core.domain.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.lsmom.biz.production.ProductionPack.CONTEXT;
import static com.lsmom.biz.production.ProductionPack.SHIFT_PLAN;

/**
 * 生产运营工作台的只读查询：工作板（按状态 / 班组 / 日期 / 风险）、我的工作（移动填报）、班组列表。
 * 写操作一律走 Operation（/api/op）与通用台账接口；这里按调用者数据域过滤。
 */
@RestController
@RequestMapping("/api/production")
public class ProductionBoardController {

    private final JdbcTemplate jdbc;
    private final ActorResolver actors;
    private final ProductionRecords records;

    public ProductionBoardController(JdbcTemplate jdbc, ActorResolver actors, ProductionRecords records) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.records = records;
    }

    /**
     * 工作板：生产工作及其班次安排、上下文、负责人与风险标记。日期按班次日期筛选（未排班的工作按计划结束日期）；
     * risk=OVERDUE 已逾期、BLOCKED 阻塞、UNSCHEDULED 未排班。
     */
    @GetMapping("/board")
    public R<List<Map<String, Object>>> board(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                              @RequestParam(required = false) Long teamId, @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String risk, @RequestParam(required = false) Long requestId,
                                              @RequestParam(defaultValue = "false") boolean mine) {
        ActorContext actor = actors.current();
        String p = records.table(SHIFT_PLAN);
        String c = records.table(CONTEXT);
        StringBuilder sql = new StringBuilder("""
            select w.id, w.code, w.title, w.status, w.priority, w.org_unit_id, w.planned_start, w.planned_end, w.actual_end, w.progress,
                   w.block_reason, w.version, w.source_id, w.created_at,
                   p.id as plan_id, p.version as plan_version, p.status as plan_status, p.%s as team_id, t.name as team_name,
                   p.%s as shift_date, p.%s as shift,
                   c.%s as planned_qty, c.%s as unit, c.%s as line
              from core_work_item w
              left join %s p on p.%s = w.id and p.del_flag = '0' and p.status = 'ACTIVE'
              left join core_org_unit t on t.id = p.%s
              left join %s c on c.%s = w.id and c.del_flag = '0'
             where w.work_type = 'PRODUCTION'""".formatted(records.column(SHIFT_PLAN, "team"), records.column(SHIFT_PLAN, "shiftDate"),
            records.column(SHIFT_PLAN, "shift"), records.column(CONTEXT, "plannedQty"), records.column(CONTEXT, "unit"),
            records.column(CONTEXT, "line"), p, records.column(SHIFT_PLAN, "workItem"), records.column(SHIFT_PLAN, "team"), c,
            records.column(CONTEXT, "workItem")));
        List<Object> args = new ArrayList<>();
        WorkItemService.appendScope(sql, args, actor.dataScope());
        String day = "coalesce(p." + records.column(SHIFT_PLAN, "shiftDate") + ", cast(w.planned_end as date))";
        if (from != null) { sql.append(" and ").append(day).append(" >= ?"); args.add(from); }
        if (to != null) { sql.append(" and ").append(day).append(" <= ?"); args.add(to); }
        if (teamId != null) { sql.append(" and p.").append(records.column(SHIFT_PLAN, "team")).append(" = ?"); args.add(teamId); }
        if (status != null && !status.isBlank()) { sql.append(" and w.status = ?"); args.add(status); }
        else sql.append(" and w.status <> 'CANCELLED'");
        if (requestId != null) { sql.append(" and w.source_type = ? and w.source_id = ?"); args.add(ProductionPack.REQUEST); args.add(requestId); }
        if (mine && actor.effectiveParty() != null) {
            sql.append(" and exists (select 1 from core_work_assignment a where a.work_item_id = w.id and a.status = 'ACTIVE'")
                .append(" and a.resource_type = 'PARTY' and a.resource_ref = ?) and w.status not in ('DONE', 'CANCELLED')");
            args.add(String.valueOf(actor.effectiveParty()));
        }
        if ("OVERDUE".equals(risk)) sql.append(" and w.planned_end < now() and w.status not in ('DONE', 'CANCELLED')");
        if ("BLOCKED".equals(risk)) sql.append(" and w.status = 'BLOCKED'");
        if ("UNSCHEDULED".equals(risk)) sql.append(" and p.id is null");
        sql.append(" order by ").append(day).append(" nulls last, w.priority, w.id limit 500");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            r.forEach((k, v) -> m.put(com.lsmom.meta.concept.MetaNames.toFieldCode(k), v instanceof java.sql.Timestamp ts ? ts.toLocalDateTime()
                : v instanceof java.sql.Date d ? d.toLocalDate() : v));
            String st = String.valueOf(r.get("status"));
            LocalDateTime end = (LocalDateTime) m.get("plannedEnd");
            List<String> risks = new ArrayList<>();
            if (end != null && end.isBefore(now) && !"DONE".equals(st) && !"CANCELLED".equals(st)) risks.add("OVERDUE");
            if ("BLOCKED".equals(st)) risks.add("BLOCKED");
            if (r.get("plan_id") == null) risks.add("UNSCHEDULED");
            m.put("risks", risks);
            m.put("assignees", jdbc.queryForList("""
                select a.resource_type as type, a.resource_ref as ref,
                       case a.resource_type when 'PARTY' then (select name from core_party where id = cast(a.resource_ref as bigint))
                                            when 'TEAM' then (select name from core_org_unit where id = cast(a.resource_ref as bigint))
                                            else a.resource_ref end as name
                  from core_work_assignment a where a.work_item_id = ? and a.status = 'ACTIVE' order by a.id""", r.get("id")));
            out.add(m);
        }
        return R.ok(out);
    }

    /**
     * 班组（组织类型 TEAM）及所属上级名称。交接班要选其他班组作为接班方，所以返回全部有效班组，
     * 并用 inScope 标出调用者数据域内的（工作板筛选默认只列这些）。
     */
    @GetMapping("/teams")
    public R<List<Map<String, Object>>> teams() {
        DataScope scope = actors.current().dataScope();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
            select t.id, t.name, p.name as parent_name from core_org_unit t left join core_org_unit p on p.id = t.parent_id
             where t.unit_type = 'TEAM' and (t.valid_to is null or t.valid_to >= current_date) order by p.name, t.name""")) {
            Map<String, Object> m = new LinkedHashMap<>();
            r.forEach((k, v) -> m.put(com.lsmom.meta.concept.MetaNames.toFieldCode(k), v));
            m.put("inScope", scope.all() || scope.orgUnitIds().contains(((Number) r.get("id")).longValue()));
            rows.add(m);
        }
        return R.ok(rows);
    }

    /** 当前用户所属班组（交接班默认交班班组）。 */
    @GetMapping("/my-teams")
    public R<List<Map<String, Object>>> myTeams() {
        Long party = actors.current().effectiveParty();
        return R.ok(jdbc.queryForList("""
            select o.id, o.name from core_party_org po join core_org_unit o on o.id = po.org_unit_id
             where po.party_id = ? and o.unit_type = 'TEAM' and (po.valid_to is null or po.valid_to >= current_date) order by o.id""", party));
    }

    /** 某需求转出的工作（需求板展开）。 */
    @GetMapping("/requests/{id}/works")
    public R<List<Map<String, Object>>> requestWorks(@PathVariable long id) {
        return board(null, null, null, null, null, id, false);
    }
}
