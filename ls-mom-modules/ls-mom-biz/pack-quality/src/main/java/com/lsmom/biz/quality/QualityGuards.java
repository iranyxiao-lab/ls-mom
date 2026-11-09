package com.lsmom.biz.quality;

import com.lsmom.action.api.OperationContext;
import com.lsmom.action.guard.Guard;
import com.lsmom.action.guard.GuardResult;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.issue.IssueService;
import com.lsmom.core.work.WorkItem;
import com.lsmom.meta.concept.ManagedRecords;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.function.Function;

/** 质量 Pack 的 Java 守卫。 */
public final class QualityGuards {

    private QualityGuards() {
    }

    /** 整改工作全部完成（取消的不计）。 */
    public static Guard rectificationDone(ManagedRecords records, IssueService issues) {
        return guard(QualityPack.GUARD_RECTIFIED, ctx -> {
            Long issue = issueOf(records, ctx);
            if (issue == null) return GuardResult.block("NC_NO_ISSUE", "不符合项尚未登记");
            try {
                issues.requireRectificationsDone(issue);
                return GuardResult.pass();
            } catch (LsBizException e) {
                return GuardResult.block(e.getCode(), e.getMessage(), e.getDetails());
            }
        });
    }

    /**
     * 整改证据：整改工作（或其填报）、不符合项本身上至少一个用途为 EVIDENCE 的附件，或整改工作 / 不符合项上的观察记录。
     */
    public static Guard evidenceRequired(ManagedRecords records, IssueService issues, JdbcTemplate jdbc) {
        return guard(QualityPack.GUARD_EVIDENCE, ctx -> {
            Long issue = issueOf(records, ctx);
            if (issue == null) return GuardResult.block("NC_NO_ISSUE", "不符合项尚未登记");
            List<String> works = issues.rectifications(issue).stream().map(WorkItem::id).map(String::valueOf).toList();
            String nc = ctx.targetId();
            String[] ids = works.toArray(String[]::new);
            Integer docs = jdbc.queryForObject("""
                select count(*) from core_document_link d
                 where d.removed_at is null and d.purpose = 'EVIDENCE'
                   and ((d.object_type in ('ls.core.Work', 'WORK') and d.object_id = any(?))
                     or (d.object_type = 'ls.core.WorkReport' and d.object_id in (select id::text from core_work_report where work_item_id::text = any(?)))
                     or (d.object_type = ? and d.object_id = ?))""", Integer.class, ids, ids, QualityPack.NC, nc);
            Integer obs = jdbc.queryForObject("""
                select count(*) from core_observation o
                 where (o.object_type in ('ls.core.Work', 'WORK') and o.object_id = any(?)) or (o.object_type = ? and o.object_id = ?)""",
                Integer.class, ids, QualityPack.NC, nc);
            if ((docs == null ? 0 : docs) + (obs == null ? 0 : obs) > 0) return GuardResult.pass();
            return GuardResult.block("EVIDENCE_REQUIRED", "整改还没有任何证据，请补充附件或观察记录后再提交验证");
        });
    }

    /** 约束 Profile 要求分离时，验证人不能是唯一的整改执行人。 */
    public static Guard verifierSeparation(ManagedRecords records, IssueService issues) {
        return guard(QualityPack.GUARD_VERIFIER, ctx -> {
            Long issue = issueOf(records, ctx);
            Long party = ctx.actor().effectiveParty();
            if (issue == null || party == null) return GuardResult.pass();
            try {
                issues.requireVerifierSeparation(issue, party);
                return GuardResult.pass();
            } catch (LsBizException e) {
                return GuardResult.block(e.getCode(), e.getMessage());
            }
        });
    }

    private static Long issueOf(ManagedRecords records, OperationContext ctx) {
        Object issue = records.row(QualityPack.NC, ctx.targetIdAsLong()).get("issue");
        return issue == null ? null : QualityEffects.num(issue);
    }

    static Guard guard(String code, Function<OperationContext, GuardResult> fn) {
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
