package com.lsmom.action.core;

import com.lsmom.action.api.Exposure;
import com.lsmom.action.api.LsOperation;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.api.OperationHandler;
import com.lsmom.action.api.Risk;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkItemService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 示例：按 docs/operation-gateway.md「新增一个 Operation」步骤实现的调整工作优先级。
 */
@Component
@LsOperation(code = "ls.core.Work.reprioritize", concept = "ls.core.Work", name = "调整优先级", risk = Risk.C1,
    exposure = Exposure.DRAFT, permission = "lsmom:work:edit", requiresVersion = true, inputSchema = """
    {"type":"object","required":["priority"],"properties":{"priority":{"type":"integer","minimum":1,"maximum":5},
      "reason":{"type":"string","maxLength":200}}}""")
public class ReprioritizeWork implements OperationHandler {

    private final WorkItemService works;

    public ReprioritizeWork(WorkItemService works) {
        this.works = works;
    }

    @Override
    public Object handle(OperationContext ctx) {
        int priority = ctx.input().get("priority").asInt();
        WorkItem before = works.get(ctx.targetIdAsLong());
        WorkItem after = works.changePriority(before.id(), ctx.targetVersion(), priority);
        ctx.target(String.valueOf(after.id()), after.version(), after.status().name(), after.orgUnitId());
        ctx.eventPayload().put("priority", Map.of("from", before.priority(), "to", after.priority()));
        if (ctx.text("reason") != null) ctx.eventPayload().put("reason", ctx.text("reason"));
        return Map.of("priority", after.priority());
    }
}
