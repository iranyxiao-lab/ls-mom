package com.lsmom.dcs;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScope;
import org.dromara.common.core.domain.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DCS 只读集成接口：测点语义维护、快照查询、报警处置（关联 / 生成草稿 / 忽略）。
 * 这里没有、也不得有任何向 DCS / PLC 写值、下发参数或解除联锁的接口。
 */
@RestController
@RequestMapping("/api/dcs")
public class DcsController {

    static final String VIEW = "ls:dcs:view";
    static final String MANAGE = "ls:dcs:manage";
    static final String HANDLE = "ls:dcs:handle";

    private final ActorResolver actors;
    private final DcsPointService points;
    private final SnapshotService snapshots;
    private final AlarmService alarms;
    private final JdbcTemplate jdbc;

    public DcsController(ActorResolver actors, DcsPointService points, SnapshotService snapshots, AlarmService alarms, JdbcTemplate jdbc) {
        this.actors = actors;
        this.points = points;
        this.snapshots = snapshots;
        this.alarms = alarms;
        this.jdbc = jdbc;
    }

    @GetMapping("/points")
    public R<List<Map<String, Object>>> points(@RequestParam(required = false) Long assetId, @RequestParam(required = false) String keyword) {
        ActorContext a = require(VIEW);
        return R.ok(points.list(assetId, keyword, orgFilter(a)));
    }

    @PutMapping("/points")
    public R<DcsPointService.Point> save(@RequestBody DcsPointService.Point p) {
        ActorContext a = require(MANAGE);
        if (p.orgUnitId() != null && orgFilter(a) != null && !orgFilter(a).contains(p.orgUnitId())) {
            throw new LsBizException("FORBIDDEN", "测点所属组织不在你的数据范围内");
        }
        return R.ok(points.save(p, a.userId()));
    }

    /** 快照（业务库）：默认最近 6 小时，最长 7 天。 */
    @GetMapping("/points/{code}/snapshots")
    public R<List<Map<String, Object>>> snapshots(@PathVariable String code, @RequestParam(required = false) Instant from,
                                                  @RequestParam(required = false) Instant to) {
        ActorContext a = require(VIEW);
        visiblePoint(a, code);
        Instant t = to == null ? Instant.now() : to;
        Instant f = from == null ? t.minus(Duration.ofHours(6)) : from;
        if (Duration.between(f, t).compareTo(Duration.ofDays(7)) > 0) throw LsBizException.validation(Map.of("from", "时间范围最长 7 天"));
        return R.ok(snapshots.snapshots(code, f, t));
    }

