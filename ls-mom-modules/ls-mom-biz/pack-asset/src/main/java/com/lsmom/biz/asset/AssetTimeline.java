package com.lsmom.biz.asset;

import com.lsmom.meta.concept.ManagedRecords;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.lsmom.biz.asset.AssetPack.DEFECT;
import static com.lsmom.biz.asset.AssetPack.OBJECT_TYPE;
import static com.lsmom.biz.asset.AssetPack.RECORD;
import static com.lsmom.biz.asset.AssetPack.STATUS_LOG;

/**
 * 设备时间轴：状态变更、点检、异常观察、DCS 报警、缺陷登记 / 维修 / 验证（缺陷的领域事件）、维修工作与占用该设备的生产工作，按时间倒序。
 * 每条：kind、at、title、detail、status、concept、id。
 */
public class AssetTimeline {

    private static final Map<String, String> DEFECT_STEPS = Map.of(
        DEFECT + ".report", "登记缺陷", DEFECT + ".createRepair", "生成维修工作", DEFECT + ".submitVerify", "维修完成待验证",
        DEFECT + ".verifyPass", "验证通过，缺陷关闭", DEFECT + ".verifyFail", "验证不通过", DEFECT + ".cancel", "取消缺陷");

    private final JdbcTemplate jdbc;
    private final ManagedRecords records;

    public AssetTimeline(JdbcTemplate jdbc, ManagedRecords records) {
        this.jdbc = jdbc;
        this.records = records;
    }

