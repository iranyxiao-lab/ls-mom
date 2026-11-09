package com.lsmom.action.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.guard.ConstraintLevel;
import com.lsmom.action.rule.CelRuleEngine;
import com.lsmom.action.rule.CelValues;
import com.lsmom.action.rule.RuleDefinition;
import com.lsmom.action.rule.RuleKind;
import com.lsmom.action.rule.RuleOutcome;
import com.lsmom.action.rule.RulePublisher;
import com.lsmom.action.rule.RuleService;
import com.lsmom.core.error.LsBizException;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 规则管理：列表、保存草稿、发布前校验、发布、试算（决策表/CEL 规则对给定数据求值）。 */
@RestController
@RequestMapping("/api/rule")
public class RuleController {

    private final RuleService rules;
    private final RulePublisher publisher;

    public RuleController(RuleService rules, RulePublisher publisher) {
        this.rules = rules;
        this.publisher = publisher;
    }

    @SaCheckPermission("lsmom:rule:list")
    @GetMapping
    public R<List<RuleDefinition>> list() {
        return R.ok(rules.listLatest());
    }

    /** 受限表达式环境说明（前端编辑器提示与一致性检查用）。 */
    @GetMapping("/environment")
    public R<Map<String, Object>> environment() {
        CelRuleEngine.Limits l = rules.engine().limits();
        return R.ok(Map.of("variables", CelRuleEngine.VARIABLES, "functions", CelRuleEngine.ALLOWED_FUNCTIONS.stream().sorted().toList(),
            "maxLength", l.maxLength(), "maxNodes", l.maxNodes(), "maxCost", l.maxCost(), "timeoutMillis", l.timeoutMillis()));
    }

    public record DraftBody(String code, String concept, ConstraintLevel level, RuleKind kind, String name, String message,
                            JsonNode definition, String ownerPack, String overrides) {
    }

    @SaCheckPermission("lsmom:rule:edit")
    @PostMapping
    public R<RuleDefinition> save(@RequestBody DraftBody b) {
        if (b.definition() == null || b.level() == null || b.kind() == null || b.name() == null) {
            throw LsBizException.validation(Map.of("definition", "level、kind、name、definition 必填"));
        }
        // 经接口保存的规则一律为客户规则；标准规则只随标准 Pack 安装
        return R.ok(rules.saveDraft(new RuleService.RuleDraft(b.code(), b.concept(), b.level(), b.kind(), b.name(), b.message(),
            b.definition(), b.ownerPack(), false, b.overrides())));
    }

    @SaCheckPermission("lsmom:rule:edit")
    @PostMapping("/{code}/{version}/validate")
    public R<List<Map<String, Object>>> validate(@PathVariable String code, @PathVariable int version) {
        return R.ok(publisher.validate(rules.require(code, version)));
    }

    @SaCheckPermission("lsmom:rule:publish")
    @PostMapping("/{code}/{version}/publish")
    public R<RuleDefinition> publish(@PathVariable String code, @PathVariable int version) {
        return R.ok(publisher.publish(code, version));
    }

    public record EvaluateBody(JsonNode model, JsonNode context) {
    }

    /** 对给定数据试算已发布的 CEL/决策表规则（只读，不触发任何写入）。 */
    @SaCheckPermission("lsmom:rule:list")
    @PostMapping("/{code}/evaluate")
    public R<RuleOutcome> evaluate(@PathVariable String code, @RequestBody EvaluateBody b) {
        RuleDefinition r = rules.published(code).orElseThrow(() -> LsBizException.notFound("规则", code));
        if (r.kind() == RuleKind.JAVA) throw new LsBizException("RULE_INVALID", "代码守卫规则不支持试算");
        return R.ok(rules.evaluate(r, CelValues.asMap(b.model()), CelValues.asMap(b.context())));
    }
}