    @GetMapping("/alarms")
    public R<List<Map<String, Object>>> alarms(@RequestParam(required = false) String status, @RequestParam(required = false) Long assetId,
                                               @RequestParam(required = false) String pointCode, @RequestParam(defaultValue = "200") int limit) {
        ActorContext a = require(VIEW);
        StringBuilder sql = new StringBuilder("""
            select a.id, a.dedup_key, a.point_code, p.name as point_name, a.alarm_type, a.severity, a.message, a.first_occurred_at,
                   a.last_occurred_at, a.last_received_at, a.occurrence_count, a.last_value, p.unit, a.clock_anomaly, a.status, a.asset_id,
                   a.org_unit_id, o.name as org_name, a.issue_id, i.code as issue_code, i.status as issue_status, i.version as issue_version, a.suggested_issue_id,
                   si.code as suggested_issue_code, si.title as suggested_issue_title
              from dcs_alarm a left join dcs_point p on p.code = a.point_code left join core_org_unit o on o.id = a.org_unit_id
              left join core_issue i on i.id = a.issue_id left join core_issue si on si.id = a.suggested_issue_id where 1 = 1""");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" and a.status = any(string_to_array(?, ','))");
            args.add(status);
        }
        if (assetId != null) {
            sql.append(" and a.asset_id = ?");
            args.add(assetId);
        }
        if (pointCode != null && !pointCode.isBlank()) {
            sql.append(" and a.point_code = ?");
            args.add(pointCode);
        }
        List<Long> orgs = orgFilter(a);
        if (orgs != null) {
            if (orgs.isEmpty()) return R.ok(List.of());
            sql.append(" and a.org_unit_id in (").append(String.join(",", orgs.stream().map(x -> "?").toList())).append(")");
            args.addAll(orgs);
        }
        sql.append(" order by a.last_occurred_at desc, a.id desc limit ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        return R.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    @GetMapping("/alarms/{id}")
    public R<Map<String, Object>> alarm(@PathVariable long id) {
        ActorContext a = require(VIEW);
        Map<String, Object> m = new LinkedHashMap<>(visibleAlarm(a, id));
        m.put("events", alarms.events(id));
        return R.ok(m);
    }

    /**
     * 报警上下文：只含报警前后指定窗口的快照（业务库降采样数据）与原始事件引用，不含高频原始值。
     * 页面与 LLM 摘要均以此为输入。
     */
    @GetMapping("/alarms/{id}/context")
    public R<Map<String, Object>> context(@PathVariable long id, @RequestParam(defaultValue = "30") int beforeMinutes,
                                          @RequestParam(defaultValue = "30") int afterMinutes) {
        ActorContext a = require(VIEW);
        Map<String, Object> alarm = visibleAlarm(a, id);
        Instant at = HistorianReader.toInstant(alarm.get("last_occurred_at"));
        Instant from = at.minus(Duration.ofMinutes(Math.min(Math.max(beforeMinutes, 1), 720)));
        Instant to = at.plus(Duration.ofMinutes(Math.min(Math.max(afterMinutes, 0), 720)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alarm", alarm);
        m.put("window", Map.of("from", from, "to", to, "bucketSeconds", snapshotSeconds()));
        m.put("snapshots", snapshots.snapshots((String) alarm.get("point_code"), from, to));
        m.put("events", alarms.events(id));
        m.put("rawRef", Map.of("sourceSystem", alarm.get("source_system"), "pointCode", alarm.get("point_code"), "from", from, "to", to,
            "note", "原始高频值保留在 Historian，按此引用查询"));
        return R.ok(m);
    }

    public record LinkBody(Long issueId) {
    }

    @PostMapping("/alarms/{id}/link")
    public R<Map<String, Object>> link(@PathVariable long id, @RequestBody LinkBody body) {
        ActorContext a = require(HANDLE);
        Map<String, Object> alarm = visibleAlarm(a, id);
        Long issueId = body == null || body.issueId() == null ? (Long) alarm.get("suggested_issue_id") : body.issueId();
        if (issueId == null) throw LsBizException.validation(Map.of("issueId", "请选择要关联的问题"));
        return R.ok(alarms.link(id, issueId));
    }

    @PostMapping("/alarms/{id}/draft")
    public R<Map<String, Object>> draft(@PathVariable long id) {
        ActorContext a = require(HANDLE);
        visibleAlarm(a, id);
        return R.ok(alarms.draft(id, a.effectiveParty()));
    }

    @PostMapping("/alarms/{id}/ignore")
    public R<Map<String, Object>> ignore(@PathVariable long id) {
        ActorContext a = require(HANDLE);
        visibleAlarm(a, id);
        return R.ok(alarms.ignore(id));
    }

    private int snapshotSeconds() {
        Integer n = jdbc.queryForObject("select max(bucket_seconds) from dcs_telemetry_snapshot", Integer.class);
        return n == null ? 60 : n;
    }

    private ActorContext require(String perm) {
        ActorContext a = actors.current();
        if (!a.hasPermission(perm)) throw new LsBizException("FORBIDDEN", "没有 DCS 相关权限：" + perm);
        return a;
    }

    /** 数据域：null 表示不限。 */
    private static List<Long> orgFilter(ActorContext a) {
        DataScope s = a.dataScope();
        return s == null || s.all() ? null : List.copyOf(s.orgUnitIds());
    }

    private void visiblePoint(ActorContext a, String code) {
        DcsPointService.Point p = points.find(code).orElseThrow(() -> LsBizException.notFound("测点", code));
        List<Long> orgs = orgFilter(a);
        if (orgs != null && (p.orgUnitId() == null || !orgs.contains(p.orgUnitId()))) throw LsBizException.notFound("测点", code);
    }

    private Map<String, Object> visibleAlarm(ActorContext a, long id) {
        Map<String, Object> m = alarms.alarm(id);
        List<Long> orgs = orgFilter(a);
        Object org = m.get("org_unit_id");
        if (orgs != null && (org == null || !orgs.contains(((Number) org).longValue()))) throw LsBizException.notFound("DCS 报警", id);
        return m;
    }
}
