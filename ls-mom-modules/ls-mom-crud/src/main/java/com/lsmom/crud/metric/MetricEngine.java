package com.lsmom.crud.metric;

import com.lsmom.action.api.ActorContext;
import com.lsmom.core.error.LsBizException;
import com.lsmom.crud.query.QuerySpec;
import com.lsmom.crud.query.SafeSqlBuilder;
import com.lsmom.meta.concept.DataType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按已发布口径计算指标与钻取明细。只从口径与元数据生成 SQL（标识符加引号、值参数绑定），并按调用者数据域过滤；
 * 人与 Agent 走同一入口。钻取条件与计算条件完全相同，因此明细条数等于计数类指标的值。
 */
public class MetricEngine {

    public static final int MAX_GROUPS = 1000;
    public static final int MAX_DRILL = 500;
    private static final Set<String> DIM_OPS = Set.of("eq", "ne", "in", "notIn", "isNull", "notNull");

    /** from 含、to 不含；grain 为空不分时间桶；groupBy 须在口径维度内；filters 只能作用于维度字段。 */
    public record MetricQuery(String from, String to, String grain, List<String> groupBy, List<MetricDefinition.Cond> filters, Integer version) {
        public MetricQuery {
            groupBy = groupBy == null ? List.of() : groupBy;
            filters = filters == null ? List.of() : filters;
        }
    }

    public record MetricResult(String code, int version, String name, String unit, String fn, String grain, List<String> groupBy,
                               List<Map<String, Object>> rows) {
    }

    /** 钻取某个单元格：bucket（时间桶起点）与 dims（维度值）定位单元格；ratio 指标可只看分子记录。 */
    public record DrillRequest(String from, String to, String grain, String bucket, Map<String, Object> dims,
                               List<MetricDefinition.Cond> filters, Boolean numeratorOnly, Integer page, Integer size, Integer version) {
        public DrillRequest {
            dims = dims == null ? Map.of() : dims;
            filters = filters == null ? List.of() : filters;
        }
    }

    public record DrillResult(String code, int version, String concept, long total, List<String> columns, List<Map<String, Object>> rows) {
    }

    private final JdbcTemplate jdbc;
    private final MetricRegistry registry;

    public MetricEngine(JdbcTemplate jdbc, MetricRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    public MetricResult query(String code, MetricQuery q, ActorContext actor) {
        MetricRegistry.Version v = registry.require(code, q.version());
        MetricDefinition d = v.definition();
        permit(d, actor);
        MetricSource s = registry.source(d.concept());
        String grain = grain(d, q.grain());
        for (String g : q.groupBy()) {
            if (!d.dimensions().contains(g)) throw reject("groupBy", "维度不在指标口径内：" + g);
        }
        List<Object> args = new ArrayList<>();
        String where = where(d, s, q.from(), q.to(), q.filters(), actor, args);
        List<String> select = new ArrayList<>();
        List<String> group = new ArrayList<>();
        if (grain != null) {
            String b = "date_trunc('" + grain.toLowerCase() + "', " + s.column("t", d.timeField()) + ")";
            select.add(b + " as \"bucket\"");
            group.add(b);
        }
        for (int i = 0; i < q.groupBy().size(); i++) {
            String col = s.column("t", q.groupBy().get(i));
            select.add(col + " as \"d" + i + "\"");
            group.add(col);
        }
        var m = d.measure();
        switch (m.fn()) {
            case "count" -> select.add("count(*) as \"value\"");
            case "ratio" -> {
                List<Object> nargs = new ArrayList<>();
                String num = conds(s, m.numerator(), nargs);
                // 分子条件的参数要排在 where 参数之前（select 列表先出现）
                args.addAll(0, nargs);
                select.add("count(*) filter (where " + num + ") as \"numerator\"");
                select.add("count(*) as \"denominator\"");
            }
            default -> select.add(m.fn() + "(" + s.column("t", m.field()) + ") as \"value\"");
        }
        String sql = "select " + String.join(", ", select) + " from " + s.table() + " t where " + where
            + (group.isEmpty() ? "" : " group by " + String.join(", ", group) + " order by " + String.join(", ", group))
            + " limit " + (MAX_GROUPS + 1);
        List<Map<String, Object>> raw = jdbc.queryForList(sql, args.toArray());
        if (raw.size() > MAX_GROUPS) throw new LsBizException("METRIC_TOO_MANY_GROUPS", "分组超过 " + MAX_GROUPS + " 个，请缩小范围或减少维度");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (grain != null) out.put("bucket", bucketText(r.get("bucket")));
            for (int i = 0; i < q.groupBy().size(); i++) out.put(q.groupBy().get(i), r.get("d" + i));
            if (m.fn().equals("ratio")) {
                long n = ((Number) r.get("numerator")).longValue();
                long den = ((Number) r.get("denominator")).longValue();
                out.put("value", den == 0 ? null : BigDecimal.valueOf(n).divide(BigDecimal.valueOf(den), 4, RoundingMode.HALF_UP));
                out.put("numerator", n);
                out.put("denominator", den);
            } else {
                out.put("value", r.get("value"));
            }
            rows.add(out);
        }
        // 无分组时始终返回一行（count 为 0 也要显示）
        if (rows.isEmpty() && group.isEmpty()) rows.add(new LinkedHashMap<>(Map.of("value", m.fn().equals("count") ? 0 : 0)));
        return new MetricResult(v.code(), v.version(), v.name(), d.unit(), m.fn(), grain, q.groupBy(), rows);
    }

