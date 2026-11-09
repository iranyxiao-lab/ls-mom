package com.lsmom.action.external;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 外部同步状态与补偿列表。 */
@Service
public class ExternalSyncService {

    public record SyncRecord(long id, String targetSystem, String objectType, String objectId, UUID eventId, String status,
                             int attempts, String externalRef, String lastError, LocalDateTime updatedAt, Long outboxId,
                             String outboxStatus) {
    }

    private final JdbcTemplate jdbc;

    public ExternalSyncService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void pending(String system, String objectType, String objectId, UUID eventId) {
        jdbc.update("""
            insert into op_external_sync (target_system, object_type, object_id, event_id) values (?, ?, ?, ?)
            on conflict (target_system, event_id) do nothing""", system, objectType, objectId, eventId);
    }

    public void synced(String system, UUID eventId, String externalRef) {
        jdbc.update("""
            update op_external_sync set status = 'SYNCED', attempts = attempts + 1, external_ref = ?, last_error = null, updated_at = now()
            where target_system = ? and event_id = ?""", externalRef, system, eventId);
    }

    public void failed(String system, UUID eventId, String error) {
        jdbc.update("""
            update op_external_sync set status = 'FAILED', attempts = attempts + 1, last_error = ?, updated_at = now()
            where target_system = ? and event_id = ?""", error == null ? null : error.substring(0, Math.min(error.length(), 1000)), system, eventId);
    }

    private static final String SELECT = """
        select s.*, o.id as outbox_id, o.status as outbox_status from op_external_sync s
        left join core_outbox o on o.event_id = s.event_id and o.destination = 'external:' || s.target_system""";

    /** 补偿列表：已失败且投递已放弃（DEAD）的同步记录。 */
    public List<SyncRecord> compensations(String system) {
        return jdbc.query(SELECT + " where s.status = 'FAILED' and o.status = 'DEAD' and (?::varchar is null or s.target_system = ?::varchar) order by s.id",
            this::map, system, system);
    }

    public List<SyncRecord> of(String objectType, String objectId) {
        return jdbc.query(SELECT + " where s.object_type = ? and s.object_id = ? order by s.id", this::map, objectType, objectId);
    }

    private SyncRecord map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new SyncRecord(rs.getLong("id"), rs.getString("target_system"), rs.getString("object_type"), rs.getString("object_id"),
            rs.getObject("event_id", UUID.class), rs.getString("status"), rs.getInt("attempts"), rs.getString("external_ref"),
            rs.getString("last_error"), rs.getObject("updated_at", LocalDateTime.class), (Long) rs.getObject("outbox_id"),
            rs.getString("outbox_status"));
    }
}
