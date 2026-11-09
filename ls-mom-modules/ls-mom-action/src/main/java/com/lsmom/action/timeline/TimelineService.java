package com.lsmom.action.timeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final JdbcTemplate jdbc;

    public TimelineService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Entry> of(String concept, String id) {
        return jdbc.query("""
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
    }

    private static Map<String, Object> read(String json) {
        try {
            return json == null ? Map.of() : JSON.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
