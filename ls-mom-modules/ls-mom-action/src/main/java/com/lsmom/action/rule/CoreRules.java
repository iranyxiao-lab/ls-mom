package com.lsmom.action.rule;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.guard.ConstraintLevel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 核心标准规则（Operation 对账之后安装，已发布则跳过）。 */
@Component
@Order(210)
public class CoreRules implements ApplicationRunner {

    public static final String PLANNED_END_HINT = "ls.core.Work.plannedEndHint";

    private final RuleService rules;
    private final RulePublisher publisher;

    public CoreRules(RuleService rules, RulePublisher publisher) {
        this.rules = rules;
        this.publisher = publisher;
    }

    @Override
    public void run(ApplicationArguments args) {
        installAll();
    }

    public void installAll() {
        if (rules.published(PLANNED_END_HINT).isEmpty()) {
            ObjectNode d = JsonNodeFactory.instance.objectNode();
            d.put("assert", "has($model.plannedEnd) && $model.plannedEnd != ''");
            d.putArray("appliesTo").add("ls.core.Work.create").add("ls.core.Work.ready");
            publisher.install(new RuleService.RuleDraft(PLANNED_END_HINT, "ls.core.Work", ConstraintLevel.L0, RuleKind.CEL,
                "计划结束时间提示", "未填写计划结束时间，建议补充以便排程与超期提醒", d, "ls.core", true, null));
        }
    }
}
