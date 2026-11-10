package com.lsmom.dcs;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.SummaryContextProvider;
import com.lsmom.core.error.LsBizException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** DCS 报警摘要的上下文：报警、原始事件、报警前后 30 分钟的快照（均值 / 极值，引用到快照桶）。 */
public class DcsAlarmSummaryContext implements SummaryContextProvider {

    private final AlarmService alarms;
    private final SnapshotService snapshots;

    public DcsAlarmSummaryContext(AlarmService alarms, SnapshotService snapshots) {
        this.alarms = alarms;
        this.snapshots = snapshots;
    }

    @Override
    public String kind() {
        return AlarmService.ALARM_OBJECT;
    }

    @Override
    public Context load(String subjectId, ActorContext actor) {
        long id = Long.parseLong(subjectId);
        Map<String, Object> a = alarms.alarm(id);
        Long org = a.get("org_unit_id") == null ? null : ((Number) a.get("org_unit_id")).longValue();
        if (!actor.dataScope().allowsOrg(org)) throw LsBizException.notFound("DCS 报警", id);
        String point = (String) a.get("point_code");
        List<Fact> facts = new ArrayList<>();
        Instant at = HistorianReader.toInstant(a.get("last_occurred_at"));
        facts.add(new Fact(new Ref("ls.dcs.Alarm", subjectId, point + " " + a.get("alarm_type")), "报警 " + point
            + (a.get("point_name") == null ? "" : "（" + a.get("point_name") + "）") + " " + a.get("alarm_type") + "，严重度 " + a.get("severity")
            + "，累计 " + a.get("occurrence_count") + " 次，最新值 " + a.get("last_value") + (a.get("unit") == null ? "" : " " + a.get("unit"))
            + "，状态 " + a.get("status") + (a.get("message") == null ? "" : "；" + a.get("message"))
            + (Boolean.TRUE.equals(a.get("clock_anomaly")) ? "；源时间存在时钟异常" : ""), local(at)));
        if (a.get("issue_code") != null) {
            facts.add(new Fact(new Ref("ls.core.Issue", String.valueOf(a.get("issue_id")), (String) a.get("issue_code")),
                "已关联问题 " + a.get("issue_code") + "「" + a.get("issue_title") + "」状态 " + a.get("issue_status"), null));
        }
        for (Map<String, Object> e : alarms.events(id)) {
            facts.add(new Fact(new Ref("ls.dcs.AlarmEvent", e.get("source_system") + ":" + e.get("source_event_id"), String.valueOf(e.get("source_event_id"))),
                "原始事件 值 " + e.get("value") + "，质量 " + e.get("quality") + (Boolean.TRUE.equals(e.get("clock_anomaly")) ? "，时钟异常" : ""),
                local(HistorianReader.toInstant(e.get("occurred_at")))));
        }
        for (Map<String, Object> s : snapshots.snapshots(point, at.minus(Duration.ofMinutes(30)), at.plus(Duration.ofMinutes(30)))) {
            Instant b = HistorianReader.toInstant(s.get("bucket_start"));
            facts.add(new Fact(new Ref("ls.dcs.Snapshot", point + "@" + b, point + " " + local(b)), "快照 " + s.get("bucket_seconds") + " 秒：均值 "
                + round(s.get("avg_value")) + "，最小 " + round(s.get("min_value")) + "，最大 " + round(s.get("max_value")) + "，样本 " + s.get("sample_count")
                + ("0".equals(String.valueOf(s.get("bad_count"))) ? "" : "，坏质量 " + s.get("bad_count")), local(b)));
        }
        return new Context("ls.dcs.Alarm", subjectId, "报警 " + point + " " + a.get("alarm_type"), org, facts);
    }

    private static String round(Object v) {
        return v == null ? "-" : String.format("%.2f", ((Number) v).doubleValue());
    }

    private static LocalDateTime local(Instant t) {
        return t == null ? null : LocalDateTime.ofInstant(t, ZoneId.systemDefault());
    }
}
