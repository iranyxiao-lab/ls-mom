package com.lsmom.action.rule;

import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.api.OperationResult;
import com.lsmom.action.guard.ConstraintLevel;
import com.lsmom.action.guard.DualSignGuard;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.party.Party;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 6.1/6.3/6.4：规则发布校验、L0～L4 约束级别、决策表、代码守卫插件、加严不放宽。 */
class RuleGuardTest {

    /** 测试用资质事实：资质过期的人员。 */
    static final Set<String> EXPIRED = ConcurrentHashMap.newKeySet();

    static final RuleFactProvider QUALIFICATION = new RuleFactProvider() {
        @Override
        public String name() {
            return "qualification";
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object facts(OperationContext ctx, Map<String, Object> model) {
            List<String> expired = new ArrayList<>();
            for (Object t : (List<Object>) model.getOrDefault("targets", List.of())) {
                String ref = String.valueOf(((Map<String, Object>) t).get("resourceRef"));
                if (EXPIRED.contains(ref)) expired.add(ref);
            }
            return Map.of("expired", expired);
        }
    };

    private final ActionFixture f = new ActionFixture(List.of(), List.of(QUALIFICATION));
    private final ActorContext admin = ActionFixture.admin();
    private final List<String> installed = new ArrayList<>();

    @AfterEach
    void retire() {
        // 共享数据库：测试规则用完即退役，避免影响其他测试
        installed.forEach(f.rules::retire);
    }

    private RuleDefinition install(String code, ConstraintLevel level, RuleKind kind, String name, String message, String definition,
                                   boolean standard, String overrides) {
        RuleDefinition r = f.rulePublisher.install(new RuleService.RuleDraft(code, "ls.core.Work", level, kind, name, message,
            json(definition), standard ? "ls.std" : "customer", standard, overrides));
        installed.add(code);
        return r;
    }

    private static String unique(String base) {
        return base + Long.toString(System.nanoTime(), 36);
    }

    @Test
    void L0提示_提交工作未填计划结束时间时成功并返回提示() {
        CoreFixture.Plant p = f.core.plant();
        OperationResult r = f.op("ls.core.Work.create", admin,
            "{\"workType\":\"PRODUCTION\",\"title\":\"无计划结束\",\"orgUnitId\":" + p.teamA().id() + "}");
        assertThat(r.targetId()).isNotNull();
        assertThat(r.warnings()).contains("未填写计划结束时间，建议补充以便排程与超期提醒");

        OperationResult ok = f.op("ls.core.Work.create", admin, "{\"workType\":\"PRODUCTION\",\"title\":\"有计划\",\"orgUnitId\":"
            + p.teamA().id() + ",\"plannedEnd\":\"2026-12-01T18:00:00\"}");
        assertThat(ok.warnings()).doesNotContain("未填写计划结束时间，建议补充以便排程与超期提醒");
        // 迁移类 Operation：$model 取目标对象字段
        OperationResult ready = f.step("ls.core.Work.ready", admin, "ls.core.Work", r.targetId(), "{}");
        assertThat(ready.warnings()).contains("未填写计划结束时间，建议补充以便排程与超期提醒");
    }

    @Test
    void L2阻断_高风险工作分派给资质过期人员被拒绝() {
        String code = unique("test.work.assigneeQualified");
        install(code, ConstraintLevel.L2, RuleKind.CEL, "分派人员资质有效", "被分派人员资质已过期，不能承担高风险工作", """
            {"when": "$model.priority >= 5", "assert": "size($context.qualification.expired) == 0",
             "appliesTo": ["ls.core.Work.assign"], "facts": ["qualification"]}""", false, null);
        CoreFixture.Plant p = f.core.plant();
        Party expired = f.core.employee("expired", p.teamA());
        Party valid = f.core.employee("valid", p.teamA());
        EXPIRED.add(String.valueOf(expired.id()));

        String risky = f.newWork(admin, p.teamA(), "高风险动火作业");
        f.on("ls.core.Work.reprioritize", admin, risky, f.core.works.get(Long.parseLong(risky)).version(), "{\"priority\":5}");
        f.step("ls.core.Work.ready", admin, "ls.core.Work", risky, "{}");
        String assignExpired = "{\"targets\":[{\"resourceType\":\"PARTY\",\"resourceRef\":\"" + expired.id() + "\"}]}";
        assertThatThrownBy(() -> f.step("ls.core.Work.assign", admin, "ls.core.Work", risky, assignExpired))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("GUARD_FAILED");
                assertThat(e.getMessage()).contains("资质已过期");
                assertThat(e.getDetails()).containsEntry("reason", code).containsEntry("guard", "rule:" + code);
                assertThat(e.getDetails().get("level")).isEqualTo("L2");
            });
        assertThat(f.core.works.get(Long.parseLong(risky)).status().name()).isEqualTo("READY");
        // 资质有效的人员可以分派
        OperationResult ok = f.step("ls.core.Work.assign", admin, "ls.core.Work", risky,
            "{\"targets\":[{\"resourceType\":\"PARTY\",\"resourceRef\":\"" + valid.id() + "\"}]}");
        assertThat(ok.status()).isEqualTo("ASSIGNED");

