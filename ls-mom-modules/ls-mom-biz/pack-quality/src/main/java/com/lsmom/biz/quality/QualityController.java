package com.lsmom.biz.quality;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.core.error.LsBizException;
import com.lsmom.meta.concept.ManagedRecords;
import org.dromara.common.core.domain.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 质量中心的只读查询：可关联对象候选、不符合项的整改工作（含证据数）。写操作一律走 Operation 与通用台账接口。 */
@RestController
@RequestMapping("/api/quality")
public class QualityController {

    private final ActorResolver actors;
    private final JdbcTemplate jdbc;
    private final ManagedRecords records;

    public QualityController(ActorResolver actors, JdbcTemplate jdbc, ManagedRecords records) {
        this.actors = actors;
        this.jdbc = jdbc;
        this.records = records;
    }

    /** 可关联的对象候选（工作按编码或标题、设备按编码或名称），限调用者数据域。 */
    @GetMapping("/objects")
    public R<List<Map<String, Object>>> objects(@org.springframework.web.bind.annotation.RequestParam String type,
                                                @org.springframework.web.bind.annotation.RequestParam(required = false) String keyword) {
        ActorContext actor = actors.current();
        String kw = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        java.util.List<Object> args = new java.util.ArrayList<>();
        StringBuilder sql;
        if ("WORK".equals(type)) {
            sql = new StringBuilder("select w.id, w.code || ' ' || w.title as label from core_work_item w where (w.code ilike ? or w.title ilike ?)");
            args.add(kw); args.add(kw);
            com.lsmom.core.work.WorkItemService.appendScope(sql, args, actor.dataScope());
            sql.append(" order by w.id desc limit 20");
        } else if ("ASSET".equals(type)) {
            String a = records.table("ls.asset.Asset");
            String code = records.column("ls.asset.Asset", "assetCode");
            String name = records.column("ls.asset.Asset", "name");
            sql = new StringBuilder("select a.id, a." + code + " || ' ' || a." + name + " as label from " + a + " a where a.del_flag = '0' and (a." + code
                + " ilike ? or a." + name + " ilike ?)");
            args.add(kw); args.add(kw);
            if (!actor.dataScope().all()) {
                if (actor.dataScope().orgUnitIds().isEmpty()) return R.ok(List.of());
                sql.append(" and a.org_id in (").append(String.join(",", actor.dataScope().orgUnitIds().stream().map(x -> "?").toList())).append(")");
                args.addAll(actor.dataScope().orgUnitIds());
            }
            sql.append(" order by a.id desc limit 20");
        } else {
            return R.ok(List.of());
        }
        return R.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    @GetMapping("/nc/{id}/rectifications")
    public R<List<Map<String, Object>>> rectifications(@PathVariable long id) {
        ActorContext actor = actors.current();
        if (!actor.hasPermission("crud:" + QualityPack.NC + ":query")) throw new LsBizException("FORBIDDEN", "没有查看不符合项的权限");
        Map<String, Object> nc = records.find(QualityPack.NC, id).orElseThrow(() -> LsBizException.notFound("不符合项", id));
        Long org = nc.get("orgId") == null ? null : ((Number) nc.get("orgId")).longValue();
        Long me = actor.effectiveParty();
        boolean owner = me != null && me.equals(nc.get("rectifyOwner") == null ? null : ((Number) nc.get("rectifyOwner")).longValue());
        if (!actor.dataScope().all() && !actor.dataScope().allowsOrg(org) && !owner) throw LsBizException.notFound("不符合项", id);
        if (nc.get("issue") == null) return R.ok(List.of());
        return R.ok(jdbc.queryForList("""
            select w.id, w.code, w.title, w.status, w.version,
                   (select string_agg(p.name, '、') from core_work_assignment a join core_party p on p.id = a.resource_ref::bigint
                     where a.work_item_id = w.id and a.status = 'ACTIVE' and a.resource_type = 'PARTY') as assignees,
                   (select count(*) from core_document_link d where d.removed_at is null and d.purpose = 'EVIDENCE'
                     and d.object_type in ('ls.core.Work', 'WORK') and d.object_id = w.id::text) as evidence
              from core_issue_link l join core_work_item w on w.id = l.object_id::bigint
             where l.issue_id = ? and l.link_role = 'RECTIFICATION' and l.object_type = 'WORK' order by w.id""", ((Number) nc.get("issue")).longValue()));
    }
}
