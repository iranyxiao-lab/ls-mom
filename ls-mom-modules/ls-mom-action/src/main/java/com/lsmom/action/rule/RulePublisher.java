package com.lsmom.action.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.guard.GuardRegistry;
import com.lsmom.core.error.LsBizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则发布校验：
 * <ul>
 *   <li>CEL 与决策表条件全部通过受限环境静态校验（非白名单函数指出函数名，超长、超复杂拒绝）；</li>
 *   <li>JAVA 规则只能引用已注册的代码守卫（配置中不内嵌代码），级别须为 L3/L4；</li>
 *   <li>引用的事实提供者必须已注册；</li>
 *   <li>加严不放宽：客户/Pack 规则不能把标准阻断规则关闭或降为提示——无论以覆盖（overrides）还是同编码新版本发布。</li>
 * </ul>
 */
@Service
public class RulePublisher {

    private final RuleService rules;
    private final GuardRegistry guards;
    private final RuleGuardProvider provider;

    public RulePublisher(RuleService rules, GuardRegistry guards, RuleGuardProvider provider) {
        this.rules = rules;
        this.guards = guards;
        this.provider = provider;
    }

    /** 返回问题列表；空表示可发布。 */
    public List<Map<String, Object>> validate(RuleDefinition r) {
        List<Map<String, Object>> issues = new ArrayList<>();
        CelRuleEngine cel = rules.engine();
        switch (r.kind()) {
            case CEL -> {
                if (r.text("assert") == null) issues.add(issue("RULE_INVALID", "缺少 assert 条件", Map.of()));
                else issues.addAll(cel.validate(r.text("assert"), true));
                if (r.text("when") != null) issues.addAll(cel.validate(r.text("when"), true));
            }
            case DECISION_TABLE -> {
                String policy = r.definition().path("hitPolicy").asText("FIRST");
                if (!List.of("FIRST", "ALL").contains(policy)) issues.add(issue("RULE_INVALID", "命中策略只能是 FIRST 或 ALL", Map.of()));
                JsonNode rows = r.definition().path("rows");
                if (!rows.isArray() || rows.isEmpty()) issues.add(issue("RULE_INVALID", "决策表至少需要一行", Map.of()));
                int i = 0;
                for (JsonNode row : rows) {
                    for (Map<String, Object> x : cel.validate(row.path("when").asText(""), true)) {
                        Map<String, Object> m = new LinkedHashMap<>(x);
                        m.put("row", i);
                        issues.add(m);
                    }
                    String outcome = row.path("output").path("outcome").asText("PASS");
                    if (!List.of("PASS", "WARN", "BLOCK").contains(outcome)) {
                        issues.add(issue("RULE_INVALID", "第 " + (i + 1) + " 行 outcome 只能是 PASS/WARN/BLOCK", Map.of("row", i)));
                    }
                    i++;
                }
            }
            case JAVA -> {
                String g = r.text("guard");
                if (g == null || guards.registered(g).isEmpty()) {
                    issues.add(issue("GUARD_NOT_FOUND", "代码守卫未注册：" + g, Map.of("guard", String.valueOf(g))));
                }
                if (r.level().ordinal() < 3) issues.add(issue("RULE_INVALID", "代码守卫规则的级别应为 L3 或 L4", Map.of()));
                if (r.definition().has("code") || r.definition().has("script")) {
                    issues.add(issue("RULE_INVALID", "配置中不能内嵌代码，请引用经评审注册的代码守卫", Map.of()));
                }
            }
        }
        for (String f : r.facts()) {
            if (!provider.hasFact(f)) issues.add(issue("RULE_INVALID", "事实提供者未注册：" + f, Map.of("fact", f)));
        }
        issues.addAll(strictness(r));
        return issues;
    }

    private List<Map<String, Object>> strictness(RuleDefinition r) {
        List<Map<String, Object>> issues = new ArrayList<>();
        if (r.overrides() != null) {
            RuleDefinition base = rules.published(r.overrides()).orElse(null);
            if (base == null) {
                issues.add(issue("RULE_INVALID", "被覆盖的规则未发布：" + r.overrides(), Map.of("overrides", r.overrides())));
            } else {
                relaxation(base, r).ifPresent(issues::add);
            }
        }
        rules.published(r.code()).filter(p -> p.standard() && !r.standard()).flatMap(p -> relaxation(p, r)).ifPresent(issues::add);
        return issues;
    }

    private static java.util.Optional<Map<String, Object>> relaxation(RuleDefinition base, RuleDefinition r) {
        if (!base.level().blocking()) return java.util.Optional.empty();
        if (r.disabled()) {
            return java.util.Optional.of(issue("RULE_RELAXATION_FORBIDDEN", "不能关闭标准阻断规则「" + base.name() + "」，只能加严",
                Map.of("standardRule", base.code(), "standardLevel", base.level().name())));
        }
        if (r.level().strictness() < base.level().strictness()) {
            return java.util.Optional.of(issue("RULE_RELAXATION_FORBIDDEN",
                "不能把标准阻断规则「" + base.name() + "」（" + base.level() + "）放宽为 " + r.level() + " 提示，只能加严",
                Map.of("standardRule", base.code(), "standardLevel", base.level().name(), "level", r.level().name())));
        }
        return java.util.Optional.empty();
    }

    private static Map<String, Object> issue(String code, String message, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>(extra);
        m.put("errorCode", code);
        m.put("message", message);
        return m;
    }

    /** 校验通过才发布（同编码旧版本退役）；否则抛 RULE_PUBLISH_REJECTED，details.issues 列出全部问题。 */
    @Transactional
    public RuleDefinition publish(String code, int version) {
        RuleDefinition r = rules.require(code, version);
        List<Map<String, Object>> issues = validate(r);
        if (!issues.isEmpty()) {
            String first = String.valueOf(issues.get(0).get("message"));
            String errorCode = issues.stream().anyMatch(i -> "RULE_RELAXATION_FORBIDDEN".equals(i.get("errorCode")))
                ? "RULE_RELAXATION_FORBIDDEN" : "RULE_PUBLISH_REJECTED";
            throw new LsBizException(errorCode, "规则发布校验失败：" + first, Map.of("rule", code, "version", version, "issues", issues));
        }
        rules.markPublished(r);
        return rules.require(code, version);
    }

    /** 保存草稿并立即发布（Pack 安装与核心标准规则使用）。 */
    @Transactional
    public RuleDefinition install(RuleService.RuleDraft draft) {
        RuleDefinition d = rules.saveDraft(draft);
        return publish(d.code(), d.version());
    }
}
