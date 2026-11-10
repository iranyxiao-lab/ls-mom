package com.lsmom.dcs;

import com.lsmom.action.ActionFixture;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueService;
import com.lsmom.core.issue.IssueStatus;
import com.lsmom.core.party.Party;
import com.lsmom.dcs.simulator.Generator;
import com.lsmom.dcs.simulator.SimConfig;
import com.lsmom.meta.concept.ManagedRecords;
import com.lsmom.test.PgTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 15.3 / 15.4：报警幂等去重与链路健壮性；处置策略与"先人工后报警"关联合并。 */
class AlarmServiceTest {

    static final AtomicLong ASSET = new AtomicLong(910_000_000L + System.nanoTime() % 1_000_000);

    final ActionFixture f = new ActionFixture();
    final JdbcTemplate jdbc = f.core.jdbc;
    final DcsPointService points = new DcsPointService(jdbc);
    final AlarmService alarms = new AlarmService(jdbc, f.issues, points, f.concepts, new ManagedRecords(jdbc, f.concepts), f.txm,
        DcsProperties.defaults());
    final long workshop = f.core.plant().workshop().id();
    final Party owner = f.core.employee(PgTestSupport.uniq("dcs-owner-"), f.core.plant().workshop());

    String point(String policy, Long asset) {
        String code = PgTestSupport.uniq("P") + ".VIB";
        points.save(new DcsPointService.Point(null, code, "P-101 振动", "mm/s", null, null, "DCS-T", asset, workshop, "循环水", policy, owner.id(), true),
            null);
        return code;
    }

    static HistorianReader.RawAlarm ev(String point, String id, Instant occurred, Instant received, String quality) {
        return new HistorianReader.RawAlarm("DCS-T", id, point, "HI", "DCS-T:" + point + ":HI", occurred, received, quality, "HIGH", 5.1,
            "P-101 振动越上限 4.5mm/s", "tr-" + id);
    }

    Map<String, Object> alarmOf(String point) {
        return jdbc.queryForMap("select * from dcs_alarm where point_code = ?", point);
    }

    long notices(long alarmId) {
        return jdbc.queryForObject("select count(*) from biz_flow_notice where kind = 'DCS_ALARM' and biz_id = ? and party_id = ?", Long.class,
            String.valueOf(alarmId), owner.id());
    }

