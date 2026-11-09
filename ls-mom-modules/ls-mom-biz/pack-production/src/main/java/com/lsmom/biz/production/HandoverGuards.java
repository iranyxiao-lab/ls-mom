package com.lsmom.biz.production;

import com.lsmom.meta.concept.ManagedRecords;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.guard.Guard;
import com.lsmom.action.guard.GuardResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Objects;

/** 交接班守卫：草稿只能由交班人操作；接班确认须由接班班组（或其上级组织）的成员完成，且不能是交班人本人。 */
final class HandoverGuards {

    static final String OWNER = "ls.production.handoverOwner";
    static final String RECEIVER = "ls.production.handoverReceiver";

    private HandoverGuards() {
    }

    static Guard owner(ManagedRecords records) {
        return guard(OWNER, ctx -> {
            Map<String, Object> h = records.row(ProductionPack.HANDOVER, ctx.targetIdAsLong());
            if (Objects.equals(toLong(h.get("createdBy")), ctx.actor().effectiveParty())) return GuardResult.pass();
            return GuardResult.block("HANDOVER_NOT_OWNER", "只有交班人可以修改或确认交接班草稿");
        });
    }

    static Guard receiver(ManagedRecords records, JdbcTemplate jdbc) {
        return guard(RECEIVER, ctx -> {
            Map<String, Object> h = records.row(ProductionPack.HANDOVER, ctx.targetIdAsLong());
            Long party = ctx.actor().effectiveParty();
            if (Objects.equals(toLong(h.get("createdBy")), party)) {
                return GuardResult.block("HANDOVER_SELF_ACCEPT", "交班人不能自己接班，请由接班班组确认");
            }
            if (ctx.actor().superAdmin()) return GuardResult.pass();
            Integer n = jdbc.queryForObject("""
                with recursive up(id) as (select cast(? as bigint) union all select o.parent_id from core_org_unit o join up on o.id = up.id
                                          where o.parent_id is not null)
                select count(*) from core_party_org po where po.party_id = ? and po.org_unit_id in (select id from up)
                   and (po.valid_to is null or po.valid_to >= current_date)""", Integer.class, toLong(h.get("toTeam")), party);
            if (n != null && n > 0) return GuardResult.pass();
            return GuardResult.block("HANDOVER_NOT_RECEIVER", "只有接班班组或其上级组织的人员可以接班");
        });
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    interface Check {
        GuardResult check(OperationContext ctx);
    }

    private static Guard guard(String code, Check check) {
        return new Guard() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public GuardResult check(OperationContext ctx) {
                return check.check(ctx);
            }
        };
    }
}
