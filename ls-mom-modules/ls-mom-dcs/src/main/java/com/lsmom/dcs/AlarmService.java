package com.lsmom.dcs;

import com.lsmom.core.error.LsBizException;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueService;
import com.lsmom.core.issue.IssueStatus;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.ManagedRecords;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 报警接入：原始事件按 (source_system, source_event_id) 只记一次；同一 dedup_key 只有一条业务报警，累加次数、取最大发生时间。
 * 坏质量位事件只记录不判定；源时间比接收时间超前超过阈值的标记时钟异常，保留原始时间与接收时间。
 * 新报警按测点处置策略：自动草稿（Issue 草稿，经人工确认才成为正式问题）/ 仅提醒 / 忽略；
 * 同一设备在时间窗口内已有人工登记的未结问题时，先建议关联而不新建。
 */
public class AlarmService {

    static final String CURSOR = "alarm";
    public static final String ALARM_OBJECT = "DCS_ALARM";
    static final String ASSET_CONCEPT = "ls.asset.Asset";
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    public record IngestResult(int received, int duplicates, int badQuality, int clockAnomalies, int alarmsCreated) {
    }

    private final JdbcTemplate jdbc;
    private final IssueService issues;
    private final DcsPointService points;
    private final ConceptRegistry concepts;
    private final ManagedRecords records;
    private final TransactionTemplate tx;
    private final DcsProperties props;

    public AlarmService(JdbcTemplate jdbc, IssueService issues, DcsPointService points, ConceptRegistry concepts, ManagedRecords records,
                        PlatformTransactionManager txm, DcsProperties props) {
        this.jdbc = jdbc;
        this.issues = issues;
        this.points = points;
        this.concepts = concepts;
        this.records = records;
        this.tx = new TransactionTemplate(txm);
        this.props = props;
    }

    /** 从 Historian 增量读取一轮报警。 */
    public IngestResult pull(HistorianReader historian) {
        Instant upTo = historian.maxAlarmReceived();
        String key = CURSOR + ":" + historian.schema();
        Instant cursor = IngestCursor.get(jdbc, key);
        if (upTo == null || !upTo.isAfter(cursor)) return new IngestResult(0, 0, 0, 0, 0);
        Instant from = cursor.equals(Instant.EPOCH) ? cursor : cursor.minusSeconds(props.overlapSeconds());
        IngestResult total = new IngestResult(0, 0, 0, 0, 0);
        while (true) {
            List<HistorianReader.RawAlarm> batch = historian.alarms(from, upTo, 1000);
            if (batch.isEmpty()) break;
            total = sum(total, ingest(batch));
            Instant last = batch.get(batch.size() - 1).receivedAt();
            if (batch.size() < 1000 || !last.isAfter(from)) break;
            // 同一接收时间的事件可能跨批：回退 1 微秒重读，重复事件按来源编号去重
            from = last.minusNanos(1000);
        }
        IngestCursor.set(jdbc, key, upTo);
        return total;
    }

    public IngestResult ingest(List<HistorianReader.RawAlarm> events) {
        int dup = 0, bad = 0, skew = 0, created = 0;
        for (HistorianReader.RawAlarm e : events) {
            Outcome o = tx.execute(s -> one(e));
            if (o == Outcome.DUPLICATE) dup++;
            if (o == Outcome.BAD) bad++;
            if (o == Outcome.CREATED_SKEW || o == Outcome.COUNTED_SKEW) skew++;
            if (o == Outcome.CREATED || o == Outcome.CREATED_SKEW) created++;
        }
        return new IngestResult(events.size(), dup, bad, skew, created);
    }

    private enum Outcome { DUPLICATE, BAD, CREATED, CREATED_SKEW, COUNTED, COUNTED_SKEW }

