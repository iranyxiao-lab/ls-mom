package com.lsmom.flow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.guard.Guard;
import com.lsmom.action.guard.GuardResult;
import com.lsmom.action.state.StateSnapshot;
import com.lsmom.action.state.StateStores;
import com.lsmom.crud.query.LedgerModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 受控审批模式的批准证据：审批通过时记录批准人、时间、输入摘要、数据版本和业务数据摘要。
 * 守卫 {@value #GUARD} 在后续 Operation 执行前比对当前业务数据摘要，实质内容变化即判定批准失效，须重新审批。
 */
public class ApprovalEvidence {

    public static final String GUARD = "ls.flow.approvalValid";
    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .findAndRegisterModules();
    /** 不计入"实质内容"的系统字段。 */
    private static final Set<String> EXCLUDED = Set.copyOf(LedgerModel.SYSTEM_MASTER.keySet());

    public record Evidence(long id, String bizType, String bizId, String procInstId, List<Long> approvers, LocalDateTime approvedAt,
                           String inputDigest, int dataVersion, String dataDigest) {
    }

    private final JdbcTemplate jdbc;
    private final StateStores stores;

    public ApprovalEvidence(JdbcTemplate jdbc, StateStores stores) {
        this.jdbc = jdbc;
        this.stores = stores;
    }

    public void record(FlowInstances.Instance inst, List<Long> approvers, String inputDigest, LocalDateTime at) {
        StateSnapshot s = stores.of(inst.bizType()).load(inst.bizId());
        jdbc.update("""
            insert into biz_approval_evidence (biz_type, biz_id, proc_inst_id, flow_code, flow_version, approvers, approved_at, input_digest,
                                               data_version, data_digest)
            values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""", inst.bizType(), inst.bizId(), inst.procInstId(), inst.flowCode(), inst.flowVersion(),
            TaskSync.json(approvers), at, inputDigest, s.version(), digest(inst.bizType(), inst.bizId()));
    }

    public Optional<Evidence> latest(String bizType, String bizId) {
        return jdbc.query("select * from biz_approval_evidence where biz_type = ? and biz_id = ? order by id desc limit 1", (rs, i) -> {
            try {
                return new Evidence(rs.getLong("id"), rs.getString("biz_type"), rs.getString("biz_id"), rs.getString("proc_inst_id"),
                    List.of(JSON.readValue(rs.getString("approvers"), Long[].class)), rs.getObject("approved_at", LocalDateTime.class),
                    rs.getString("input_digest"), rs.getInt("data_version"), rs.getString("data_digest"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, bizType, bizId).stream().findFirst();
    }

    /** 业务数据实质内容摘要（排除状态、版本、更新时间等系统字段）。 */
    public String digest(String bizType, String bizId) {
        Map<String, Object> attrs = new TreeMap<>(stores.of(bizType).attributes(bizId));
        EXCLUDED.forEach(attrs::remove);
        return sha256(write(attrs));
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 守卫：存在批准证据且业务数据未实质变化。 */
    public Guard guard() {
        return new Guard() {
            @Override
            public String code() {
                return GUARD;
            }

            @Override
            public GuardResult check(OperationContext ctx) {
                String concept = ctx.operation().concept();
                Optional<Evidence> e = latest(concept, ctx.targetId());
                if (e.isEmpty()) return GuardResult.block("APPROVAL_REQUIRED", "该操作需要先经审批通过");
                if (!e.get().dataDigest().equals(digest(concept, ctx.targetId()))) {
                    return GuardResult.block("APPROVAL_INVALID", "批准后业务数据已被修改，原批准失效，需要重新审批",
                        Map.of("approvedAt", String.valueOf(e.get().approvedAt()), "procInstId", e.get().procInstId(),
                            "approvedVersion", e.get().dataVersion()));
                }
                return GuardResult.pass();
            }
        };
    }
}