    public List<Map<String, Object>> of(long assetId, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sid = String.valueOf(assetId);

        String log = records.table(STATUS_LOG);
        for (Map<String, Object> r : jdbc.queryForList("""
            select l.id, l.%s as at, l.%s as from_status, l.%s as to_status, l.%s as reason, p.name as operator
              from %s l left join core_party p on p.id = l.%s
             where l.%s = ? and l.del_flag = '0' order by l.id desc limit ?""".formatted(col(STATUS_LOG, "changedAt"), col(STATUS_LOG, "fromStatus"),
            col(STATUS_LOG, "toStatus"), col(STATUS_LOG, "reason"), log, col(STATUS_LOG, "operator"), col(STATUS_LOG, "asset")), assetId, limit)) {
            out.add(item("STATUS", r.get("at"), "状态：" + r.get("from_status") + " → " + r.get("to_status"),
                join(r.get("reason"), r.get("operator")), String.valueOf(r.get("to_status")), STATUS_LOG, r.get("id")));
        }

        for (Map<String, Object> r : jdbc.queryForList("""
            select r.id, r.%s as at, r.%s as result, r.%s as abnormal, r.%s as defects, p.name as inspector
              from %s r left join core_party p on p.id = r.%s
             where r.%s = ? and r.status = 'SUBMITTED' and r.del_flag = '0' order by r.id desc limit ?""".formatted(col(RECORD, "inspectedAt"),
            col(RECORD, "result"), col(RECORD, "abnormalCount"), col(RECORD, "defectCount"), records.table(RECORD), col(RECORD, "inspector"),
            col(RECORD, "asset")), assetId, limit)) {
            boolean bad = "ABNORMAL".equals(r.get("result"));
            out.add(item("INSPECTION", r.get("at"), bad ? "点检异常 " + r.get("abnormal") + " 项" : "点检正常",
                join(r.get("inspector"), r.get("defects") == null ? null : "已生成缺陷 " + r.get("defects") + " 条"), String.valueOf(r.get("result")), RECORD, r.get("id")));
        }

        for (Map<String, Object> r : jdbc.queryForList("""
            select id, observed_at as at, item_code, value_num, value_text, unit, source, quality from core_observation
             where object_type = ? and object_id = ? and (quality <> 'GOOD' or source = 'DCS') order by observed_at desc limit ?""", OBJECT_TYPE, sid, limit)) {
            Object v = r.get("value_num") != null ? r.get("value_num") : r.get("value_text");
            out.add(item("OBSERVATION", r.get("at"), ("DCS".equals(r.get("source")) ? "DCS 读数 " : "异常读数 ") + r.get("item_code") + " = " + v
                + (r.get("unit") == null ? "" : String.valueOf(r.get("unit"))), null, String.valueOf(r.get("quality")), "ls.core.Observation", r.get("id")));
        }

        for (Map<String, Object> r : jdbc.queryForList("""
            select i.id, i.code, i.title, i.status, i.occurrence_count, i.last_occurred_at as at from core_issue i
             where i.source_type = 'DCS' and exists (select 1 from core_issue_link l where l.issue_id = i.id and l.object_type = ? and l.object_id = ?)
             order by i.last_occurred_at desc limit ?""", OBJECT_TYPE, sid, limit)) {
            out.add(item("ALARM", r.get("at"), "DCS 报警：" + r.get("title"),
                r.get("code") + (((Number) r.get("occurrence_count")).intValue() > 1 ? "，累计 " + r.get("occurrence_count") + " 次" : ""),
                String.valueOf(r.get("status")), "ls.core.Issue", r.get("id")));
        }

        String d = records.table(DEFECT);
        for (Map<String, Object> r : jdbc.queryForList("""
            select e.type, e.occurred_at as at, e.payload::text as payload, x.id, x.%s as title, x.status, p.name as actor, i.code as issue_code,
                   i.source_type as issue_source
              from %s x join core_domain_event e on e.subject_concept = ? and e.subject_id = x.id::varchar
              left join core_party p on p.id = coalesce(e.on_behalf_of, e.actor_party)
              left join core_issue i on i.id = x.%s
             where x.%s = ? and x.del_flag = '0' and e.type like ? order by e.occurred_at desc limit ?""".formatted(col(DEFECT, "title"), d,
            col(DEFECT, "issue"), col(DEFECT, "asset")), DEFECT, assetId, DEFECT + ".%", limit)) {
            String step = DEFECT_STEPS.get(String.valueOf(r.get("type")));
            if (step == null) continue;
            String kind = String.valueOf(r.get("type")).endsWith("verifyPass") || String.valueOf(r.get("type")).endsWith("verifyFail") ? "VERIFY" : "DEFECT";
            String src = "DCS".equals(r.get("issue_source")) ? "来源：DCS 报警" : null;
            out.add(item(kind, r.get("at"), step + "：" + r.get("title"), join(r.get("issue_code"), r.get("actor"), src, conclusion(r.get("payload"))),
                String.valueOf(r.get("status")), DEFECT, r.get("id")));
        }

        for (Map<String, Object> r : jdbc.queryForList("""
            select w.id, w.code, w.title, w.work_type, w.status, coalesce(w.actual_end, w.actual_start, w.created_at) as at
              from core_work_item w
             where w.work_type <> 'INSPECTION' and exists (select 1 from core_work_assignment a where a.work_item_id = w.id
                   and a.resource_type = 'EQUIPMENT' and a.resource_ref = ?)
             order by w.id desc limit ?""", sid, limit)) {
            boolean repair = "MAINTENANCE".equals(r.get("work_type"));
            out.add(item(repair ? "REPAIR" : "WORK", r.get("at"), (repair ? "维修工作：" : "相关工作：") + r.get("title"), String.valueOf(r.get("code")),
                String.valueOf(r.get("status")), "ls.core.Work", r.get("id")));
        }

        out.sort(Comparator.comparing((Map<String, Object> m) -> (LocalDateTime) m.get("at"), Comparator.nullsLast(Comparator.reverseOrder())));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private String col(String concept, String field) {
        return records.column(concept, field);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String conclusion(Object payload) {
        if (payload == null) return null;
        try {
            var n = JSON.readTree(String.valueOf(payload));
            return n.hasNonNull("conclusion") ? "结论：" + n.get("conclusion").asText() : n.hasNonNull("reason") ? "原因：" + n.get("reason").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String join(Object... parts) {
        List<String> s = new ArrayList<>();
        for (Object p : parts) if (p != null && !String.valueOf(p).isBlank()) s.add(String.valueOf(p));
        return s.isEmpty() ? null : String.join(" · ", s);
    }

    private static Map<String, Object> item(String kind, Object at, String title, String detail, String status, String concept, Object id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("at", at instanceof java.sql.Timestamp t ? t.toLocalDateTime() : at);
        m.put("title", title);
        m.put("detail", detail);
        m.put("status", status);
        m.put("concept", concept);
        m.put("id", id);
        return m;
    }
}