        // 普通工作（when 不满足）不受限制
        String normal = f.newWork(admin, p.teamA(), "普通巡检");
        f.step("ls.core.Work.ready", admin, "ls.core.Work", normal, "{}");
        assertThat(f.step("ls.core.Work.assign", admin, "ls.core.Work", normal, assignExpired).status()).isEqualTo("ASSIGNED");
    }

    @Test
    void 绕过前端直接调用违反条件必填规则被后端拒绝() {
        String code = unique("test.work.qualityNeedsDescription");
        install(code, ConstraintLevel.L1, RuleKind.CEL, "质量工作需说明", "质量类工作必须填写说明", """
            {"when": "$model.workType == 'INSPECTION'", "assert": "has($model.description) && size($model.description) > 0",
             "field": "description", "appliesTo": ["ls.core.Work.create"]}""", false, null);
        CoreFixture.Plant p = f.core.plant();
        assertThatThrownBy(() -> f.op("ls.core.Work.create", admin,
            "{\"workType\":\"INSPECTION\",\"title\":\"来料检验\",\"orgUnitId\":" + p.teamA().id() + "}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED");
                assertThat(e.fieldErrors()).containsEntry("description", "质量类工作必须填写说明");
            });
        assertThat(f.op("ls.core.Work.create", admin, "{\"workType\":\"INSPECTION\",\"title\":\"来料检验\",\"description\":\"按 GB 抽检\",\"orgUnitId\":"
            + p.teamA().id() + "}").targetId()).isNotNull();
    }

    @Test
    void 决策表_严重度HIGH且涉及安全设备返回风险等级L3() {
        String code = unique("test.issue.riskGrade");
        RuleDefinition table = install(code, ConstraintLevel.L2, RuleKind.DECISION_TABLE, "异常风险定级", null, """
            {"hitPolicy": "FIRST", "rows": [
               {"when": "$model.severity in ['HIGH','CRITICAL'] && $model.safetyDevice == true", "output": {"riskLevel": "L3"}},
               {"when": "$model.severity in ['HIGH','CRITICAL']", "output": {"riskLevel": "L2"}},
               {"when": "$model.severity == 'MEDIUM'", "output": {"riskLevel": "L1"}}],
             "default": {"riskLevel": "L0"}}""", false, null);
        RuleOutcome high = f.rules.evaluate(table, Map.of("severity", "HIGH", "safetyDevice", true), Map.of());
        assertThat(high.output()).containsEntry("riskLevel", "L3");
        assertThat(high.violated()).isFalse();
        assertThat(f.rules.evaluate(table, Map.of("severity", "HIGH", "safetyDevice", false), Map.of()).output()).containsEntry("riskLevel", "L2");
        assertThat(f.rules.evaluate(table, Map.of("severity", "LOW", "safetyDevice", false), Map.of()).output()).containsEntry("riskLevel", "L0");

        // 全部命中 + 作为守卫：命中行 outcome 最严格者生效
        String guard = unique("test.work.dispatchCheck");
        install(guard, ConstraintLevel.L2, RuleKind.DECISION_TABLE, "派工检查", null, """
            {"hitPolicy": "ALL", "appliesTo": ["ls.core.Work.create"], "rows": [
               {"when": "$model.workType == 'MAINTENANCE'", "output": {"outcome": "WARN", "message": "检修工作请确认停机窗口"}},
               {"when": "$model.workType == 'MAINTENANCE' && has($model.priority) && $model.priority >= 5",
                "output": {"outcome": "BLOCK", "message": "最高优先级检修需走紧急检修流程"}}]}""", false, null);
        CoreFixture.Plant p = f.core.plant();
        OperationResult warned = f.op("ls.core.Work.create", admin,
            "{\"workType\":\"MAINTENANCE\",\"title\":\"换泵\",\"orgUnitId\":" + p.teamA().id() + ",\"priority\":3}");
        assertThat(warned.warnings()).contains("检修工作请确认停机窗口");
        assertThatThrownBy(() -> f.op("ls.core.Work.create", admin,
            "{\"workType\":\"MAINTENANCE\",\"title\":\"换泵\",\"orgUnitId\":" + p.teamA().id() + ",\"priority\":5}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("GUARD_FAILED");
                assertThat(e.getMessage()).isEqualTo("最高优先级检修需走紧急检修流程");
            });
    }

    @Test
    void 非白名单函数与超复杂表达式发布失败并指出函数名() {
        String code = unique("test.work.illegal");
        RuleDefinition d = f.rules.saveDraft(new RuleService.RuleDraft(code, "ls.core.Work", ConstraintLevel.L2, RuleKind.CEL, "非法",
            null, json("{\"assert\": \"exec($model.cmd) == 0\"}"), "customer", false, null));
        assertThatThrownBy(() -> f.rulePublisher.publish(code, d.version()))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("RULE_PUBLISH_REJECTED");
                assertThat(e.getMessage()).contains("exec");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> issues = (List<Map<String, Object>>) e.getDetails().get("issues");
                assertThat(issues.get(0)).containsEntry("function", "exec").containsEntry("errorCode", "RULE_ILLEGAL_FUNCTION");
            });
        assertThat(f.rules.published(code)).isEmpty();

        String table = unique("test.work.illegalTable");
        RuleDefinition t = f.rules.saveDraft(new RuleService.RuleDraft(table, "ls.core.Work", ConstraintLevel.L2, RuleKind.DECISION_TABLE,
            "非法决策表", null, json("{\"rows\":[{\"when\":\"$model.tags.exists(x, x == 'a')\",\"output\":{}}]}"), "customer", false, null));
        assertThat(f.rulePublisher.validate(t)).anySatisfy(i -> assertThat(i).containsEntry("function", "exists").containsEntry("row", 0));

        String huge = unique("test.work.huge");
        String expr = "$model.a == 1" + " || $model.a == 1".repeat(300);
        RuleDefinition h = f.rules.saveDraft(new RuleService.RuleDraft(huge, "ls.core.Work", ConstraintLevel.L2, RuleKind.CEL, "超长",
            null, json("{\"assert\": \"" + expr + "\"}"), "customer", false, null));
        assertThat(f.rulePublisher.validate(h)).anySatisfy(i -> assertThat(i).containsEntry("errorCode", "RULE_TOO_COMPLEX"));
    }

    @Test
    void 求值超限或出错时按规则失败处理() {
        String code = unique("test.work.brokenRule");
        // 发布时可通过（引用字段在运行时缺失），运行时求值失败 → L2 阻断
        install(code, ConstraintLevel.L2, RuleKind.CEL, "损坏的规则", "规则校验未通过", """
            {"assert": "$model.notExisting > 0", "appliesTo": ["ls.core.Work.create"]}""", false, null);
        CoreFixture.Plant p = f.core.plant();
        assertThatThrownBy(() -> f.op("ls.core.Work.create", admin, "{\"workType\":\"PRODUCTION\",\"title\":\"x\",\"orgUnitId\":" + p.teamA().id() + "}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("GUARD_FAILED");
                assertThat(e.getDetails()).containsEntry("ruleError", "RULE_EVAL_FAILED");
            });
    }

    @Test
    void 代码守卫_双签互斥() {
        String code = unique("test.work.dualSign");
        install(code, ConstraintLevel.L3, RuleKind.JAVA, "双签互斥", null,
            "{\"guard\": \"" + DualSignGuard.CODE + "\", \"appliesTo\": [\"ls.core.Work.testDualSign\"]}", false, null);
        CoreFixture.Plant p = f.core.plant();
        Party worker = f.core.employee("signer", p.teamA());
        Party checker = f.core.employee("checker", p.teamA());
        String id = f.newWork(admin, p.teamA(), "双签");
        ActorContext asWorker = f.superUser(worker);
        int v = f.core.works.get(Long.parseLong(id)).version();
        assertThatThrownBy(() -> f.on("ls.core.Work.testDualSign", asWorker, id, v, "{\"executorParty\":" + worker.id() + "}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("GUARD_FAILED");
                assertThat(e.getMessage()).contains("执行人与复核人不能是同一人");
            });
        assertThat(f.on("ls.core.Work.testDualSign", f.superUser(checker), id, v, "{\"executorParty\":" + worker.id() + "}").data())
            .isEqualTo(Map.of("signed", true));

        // 配置不能内嵌代码，只能引用已注册守卫
        String bad = unique("test.work.script");
        RuleDefinition d = f.rules.saveDraft(new RuleService.RuleDraft(bad, "ls.core.Work", ConstraintLevel.L3, RuleKind.JAVA, "脚本",
            null, json("{\"guard\": \"not.registered\", \"script\": \"Runtime.exec()\"}"), "customer", false, null));
        assertThat(f.rulePublisher.validate(d)).extracting(i -> i.get("errorCode")).contains("GUARD_NOT_FOUND", "RULE_INVALID");
    }

    @Test
    void 客户规则只能加严_试图放宽被拒绝() {
        String std = unique("test.std.hotWorkPermit");
        install(std, ConstraintLevel.L2, RuleKind.CEL, "动火作业须有许可证", "动火作业必须关联作业许可证", """
            {"when": "has($model.hotWork) && $model.hotWork == true", "assert": "has($model.permitNo)", "appliesTo": ["ls.core.Work.testDualSign"]}""",
            true, null);

        // 覆盖为提示 → 拒绝
        String relax = unique("test.customer.hotWorkRelax");
        RuleDefinition r1 = f.rules.saveDraft(new RuleService.RuleDraft(relax, "ls.core.Work", ConstraintLevel.L0, RuleKind.CEL, "放宽",
            "仅提示", json("{\"assert\": \"true\"}"), "customer", false, std));
        assertThatThrownBy(() -> f.rulePublisher.publish(relax, r1.version()))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("RULE_RELAXATION_FORBIDDEN"));
        // 关闭 → 拒绝
        RuleDefinition r2 = f.rules.saveDraft(new RuleService.RuleDraft(relax, "ls.core.Work", ConstraintLevel.L2, RuleKind.CEL, "关闭",
            null, json("{\"assert\": \"true\", \"disabled\": true}"), "customer", false, std));
        assertThatThrownBy(() -> f.rulePublisher.publish(relax, r2.version())).extracting("code").isEqualTo("RULE_RELAXATION_FORBIDDEN");
        // 以同编码新版本降级 → 拒绝
        RuleDefinition r3 = f.rules.saveDraft(new RuleService.RuleDraft(std, "ls.core.Work", ConstraintLevel.L0, RuleKind.CEL, "降级",
            null, json("{\"assert\": \"true\"}"), "customer", false, null));
        assertThatThrownBy(() -> f.rulePublisher.publish(std, r3.version())).extracting("code").isEqualTo("RULE_RELAXATION_FORBIDDEN");
        assertThat(f.rules.published(std).orElseThrow().level()).isEqualTo(ConstraintLevel.L2);

        // 加严：在标准规则之上叠加，二者同时执行
        String tighten = unique("test.customer.hotWorkTighten");
        install(tighten, ConstraintLevel.L2, RuleKind.CEL, "动火须双人监护", "动火作业必须指定监护人", """
            {"when": "has($model.hotWork) && $model.hotWork == true", "assert": "has($model.guardian)"}""", false, std);
        CoreFixture.Plant p = f.core.plant();
        Party worker = f.core.employee("hot", p.teamA());
        String id = f.newWork(admin, p.teamA(), "动火");
        int v = f.core.works.get(Long.parseLong(id)).version();
        assertThatThrownBy(() -> f.on("ls.core.Work.testDualSign", admin, id, v, "{\"executorParty\":" + worker.id() + ",\"hotWork\":true}"))
            .extracting("message").isEqualTo("动火作业必须关联作业许可证");
        assertThatThrownBy(() -> f.on("ls.core.Work.testDualSign", admin, id, v,
            "{\"executorParty\":" + worker.id() + ",\"hotWork\":true,\"permitNo\":\"DH-01\"}"))
            .extracting("message").isEqualTo("动火作业必须指定监护人");
        assertThat(f.on("ls.core.Work.testDualSign", admin, id, v,
            "{\"executorParty\":" + worker.id() + ",\"hotWork\":true,\"permitNo\":\"DH-01\",\"guardian\":\"张工\"}").data()).isEqualTo(Map.of("signed", true));
    }
}