    @Test
    void 乱序重复到达三次_只有一条业务报警_次数3_最近发生时间取最大() {
        String p = point("NOTIFY", null);
        Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AlarmService.IngestResult r = alarms.ingest(List.of(
            ev(p, p + "-2", t.plusSeconds(20), t.plusSeconds(25), "GOOD"),
            ev(p, p + "-3", t.plusSeconds(40), t.plusSeconds(41), "GOOD"),
            ev(p, p + "-1", t, t.plusSeconds(50), "GOOD"),
            // 断网恢复后补传：同一来源事件再次送达
            ev(p, p + "-3", t.plusSeconds(40), t.plusSeconds(60), "GOOD")));
        assertThat(r.duplicates()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from dcs_alarm where point_code = ?", Long.class, p)).isEqualTo(1L);
        Map<String, Object> a = alarmOf(p);
        assertThat(a.get("occurrence_count")).isEqualTo(3);
        assertThat(((Timestamp) a.get("last_occurred_at")).toInstant()).isEqualTo(t.plusSeconds(40));
        assertThat(((Timestamp) a.get("first_occurred_at")).toInstant()).isEqualTo(t);
        assertThat(a.get("status")).isEqualTo("NOTIFIED");
        // 原始事件引用齐全
        assertThat(alarms.events(((Number) a.get("id")).longValue())).hasSize(3)
            .allSatisfy(e -> assertThat(e).containsKeys("source_system", "source_event_id", "occurred_at", "received_at", "quality", "trace_id"));
    }

    @Test
    void 坏质量位只记录不判定_时钟异常标记并保留原始时间() {
        String p = point("NOTIFY", null);
        Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AlarmService.IngestResult r = alarms.ingest(List.of(ev(p, p + "-bad", t, t.plusSeconds(1), "BAD")));
        assertThat(r.badQuality()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from dcs_alarm where point_code = ?", Long.class, p)).isZero();
        assertThat(jdbc.queryForObject("select quality from dcs_alarm_event where source_event_id = ?", String.class, p + "-bad")).isEqualTo("BAD");

        // 源时间比接收时间超前 10 分钟
        r = alarms.ingest(List.of(ev(p, p + "-skew", t.plusSeconds(600), t, "GOOD")));
        assertThat(r.clockAnomalies()).isEqualTo(1);
        Map<String, Object> e = jdbc.queryForMap("select * from dcs_alarm_event where source_event_id = ?", p + "-skew");
        assertThat(e.get("clock_anomaly")).isEqualTo(true);
        assertThat(((Timestamp) e.get("occurred_at")).toInstant()).isEqualTo(t.plusSeconds(600));
        assertThat(((Timestamp) e.get("received_at")).toInstant()).isEqualTo(t);
        Map<String, Object> a = alarmOf(p);
        assertThat(a.get("clock_anomaly")).isEqualTo(true);
        assertThat(((Timestamp) a.get("last_occurred_at")).toInstant()).isEqualTo(t);
    }

    @Test
    void 从Historian增量读取_断网补传重放不重复计数() {
        String px = PgTestSupport.uniq("H");
        SimConfig.Scenario sc = new SimConfig.Scenario(new SimConfig.Outage(60, 10), 5, 90, new SimConfig.BadQuality(px + ".TEMP", 900, 999), null);
        SimConfig cfg = DcsTestSupport.config(px, sc);
        Instant origin = Instant.parse("2026-11-09T00:00:00Z");
        Generator.Batch b = new Generator(cfg, origin).generate(origin, origin.plus(Duration.ofHours(3)));
        String schema = DcsTestSupport.schema();
        DcsTestSupport.write(schema, List.of(), b.alarms());
        HistorianReader reader = new HistorianReader(DcsTestSupport.readOnly(), schema);

        AlarmService.IngestResult r = alarms.pull(reader);
        long distinctGood = b.alarms().stream().filter(a -> a.quality().equals(Generator.GOOD)).map(Generator.AlarmEvent::sourceEventId).distinct().count();
        long bad = b.alarms().stream().filter(a -> a.quality().equals(Generator.BAD)).map(Generator.AlarmEvent::sourceEventId).distinct().count();
        assertThat(r.received()).isEqualTo(b.alarms().size());
        assertThat(r.duplicates()).isEqualTo(b.alarms().size() - distinctGood - bad).isPositive();
        assertThat(r.badQuality()).isEqualTo(bad).isPositive();
        Long total = jdbc.queryForObject("select sum(occurrence_count) from dcs_alarm where point_code like ?", Long.class, px + ".%");
        assertThat(total).isEqualTo(distinctGood);
        // 温度点只有坏值越限（不判定），只有振动点形成业务报警
        assertThat(jdbc.queryForObject("select count(*) from dcs_alarm where point_code like ?", Long.class, px + ".%")).isEqualTo(1L);

        assertThat(alarms.pull(reader).received()).isZero();
        // 适配器重启后整段重发
        Generator.Batch again = new Generator(cfg, origin).generate(origin, origin.plus(Duration.ofHours(3)));
        DcsTestSupport.write(schema, List.of(), again.alarms().stream().map(a -> new Generator.AlarmEvent(a.sourceSystem(), a.sourceEventId(), a.point(),
            a.alarmType(), a.dedupKey(), a.occurredAt(), a.receivedAt().plus(Duration.ofHours(4)), a.quality(), a.severity(), a.value(), a.message(),
            a.traceId())).toList());
        AlarmService.IngestResult r2 = alarms.pull(reader);
        assertThat(r2.duplicates()).isEqualTo(r2.received());
        assertThat(jdbc.queryForObject("select sum(occurrence_count) from dcs_alarm where point_code like ?", Long.class, px + ".%")).isEqualTo(total);
    }

    @Test
    void 自动草稿_关联报警与设备并通知负责人_确认后成为正式问题() {
        long asset = ASSET.incrementAndGet();
        String p = point("AUTO_DRAFT", asset);
        Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        alarms.ingest(List.of(ev(p, p + "-1", t, t, "GOOD")));
        Map<String, Object> a = alarmOf(p);
        long alarmId = ((Number) a.get("id")).longValue();
        assertThat(a.get("status")).isEqualTo("DRAFTED");
        Issue draft = f.issues.get(((Number) a.get("issue_id")).longValue());
        assertThat(draft.status()).isEqualTo(IssueStatus.DRAFT);
        assertThat(draft.sourceType()).isEqualTo("DCS");
        assertThat(draft.ownerParty()).isEqualTo(owner.id());
        assertThat(f.issues.links(draft.id(), null)).contains(new IssueService.ObjectRef("ASSET", String.valueOf(asset)),
            new IssueService.ObjectRef(AlarmService.ALARM_OBJECT, String.valueOf(alarmId)));
        assertThat(notices(alarmId)).isEqualTo(1L);

        // 再次发生：报警与草稿同步累计
        alarms.ingest(List.of(ev(p, p + "-2", t.plusSeconds(30), t.plusSeconds(30), "GOOD")));
        assertThat(f.issues.get(draft.id()).occurrenceCount()).isEqualTo(2);
        assertThat(notices(alarmId)).isEqualTo(1L);

        // 人工确认（严重度 HIGH：报警事件引用即证据）
        assertThat(f.step("ls.core.Issue.submit", ActionFixture.admin(), "ls.core.Issue", String.valueOf(draft.id()), "{}").status()).isEqualTo("OPEN");
    }

    @Test
    void 仅提醒与忽略() {
        String n = point("NOTIFY", ASSET.incrementAndGet());
        String i = point("IGNORE", ASSET.incrementAndGet());
        Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        alarms.ingest(List.of(ev(n, n + "-1", t, t, "GOOD"), ev(i, i + "-1", t, t, "GOOD")));
        Map<String, Object> na = alarmOf(n);
        Map<String, Object> ia = alarmOf(i);
        assertThat(na.get("status")).isEqualTo("NOTIFIED");
        assertThat(na.get("issue_id")).isNull();
        assertThat(notices(((Number) na.get("id")).longValue())).isEqualTo(1L);
        assertThat(ia.get("status")).isEqualTo("IGNORED");
        assertThat(notices(((Number) ia.get("id")).longValue())).isZero();

        // 仅提醒的报警可人工生成草稿
        Map<String, Object> d = alarms.draft(((Number) na.get("id")).longValue(), owner.id());
        assertThat(d.get("status")).isEqualTo("DRAFTED");
        assertThat(f.issues.get(((Number) d.get("issue_id")).longValue()).status()).isEqualTo(IssueStatus.DRAFT);
        assertThatThrownBy(() -> alarms.ignore(((Number) na.get("id")).longValue())).hasMessageContaining("已关联");
    }

    @Test
    void 先人工后报警_建议关联已有缺陷_确认后报警挂在该Issue下而不新建() {
        long asset = ASSET.incrementAndGet();
        String p = point("AUTO_DRAFT", asset);
        // 巡检员先登记泵振动缺陷
        Issue manual = f.issues.raise(IssueService.RaiseIssue.manual("EQUIPMENT_DEFECT", "P-101 泵体振动偏大", workshop, owner.id())
            .withLinks(List.of(new IssueService.ObjectRef("ASSET", String.valueOf(asset))))).issue();
        // 另一台设备的人工缺陷不应被建议
        f.issues.raise(IssueService.RaiseIssue.manual("EQUIPMENT_DEFECT", "其他泵", workshop, owner.id())
            .withLinks(List.of(new IssueService.ObjectRef("ASSET", String.valueOf(ASSET.incrementAndGet())))));
        // 10 分钟后该泵振动报警到达
        Instant t = Instant.now().plus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.SECONDS);
        alarms.ingest(List.of(ev(p, p + "-1", t, t, "GOOD")));
        Map<String, Object> a = alarmOf(p);
        long alarmId = ((Number) a.get("id")).longValue();
        assertThat(a.get("status")).isEqualTo("SUGGESTED");
        assertThat(((Number) a.get("suggested_issue_id")).longValue()).isEqualTo(manual.id());
        assertThat(a.get("issue_id")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from core_issue where source_key = ?", Long.class, "DCS:" + a.get("dedup_key"))).isZero();
        assertThat(notices(alarmId)).isEqualTo(1L);

        Map<String, Object> linked = alarms.link(alarmId, manual.id());
        assertThat(linked.get("status")).isEqualTo("LINKED");
        assertThat(((Number) linked.get("issue_id")).longValue()).isEqualTo(manual.id());
        assertThat(f.issues.links(manual.id(), null)).contains(new IssueService.ObjectRef(AlarmService.ALARM_OBJECT, String.valueOf(alarmId)));
        assertThat(jdbc.queryForObject("select count(*) from core_issue i where exists (select 1 from core_issue_link l where l.issue_id = i.id "
            + "and l.object_type = 'ASSET' and l.object_id = ?)", Long.class, String.valueOf(asset))).isEqualTo(1L);
        // 后续发生只累计报警次数，不新建
        alarms.ingest(List.of(ev(p, p + "-2", t.plusSeconds(60), t.plusSeconds(60), "GOOD")));
        assertThat(alarmOf(p).get("occurrence_count")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from core_issue where source_key = ?", Long.class, "DCS:" + a.get("dedup_key"))).isZero();
        assertThatThrownBy(() -> alarms.link(alarmId, manual.id())).hasMessageContaining("已关联");
    }

    /** AI-09：报警上下文摘要——要点引用报警与原始事件；引用不在上下文中被拒绝；草稿待真人确认。 */
    @Test
    void 报警上下文摘要_带引用草稿() {
        String p = point("NOTIFY", null);
        Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        alarms.ingest(List.of(ev(p, p + "-1", t, t, "GOOD"), ev(p, p + "-2", t.plusSeconds(20), t.plusSeconds(20), "GOOD")));
        long alarmId = ((Number) alarmOf(p).get("id")).longValue();
        f.summaries.register(new DcsAlarmSummaryContext(alarms, new SnapshotService(jdbc, null, DcsProperties.defaults())));
        com.lsmom.action.api.ActorContext boss = new com.lsmom.action.api.ActorContext(com.lsmom.action.api.ActorContext.ActorType.HUMAN, null, 1L, null,
            false, java.util.Set.of("lsmom:summary:draft"), new com.lsmom.core.org.DataScope(false, java.util.Set.of(workshop), null,
            com.lsmom.core.org.DataScopeLevel.WORKSHOP), "车间主任");
        // 其他车间看不到该报警
        com.lsmom.action.api.ActorContext outsider = f.human(owner, com.lsmom.core.org.DataScopeLevel.WORKSHOP, "lsmom:summary:draft");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.summaries.context(AlarmService.ALARM_OBJECT, String.valueOf(alarmId), outsider))
            .hasMessageContaining("不存在");
        var ctx = f.summaries.context(AlarmService.ALARM_OBJECT, String.valueOf(alarmId), boss);
        assertThat(ctx.facts()).extracting(x -> x.ref().type()).contains("ls.dcs.Alarm", "ls.dcs.AlarmEvent");
        assertThat(ctx.facts().get(0).text()).contains("累计 2 次").contains("HI");
        String ev = ctx.facts().stream().filter(x -> x.ref().type().equals("ls.dcs.AlarmEvent")).findFirst().orElseThrow().ref().id();
        String id = f.op("ls.core.Summary.draft", boss, "{\"kind\":\"DCS_ALARM\",\"subjectId\":\"" + alarmId + "\",\"content\":\"振动越限两次\","
            + "\"points\":[{\"text\":\"振动越上限\",\"refs\":[{\"type\":\"ls.dcs.Alarm\",\"id\":\"" + alarmId + "\"},{\"type\":\"ls.dcs.AlarmEvent\",\"id\":\"" + ev + "\"}]}]}").targetId();
        assertThat(f.summaries.find(Long.parseLong(id)).orElseThrow()).containsEntry("status", "DRAFT").containsEntry("subjectConcept", "ls.dcs.Alarm")
            .containsEntry("orgUnitId", workshop);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.op("ls.core.Summary.draft", boss, "{\"kind\":\"DCS_ALARM\",\"subjectId\":\"" + alarmId
            + "\",\"content\":\"x\",\"points\":[{\"text\":\"x\",\"refs\":[{\"type\":\"ls.dcs.AlarmEvent\",\"id\":\"DCS-T:fake\"}]}]}"))
            .hasMessageContaining("不在该对象的上下文中");
        assertThat(alarmOf(p).get("status")).isEqualTo("NOTIFIED");
    }
}
