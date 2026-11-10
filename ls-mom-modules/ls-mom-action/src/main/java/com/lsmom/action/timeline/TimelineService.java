package com.lsmom.action.timeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.action.api.ActorContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 业务时间轴：按时间聚合对象的状态迁移、填报、附件、整改等事件，标注操作人与动作名称。
 */
@Service
public class TimelineService {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Entry(LocalDateTime at, String type, String action, Long actorParty, String actorName, Long onBehalfOf,
                        String fromStatus, String toStatus, Map<String, Object> payload, String traceId) {
    }

    /** 操作人已隐藏的条目显示的名称。 */
    public static final String HIDDEN_ACTOR = "（匿名）";
    /** 时间轴里的附件条目类型。 */
    public static final String DOCUMENT = "DOCUMENT";

    private final JdbcTemplate jdbc;
    private List<TimelineRedactor> redactors = List.of();

    public TimelineService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Autowired(required = false)
    public void setRedactors(List<TimelineRedactor> redactors) {
        this.redactors = List.copyOf(redactors);
    }

    /** 查看人可见的时间轴（经各 Pack 的脱敏规则处理）。 */
    public List<Entry> of(String concept, String id, ActorContext viewer) {
        List<Entry> out = of(concept, id);
        for (TimelineRedactor r : redactors) out = r.redact(concept, id, out, viewer);
        return out;
    }

    /** 去掉操作人（含代办主体）。 */
    public static Entry withoutActor(Entry e) {
        return new Entry(e.at(), e.type(), e.action(), null, HIDDEN_ACTOR, null, e.fromStatus(), e.toStatus(), e.payload(), e.traceId());
    }

    public List<Entry> of(String concept, String id) {
        List<Entry> events = jdbc.query("""
            select e.occurred_at, e.type, coalesce(o.display_name, e.type) as action, e.actor_party, p.name as actor_name,
                   e.on_behalf_of, e.payload::text as payload, e.trace_id
            from core_domain_event e
            left join meta_operation o on o.code = e.type
            left join core_party p on p.id = coalesce(e.on_behalf_of, e.actor_party)
            where e.subject_concept = ? and e.subject_id = ?
            order by e.occurred_at, e.event_id""", (rs, i) -> {
            Map<String, Object> payload = read(rs.getString("payload"));
            return new Entry(rs.getObject("occurred_at", LocalDateTime.class), rs.getString("type"), rs.getString("action"),
                (Long) rs.getObject("actor_party"), rs.getString("actor_name"), (Long) rs.getObject("on_behalf_of"),
                (String) payload.get("from"), (String) payload.get("to"), payload, rs.getString("trace_id"));
        }, concept, id);
        // 附件与现场证据（如移动端拍照）也出现在时间轴上
        List<Entry> docs = jdbc.query("""
            select d.created_at, d.purpose, d.file_name, d.oss_id, d.created_by, p.name as actor_name
            from core_document_link d
            left join core_party p on p.id = d.created_by
            where d.object_type = ? and d.object_id = ? and d.removed_at is null
            order by d.created_at, d.id""", (rs, i) -> {
            String purpose = rs.getString("purpose");
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("fileName", rs.getString("file_name"));
            payload.put("ossId", String.valueOf(rs.getLong("oss_id")));
            payload.put("purpose", purpose);
            return new Entry(rs.getObject("created_at", LocalDateTime.class), DOCUMENT,
                ("EVIDENCE".equals(purpose) ? "上传现场证据：" : "上传附件：") + rs.getString("file_name"),
                (Long) rs.getObject("created_by"), rs.getString("actor_name"), null, null, null, payload, null);
        }, concept, id);
        if (docs.isEmpty()) return events;
        List<Entry> all = new java.util.ArrayList<>(events);
        all.addAll(docs);
        all.sort(java.util.Comparator.comparing(Entry::at, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return all;
    }

    private static Map<String, Object> read(String json) {
        try {
            return json == null ? Map.of() : JSON.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