    public DrillResult drill(String code, DrillRequest r, ActorContext actor) {
        MetricRegistry.Version v = registry.require(code, r.version());
        MetricDefinition d = v.definition();
        permit(d, actor);
        MetricSource s = registry.source(d.concept());
        List<Object> args = new ArrayList<>();
        String where = where(d, s, r.from(), r.to(), r.filters(), actor, args);
        List<String> extra = new ArrayList<>();
        String grain = grain(d, r.grain());
        if (r.bucket() != null) {
            if (grain == null) throw reject("bucket", "钻取时间桶须指定粒度");
            extra.add("date_trunc('" + grain.toLowerCase() + "', " + s.column("t", d.timeField()) + ") = ?");
            args.add(Timestamp.valueOf(parseTime(r.bucket())));
        }
        for (var e : r.dims().entrySet()) {
            if (!d.dimensions().contains(e.getKey())) throw reject("dims", "维度不在指标口径内：" + e.getKey());
            if (e.getValue() == null) extra.add(s.column("t", e.getKey()) + " is null");
            else extra.add(SafeSqlBuilder.expression(s.column("t", e.getKey()), s.type(e.getKey()),
                new QuerySpec.Filter(e.getKey(), "eq", e.getValue()), args));
        }
        if (Boolean.TRUE.equals(r.numeratorOnly())) {
            if (!d.measure().fn().equals("ratio")) throw reject("numeratorOnly", "只有比率指标有分子");
            extra.add(conds(s, d.measure().numerator(), args));
        }
        String fullWhere = where + (extra.isEmpty() ? "" : " and " + String.join(" and ", extra));
        long total = jdbc.queryForObject("select count(*) from " + s.table() + " t where " + fullWhere, Long.class, args.toArray());
        List<String> columns = new ArrayList<>();
        columns.add("id");
        List<String> wanted = !d.drillColumns().isEmpty() ? d.drillColumns() : s.fieldCodes().stream()
            .filter(f -> !f.equals("id") && s.type(f) != DataType.TEXT && s.type(f) != DataType.JSON && !s.field(f).systemField()).limit(8).toList();
        for (String c : wanted) if (!columns.contains(c)) columns.add(c);
        int size = r.size() == null || r.size() < 1 ? 50 : Math.min(r.size(), MAX_DRILL);
        int page = r.page() == null || r.page() < 1 ? 1 : r.page();
        List<String> sel = columns.stream().map(c -> s.column("t", c) + " as \"" + c + "\"").toList();
        String order = d.timeField() != null ? s.column("t", d.timeField()) + " desc nulls last, " + s.column("t", "id") + " desc"
            : s.column("t", "id") + " desc";
        List<Map<String, Object>> rows = jdbc.queryForList("select " + String.join(", ", sel) + " from " + s.table() + " t where " + fullWhere
            + " order by " + order + " limit " + size + " offset " + ((long) (page - 1) * size), args.toArray());
        return new DrillResult(v.code(), v.version(), d.concept(), total, columns, rows);
    }

