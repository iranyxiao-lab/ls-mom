package com.lsmom.action.rule;

import com.lsmom.core.error.LsBizException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 6.1：受限 CEL 环境——白名单、发布期静态校验、成本与时间上限。 */
class CelRuleEngineTest {

    private final CelRuleEngine engine = new CelRuleEngine();

    private boolean eval(String expr, Map<String, Object> model) {
        return engine.test(engine.compileCondition(expr), model, Map.of("now", "2026-11-10T08:00:00"));
    }

    @Test
    void 支持比较逻辑范围正则长度枚举与四则运算() {
        Map<String, Object> m = new HashMap<>();
        m.put("qty", 12L);
        m.put("price", 2.5);
        m.put("code", "WO-2026-001");
        m.put("severity", "HIGH");
        m.put("tags", List.of("a", "b"));
        m.put("plannedEnd", null);
        assertThat(eval("$model.qty > 10 && $model.qty <= 20", m)).isTrue();
        assertThat(eval("double($model.qty) * $model.price == 30.0", m)).isTrue();
        assertThat(eval("$model.qty + 3 == 15 && $model.qty % 5 == 2", m)).isTrue();
        assertThat(eval("$model.code.matches('^WO-[0-9]{4}-[0-9]+$')", m)).isTrue();
        assertThat(eval("size($model.code) == 11 && size($model.tags) == 2", m)).isTrue();
        assertThat(eval("$model.severity in ['HIGH', 'CRITICAL']", m)).isTrue();
        assertThat(eval("!has($model.missing) && !has($model.plannedEnd)", m)).isTrue();
        assertThat(eval("$model.code.startsWith('WO') ? $context.now > '2026' : false", m)).isTrue();
        assertThat(eval("'$model' == '$' + 'model'", m)).isTrue();
    }

    @Test
    void 非白名单函数发布校验失败并指出函数名() {
        assertThatThrownBy(() -> engine.compileCondition("exec($model.cmd) == 0"))
            .isInstanceOf(LsBizException.class)
            .satisfies(e -> {
                LsBizException b = (LsBizException) e;
                assertThat(b.getCode()).isEqualTo("RULE_ILLEGAL_FUNCTION");
                assertThat(b.getMessage()).contains("exec");
                assertThat(b.getDetails().get("function")).isEqualTo("exec");
            });
        // 推导式（循环）宏关闭：all/exists/map/filter 作为非法函数报出
        assertThatThrownBy(() -> engine.compileCondition("$model.tags.all(t, t != '')"))
            .satisfies(e -> assertThat(((LsBizException) e).getDetails().get("function")).isEqualTo("all"));
        assertThatThrownBy(() -> engine.compileCondition("timestamp($model.at) > timestamp('2026-01-01T00:00:00Z')"))
            .satisfies(e -> assertThat(((LsBizException) e).getDetails().get("function")).isEqualTo("timestamp"));
        // 只能引用 $model / $context
        assertThatThrownBy(() -> engine.compileCondition("$env.secret == 'x'"))
            .satisfies(e -> assertThat(((LsBizException) e).getCode()).isEqualTo("RULE_INVALID"));
        assertThatThrownBy(() -> engine.compileCondition("other.x == 1"))
            .satisfies(e -> assertThat(((LsBizException) e).getCode()).isEqualTo("RULE_INVALID"));
        assertThat(engine.validate("exec(1)", true)).singleElement()
            .satisfies(i -> assertThat(i.get("function")).isEqualTo("exec"));
        assertThat(engine.validate("$model.a == 1", true)).isEmpty();
        // 非布尔结果不能作为条件
        assertThat(engine.validate("$model.a + 1", true)).isNotEmpty();
    }

    @Test
    void 超过长度与节点上限在发布期拒绝() {
        String longExpr = "$model.a == 1" + " && $model.a == 1".repeat(200);
        assertThatThrownBy(() -> engine.compileCondition(longExpr))
            .satisfies(e -> assertThat(((LsBizException) e).getCode()).isEqualTo("RULE_TOO_COMPLEX"));
        String deep = "(".repeat(40) + "true" + ")".repeat(40);
        assertThatThrownBy(() -> engine.compileCondition(deep)).isInstanceOf(LsBizException.class);
    }

    @Test
    void 求值超过成本上限时中止并按规则失败处理() {
        CelRuleEngine tight = new CelRuleEngine(new CelRuleEngine.Limits(2000, 200, 24, 10, 100));
        var c = tight.compileCondition("$model.a + $model.a + $model.a + $model.a + $model.a + $model.a > 0");
        assertThatThrownBy(() -> tight.test(c, Map.of("a", 1L), Map.of()))
            .satisfies(e -> {
                assertThat(((LsBizException) e).getCode()).isEqualTo("RULE_LIMIT_EXCEEDED");
                assertThat(e.getMessage()).contains("成本");
            });
        // 同一表达式在默认上限内正常求值
        assertThat(engine.test(engine.compileCondition(c.source()), Map.of("a", 1L), Map.of())).isTrue();
    }

    @Test
    void 求值错误按规则失败处理() {
        var c = engine.compileCondition("$model.missing == 1");
        assertThatThrownBy(() -> engine.test(c, Map.of(), Map.of()))
            .satisfies(e -> assertThat(((LsBizException) e).getCode()).isEqualTo("RULE_EVAL_FAILED"));
    }
}
