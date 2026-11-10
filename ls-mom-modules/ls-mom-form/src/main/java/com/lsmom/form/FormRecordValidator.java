package com.lsmom.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.action.rule.CelRuleEngine;
import com.lsmom.action.state.StateMachineDefinition;
import com.lsmom.action.state.StateMachineRegistry;
import com.lsmom.action.rule.CelValues;
import com.lsmom.core.error.LsBizException;
import com.lsmom.crud.RecordValidator;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.FieldDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * 按记录冻结的表单版本做服务端校验（RecordValidator 实现）：必填、长度、范围、正则、选项、字典、引用过滤、子表行数、
 * 只读/隐藏/计算字段不可由客户端写入、计算字段服务端重算、L1/L2 CEL 规则。
 * <p>表单选择：报文 {@code _form.code}（缺省为该 Concept 的默认表单）；版本：报文 {@code _form.version} ＞ 记录已冻结版本 ＞ 最新发布版本。
 * 不允许降级到比记录已冻结版本更旧的表单。</p>
 */
@Component
public class FormRecordValidator implements RecordValidator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FormRegistry forms;
    private final ConceptRegistry concepts;
    private final CelRuleEngine cel;
    private final JdbcTemplate jdbc;

    private final StateMachineRegistry stateMachines;

    public FormRecordValidator(FormRegistry forms, ConceptRegistry concepts, CelRuleEngine cel, JdbcTemplate jdbc, StateMachineRegistry stateMachines) {
        this.forms = forms;
        this.concepts = concepts;
        this.cel = cel;
        this.jdbc = jdbc;
        this.stateMachines = stateMachines;
    }

    @Override
    public Outcome validate(Context ctx) {
        FormRegistry.View view = select(ctx).orElse(null);
        if (view == null) return Outcome.NONE;
        JsonNode form = view.schema();
        Map<String, FieldPolicy.Access> access = FieldPolicy.resolve(form, null);
        Map<String, Object> model = merged(ctx, form);
        Map<String, Object> context = context(ctx);
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, String> codes = new LinkedHashMap<>();
        Map<String, String> fixes = new LinkedHashMap<>();
        Map<String, JsonNode> overrides = new LinkedHashMap<>();
        JsonNode body = ctx.body();
        ConceptDef concept = concepts.get(ctx.concept());

        // 计算字段：服务端重算并覆盖（先算，规则使用重算后的值）
        for (JsonNode f : form.path("fields")) {
            String key = f.path("key").asText();
            if (!f.has("computed")) continue;
            Object v = compute(f.path("computed").path("expression").asText(), model, context);
            model.put(key, v);
            overrides.put(key, JSON.valueToTree(v));
        }
        for (JsonNode f : form.path("fields")) {
            String key = f.path("key").asText();
            if (f.has("computed")) continue;
            FieldPolicy.Access a = access.get(key);
            if (!a.editable() && body != null && body.has(key)) {
                Object submitted = CelValues.fromJson(body.get(key));
                Object before = ctx.current() == null ? CelValues.fromJson(f.get("default")) : CelValues.fromJava(ctx.current().get(key));
                if (!same(submitted, before)) {
                    put(errors, codes, fixes, key, new Problem("READONLY", label(f, concept) + "为只读，不能修改", "刷新后重新填写"));
                    continue;
                }
            }
            Problem err = checkValue(f, a, model.get(key), concept, model, context, ctx, unchanged(ctx.current() == null ? null : ctx.current().get(key)));
            if (err != null) put(errors, codes, fixes, key, err);
        }
        for (JsonNode st : form.path("subTables")) {
            String sk = st.path("key").asText();
            Object rows = model.get(sk);
            List<?> list = rows instanceof List<?> l ? l : List.of();
            String name = st.path("label").asText(sk);
            boolean imported = body != null && body.path(RecordValidator.IMPORT_FLAG).asBoolean(false);
            if (!imported && st.has("minRows") && list.size() < st.get("minRows").asInt()) {
                put(errors, codes, fixes, sk, new Problem("ROWS_MIN", name + "至少 " + st.get("minRows").asInt() + " 行", "添加一行"));
            }
            if (st.has("maxRows") && list.size() > st.get("maxRows").asInt()) {
                put(errors, codes, fixes, sk, new Problem("ROWS_MAX", name + "最多 " + st.get("maxRows").asInt() + " 行", "删除多余的行"));
            }
            ConceptDef child = concepts.find(st.path("conceptCode").asText()).orElse(null);
            Object beforeRows = ctx.current() == null ? null : ctx.current().get(sk);
            for (int i = 0; i < list.size(); i++) {
                Map<String, Object> row = CelValues.asMap(list.get(i));
                for (JsonNode f : st.path("fields")) {
                    String key = f.path("key").asText();
                    FieldPolicy.Access a = access.get(sk + "." + key);
                    Problem err = checkValue(f, a, row.get(key), child, model, context, ctx, unchangedInColumn(beforeRows, key));
                    if (err != null) put(errors, codes, fixes, sk + "[" + i + "]." + key, new Problem(err.code(), "第 " + (i + 1) + " 行：" + err.message(), err.fix()));
                }
            }
        }
        if (errors.isEmpty()) {
            for (JsonNode r : form.path("rules")) {
                String level = r.path("level").asText();
                if (level.equals("L0")) continue;
                if (r.has("when") && !applies(r.get("when").asText(), model, context)) continue;
                try {
                    String rk = r.has("field") ? r.get("field").asText() : "_form";
                    if (!cel.test(cel.compileCondition(r.path("assert").asText()), model, context) && !errors.containsKey(rk)) {
                        put(errors, codes, fixes, rk, new Problem("RULE", r.path("message").asText(), null));
                    }
                } catch (LsBizException e) {
                    String rk = r.has("field") ? r.get("field").asText() : "_form";
                    if (!errors.containsKey(rk)) put(errors, codes, fixes, rk, new Problem("RULE", r.path("message").asText() + "（" + e.getMessage() + "）", null));
                }
            }
        }
        if (!errors.isEmpty()) {
            String first = errors.values().iterator().next();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("fields", errors);
            details.put("codes", codes);
            details.put("fixes", fixes);
            details.put("form", view.code());
            details.put("formVersion", view.version());
            throw new LsBizException("VALIDATION_FAILED", "输入校验失败：" + first, details);
        }
        return new Outcome(view.version(), overrides);
    }

    // ---------- 表单与版本 ----------

    Optional<FormRegistry.View> select(Context ctx) {
        JsonNode meta = ctx.body() == null ? null : ctx.body().get("_form");
        String code = meta != null && meta.hasNonNull("code") ? meta.get("code").asText() : null;
        FormRegistry.View base;
        if (code == null) {
            Optional<FormRegistry.View> def = forms.defaultFor(ctx.concept(), ViewType.FORM);
            if (def.isEmpty()) return Optional.empty();
            base = def.get();
            code = base.code();
        } else {
            base = forms.published(code).orElseThrow(() -> LsBizException.validation(Map.of("_form", "表单未发布")));
            if (!ctx.concept().equals(base.concept()) || base.type() != ViewType.FORM) {
                throw LsBizException.validation(Map.of("_form", "表单不属于 " + ctx.concept()));
            }
        }
        Integer frozen = ctx.current() != null && ctx.current().get("schemaVersion") instanceof Number n ? n.intValue() : null;
        Integer requested = meta != null && meta.hasNonNull("version") ? meta.get("version").asInt() : null;
        int version = requested != null ? requested : frozen != null && forms.find(code, frozen).isPresent() ? frozen : base.version();
        if (frozen != null && version < frozen && forms.find(code, frozen).isPresent()) {
            throw LsBizException.validation(Map.of("_form", "记录已按 v" + frozen + " 保存，不能切换到更旧的 v" + version));
        }
        FormRegistry.View v = forms.find(code, version).filter(x -> !"DRAFT".equals(x.status()))
            .orElseThrow(() -> LsBizException.validation(Map.of("_form", "表单版本不存在或未发布")));
        return Optional.of(v);
    }

    private static Map<String, Object> merged(Context ctx, JsonNode form) {
        Map<String, Object> model = new LinkedHashMap<>();
        if (ctx.current() != null) ctx.current().forEach((k, v) -> model.put(k, CelValues.fromJava(v)));
        if (ctx.body() != null) {
            Iterator<Map.Entry<String, JsonNode>> it = ctx.body().fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().startsWith("_")) continue;
                model.put(e.getKey(), CelValues.fromJson(e.getValue()));
            }
        }
        return model;
    }

    private static Map<String, Object> context(Context ctx) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("orgId", ctx.orgId());
        c.put("deptId", ctx.orgId());
        c.put("userId", ctx.actor().userId());
        c.put("partyId", ctx.actor().effectiveParty());
        return c;
    }

    // ---------- 字段校验 ----------

    /** 一条字段问题：编码、文字、修复提示（可为空）。 */
    record Problem(String code, String message, String fix) {
    }

    private static void put(Map<String, String> errors, Map<String, String> codes, Map<String, String> fixes, String key, Problem p) {
        errors.put(key, p.message());
        codes.put(key, p.code());
        if (p.fix() != null) fixes.put(key, p.fix());
    }

    private Problem checkValue(JsonNode f, FieldPolicy.Access a, Object v, ConceptDef concept, Map<String, Object> model,
                               Map<String, Object> context, Context ctx, Set<String> unchanged) {
        String label = label(f, concept);
        boolean empty = v == null || (v instanceof String s && s.isBlank()) || (v instanceof List<?> l && l.isEmpty());
        if (empty) return a != null && a.required() ? new Problem("REQUIRED", label + "必填", "填写" + label) : null;
        if (v instanceof String s) {
            int len = s.codePointCount(0, s.length());
            if (f.has("minLength") && len < f.get("minLength").asInt()) return new Problem("MIN_LENGTH", label + "至少 " + f.get("minLength").asInt() + " 个字符", null);
            if (f.has("maxLength") && len > f.get("maxLength").asInt()) return new Problem("MAX_LENGTH", label + "最多 " + f.get("maxLength").asInt() + " 个字符", null);
            if (f.has("pattern")) {
                try {
                    if (!Pattern.compile(f.get("pattern").asText()).matcher(s).matches()) {
                        return new Problem("PATTERN", f.has("patternMessage") ? f.get("patternMessage").asText() : label + "格式不正确", null);
                    }
                } catch (RuntimeException e) {
                    return new Problem("PATTERN", label + "格式规则无效", null);
                }
            }
        }
        if ((f.has("min") || f.has("max")) && (v instanceof Number || v instanceof String)) {
            BigDecimal d;
            try {
                d = new BigDecimal(v.toString());
            } catch (NumberFormatException e) {
                return new Problem("NOT_NUMBER", label + "必须是数字", null);
            }
            if (f.has("min") && d.compareTo(f.get("min").decimalValue()) < 0) return new Problem("MIN", label + "不能小于 " + f.get("min").asText(), null);
            if (f.has("max") && d.compareTo(f.get("max").decimalValue()) > 0) return new Problem("MAX", label + "不能大于 " + f.get("max").asText(), null);
        }
        List<Object> values = v instanceof List<?> l ? new ArrayList<>(l) : List.of(v);
        if (f.has("options") && f.get("options").size() > 0) {
            List<String> allowed = new ArrayList<>();
            collectOptions(f.get("options"), allowed);
            for (Object x : values) if (!allowed.contains(String.valueOf(x))) return new Problem("OPTION", label + "的取值不在可选范围内", "从下拉列表中重新选择");
        }
        if (f.has("dictType")) {
            List<String> dict = jdbc.queryForList("select dict_value from sys_dict_data where dict_type = ?", String.class, f.get("dictType").asText());
            for (Object x : values) {
                if (!dict.contains(String.valueOf(x))) return new Problem("DICT", label + "的取值不在字典「" + f.get("dictType").asText() + "」中", "从下拉列表中重新选择");
            }
        }
        if (f.has("ref")) return refProblem(f.get("ref"), values, model, context, ctx, label, unchanged);
        return null;
    }

    private static void collectOptions(JsonNode options, List<String> out) {
        for (JsonNode o : options) {
            out.add(o.path("value").asText());
            if (o.has("children")) collectOptions(o.get("children"), out);
        }
    }

    /** 记录里原有的引用编号：修改记录时未改动的引用不再复核（引用对象后来停用或移出范围，不妨碍修改其他字段）。 */
    private static Set<String> unchanged(Object before) {
        Set<String> out = new HashSet<>();
        if (before instanceof List<?> l) l.forEach(x -> out.add(String.valueOf(x)));
        else if (before != null) out.add(String.valueOf(before));
        return out;
    }

    private static Set<String> unchangedInColumn(Object rows, String key) {
        Set<String> out = new HashSet<>();
        if (rows instanceof List<?> l) for (Object r : l) if (r instanceof Map<?, ?> m) out.addAll(unchanged(m.get(key)));
        return out;
    }

    /**
     * 引用目标必须存在、满足表单声明的过滤（如 orgId = $context.orgId、只可选在用设备）并在调用者的数据范围内；
     * 默认值、记忆值、粘贴、扫码带入的值与手工选择的一样检查。不通过时说明具体原因：已删除、已停用（按状态机迁移名称）、无权使用。
     */
    private Problem refProblem(JsonNode ref, List<Object> ids, Map<String, Object> model, Map<String, Object> context, Context ctx,
                               String label, Set<String> unchanged) {
        String fix = "重新选择" + label;
        ConceptDef target = concepts.find(ref.path("concept").asText()).orElse(null);
        if (target == null) return new Problem("REF", label + "的引用目标不存在", null);
        for (Object idv : ids) {
            long id;
            try {
                id = Long.parseLong(String.valueOf(idv));
            } catch (NumberFormatException e) {
                return new Problem("REF", label + "必须是对象编号", fix);
            }
            if (unchanged.contains(String.valueOf(id))) continue;
            String base = "select count(*) from " + RefQuery.table(target) + " t where t.id = ?" + (RefQuery.managed(target) ? " and t.del_flag = '0'" : "");
            if (count(base, List.of(id)) == 0) return new Problem("REF_MISSING", "所选" + label + "已删除或不存在，请重新选择", fix);

            StringBuilder sql = new StringBuilder(base);
            List<Object> args = new ArrayList<>(List.of(id));
            String err = appendFilters(target, ref.path("filter"), model, context, sql, args);
            if (err != null) return new Problem("REF", label + err, fix);
            if (count(sql.toString(), args) == 0) {
                Problem inactive = inactive(target, ref, id, base, model, context, fix);
                return inactive != null ? inactive : new Problem("REF", label + "不在允许的引用范围内", fix);
            }

            StringBuilder scoped = new StringBuilder(base);
            List<Object> scopeArgs = new ArrayList<>(List.of(id));
            RefQuery.scope(ctx.actor().dataScope(), target, scoped, scopeArgs);
            if (count(scoped.toString(), scopeArgs) == 0) return new Problem("REF_SCOPE", "你无权使用所选" + target.displayName() + "，请重新选择", fix);
        }
        return null;
    }

    /** 引用对象的状态不满足表单的状态过滤（如设备已报废）：提示"该设备已报废，请重新选择"。 */
    private Problem inactive(ConceptDef target, JsonNode ref, long id, String base, Map<String, Object> model, Map<String, Object> context, String fix) {
        if (!RefQuery.managed(target)) return null;
        com.fasterxml.jackson.databind.node.ArrayNode statusFilters = JSON.createArrayNode();
        for (JsonNode flt : ref.path("filter")) if (flt.path("field").asText().equals("status")) statusFilters.add(flt);
        if (statusFilters.isEmpty()) return null;
        StringBuilder sql = new StringBuilder(base);
        List<Object> args = new ArrayList<>(List.of(id));
        if (appendFilters(target, statusFilters, model, context, sql, args) != null || count(sql.toString(), args) > 0) return null;
        String status = jdbc.queryForObject("select status from " + RefQuery.table(target) + " t where t.id = ?", String.class, id);
        return new Problem("REF_INACTIVE", "该" + target.displayName() + stateText(target.code(), status) + "，请重新选择", fix);
    }

    /** 状态说明：初始状态为"尚未启用"；其他取进入该状态的迁移名称（如 SCRAPPED ← 报废 → "已报废"）；都没有时显示状态编码。 */
    private String stateText(String concept, String status) {
        if (stateMachines != null && status != null) {
            try {
                StateMachineDefinition d = stateMachines.forConcept(concept);
                if (status.equals(d.initial())) return "尚未启用";
                for (StateMachineDefinition.Transition t : d.transitions()) if (status.equals(t.to()) && t.name() != null) return "已" + t.name();
            } catch (LsBizException e) {
                // 没有状态机
            }
        }
        return "当前状态（" + status + "）不可选用";
    }

    private int count(String sql, List<Object> args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** 追加表单声明的引用过滤；上下文变量缺失（如调用者没有组织）时拒绝，而不是退化为 is null。 */
    static String appendFilters(ConceptDef target, JsonNode filters, Map<String, Object> model, Map<String, Object> context,
                                StringBuilder sql, List<Object> args) {
        for (JsonNode flt : filters) {
            Object val = resolve(flt.get("value"), model, context);
            if (val == null && flt.path("value").asText("").startsWith("$")) return "不在允许的引用范围内";
            Optional<String> c = RefQuery.condition(target, flt.path("field").asText(), flt.path("op").asText(), val, args);
            if (c.isEmpty()) return "的引用过滤配置无效";
            sql.append(" and ").append(c.get());
        }
        return null;
    }

    static Object resolve(JsonNode v, Map<String, Object> model, Map<String, Object> context) {
        if (v == null || v.isNull()) return null;
        if (v.isTextual() && v.asText().startsWith("$context.")) return context.get(v.asText().substring("$context.".length()));
        if (v.isTextual() && v.asText().startsWith("$model.")) return model.get(v.asText().substring("$model.".length()));
        return CelValues.fromJson(v);
    }

    /** when 求值出错（如引用的可选字段未填写）视为条件不成立，与前端渲染器一致。 */
    private boolean applies(String when, Map<String, Object> model, Map<String, Object> context) {
        try {
            return cel.test(cel.compileCondition(when), model, context);
        } catch (LsBizException e) {
            return false;
        }
    }

    private Object compute(String expr, Map<String, Object> model, Map<String, Object> context) {
        try {
            return CelValues.fromJava(cel.evaluate(cel.compileValue(expr), model, context));
        } catch (LsBizException e) {
            return null;
        }
    }

    private static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number x && b instanceof Number y) return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static String label(JsonNode f, ConceptDef concept) {
        if (f.hasNonNull("label")) return f.get("label").asText();
        FieldDef d = concept == null ? null : concept.field(f.path("key").asText());
        return d == null ? f.path("key").asText() : d.displayName();
    }
}
