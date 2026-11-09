package com.lsmom.action.rule;

import com.lsmom.core.error.LsBizException;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelIssue;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.navigation.CelNavigableAst;
import dev.cel.common.navigation.CelNavigableExpr;
import dev.cel.common.types.CelType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受限 CEL 环境（L2 声明式规则）。
 * <ul>
 *   <li>只能访问 {@code $model}（当前对象：目标对象字段叠加本次输入）与 {@code $context}（调用主体、时间、事实提供者给出的跨对象事实）；</li>
 *   <li>只允许比较、逻辑、范围（in）、正则（matches）、长度（size）、字符串前后缀与四则运算等白名单函数；
 *       不提供循环（推导式宏全部关闭）、递归、任意方法调用、网络或数据库访问；</li>
 *   <li>发布期静态校验：长度、节点数、嵌套深度上限，非白名单函数报出函数名；</li>
 *   <li>运行期成本上限（求值节点数）与时间上限，超限中止并按规则失败处理。</li>
 * </ul>
 * 前端 TS 实现（plus-ui {@code utils/cel}）遵循同一白名单与预处理规则，一致性用例见 {@code packs/_shared/cel-conformance}。
 */
@Component
public class CelRuleEngine {

    /** 白名单函数（不含运算符）。与前端 {@code ALLOWED_FUNCTIONS} 保持一致。 */
    public static final Set<String> ALLOWED_FUNCTIONS = Set.of(
        "size", "matches", "startsWith", "endsWith", "contains", "int", "double", "string", "bool");

    /** 规则可引用的变量。 */
    public static final List<String> VARIABLES = List.of("$model", "$context");

    public record Limits(int maxLength, int maxNodes, int maxDepth, int maxCost, long timeoutMillis) {
        public static final Limits DEFAULT = new Limits(2000, 200, 24, 1000, 100);
    }

    /** 编译后的表达式；线程安全，可缓存复用。 */
    public record Compiled(String source, CelRuntime.Program program, int nodes) {
    }

    private static final Pattern VAR = Pattern.compile("\\$(model|context)\\b");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Limits limits;
    private final CelCompiler boolCompiler;
    private final CelCompiler dynCompiler;
    private final CelRuntime runtime;
    private final Map<String, Compiled> cache = new ConcurrentHashMap<>();

    public CelRuleEngine() {
        this(Limits.DEFAULT);
    }

    public CelRuleEngine(Limits limits) {
        this.limits = limits;
        CelOptions options = CelOptions.current()
            .maxExpressionCodePointSize(limits.maxLength())
            .maxParseRecursionDepth(limits.maxDepth())
            .maxParseExpressionNodeCount(limits.maxNodes())
            .enableComprehension(false)
            .enableHeterogeneousNumericComparisons(true)
            .maxRegexProgramSize(200)
            .build();
        this.boolCompiler = compiler(options, SimpleType.BOOL);
        this.dynCompiler = compiler(options, SimpleType.DYN);
        this.runtime = CelRuntimeFactory.standardCelRuntimeBuilder().setOptions(options).build();
    }

    private static CelCompiler compiler(CelOptions options, CelType result) {
        MapType map = MapType.create(SimpleType.STRING, SimpleType.DYN);
        return CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(options)
            .setStandardMacros(CelStandardMacro.HAS)
            .addVar("model", map)
            .addVar("context", map)
            .setResultType(result)
            .build();
    }

    public Limits limits() {
        return limits;
    }

    /** 编译布尔表达式（守卫条件）；不合法时抛 RULE_INVALID 并给出原因与函数名。 */
    public Compiled compileCondition(String source) {
        return cache.computeIfAbsent("B:" + source, k -> compile(source, boolCompiler));
    }

    /** 编译任意类型表达式（决策表输出等）。 */
    public Compiled compileValue(String source) {
        return cache.computeIfAbsent("D:" + source, k -> compile(source, dynCompiler));
    }

    /** 发布期静态校验：返回问题列表（空表示通过），不抛异常。 */
    public List<Map<String, Object>> validate(String source, boolean condition) {
        try {
            compile(source, condition ? boolCompiler : dynCompiler);
            return List.of();
        } catch (LsBizException e) {
            Map<String, Object> issue = new LinkedHashMap<>(e.getDetails());
            issue.put("errorCode", e.getCode());
            issue.put("message", e.getMessage());
            return List.of(issue);
        }
    }

