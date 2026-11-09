package com.lsmom.action.rule;

import com.lsmom.action.api.OperationContext;
import com.lsmom.action.guard.ConstraintLevel;
import com.lsmom.action.guard.Guard;
import com.lsmom.action.guard.GuardRegistry;
import com.lsmom.action.guard.GuardResult;
import com.lsmom.action.state.StateStores;
import com.lsmom.core.error.LsBizException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 以 {@code rule:<规则编码>} 引用已发布规则作为守卫，并把声明了 appliesTo 的规则自动附加到对应 Operation。
 * <p>按级别处理未满足的规则：L0 返回提示不阻断；L1 带 field 时按字段校验失败（400），否则阻断；
 * L2/L3 阻断（422 GUARD_FAILED，reason 为规则编码）；L4 阻断并要求人工审批（reason=APPROVAL_REQUIRED）。
 * 加严覆盖规则与被覆盖规则一起执行。后端对每次提交重新求值，不信任前端结论。
 */
@Component
public class RuleGuardProvider implements GuardRegistry.GuardProvider, GuardRegistry.GuardAttachment {

    public static final String PREFIX = "rule:";

    private final RuleService rules;
    private final StateStores stores;
    private final Map<String, RuleFactProvider> facts = new LinkedHashMap<>();
    private final Clock clock;
    private GuardRegistry registry;

    public RuleGuardProvider(RuleService rules, StateStores stores, List<RuleFactProvider> factProviders, Clock clock) {
        this.rules = rules;
        this.stores = stores;
        factProviders.forEach(f -> facts.put(f.name(), f));
        this.clock = clock;
    }

    @Override
    public void bind(GuardRegistry registry) {
        this.registry = registry;
    }

    public boolean hasFact(String name) {
        return facts.containsKey(name);
    }

    @Override
    public boolean supports(String code) {
        return code.startsWith(PREFIX);
    }

    @Override
    public List<String> guardsFor(String operationCode) {
        return rules.appliesTo(operationCode).stream().map(c -> PREFIX + c).toList();
    }

    @Override
    public Guard resolve(String code) {
        String ruleCode = code.substring(PREFIX.length());
        return new Guard() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public ConstraintLevel level() {
                return rules.published(ruleCode).map(RuleDefinition::level).orElse(ConstraintLevel.L2);
            }

            @Override
            public GuardResult check(OperationContext ctx) {
                RuleDefinition base = rules.published(ruleCode)
                    .orElseThrow(() -> new LsBizException("GUARD_NOT_FOUND", "规则未发布：" + ruleCode, Map.of("guard", code)));
                List<RuleDefinition> all = new ArrayList<>();
                all.add(base);
                all.addAll(rules.overridesOf(ruleCode));
                Map<String, Object> model = model(ctx);
                for (RuleDefinition r : all) {
                    if (r.disabled()) continue;
                    RuleOutcome o = r.kind() == RuleKind.JAVA ? java(r, ctx) : rules.evaluate(r, model, context(ctx, model, r));
                    if (!o.violated()) continue;
                    GuardResult result = apply(o, ctx);
                    if (result != null) return result;
                }
                return GuardResult.pass();
            }
        };
    }

    private RuleOutcome java(RuleDefinition r, OperationContext ctx) {
        String guard = r.text("guard");
        Guard g = registry == null ? null : registry.registered(guard).orElse(null);
        if (g == null) throw new LsBizException("GUARD_NOT_FOUND", "规则引用的代码守卫未注册：" + guard, Map.of("guard", guard));
        GuardResult gr = g.check(ctx);
        return switch (gr.outcome()) {
            case PASS -> RuleOutcome.pass(r, Map.of());
            case WARN -> new RuleOutcome(r.code(), ConstraintLevel.L0, true, gr.message(), null, Map.of(), null);
            case BLOCK -> new RuleOutcome(r.code(), r.level(), true, gr.message(), null, gr.details(), gr.code());
        };
    }

    /** 返回 null 表示只提示、继续执行。 */
    private static GuardResult apply(RuleOutcome o, OperationContext ctx) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rule", o.rule());
        details.put("level", o.level().name());
        if (o.error() != null) details.put("ruleError", o.error());
        if (o.output() != null && !o.output().isEmpty()) details.put("output", o.output());
        return switch (o.level()) {
            case L0 -> {
                ctx.warn(o.message());
                yield null;
            }
            case L1 -> {
                if (o.field() != null) {
                    throw new LsBizException("VALIDATION_FAILED", "输入校验失败", Map.of("fields", Map.of(o.field(), o.message()), "rule", o.rule()));
                }
                yield GuardResult.block(o.rule(), o.message(), details);
            }
            case L2, L3 -> GuardResult.block(o.rule(), o.message(), details);
            case L4 -> GuardResult.block("APPROVAL_REQUIRED", o.message() + "（需人工审批）", details);
        };
    }

    /** $model：目标对象字段叠加本次输入（输入优先）。 */
    Map<String, Object> model(OperationContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (ctx.targetId() != null && stores.has(ctx.operation().concept())) {
            m.putAll(stores.of(ctx.operation().concept()).attributes(ctx.targetId()));
        }
        if (ctx.input() != null && ctx.input().isObject()) m.putAll(CelValues.asMap(ctx.input()));
        return m;
    }

    /** $context：调用主体、当前时间、Operation、目标状态与规则声明的事实。 */
    Map<String, Object> context(OperationContext ctx, Map<String, Object> model, RuleDefinition r) {
        Map<String, Object> c = new LinkedHashMap<>();
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("partyId", ctx.actor().effectiveParty());
        actor.put("userId", ctx.actor().userId());
        actor.put("type", ctx.actor().type().name());
        actor.put("name", ctx.actor().displayName());
        c.put("actor", CelValues.fromJava(actor));
        c.put("now", LocalDateTime.now(clock).toString());
        c.put("operation", ctx.operation().code());
        c.put("targetStatus", ctx.targetStatus());
        for (String f : r.facts()) {
            RuleFactProvider p = facts.get(f);
            if (p == null) throw new LsBizException("RULE_EVAL_FAILED", "规则引用的事实未注册：" + f, Map.of("fact", f));
            c.put(f, CelValues.fromJava(p.facts(ctx, model)));
        }
        return c;
    }
}