    private String where(MetricDefinition d, MetricSource s, String from, String to, List<MetricDefinition.Cond> filters, ActorContext actor,
                         List<Object> args) {
        List<String> parts = new ArrayList<>();
        parts.add(s.baseWhere("t", actor.dataScope(), args));
        if (!d.filters().isEmpty()) parts.add(conds(s, d.filters(), args));
        if (from != null || to != null) {
            if (d.timeField() == null) throw reject("from", "该指标没有时间字段，不能按时间范围查询");
            String col = s.column("t", d.timeField());
            if (from != null) {
                parts.add(col + " >= ?");
                args.add(Timestamp.valueOf(parseTime(from)));
            }
            if (to != null) {
                parts.add(col + " < ?");
                args.add(Timestamp.valueOf(parseTime(to)));
            }
        }
        for (var f : filters) {
            if (!d.dimensions().contains(f.field())) throw reject("filters", "只能按口径维度筛选：" + f.field());
            if (f.valueField() != null || !DIM_OPS.contains(f.op())) throw reject("filters", "维度筛选只支持 " + DIM_OPS);
            parts.add(condition(s, "t", f, args));
        }
        return String.join(" and ", parts);
    }

    private static String conds(MetricSource s, List<MetricDefinition.Cond> cs, List<Object> args) {
        List<String> out = new ArrayList<>();
        for (var c : cs) out.add(condition(s, "t", c, args));
        return out.isEmpty() ? "true" : "(" + String.join(" and ", out) + ")";
    }

    static String condition(MetricSource s, String alias, MetricDefinition.Cond c, List<Object> args) {
        if (c.field() == null || !s.has(c.field())) throw reject("filters", "条件字段不存在：" + c.field());
        if (c.op() == null || !SafeSqlBuilder.OPERATORS.contains(c.op())) throw reject("filters", "不支持的操作符：" + c.op());
        String col = s.column(alias, c.field());
        if (c.valueField() != null) {
            if (!s.has(c.valueField())) throw reject("filters", "比较字段不存在：" + c.valueField());
            String op = switch (c.op()) {
                case "eq" -> "=";
                case "ne" -> "<>";
                case "gt" -> ">";
                case "ge" -> ">=";
                case "lt" -> "<";
                case "le" -> "<=";
                default -> throw reject("filters", "字段间比较只支持 eq/ne/gt/ge/lt/le");
            };
            return col + " " + op + " " + s.column(alias, c.valueField());
        }
        return SafeSqlBuilder.expression(col, s.type(c.field()), new QuerySpec.Filter(c.field(), c.op(), c.value()), args);
    }

    private static String grain(MetricDefinition d, String grain) {
        if (grain == null || grain.isBlank()) return null;
        String g = grain.toUpperCase();
        if (!d.grains().contains(g)) throw reject("grain", "该指标不支持粒度 " + grain + "，可选：" + d.grains());
        return g;
    }

    private static void permit(MetricDefinition d, ActorContext actor) {
        if (d.permission() != null && !actor.hasPermission(d.permission())) {
            throw new LsBizException("ACCESS_DENIED", "没有查看指标「" + d.name() + "」的权限", Map.of("permission", d.permission()));
        }
    }

    static LocalDateTime parseTime(String s) {
        String t = s.trim();
        try {
            if (t.length() <= 10) return LocalDate.parse(t).atStartOfDay();
            return LocalDateTime.parse(t.replace(' ', 'T').replaceFirst("Z$", ""));
        } catch (RuntimeException e) {
            throw reject("time", "时间格式不正确：" + s);
        }
    }

    private static String bucketText(Object o) {
        if (o instanceof Timestamp ts) return ts.toLocalDateTime().toLocalDate().toString();
        if (o instanceof LocalDateTime l) return l.toLocalDate().toString();
        return o == null ? null : o.toString();
    }

    private static LsBizException reject(String field, String message) {
        return new LsBizException("METRIC_QUERY_INVALID", message, Map.of("fields", Map.of(field, message)));
    }
}