    private Compiled compile(String source, CelCompiler compiler) {
        if (source == null || source.isBlank()) throw invalid("RULE_INVALID", "表达式为空", source, Map.of());
        if (source.codePointCount(0, source.length()) > limits.maxLength()) {
            throw invalid("RULE_TOO_COMPLEX", "表达式长度超过上限 " + limits.maxLength(), source, Map.of("limit", limits.maxLength()));
        }
        String cel = preprocess(source);
        CelAbstractSyntaxTree parsed;
        try {
            CelValidationResult r = compiler.parse(cel);
            if (r.hasError()) throw invalid(complexity(r) ? "RULE_TOO_COMPLEX" : "RULE_INVALID", "表达式语法错误：" + messages(r), source, Map.of());
            parsed = r.getAst();
        } catch (dev.cel.common.CelValidationException e) {
            throw invalid("RULE_INVALID", "表达式语法错误：" + e.getMessage(), source, Map.of());
        }
        List<CelNavigableExpr> nodes = CelNavigableAst.fromAst(parsed).getRoot().allNodes().toList();
        if (nodes.size() > limits.maxNodes()) {
            throw invalid("RULE_TOO_COMPLEX", "表达式节点数 " + nodes.size() + " 超过上限 " + limits.maxNodes(), source,
                Map.of("nodes", nodes.size(), "limit", limits.maxNodes()));
        }
        Set<String> illegal = new LinkedHashSet<>();
        for (CelNavigableExpr n : nodes) {
            CelExpr e = n.expr();
            switch (e.getKind()) {
                case CALL -> {
                    String fn = e.call().function();
                    if (!isOperator(fn) && !ALLOWED_FUNCTIONS.contains(fn)) illegal.add(fn);
                }
                case COMPREHENSION -> illegal.add("<comprehension>");
                case STRUCT -> illegal.add("<message>");
                default -> { }
            }
        }
        if (!illegal.isEmpty()) {
            String first = illegal.iterator().next();
            throw invalid("RULE_ILLEGAL_FUNCTION", "函数「" + first + "」不在白名单中", source,
                Map.of("function", first, "functions", List.copyOf(illegal), "allowed", ALLOWED_FUNCTIONS.stream().sorted().toList()));
        }
        try {
            CelValidationResult checked = compiler.check(parsed);
            if (checked.hasError()) throw invalid("RULE_INVALID", "表达式校验失败：" + undollar(messages(checked)), source, Map.of());
            CelAbstractSyntaxTree ast = checked.getAst();
            return new Compiled(source, runtime.createProgram(ast), nodes.size());
        } catch (dev.cel.common.CelValidationException | CelEvaluationException e) {
            throw invalid("RULE_INVALID", "表达式校验失败：" + undollar(e.getMessage()), source, Map.of());
        }
    }

    private static boolean isOperator(String fn) {
        return !fn.isEmpty() && !IDENT.matcher(fn).matches();
    }

    private static boolean complexity(CelValidationResult r) {
        String m = messages(r).toLowerCase();
        return m.contains("recursion") || m.contains("node count") || m.contains("exceeds") || m.contains("size");
    }

    private static String messages(CelValidationResult r) {
        List<String> out = new ArrayList<>();
        for (CelIssue i : r.getAllIssues()) out.add(i.getMessage());
        return String.join("；", out);
    }

    /** {@code $model} / {@code $context} → CEL 合法标识符（字符串字面量内不替换）。 */
    static String preprocess(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        char quote = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < source.length()) {
                    out.append(source.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                out.append(c);
                i++;
                continue;
            }
            if (c == '$') {
                Matcher m = VAR.matcher(source).region(i, source.length());
                if (m.lookingAt()) {
                    out.append(m.group(1));
                    i = m.end();
                    continue;
                }
                throw invalid("RULE_INVALID", "只能引用 $model 与 $context", source, Map.of());
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String undollar(String msg) {
        return msg == null ? null : msg.replaceAll("\\b(model|context)\\b", "\\$$1");
    }

    private static LsBizException invalid(String code, String message, String source, Map<String, Object> extra) {
        Map<String, Object> d = new LinkedHashMap<>(extra);
        d.put("expression", source);
        return new LsBizException(code, message, d);
    }

    // —— 求值 ——

    private static final class LimitExceeded extends RuntimeException {
        LimitExceeded(String message) {
            super(message, null, false, false);
        }
    }

    /** 求值布尔表达式。超限或求值错误抛 RULE_LIMIT_EXCEEDED / RULE_EVAL_FAILED，由调用方按规则失败处理。 */
    public boolean test(Compiled c, Map<String, Object> model, Map<String, Object> context) {
        Object v = evaluate(c, model, context);
        if (v instanceof Boolean b) return b;
        throw new LsBizException("RULE_EVAL_FAILED", "规则结果不是布尔值", Map.of("expression", c.source()));
    }

    public Object evaluate(Compiled c, Map<String, Object> model, Map<String, Object> context) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("model", model == null ? Map.of() : nulls(model));
        vars.put("context", context == null ? Map.of() : nulls(context));
        long deadline = System.nanoTime() + limits.timeoutMillis() * 1_000_000L;
        int[] cost = {0};
        try {
            return c.program().trace(vars, (expr, value) -> {
                if (++cost[0] > limits.maxCost()) throw new LimitExceeded("求值成本超过上限 " + limits.maxCost());
                if (System.nanoTime() > deadline) throw new LimitExceeded("求值时间超过上限 " + limits.timeoutMillis() + "ms");
            });
        } catch (LimitExceeded e) {
            throw limit(c, e);
        } catch (CelEvaluationException e) {
            LimitExceeded le = cause(e);
            if (le != null) throw limit(c, le);
            throw new LsBizException("RULE_EVAL_FAILED", "规则求值失败：" + undollar(e.getMessage()), Map.of("expression", c.source()));
        } catch (RuntimeException e) {
            LimitExceeded le = cause(e);
            if (le != null) throw limit(c, le);
            throw new LsBizException("RULE_EVAL_FAILED", "规则求值失败：" + e.getMessage(), Map.of("expression", c.source()));
        }
    }

    /**
     * 对象（map）中值为 null 的字段视为"未填写"并移除：{@code has($model.x)} 为 false，直接访问报错。
     * 前端实现同此规则（@bufbuild/cel 的 has 对 null 值返回 false，两端由此一致）。列表中的 null 以 NullValue 保留。
     */
    private static Object nulls(Object v) {
        if (v == null) return com.google.protobuf.NullValue.NULL_VALUE;
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> {
                if (x != null) out.put(String.valueOf(k), nulls(x));
            });
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(x -> out.add(nulls(x)));
            return out;
        }
        return v;
    }

    private static LimitExceeded cause(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) if (x instanceof LimitExceeded le) return le;
        return null;
    }

    private LsBizException limit(Compiled c, LimitExceeded e) {
        return new LsBizException("RULE_LIMIT_EXCEEDED", e.getMessage(),
            Map.of("expression", c.source(), "maxCost", limits.maxCost(), "timeoutMillis", limits.timeoutMillis()));
    }
}
