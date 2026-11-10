package com.lsmom.action.core;

import com.lsmom.action.api.OperationContext;
import com.lsmom.action.guard.Guard;
import com.lsmom.action.guard.GuardResult;
import com.lsmom.core.document.DocumentService;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/** 核心对象的 Java 守卫（L3 跨对象约束）。 */
@Configuration(proxyBeanMethods = false)
public class CoreGuards {

    public static final String EVIDENCE_REQUIRED = "ls.core.Issue.evidenceRequired";
    public static final String RECTIFICATION_DONE = "ls.core.Issue.rectificationDone";
    public static final String VERIFIER_SEPARATION = "ls.core.Issue.verifierSeparation";

    /** 高、严重级别的 Issue 提交时必须附证据（用途 EVIDENCE 的附件；DCS 报警来源以报警事件引用为证据）。 */
    @Bean
    public Guard issueEvidenceRequired(IssueService issues, DocumentService docs) {
        return guard(EVIDENCE_REQUIRED, ctx -> {
            Issue i = issues.get(ctx.targetIdAsLong());
            if (!List.of("HIGH", "CRITICAL").contains(i.severity())) return GuardResult.pass();
            // DCS 报警生成的问题：原始报警事件引用即为证据
            if ("DCS".equals(i.sourceType()) && issues.links(i.id(), null).stream().anyMatch(r -> "DCS_ALARM".equals(r.objectType()))) {
                return GuardResult.pass();
            }
            boolean hasEvidence = docs.listByObject("ISSUE", String.valueOf(i.id())).stream().anyMatch(d -> "EVIDENCE".equals(d.purpose()));
            return hasEvidence ? GuardResult.pass()
                : GuardResult.block("EVIDENCE_REQUIRED", "严重度为 " + i.severity() + " 的异常提交前必须上传现场证据", Map.of(
                    "why", "高、严重级别的异常需要现场照片或检测记录作为依据，便于后续判定与追溯。",
                    "fix", List.of(Map.of("label", "上传现场证据", "action", "upload-evidence"))));
        });
    }

    @Bean
    public Guard issueRectificationDone(IssueService issues) {
        return guard(RECTIFICATION_DONE, ctx -> {
            try {
                issues.requireRectificationsDone(ctx.targetIdAsLong());
                return GuardResult.pass();
            } catch (LsBizException e) {
                Map<String, Object> d = new java.util.LinkedHashMap<>(e.getDetails());
                d.put("why", "整改工作全部完成（或取消）后才能提交验证。");
                d.put("fix", List.of(Map.of("label", "查看整改工作", "action", "show-rectifications")));
                return GuardResult.block(e.getCode(), e.getMessage(), d);
            }
        });
    }

    @Bean
    public Guard issueVerifierSeparation(IssueService issues) {
        return guard(VERIFIER_SEPARATION, ctx -> {
            try {
                issues.requireVerifierSeparation(ctx.targetIdAsLong(), ctx.actor().effectiveParty());
                return GuardResult.pass();
            } catch (LsBizException e) {
                return GuardResult.block(e.getCode(), e.getMessage(), Map.of(
                    "why", "该约束配置要求验证与整改分离：唯一的整改执行人不能自己验证。",
                    "fix", List.of(Map.of("label", "请他人验证", "action", "ask-verifier"))));
            }
        });
    }

    static Guard guard(String code, java.util.function.Function<OperationContext, GuardResult> fn) {
        return new Guard() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public GuardResult check(OperationContext ctx) {
                return fn.apply(ctx);
            }
        };
    }
}
