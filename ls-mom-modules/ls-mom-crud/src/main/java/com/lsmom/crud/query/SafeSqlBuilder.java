package com.lsmom.crud.query;

import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScope;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.meta.concept.DataType;
import com.lsmom.meta.concept.FieldDef;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 通用台账 SQL 构造器。标识符只来自 {@link LedgerModel}（已发布元数据 + 白名单校验，统一加引号）；
 * 操作符、聚合函数、排序方向取固定枚举；所有值以 {@code ?} 参数绑定；行级数据域以 org_id / created_by 条件注入。
 */
public final class SafeSqlBuilder {

    public static final Set<String> OPERATORS = Set.of("eq", "ne", "gt", "ge", "lt", "le", "like", "startsWith", "in", "notIn",
        "between", "isNull", "notNull");
    public static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max");
    private static final Pattern ALIAS = Pattern.compile("^[a-zA-Z][a-zA-Z0-9]{0,30}$");
    private static final int MAX_IN = 500;

    public record Sql(String sql, List<Object> args) {
    }

    /** 关联对象解析：ref 字段 → 被引用台账模型（无权或不是台账时返回 null）及其数据域。 */
    public interface RefResolver {
        RefTarget resolve(FieldDef refField);
    }

    public record RefTarget(LedgerModel model, DataScope scope) {
    }

    private final LedgerModel model;
    private final DataScope scope;
    private final Predicate<String> readable;
    private final RefResolver refs;

    public SafeSqlBuilder(LedgerModel model, DataScope scope, Predicate<String> readable, RefResolver refs) {
        this.model = model;
        this.scope = scope;
        this.readable = readable;
        this.refs = refs;
    }

    static String q(String identifier) {
        return "\"" + com.lsmom.meta.concept.MetaNames.requireIdentifier(identifier) + "\"";
    }