    private Outcome one(HistorianReader.RawAlarm e) {
        boolean anomaly = Duration.between(e.receivedAt(), e.occurredAt()).getSeconds() > props.clockSkewSeconds();
        List<Long> ev = jdbc.queryForList("""
            insert into dcs_alarm_event (source_system, source_event_id, point_code, dedup_key, occurred_at, received_at, quality, clock_anomaly,
                                         value, trace_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (source_system, source_event_id) do nothing returning id""", Long.class,
            e.sourceSystem(), e.sourceEventId(), e.pointCode(), e.dedupKey(), Timestamp.from(e.occurredAt()), Timestamp.from(e.receivedAt()),
            e.quality(), anomaly, e.value(), e.traceId());
        if (ev.isEmpty()) return Outcome.DUPLICATE;
        // 坏质量位：记录原始事件，不参与报警判定
        if (!"GOOD".equals(e.quality())) return Outcome.BAD;

        Optional<DcsPointService.Point> point = points.find(e.pointCode());
        Long assetId = point.map(DcsPointService.Point::assetId).orElse(null);
        Long org = point.map(DcsPointService.Point::orgUnitId).orElse(null);
        if (org == null && assetId != null) org = assetField(assetId, "orgId");
        // 时钟异常：源时间不可信，用接收时间参与"最近发生时间"，原始时间保留在事件上
        Instant effective = anomaly ? e.receivedAt() : e.occurredAt();
        Map<String, Object> row = jdbc.queryForMap("""
            insert into dcs_alarm (dedup_key, source_system, point_code, alarm_type, severity, message, first_occurred_at, last_occurred_at,
                                   first_received_at, last_received_at, last_value, clock_anomaly, trace_id, asset_id, org_unit_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (dedup_key) do update set occurrence_count = dcs_alarm.occurrence_count + 1,
              last_value = case when excluded.last_occurred_at >= dcs_alarm.last_occurred_at then excluded.last_value else dcs_alarm.last_value end,
              message = case when excluded.last_occurred_at >= dcs_alarm.last_occurred_at then excluded.message else dcs_alarm.message end,
              last_occurred_at = greatest(dcs_alarm.last_occurred_at, excluded.last_occurred_at),
              first_occurred_at = least(dcs_alarm.first_occurred_at, excluded.first_occurred_at),
              last_received_at = greatest(dcs_alarm.last_received_at, excluded.last_received_at),
              clock_anomaly = dcs_alarm.clock_anomaly or excluded.clock_anomaly, updated_at = now()
            returning id, (xmax = 0) as inserted, status, issue_id""",
            e.dedupKey(), e.sourceSystem(), e.pointCode(), e.alarmType(), e.severity(), e.message(), Timestamp.from(effective),
            Timestamp.from(effective), Timestamp.from(e.receivedAt()), Timestamp.from(e.receivedAt()), e.value(), anomaly, e.traceId(), assetId, org);
        long alarmId = ((Number) row.get("id")).longValue();
        jdbc.update("update dcs_alarm_event set alarm_id = ? where id = ?", alarmId, ev.get(0));
        boolean inserted = Boolean.TRUE.equals(row.get("inserted"));
        if (inserted) {
            dispose(alarmId, e, point.orElse(null), assetId, org, effective);
        } else if ("DRAFTED".equals(row.get("status")) && row.get("issue_id") != null) {
            // 草稿 Issue 同步累计发生次数（同一来源键）
            issues.raise(draftCommand(e, assetId, org, alarmId, null));
        }
        return inserted ? (anomaly ? Outcome.CREATED_SKEW : Outcome.CREATED) : (anomaly ? Outcome.COUNTED_SKEW : Outcome.COUNTED);
    }

    /** 新报警的处置：忽略 / 建议关联已有人工缺陷 / 自动草稿 / 仅提醒。 */
    private void dispose(long alarmId, HistorianReader.RawAlarm e, DcsPointService.Point point, Long assetId, Long org, Instant at) {
        String policy = point == null ? "NOTIFY" : point.alarmPolicy();
        if ("IGNORE".equals(policy)) {
            jdbc.update("update dcs_alarm set status = 'IGNORED' where id = ?", alarmId);
            return;
        }
        Long owner = owner(point, assetId);
        Long suggested = assetId == null ? null : suggestIssue(assetId, at);
        if (suggested != null) {
            jdbc.update("update dcs_alarm set status = 'SUGGESTED', suggested_issue_id = ? where id = ?", suggested, alarmId);
            Issue i = issues.get(suggested);
            notice(owner, alarmId, "DCS 报警「" + e.message() + "」与已登记问题 " + i.code() + "「" + i.title() + "」指向同一设备，建议关联");
            return;
        }
        if ("AUTO_DRAFT".equals(policy) && org != null) {
            Issue draft = issues.raise(draftCommand(e, assetId, org, alarmId, owner)).issue();
            jdbc.update("update dcs_alarm set status = 'DRAFTED', issue_id = ? where id = ?", draft.id(), alarmId);
            notice(owner, alarmId, "DCS 报警「" + e.message() + "」已生成问题草稿 " + draft.code() + "，请确认");
            return;
        }
        jdbc.update("update dcs_alarm set status = 'NOTIFIED' where id = ?", alarmId);
        notice(owner, alarmId, "DCS 报警：" + e.message());
    }

