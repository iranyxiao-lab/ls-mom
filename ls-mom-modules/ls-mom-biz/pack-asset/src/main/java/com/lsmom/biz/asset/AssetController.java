package com.lsmom.biz.asset;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScope;
import com.lsmom.meta.concept.ManagedRecords;
import org.dromara.common.core.domain.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 设备中心的只读查询：设备时间轴、我的点检。写操作一律走 Operation 与通用台账接口。 */
@RestController
@RequestMapping("/api/asset")
public class AssetController {

    private final ActorResolver actors;
    private final AssetTimeline timeline;
    private final JdbcTemplate jdbc;
    private final ManagedRecords records;

    public AssetController(ActorResolver actors, AssetTimeline timeline, JdbcTemplate jdbc, ManagedRecords records) {
        this.actors = actors;
        this.timeline = timeline;
        this.jdbc = jdbc;
        this.records = records;
    }

    @GetMapping("/assets/{id}/timeline")
    public R<List<Map<String, Object>>> timeline(@PathVariable long id, @RequestParam(defaultValue = "100") int limit) {
        ActorContext actor = actors.current();
        requireVisible(actor, id);
        return R.ok(timeline.of(id, Math.min(Math.max(limit, 1), 500)));
    }

    /** 我的点检：分给我（点检人）的点检记录，默认只看待填。 */
    @GetMapping("/inspections/mine")
    public R<List<Map<String, Object>>> mine(@RequestParam(defaultValue = "DRAFT") String status) {
        ActorContext actor = actors.current();
        if (actor.effectiveParty() == null) return R.ok(List.of());
        String r = records.table(AssetPack.RECORD);
        String a = records.table(AssetPack.ASSET);
        return R.ok(jdbc.queryForList("""
            select r.id, r.version, r.status, r.%s as due_date, r.%s as result, r.%s as abnormal_count, r.%s as defect_count,
                   r.%s as asset_id, a.%s as asset_code, a.%s as asset_name, a.%s as location
              from %s r join %s a on a.id = r.%s
             where r.del_flag = '0' and r.%s = ? and (?::varchar = 'ALL' or r.status = ?::varchar)
             order by r.%s, r.id""".formatted(records.column(AssetPack.RECORD, "dueDate"), records.column(AssetPack.RECORD, "result"),
            records.column(AssetPack.RECORD, "abnormalCount"), records.column(AssetPack.RECORD, "defectCount"),
            records.column(AssetPack.RECORD, "asset"), records.column(AssetPack.ASSET, "assetCode"), records.column(AssetPack.ASSET, "name"),
            records.column(AssetPack.ASSET, "location"), r, a, records.column(AssetPack.RECORD, "asset"),
            records.column(AssetPack.RECORD, "inspector"), records.column(AssetPack.RECORD, "dueDate")),
            actor.effectiveParty(), status, status));
    }

    /** 缺陷的维修工作（Issue 的整改工作）及其负责人。 */
    @GetMapping("/defects/{id}/repairs")
    public R<List<Map<String, Object>>> repairs(@PathVariable long id) {
        ActorContext actor = actors.current();
        if (!actor.hasPermission("crud:" + AssetPack.DEFECT + ":query")) throw new LsBizException("FORBIDDEN", "没有查看缺陷的权限");
        Map<String, Object> d = records.find(AssetPack.DEFECT, id).orElseThrow(() -> LsBizException.notFound("缺陷", id));
        if (d.get("issue") == null) return R.ok(List.of());
        return R.ok(jdbc.queryForList("""
            select w.id, w.code, w.title, w.status, w.version, w.description, w.actual_end,
                   (select string_agg(p.name, '、') from core_work_assignment a join core_party p on p.id = a.resource_ref::bigint
                     where a.work_item_id = w.id and a.status = 'ACTIVE' and a.resource_type = 'PARTY') as assignees
              from core_issue_link l join core_work_item w on w.id = l.object_id::bigint
             where l.issue_id = ? and l.link_role = 'RECTIFICATION' and l.object_type = 'WORK' order by w.id""", ((Number) d.get("issue")).longValue()));
    }

    /** 可选的所属组织（车间、班组、科室），限调用者数据域内。 */
    @GetMapping("/orgs")
    public R<List<Map<String, Object>>> orgs() {
        DataScope scope = actors.current().dataScope();
        StringBuilder sql = new StringBuilder("""
            select o.id, o.name, o.unit_type, p.name as parent_name from core_org_unit o left join core_org_unit p on p.id = o.parent_id
             where (o.valid_to is null or o.valid_to >= current_date) and o.unit_type in ('WORKSHOP', 'TEAM', 'DEPARTMENT', 'SECTION')""");
        List<Object> args = new java.util.ArrayList<>();
        if (!scope.all()) {
            if (scope.orgUnitIds().isEmpty()) return R.ok(List.of());
            sql.append(" and o.id in (").append(String.join(",", scope.orgUnitIds().stream().map(x -> "?").toList())).append(")");
            args.addAll(scope.orgUnitIds());
        }
        sql.append(" order by o.parent_id nulls first, o.id");
        return R.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    private void requireVisible(ActorContext actor, long id) {
        if (!actor.hasPermission("crud:" + AssetPack.ASSET + ":query")) throw new LsBizException("FORBIDDEN", "没有查看设备的权限");
        Map<String, Object> asset = records.find(AssetPack.ASSET, id).orElseThrow(() -> LsBizException.notFound("设备", id));
        DataScope scope = actor.dataScope();
        Long org = asset.get("orgId") == null ? null : ((Number) asset.get("orgId")).longValue();
        if (scope.all() || (org != null && scope.orgUnitIds().contains(org))) return;
        Long me = actor.effectiveParty();
        // 数据域外的设备：负责人、或被派过该设备点检的人也可查看（班组点检外车间的设备）
        if (me != null) {
            if (me.equals(asset.get("owner") == null ? null : ((Number) asset.get("owner")).longValue())) return;
            Integer n = jdbc.queryForObject("select count(*) from " + records.table(AssetPack.RECORD) + " where " + records.column(AssetPack.RECORD, "asset")
                + " = ? and " + records.column(AssetPack.RECORD, "inspector") + " = ? and del_flag = '0'", Integer.class, id, me);
            if (n != null && n > 0) return;
        }
        throw LsBizException.notFound("设备", id);
    }
}
