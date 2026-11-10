package com.lsmom.flow.runtime;

import com.lsmom.action.api.OperationContext;
import com.lsmom.action.guard.Guard;
import com.lsmom.action.guard.GuardRegistry;
import com.lsmom.action.guard.GuardResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 审批进行中的单据，流程回调 Operation（通过、驳回、退回、再提交、撤回）只能经流程办理，
 * 不能从列表按钮或接口直接调用——否则单据状态变了，流程实例和审批人的待办却还在（审批人办理时报"无权"）。
 * 撤回请用单据流程头部的"撤回"（经流程撤回，同时结束待办）。
 */
public final class FlowCallbackGuard implements Guard, GuardRegistry.GuardAttachment {

    public static final String CODE = "ls.flow.callbackViaFlow";
    private static final ThreadLocal<Boolean> VIA_FLOW = new ThreadLocal<>();
    private static final long TTL_MS = 10_000;

    private final JdbcTemplate jdbc;
    private final Map<String, long[]> cache = new ConcurrentHashMap<>();

    public FlowCallbackGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 流程引擎调用业务 Operation 时包一层，放行回调。 */
    public static <T> T viaFlow(Supplier<T> body) {
        Boolean prev = VIA_FLOW.get();
        VIA_FLOW.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            if (prev == null) VIA_FLOW.remove();
            else VIA_FLOW.set(prev);
        }
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<String> guardsFor(String operationCode) {
        long now = System.currentTimeMillis();
        long[] hit = cache.get(operationCode);
        if (hit == null || now - hit[1] > TTL_MS) {
            Boolean callback = jdbc.queryForObject("""
                select exists (select 1 from meta_flow f, jsonb_each_text(f.definition::jsonb -> 'callbacks') c
                                where f.kind = 'APPROVAL' and f.status in ('PUBLISHED', 'RETIRED') and c.value = ?)""", Boolean.class, operationCode);
            hit = new long[]{Boolean.TRUE.equals(callback) ? 1 : 0, now};
            cache.put(operationCode, hit);
        }
        return hit[0] == 1 ? List.of(CODE) : List.of();
    }

    @Override
    public GuardResult check(OperationContext ctx) {
        if (Boolean.TRUE.equals(VIA_FLOW.get()) || NodeContext.get() != null || ctx.targetId() == null) return GuardResult.pass();
        Integer n = jdbc.queryForObject("select count(*) from biz_flow_instance where biz_type = ? and biz_id = ? and status = 'RUNNING'",
            Integer.class, ctx.operation().concept(), ctx.targetId());
        if (n != null && n > 0) {
            return GuardResult.block("FLOW_RUNNING", "单据正在审批中，请在单据的流程区域办理或撤回", Map.of("operation", ctx.operation().code()));
        }
        return GuardResult.pass();
    }
}
