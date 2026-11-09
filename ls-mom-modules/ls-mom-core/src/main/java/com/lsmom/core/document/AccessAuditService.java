package com.lsmom.core.document;

import com.lsmom.core.trace.TraceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 受控访问审计。拒绝记录在独立事务中写入，调用方随后抛错回滚也不会丢失。 */
@Service
public class AccessAuditService {

    private final JdbcTemplate jdbc;

    public AccessAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorParty, String action, String objectType, String objectId, boolean allowed, String reason) {
        jdbc.update("""
            insert into core_access_audit (actor_party, action, object_type, object_id, result, reason, trace_id)
            values (?, ?, ?, ?, ?, ?, ?)""", actorParty, action, objectType, objectId, allowed ? "ALLOWED" : "DENIED", reason,
            TraceContext.current());
    }
}
