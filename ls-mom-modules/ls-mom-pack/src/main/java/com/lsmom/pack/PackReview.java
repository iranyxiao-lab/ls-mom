package com.lsmom.pack;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 使用量与复审：使用量取自 Operation 审计（Pack 对象上的成功调用），失败任务取流程回调失败与未处理的异常待办。
 * 到达复审日期为"待复审"；到期且 90 天无使用为"建议下线"，状态变化时通知业务负责人（消息中心）。
 */
public class PackReview {

    public static final int IDLE_DAYS = 90;

    public record Usage(long uses90d, LocalDateTime lastUsedAt, long failedTasks) {
    }

    public record Change(String pack, String from, String to, boolean notified) {
    }

    private final JdbcTemplate jdbc;
    private final PackStore store;
    private final Clock clock;

    public PackReview(JdbcTemplate jdbc, PackStore store, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.clock = clock;
    }

    public Usage usage(String pack) {
        Map<String, Object> u = jdbc.queryForMap("""
            select count(*) filter (where a.occurred_at >= ?) as uses, max(a.occurred_at) as last_used
              from op_audit a where a.result = 'SUCCESS'
               and a.target_concept in (select code from meta_concept where owner_pack = ?)""",
            LocalDateTime.now(clock).minusDays(IDLE_DAYS), pack);
        Long failed = jdbc.queryForObject("""
            select (select count(*) from biz_flow_callback_log where status = 'FAILED'
                       and biz_type in (select code from meta_concept where owner_pack = ?))
                 + (select count(*) from biz_task where kind = 'EXCEPTION' and status = 'TODO'
                       and biz_type in (select code from meta_concept where owner_pack = ?))""", Long.class, pack, pack);
        Object last = u.get("last_used");
        LocalDateTime lastUsed = last instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) last;
        return new Usage(((Number) u.get("uses")).longValue(), lastUsed, failed == null ? 0 : failed);
    }

    /** 复审检查（每日任务；也可手动触发）。返回状态变化。 */
    public List<Change> review() {
        LocalDate today = LocalDate.now(clock);
        List<Change> changes = new ArrayList<>();
        for (PackStore.Pack p : store.list()) {
            if (!"ENABLED".equals(p.status()) && !"DISABLED".equals(p.status())) continue;
            boolean due = p.reviewDue() != null && p.reviewDue().isBefore(today);
            Usage u = usage(p.code());
            LocalDateTime since = u.lastUsedAt() != null ? u.lastUsedAt() : p.installedAt();
            boolean idle = since == null || since.isBefore(today.minusDays(IDLE_DAYS).atStartOfDay());
            String next = due && idle ? "SUGGEST_RETIRE" : due ? "REVIEW_DUE" : "OK";
            jdbc.update("update meta_pack set review_checked_at = now() where code = ?", p.code());
            if (next.equals(p.reviewStatus())) continue;
            boolean notify = !"OK".equals(next) && p.ownerParty() != null;
            if (notify) {
                String msg = "SUGGEST_RETIRE".equals(next)
                    ? "Pack「" + p.name() + "」已过复审日期 " + p.reviewDue() + " 且 " + IDLE_DAYS + " 天无使用，建议下线"
                    : "Pack「" + p.name() + "」已到复审日期 " + p.reviewDue() + "，请复审";
                jdbc.update("insert into biz_flow_notice (kind, party_id, biz_type, biz_id, message) values ('PACK_REVIEW', ?, 'ls.pack', ?, ?)",
                    p.ownerParty(), p.code(), msg);
            }
            jdbc.update("update meta_pack set review_status = ?, review_notified_at = case when ? then now() else review_notified_at end where code = ?",
                next, notify, p.code());
            changes.add(new Change(p.code(), p.reviewStatus(), next, notify));
        }
        return changes;
    }
}