    private static LsBizException reject(String field, String message) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("fields", Map.of(field, message));
        return new LsBizException("QUERY_REJECTED", message, d);
    }

    /** 选择列：系统列 + 可读字段列 + ext_attrs。 */
    public String selectList(String alias) {
        List<String> cols = new ArrayList<>();
        for (String c : model.systemColumns().values()) cols.add(alias + "." + q(c));
        if (model.child()) cols.add(alias + "." + q("parent_id"));
        for (FieldDef f : model.fields().values()) {
            if (!f.ext() && readable.test(f.code())) cols.add(alias + "." + q(f.columnName()));
        }
        cols.add(alias + "." + q("ext_attrs"));
        return String.join(", ", cols);
    }

    /** where 子句（不含 where 关键字）：未删除 + 数据域 + 筛选条件。 */
    public String where(String alias, List<QuerySpec.Filter> filters, List<Object> args) {
        List<String> parts = new ArrayList<>();
        parts.add(alias + ".\"del_flag\" = '0'");
        String sc = scopeCondition(alias, scope, args);
        if (sc != null) parts.add(sc);
        for (QuerySpec.Filter f : filters) parts.add(condition(alias, f, args));
        return String.join(" and ", parts);
    }

    /** 行级数据域：全部 → 无条件；仅本人 → created_by；其余 → org_id 在范围内或本人创建。 */
    static String scopeCondition(String alias, DataScope scope, List<Object> args) {
        if (scope == null || scope.all()) return null;
        List<String> or = new ArrayList<>();
        if (scope.level() != DataScopeLevel.SELF && !scope.orgUnitIds().isEmpty()) {
            or.add(alias + ".\"org_id\" in (" + placeholders(scope.orgUnitIds().size()) + ")");
            args.addAll(scope.orgUnitIds());
        }
        if (scope.partyId() != null) {
            or.add(alias + ".\"created_by\" = ?");
            args.add(scope.partyId());
        }
        return or.isEmpty() ? "1 = 0" : "(" + String.join(" or ", or) + ")";
    }

    private String condition(String alias, QuerySpec.Filter f, List<Object> args) {
        if (f.field() == null) throw reject("filters", "筛选字段不能为空");
        if (f.op() == null || !OPERATORS.contains(f.op())) throw reject(f.field(), "不支持的操作符：" + f.op());
        int dot = f.field().indexOf('.');
        if (dot > 0) return refCondition(alias, f, dot, args);
        if (!model.queryable(f.field()) || !readable(f.field())) throw reject(f.field(), "字段不允许作为筛选条件：" + f.field());
        String col = alias + "." + q(model.column(f.field()));
        return expression(col, model.typeOf(f.field()), f, args);
    }

    private boolean readable(String field) {
        return model.systemColumns().containsKey(field) || readable.test(field);
    }

    /** 关联对象筛选：{@code ref.field}，被引用对象的字段须开放查询，且按被引用对象的数据域过滤。 */
    private String refCondition(String alias, QuerySpec.Filter f, int dot, List<Object> args) {
        String refField = f.field().substring(0, dot);
        String sub = f.field().substring(dot + 1);
        FieldDef ref = model.field(refField);
        if (ref == null || ref.dataType() != DataType.REF || !readable.test(refField) || !ref.flag("queryable")) {
            throw reject(f.field(), "关联字段不允许作为筛选条件：" + f.field());
        }
        RefTarget target = refs == null ? null : refs.resolve(ref);
        if (target == null) throw reject(f.field(), "无权按关联对象筛选：" + ref.refConcept());
        LedgerModel rm = target.model();
        if (sub.contains(".") || !rm.queryable(sub)) throw reject(f.field(), "关联对象字段不允许作为筛选条件：" + f.field());
        List<Object> inner = new ArrayList<>();
        String cond = expression("r." + q(rm.column(sub)), rm.typeOf(sub), new QuerySpec.Filter(sub, f.op(), f.value()), inner);
        String sc = scopeCondition("r", target.scope(), inner);
        args.addAll(inner);
        return "exists (select 1 from " + q(rm.table()) + " r where r.\"id\" = " + alias + "." + q(model.column(refField))
            + " and r.\"del_flag\" = '0' and " + cond + (sc == null ? "" : " and " + sc) + ")";
    }

    private static String expression(String col, DataType type, QuerySpec.Filter f, List<Object> args) {
        Function<Object, Object> conv = v -> convert(f.field(), type, v);
        switch (f.op()) {
            case "isNull" -> {
                return col + " is null";
            }
            case "notNull" -> {
                return col + " is not null";
            }
            case "in", "notIn" -> {
                if (!(f.value() instanceof Collection<?> c) || c.isEmpty() || c.size() > MAX_IN) {
                    throw reject(f.field(), "in 的值必须是 1～" + MAX_IN + " 个元素的数组");
                }
                c.forEach(v -> args.add(conv.apply(v)));
                return col + (f.op().equals("in") ? " in (" : " not in (") + placeholders(c.size()) + ")";
            }
            case "between" -> {
                if (!(f.value() instanceof List<?> l) || l.size() != 2) throw reject(f.field(), "between 的值必须是 [起, 止]");
                args.add(conv.apply(l.get(0)));
                args.add(conv.apply(l.get(1)));
                return col + " between ? and ?";
            }
            case "like", "startsWith" -> {
                if (type != DataType.STRING && type != DataType.TEXT && type != DataType.ENUM) throw reject(f.field(), "只有文本字段支持模糊匹配");
                String s = String.valueOf(f.value()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                args.add(f.op().equals("like") ? "%" + s + "%" : s + "%");
                return col + " like ? escape '\\'";
            }
            default -> {
                if (f.value() == null) throw reject(f.field(), "比较值不能为空，判空请用 isNull");
                args.add(conv.apply(f.value()));
                String op = switch (f.op()) {
                    case "eq" -> "=";
                    case "ne" -> "<>";
                    case "gt" -> ">";
                    case "ge" -> ">=";
                    case "lt" -> "<";
                    default -> "<=";
                };
                return col + " " + op + " ?";
            }
        }
    }

    /** 值按字段类型转换后绑定，转换失败按字段报错。 */
    public static Object convert(String field, DataType type, Object v) {
        if (v == null) return null;
        try {
            return switch (type) {
                case INTEGER, REF -> v instanceof Number n ? (Object) n.longValue() : (Object) Long.parseLong(v.toString().trim());
                case DECIMAL -> new BigDecimal(v.toString().trim());
                case BOOLEAN -> v instanceof Boolean b ? b : parseBool(v.toString());
                case DATE -> LocalDate.parse(v.toString().trim().substring(0, Math.min(10, v.toString().trim().length())));
                case DATETIME -> LocalDateTime.parse(v.toString().trim().replace(' ', 'T'));
                default -> v.toString();
            };
        } catch (RuntimeException e) {
            throw reject(field, "值的类型不正确：" + v);
        }
    }

    private static Boolean parseBool(String s) {
        return switch (s.trim().toLowerCase()) {
            case "true", "1", "y", "yes", "是" -> true;
            case "false", "0", "n", "no", "否" -> false;
            default -> throw new IllegalArgumentException(s);
        };
    }

    /** order by 子句：字段须在排序白名单中，方向只能 asc/desc；默认 id desc。 */
    public String orderBy(String alias, List<QuerySpec.Sort> sort) {
        List<String> parts = new ArrayList<>();
        for (QuerySpec.Sort s : sort) {
            if (s.field() == null || !model.sortable(s.field()) || !readable(s.field())) {
                throw reject("sort", "非法排序字段：" + s.field());
            }
            String dir = s.dir() == null ? "asc" : s.dir().toLowerCase();
            if (!dir.equals("asc") && !dir.equals("desc")) throw reject("sort", "排序方向只能是 asc 或 desc");
            parts.add(alias + "." + q(model.column(s.field())) + " " + dir);
        }
        parts.add(alias + ".\"id\" desc");
        return String.join(", ", parts);
    }

    public Sql page(QuerySpec spec, int maxSize) {
        List<Object> args = new ArrayList<>();
        String where = where("t", spec.filters(), args);
        int size = spec.sizeOrDefault(maxSize);
        String sql = "select " + selectList("t") + " from " + q(model.table()) + " t where " + where + " order by " + orderBy("t", spec.sort())
            + " limit " + size + " offset " + ((long) (spec.pageOrDefault() - 1) * size);
        return new Sql(sql, args);
    }

    public Sql count(QuerySpec spec) {
        List<Object> args = new ArrayList<>();
        return new Sql("select count(*) from " + q(model.table()) + " t where " + where("t", spec.filters(), args), args);
    }

    /** 分组聚合：分组字段须可查询，sum/avg 只能用于数值字段。 */
    public Sql aggregate(QuerySpec spec) {
        if (spec.aggregates().isEmpty()) throw reject("aggregates", "至少需要一个聚合");
        List<String> select = new ArrayList<>();
        List<String> group = new ArrayList<>();
        for (String g : spec.groupBy()) {
            if (!model.queryable(g) || !readable(g)) throw reject("groupBy", "字段不允许分组：" + g);
            String col = "t." + q(model.column(g));
            select.add(col + " as " + q(com.lsmom.meta.concept.MetaNames.toColumn(g)));
            group.add(col);
        }
        for (QuerySpec.Aggregate a : spec.aggregates()) {
            if (a.fn() == null || !AGGREGATES.contains(a.fn())) throw reject("aggregates", "不支持的聚合函数：" + a.fn());
            if (a.alias() == null || !ALIAS.matcher(a.alias()).matches()) throw reject("aggregates", "聚合别名不合法：" + a.alias());
            String expr;
            if (a.fn().equals("count") && (a.field() == null || a.field().equals("*"))) {
                expr = "count(*)";
            } else {
                if (a.field() == null || !model.queryable(a.field()) || !readable(a.field())) throw reject("aggregates", "字段不允许聚合：" + a.field());
                DataType t = model.typeOf(a.field());
                if ((a.fn().equals("sum") || a.fn().equals("avg")) && t != DataType.INTEGER && t != DataType.DECIMAL) {
                    throw reject("aggregates", "只有数值字段可以求和或平均：" + a.field());
                }
                expr = a.fn() + "(t." + q(model.column(a.field())) + ")";
            }
            select.add(expr + " as " + q(com.lsmom.meta.concept.MetaNames.toColumn(a.alias())));
        }
        List<Object> args = new ArrayList<>();
        String sql = "select " + String.join(", ", select) + " from " + q(model.table()) + " t where " + where("t", spec.filters(), args)
            + (group.isEmpty() ? "" : " group by " + String.join(", ", group) + " order by " + String.join(", ", group));
        return new Sql(sql, args);
    }

    static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }
}
