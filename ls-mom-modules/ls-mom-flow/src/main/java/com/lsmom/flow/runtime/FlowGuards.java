package com.lsmom.flow.runtime;

import com.lsmom.action.api.OperationContext;
import com.lsmom.action.guard.Guard;
import com.lsmom.action.guard.GuardResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/** 流程相关守卫，供业务 Operation（撤回、退回等）在状态机迁移中引用。 */
public final class FlowGuards {

    /** 撤回合法性：进行中的流程尚未被发起人以外的人办理过。 */
    public static final String NOT_HANDLED = "ls.flow.notHandled";

    private FlowGuards() {
    }

    public static Guard notHandled(JdbcTemplate jdbc) {
        return new Guard() {
            @Override
            public String code() {
                return NOT_HANDLED;
            }

            @Override
            public GuardResult check(OperationContext ctx) {
                Integer n = jdbc.queryForObject("""
                    select count(*) from biz_task t join biz_flow_instance i on i.id = t.flow_instance_id
                    where i.biz_type = ? and i.biz_id = ? and i.status = 'RUNNING' and t.status = 'DONE'
                      and t.kind in ('TASK', 'EXCEPTION') and t.node_key <> 'initiator'
                      and (i.initiator_party is null or t.completed_by is distinct from i.initiator_party)""", Integer.class,
                    ctx.operation().concept(), ctx.targetId());
                if (n != null && n > 0) {
                    return GuardResult.block("FLOW_ALREADY_HANDLED", "已有审核人办理，不能撤回", Map.of("handledTasks", n));
                }
                return GuardResult.pass();
            }
        };
    }
}
