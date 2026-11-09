package com.lsmom.biz.asset;

import com.lsmom.action.api.OperationContext;
import com.lsmom.action.guard.Guard;
import com.lsmom.action.guard.GuardResult;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.issue.IssueService;
import com.lsmom.meta.concept.ManagedRecords;

import java.util.Map;
import java.util.function.Function;

/** 设备 Pack 的 Java 守卫。 */
public final class AssetGuards {

    private AssetGuards() {
    }

    /** 点检计划启用前：至少一个检查项、设备未报废。 */
    public static Guard planReady(ManagedRecords records) {
        return guard(AssetPack.GUARD_PLAN_READY, ctx -> {
            long id = ctx.targetIdAsLong();
            if (records.children(AssetPack.PLAN_ITEM, id).isEmpty()) return GuardResult.block("PLAN_NO_ITEMS", "点检计划至少需要一个检查项");
            Map<String, Object> plan = records.row(AssetPack.PLAN, id);
            Object asset = records.row(AssetPack.ASSET, AssetEffects.num(plan.get("asset"))).get("status");
            if ("SCRAPPED".equals(asset)) return GuardResult.block("ASSET_SCRAPPED", "设备已报废");
            return GuardResult.pass();
        });
    }

    /** 维修工作全部完成（取消的不计）才能提交验证。 */
    public static Guard repairsDone(ManagedRecords records, IssueService issues) {
        return guard(AssetPack.GUARD_REPAIRS_DONE, ctx -> {
            Object issue = records.row(AssetPack.DEFECT, ctx.targetIdAsLong()).get("issue");
            if (issue == null) return GuardResult.block("DEFECT_NO_ISSUE", "缺陷尚未登记");
            if (issues.rectifications(AssetEffects.num(issue)).isEmpty()) return GuardResult.block("NO_REPAIR_WORK", "还没有维修工作");
            try {
                issues.requireRectificationsDone(AssetEffects.num(issue));
                return GuardResult.pass();
            } catch (LsBizException e) {
                return GuardResult.block(e.getCode(), e.getMessage().replace("整改", "维修"), e.getDetails());
            }
        });
    }

    /** 约束 Profile 要求分离时，验证人不能是唯一的维修执行人。 */
    public static Guard verifierSeparation(ManagedRecords records, IssueService issues) {
        return guard(AssetPack.GUARD_VERIFIER, ctx -> {
            Object issue = records.row(AssetPack.DEFECT, ctx.targetIdAsLong()).get("issue");
            Long party = ctx.actor().effectiveParty();
            if (issue == null || party == null) return GuardResult.pass();
            try {
                issues.requireVerifierSeparation(AssetEffects.num(issue), party);
                return GuardResult.pass();
            } catch (LsBizException e) {
                return GuardResult.block(e.getCode(), "验证人不能是唯一的维修执行人");
            }
        });
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
