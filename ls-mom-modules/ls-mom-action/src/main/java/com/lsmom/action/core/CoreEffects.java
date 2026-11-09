package com.lsmom.action.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.state.StateSnapshot;
import com.lsmom.action.state.TransitionEffect;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueService;
import com.lsmom.core.work.Assignment;
import com.lsmom.core.work.AssignmentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** 核心迁移的业务效果。 */
@Configuration(proxyBeanMethods = false)
public class CoreEffects {

    @Bean
    public TransitionEffect workAssignEffect(AssignmentService assignments) {
        return effect("ls.core.Work.assign", (ctx, before) -> {
            List<AssignmentService.AssignTarget> targets = new ArrayList<>();
            for (JsonNode t : ctx.input().get("targets")) {
                targets.add(new AssignmentService.AssignTarget(Assignment.ResourceType.valueOf(t.get("resourceType").asText()),
                    t.get("resourceRef").asText(), t.hasNonNull("role") ? t.get("role").asText() : null,
                    time(t, "plannedStart"), time(t, "plannedEnd")));
            }
            AssignmentService.AssignResult r = assignments.assign(Long.parseLong(before.id()), targets, ctx.actor().effectiveParty());
            r.conflicts().forEach(c -> ctx.warn("资源时间冲突：" + c.workCode() + " " + c.workTitle()));
            ctx.eventPayload().put("targets", targets.stream().map(t -> t.resourceType() + ":" + t.resourceRef()).toList());
            if (r.hasWarnings()) ctx.eventPayload().put("conflicts", r.conflicts().stream().map(AssignmentService.Conflict::workCode).toList());
            return Map.of();
        });
    }

    @Bean
    public TransitionEffect workStartEffect(Clock clock) {
        return effect("ls.core.Work.start", (ctx, before) -> Map.of("actual_start", LocalDateTime.now(clock)));
    }

    @Bean
    public TransitionEffect workBlockEffect() {
        return effect("ls.core.Work.block", (ctx, before) -> {
            ctx.eventPayload().put("reason", ctx.text("reason"));
            return Map.of("block_reason", ctx.text("reason"));
        });
    }

    @Bean
    public TransitionEffect workUnblockEffect() {
        return effect("ls.core.Work.unblock", (ctx, before) -> {
            if (ctx.text("reason") != null) ctx.eventPayload().put("reason", ctx.text("reason"));
            Map<String, Object> m = new HashMap<>();
            m.put("block_reason", null);
            return m;
        });
    }

    @Bean
    public TransitionEffect workConfirmEffect(Clock clock) {
        return effect("ls.core.Work.confirm", (ctx, before) -> Map.of("actual_end", LocalDateTime.now(clock), "progress", 100));
    }

    @Bean
    public TransitionEffect workReasonEffects() {
        return effect("ls.core.Work.rejectConfirm", (ctx, before) -> {
            ctx.eventPayload().put("reason", ctx.text("reason"));
            return Map.of();
        });
    }

    @Bean
    public TransitionEffect workCancelEffect(AssignmentService assignments) {
        return effect("ls.core.Work.cancel", (ctx, before) -> {
            ctx.eventPayload().put("reason", ctx.text("reason"));
            assignments.release(Long.parseLong(before.id()));
            return Map.of();
        });
    }

    @Bean
    public TransitionEffect issueStartRectificationEffect() {
        return effect("ls.core.Issue.startRectification", (ctx, before) -> {
            Map<String, Object> m = new HashMap<>();
            if (ctx.longValue("ownerParty") != null) m.put("owner_party", ctx.longValue("ownerParty"));
            if (ctx.text("dueAt") != null) m.put("due_at", LocalDateTime.parse(ctx.text("dueAt")));
            return m;
        });
    }

    @Bean
    public TransitionEffect issueVerifyPassEffect(Clock clock) {
        return effect("ls.core.Issue.verifyPass", (ctx, before) -> {
            ctx.eventPayload().put("verifier", ctx.actor().effectiveParty());
            ctx.eventPayload().put("conclusion", ctx.text("conclusion"));
            Map<String, Object> m = new HashMap<>();
            m.put("verified_by", ctx.actor().effectiveParty());
            m.put("verify_conclusion", ctx.text("conclusion"));
            m.put("verified_at", LocalDateTime.now(clock));
            return m;
        });
    }

    /** 验证不通过：回到整改中，并通知整改负责人。 */
    @Bean
    public TransitionEffect issueVerifyFailEffect(IssueService issues) {
        return effect("ls.core.Issue.verifyFail", (ctx, before) -> {
            Issue i = issues.get(Long.parseLong(before.id()));
            ctx.eventPayload().put("verifier", ctx.actor().effectiveParty());
            ctx.eventPayload().put("conclusion", ctx.text("conclusion"));
            if (i.ownerParty() != null) {
                ctx.emit("ls.core.Issue.rectificationReturned", "ls.core.Issue", before.id(), i.orgUnitId(),
                    Map.of("notify", List.of(i.ownerParty()), "title", "整改验证未通过：" + i.title(), "conclusion", ctx.text("conclusion")));
            } else {
                ctx.warn("该 Issue 没有整改负责人，无法通知");
            }
            return Map.of("verify_conclusion", ctx.text("conclusion"));
        });
    }

    @Bean
    public TransitionEffect issueRejectEffect() {
        return effect("ls.core.Issue.reject", (ctx, before) -> {
            ctx.eventPayload().put("reason", ctx.text("reason"));
            return Map.of();
        });
    }

    private static LocalDateTime time(JsonNode n, String f) {
        return n.hasNonNull(f) ? LocalDateTime.parse(n.get(f).asText()) : null;
    }

    static TransitionEffect effect(String op, BiFunction<OperationContext, StateSnapshot, Map<String, Object>> fn) {
        return new TransitionEffect() {
            @Override
            public String operation() {
                return op;
            }

            @Override
            public Map<String, Object> apply(OperationContext ctx, StateSnapshot before) {
                return fn.apply(ctx, before);
            }
        };
    }
}
