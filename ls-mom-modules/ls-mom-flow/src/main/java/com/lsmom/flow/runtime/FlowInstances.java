package com.lsmom.flow.runtime;

import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** biz_flow_instance 读写。 */
public class FlowInstances {

    public record Instance(long id, String flowCode, int flowVersion, String formCode, Integer formVersion, String bizType, String bizId,
                           String procInstId, String procDefId, Long initiatorParty, Long orgUnitId, String status, String syncStatus,
                           int attempts, String lastError, LocalDateTime startedAt, LocalDateTime endedAt) {
    }

    private final JdbcTemplate jdbc;

    public FlowInstances(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(String flowCode, int flowVersion, String formCode, Integer formVersion, String bizType, String bizId,
                       String procDefId, Long initiator, Long orgUnitId, String traceId) {
        Long id = jdbc.queryForObject("""
            insert into biz_flow_instance (flow_code, flow_version, form_code, form_version, biz_type, biz_id, proc_def_id, initiator_party,
                                           org_unit_id, status, sync_status, trace_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', 'PENDING_START', ?) returning id""", Long.class,
            flowCode, flowVersion, formCode, formVersion, bizType, bizId, procDefId, initiator, orgUnitId, traceId);
        return id == null ? 0 : id;
    }

    public Instance get(long id) {
        return jdbc.query("select * from biz_flow_instance where id = ?", (rs, i) -> map(rs), id).stream().findFirst()
            .orElseThrow(() -> LsBizException.notFound("流程实例", id));
    }

    public Optional<Instance> byProcInst(String procInstId) {
        return jdbc.query("select * from biz_flow_instance where proc_inst_id = ?", (rs, i) -> map(rs), procInstId).stream().findFirst();
    }

    public Optional<Instance> running(String bizType, String bizId) {
        return jdbc.query("select * from biz_flow_instance where biz_type = ? and biz_id = ? and status = 'RUNNING' order by id desc limit 1",
            (rs, i) -> map(rs), bizType, bizId).stream().findFirst();
    }

    public List<Instance> ofBiz(String bizType, String bizId) {
        return jdbc.query("select * from biz_flow_instance where biz_type = ? and biz_id = ? order by id desc", (rs, i) -> map(rs), bizType, bizId);
    }

    public List<Instance> dueForRetry(LocalDateTime now) {
        return jdbc.query("""
            select * from biz_flow_instance where sync_status in ('PENDING_START', 'PENDING_RETRY')
              and (next_retry_at is null or next_retry_at <= ?) order by id limit 50""", (rs, i) -> map(rs), now);
    }

    public List<Instance> runningAll() {
        return jdbc.query("select * from biz_flow_instance where status = 'RUNNING' and proc_inst_id is not null", (rs, i) -> map(rs));
    }

    public void started(long id, String procInstId) {
        jdbc.update("""
            update biz_flow_instance set proc_inst_id = ?, sync_status = 'SYNCED', last_error = null, next_retry_at = null, updated_at = now()
            where id = ?""", procInstId, id);
    }

    public void startFailed(long id, String error, LocalDateTime nextRetry, boolean giveUp) {
        jdbc.update("""
            update biz_flow_instance set sync_status = ?, attempts = attempts + 1, last_error = ?, next_retry_at = ?, updated_at = now()
            where id = ?""", giveUp ? "FAILED" : "PENDING_RETRY", trim(error), nextRetry, id);
    }

    public void ended(long id, String status) {
        jdbc.update("update biz_flow_instance set status = ?, ended_at = now(), updated_at = now() where id = ? and status = 'RUNNING'", status, id);
    }

    static String trim(String s) {
        return s == null ? null : s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    private static Instance map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Instance(rs.getLong("id"), rs.getString("flow_code"), rs.getInt("flow_version"), rs.getString("form_code"),
            (Integer) rs.getObject("form_version"), rs.getString("biz_type"), rs.getString("biz_id"), rs.getString("proc_inst_id"),
            rs.getString("proc_def_id"), (Long) rs.getObject("initiator_party"), (Long) rs.getObject("org_unit_id"), rs.getString("status"),
            rs.getString("sync_status"), rs.getInt("attempts"), rs.getString("last_error"), rs.getObject("started_at", LocalDateTime.class),
            rs.getObject("ended_at", LocalDateTime.class));
    }
}