    private IssueService.RaiseIssue draftCommand(HistorianReader.RawAlarm e, Long assetId, Long org, long alarmId, Long owner) {
        List<IssueService.ObjectRef> links = assetId == null
            ? List.of(new IssueService.ObjectRef(ALARM_OBJECT, String.valueOf(alarmId)))
            : List.of(new IssueService.ObjectRef("ASSET", String.valueOf(assetId)), new IssueService.ObjectRef(ALARM_OBJECT, String.valueOf(alarmId)));
        String sev = e.severity() != null && SEVERITIES.contains(e.severity()) ? e.severity() : "MEDIUM";
        return new IssueService.RaiseIssue("DCS_ALARM", e.message() == null ? e.pointCode() + " 报警" : e.message(),
            "测点 " + e.pointCode() + "，类型 " + e.alarmType() + "，原始事件 " + e.sourceSystem() + "/" + e.sourceEventId() + "，追踪号 " + e.traceId(),
            sev, org, "DCS", "DCS:" + e.dedupKey(), null, owner, null, null, null, links);
    }

    /** 同一设备、时间窗口内人工登记（非 DCS 来源）的未结问题，取时间最接近的一条。 */
    private Long suggestIssue(long assetId, Instant at) {
        List<Long> ids = jdbc.queryForList("""
            select i.id from core_issue i
             where i.source_type <> 'DCS' and i.status not in ('CLOSED', 'CANCELLED', 'REJECTED')
               and exists (select 1 from core_issue_link l where l.issue_id = i.id and l.object_type = 'ASSET' and l.object_id = ?)
               and abs(extract(epoch from (i.created_at::timestamptz - ?::timestamptz))) <= ?
             order by abs(extract(epoch from (i.created_at::timestamptz - ?::timestamptz))), i.id desc limit 1""", Long.class,
            String.valueOf(assetId), Timestamp.from(at), props.linkWindowMinutes() * 60, Timestamp.from(at));
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 确认关联：报警挂到已有 Issue 下（不新建），Issue 关联报警与设备。 */
    public Map<String, Object> link(long alarmId, long issueId) {
        return tx.execute(s -> {
            Map<String, Object> a = alarm(alarmId);
            if ("LINKED".equals(a.get("status")) || "DRAFTED".equals(a.get("status"))) {
                throw new LsBizException("ALARM_HANDLED", "报警已关联问题或已生成草稿");
            }
            Issue i = issues.get(issueId);
            if (Set.of(IssueStatus.CLOSED, IssueStatus.CANCELLED, IssueStatus.REJECTED).contains(i.status())) {
                throw new LsBizException("ISSUE_CLOSED", "问题已结束，不能再关联报警");
            }
            issues.link(issueId, new IssueService.ObjectRef(ALARM_OBJECT, String.valueOf(alarmId)), "RELATED");
            if (a.get("asset_id") != null) issues.link(issueId, new IssueService.ObjectRef("ASSET", String.valueOf(a.get("asset_id"))), "RELATED");
            jdbc.update("update dcs_alarm set status = 'LINKED', issue_id = ?, updated_at = now() where id = ?", issueId, alarmId);
            return alarm(alarmId);
        });
    }

    /** 不采纳关联建议或仅提醒的报警：人工生成 Issue 草稿。 */
    public Map<String, Object> draft(long alarmId, Long by) {
        return tx.execute(s -> {
            Map<String, Object> a = alarm(alarmId);
            if (a.get("issue_id") != null) throw new LsBizException("ALARM_HANDLED", "报警已关联问题或已生成草稿");
            Long org = a.get("org_unit_id") == null ? null : ((Number) a.get("org_unit_id")).longValue();
            if (org == null) throw LsBizException.validation(Map.of("orgUnitId", "测点未映射设备或组织，不能生成问题"));
            Long assetId = a.get("asset_id") == null ? null : ((Number) a.get("asset_id")).longValue();
            HistorianReader.RawAlarm e = latestEvent(alarmId, a);
            Issue draft = issues.raise(draftCommand(e, assetId, org, alarmId, owner(points.find((String) a.get("point_code")).orElse(null), assetId))
                .withSource("DCS", "DCS:" + a.get("dedup_key"))).issue();
            jdbc.update("update dcs_alarm set status = 'DRAFTED', issue_id = ?, updated_at = now() where id = ?", draft.id(), alarmId);
            return alarm(alarmId);
        });
    }

    public Map<String, Object> ignore(long alarmId) {
        Map<String, Object> a = alarm(alarmId);
        if (a.get("issue_id") != null) throw new LsBizException("ALARM_HANDLED", "报警已关联问题或已生成草稿");
        jdbc.update("update dcs_alarm set status = 'IGNORED', updated_at = now() where id = ?", alarmId);
        return alarm(alarmId);
    }

    public Map<String, Object> alarm(long id) {
        List<Map<String, Object>> l = jdbc.queryForList("""
            select a.*, p.name as point_name, p.unit, si.code as suggested_issue_code, si.title as suggested_issue_title,
                   i.code as issue_code, i.title as issue_title, i.status as issue_status, i.version as issue_version
              from dcs_alarm a left join dcs_point p on p.code = a.point_code
              left join core_issue si on si.id = a.suggested_issue_id left join core_issue i on i.id = a.issue_id
             where a.id = ?""", id);
        if (l.isEmpty()) throw LsBizException.notFound("DCS 报警", id);
        return l.get(0);
    }

    public List<Map<String, Object>> events(long alarmId) {
        return jdbc.queryForList("""
            select source_system, source_event_id, occurred_at, received_at, quality, clock_anomaly, value, trace_id
              from dcs_alarm_event where alarm_id = ? or (alarm_id is null and dedup_key = (select dedup_key from dcs_alarm where id = ?))
             order by occurred_at, id""", alarmId, alarmId);
    }

    private HistorianReader.RawAlarm latestEvent(long alarmId, Map<String, Object> a) {
        Map<String, Object> ev = jdbc.queryForList("select * from dcs_alarm_event where alarm_id = ? order by occurred_at desc limit 1", alarmId)
            .stream().findFirst().orElse(Map.of());
        return new HistorianReader.RawAlarm((String) a.get("source_system"), String.valueOf(ev.getOrDefault("source_event_id", "")),
            (String) a.get("point_code"), (String) a.get("alarm_type"), (String) a.get("dedup_key"), HistorianReader.toInstant(a.get("last_occurred_at")),
            HistorianReader.toInstant(a.get("last_received_at")), "GOOD", (String) a.get("severity"), (Double) a.get("last_value"),
            (String) a.get("message"), (String) a.get("trace_id"));
    }

    /** 设备负责人：测点上指定的负责人优先，否则取设备台账的负责人。 */
    private Long owner(DcsPointService.Point point, Long assetId) {
        if (point != null && point.ownerParty() != null) return point.ownerParty();
        return assetId == null ? null : assetField(assetId, "owner");
    }

    private Long assetField(long assetId, String field) {
        if (concepts.find(ASSET_CONCEPT).isEmpty()) return null;
        return records.find(ASSET_CONCEPT, assetId).map(r -> r.get(field)).map(v -> ((Number) v).longValue()).orElse(null);
    }

    private void notice(Long party, long alarmId, String message) {
        if (party == null) return;
        jdbc.update("insert into biz_flow_notice (kind, party_id, biz_type, biz_id, message) values ('DCS_ALARM', ?, 'ls.dcs.Alarm', ?, ?)",
            party, String.valueOf(alarmId), message.length() > 500 ? message.substring(0, 500) : message);
    }

    private static IngestResult sum(IngestResult a, IngestResult b) {
        return new IngestResult(a.received() + b.received(), a.duplicates() + b.duplicates(), a.badQuality() + b.badQuality(),
            a.clockAnomalies() + b.clockAnomalies(), a.alarmsCreated() + b.alarmsCreated());
    }
}
