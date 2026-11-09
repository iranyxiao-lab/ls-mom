package com.lsmom.crud.metric;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.core.error.LsBizException;
import com.lsmom.crud.query.SafeSqlBuilder;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.DataType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 指标口径注册：校验、重复口径检测、版本化发布。同一编码口径变化生成新版本（旧版本置 RETIRED，仍可按版本查询）；
 * 与其他编码的已发布指标口径完全相同时拒绝发布并提示复用。
 */
public class MetricRegistry {

    public static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*\\.[a-z][A-Za-z0-9]{0,60}$");
    public static final Set<String> FNS = Set.of("count", "sum", "avg", "min", "max", "ratio");
    public static final Set<String> GRAINS = Set.of("DAY", "WEEK", "MONTH");
    public static final Set<String> COMPARE = Set.of("eq", "ne", "gt", "ge", "lt", "le");

    public record Issue(String path, String code, String message) {
    }

    public record Version(String code, int version, String name, String concept, String status, MetricDefinition definition,
                          LocalDateTime publishedAt) {
    }

    private final JdbcTemplate jdbc;
    private final ConceptRegistry concepts;
    private final TransactionTemplate tx;

    public MetricRegistry(JdbcTemplate jdbc, ConceptRegistry concepts, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.concepts = concepts;
        this.tx = new TransactionTemplate(txm);
    }

    public MetricSource source(String concept) {
        return new MetricSource(concepts.get(concept));
    }

    public List<Issue> validate(MetricDefinition d) {
        List<Issue> issues = new ArrayList<>();
        if (d.code() == null || !CODE.matcher(d.code()).matches()) issues.add(new Issue("/code", "CODE_INVALID", "指标编码须形如 ls.prod.onTimeRate"));
        if (d.name() == null || d.name().isBlank()) issues.add(new Issue("/name", "NAME_REQUIRED", "指标名称必填"));
        Optional<ConceptDef> c = d.concept() == null ? Optional.empty() : concepts.find(d.concept());
        if (c.isEmpty()) {
            issues.add(new Issue("/concept", "CONCEPT_NOT_FOUND", "来源 Concept 不存在：" + d.concept()));
            return issues;
        }
        MetricSource s = new MetricSource(c.get());
        var m = d.measure();
        if (m == null || m.fn() == null || !FNS.contains(m.fn())) {
            issues.add(new Issue("/measure/fn", "MEASURE_INVALID", "度量只能是 " + FNS));
        } else {
            if (Set.of("sum", "avg", "min", "max").contains(m.fn())) {
                if (m.field() == null || !s.has(m.field())) issues.add(new Issue("/measure/field", "FIELD_NOT_FOUND", "度量字段不存在：" + m.field()));
                else if (Set.of("sum", "avg").contains(m.fn()) && !numeric(s.type(m.field()))) {
                    issues.add(new Issue("/measure/field", "FIELD_NOT_NUMERIC", "只有数值字段可以求和或平均：" + m.field()));
                }
            }
            if (m.fn().equals("ratio") && m.numerator().isEmpty()) issues.add(new Issue("/measure/numerator", "NUMERATOR_REQUIRED", "比率指标须设置分子条件"));
            conds(s, m.numerator(), "/measure/numerator", issues);
        }
        conds(s, d.filters(), "/filters", issues);
        if (d.timeField() != null) {
            DataType t = s.type(d.timeField());
            if (t != DataType.DATE && t != DataType.DATETIME) issues.add(new Issue("/timeField", "TIME_FIELD_INVALID", "时间字段须为日期或日期时间：" + d.timeField()));
        } else if (!d.grains().isEmpty()) {
            issues.add(new Issue("/grains", "TIME_FIELD_REQUIRED", "设置时间粒度须先指定时间字段"));
        }
        for (int i = 0; i < d.grains().size(); i++) {
            if (!GRAINS.contains(d.grains().get(i))) issues.add(new Issue("/grains/" + i, "GRAIN_INVALID", "时间粒度只能是 DAY、WEEK、MONTH"));
        }
        for (int i = 0; i < d.dimensions().size(); i++) {
            String f = d.dimensions().get(i);
            if (!s.has(f)) issues.add(new Issue("/dimensions/" + i, "FIELD_NOT_FOUND", "维度字段不存在：" + f));
            else if (s.type(f) == DataType.TEXT || s.type(f) == DataType.JSON) issues.add(new Issue("/dimensions/" + i, "DIMENSION_INVALID", "长文本不能作为维度：" + f));
        }
        for (int i = 0; i < d.drillColumns().size(); i++) {
            if (!s.has(d.drillColumns().get(i))) issues.add(new Issue("/drillColumns/" + i, "FIELD_NOT_FOUND", "钻取列不存在：" + d.drillColumns().get(i)));
        }
        if (issues.isEmpty() && d.code() != null) {
            String dup = jdbc.queryForList("""
                select code from meta_metric where definition_hash = ? and status = 'PUBLISHED' and code <> ? order by code limit 1""",
                String.class, d.semanticHash(), d.code()).stream().findFirst().orElse(null);
            if (dup != null) issues.add(new Issue("/", "METRIC_DUPLICATE", "口径与已发布指标 " + dup + " 完全相同，请复用该指标"));
        }
        return issues;
    }

    private static boolean numeric(DataType t) {
        return t == DataType.INTEGER || t == DataType.DECIMAL;
    }

    private void conds(MetricSource s, List<MetricDefinition.Cond> cs, String path, List<Issue> issues) {
        for (int i = 0; i < cs.size(); i++) {
            var c = cs.get(i);
            String p = path + "/" + i;
            if (c.field() == null || !s.has(c.field())) {
                issues.add(new Issue(p + "/field", "FIELD_NOT_FOUND", "条件字段不存在：" + c.field()));
                continue;
            }
            if (c.op() == null || !SafeSqlBuilder.OPERATORS.contains(c.op())) {
                issues.add(new Issue(p + "/op", "OP_INVALID", "不支持的操作符：" + c.op()));
                continue;
            }
            if (c.valueField() != null) {
                if (!COMPARE.contains(c.op())) issues.add(new Issue(p + "/op", "OP_INVALID", "字段间比较只支持 eq/ne/gt/ge/lt/le"));
                if (!s.has(c.valueField())) issues.add(new Issue(p + "/valueField", "FIELD_NOT_FOUND", "比较字段不存在：" + c.valueField()));
            } else {
                try {
                    MetricEngine.condition(s, "t", c, new ArrayList<>());
                } catch (LsBizException e) {
                    issues.add(new Issue(p, "CONDITION_INVALID", e.getMessage()));
                }
            }
        }
    }

    /** 发布：校验通过后，口径或展示属性有变化则生成新版本。 */
    public Version publish(MetricDefinition d, Long by) {
        List<Issue> issues = validate(d);
        if (!issues.isEmpty()) {
            Issue first = issues.get(0);
            throw new LsBizException(first.code().equals("METRIC_DUPLICATE") ? "METRIC_DUPLICATE" : "METRIC_INVALID", first.message(),
                Map.of("issues", issues));
        }
        return tx.execute(st -> {
            jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?))", "metric:" + d.code());
            Optional<Version> cur = latest(d.code());
            if (cur.isPresent() && cur.get().definition().toJson().equals(d.toJson())) return cur.get();
            int v = cur.map(x -> x.version() + 1).orElse(1);
            jdbc.update("update meta_metric set status = 'RETIRED' where code = ? and status = 'PUBLISHED'", d.code());
            jdbc.update("""
                insert into meta_metric (code, version, display_name, definition, status, definition_hash, concept_code, published_at, created_by)
                values (?, ?, ?, ?::jsonb, 'PUBLISHED', ?, ?, current_timestamp, ?)""",
                d.code(), v, d.name(), d.toJson().toString(), d.semanticHash(), d.concept(), by);
            return get(d.code(), v);
        });
    }

    public Optional<Version> latest(String code) {
        return jdbc.query("select * from meta_metric where code = ? and status = 'PUBLISHED' order by version desc limit 1", this::map, code)
            .stream().findFirst();
    }

    public Version require(String code, Integer version) {
        if (version != null) return get(code, version);
        return latest(code).orElseThrow(() -> LsBizException.notFound("已发布指标", code));
    }

    public Version get(String code, int version) {
        return jdbc.query("select * from meta_metric where code = ? and version = ?", this::map, code, version).stream().findFirst()
            .orElseThrow(() -> LsBizException.notFound("指标版本", code + " v" + version));
    }

    public List<Version> history(String code) {
        return jdbc.query("select * from meta_metric where code = ? order by version desc", this::map, code);
    }

    public List<Version> list() {
        return jdbc.query("select * from meta_metric where status = 'PUBLISHED' order by code", this::map);
    }

    public boolean exists(String code) {
        return latest(code).isPresent();
    }

    private Version map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        MetricDefinition d = MetricDefinition.parse(rs.getString("definition"));
        return new Version(rs.getString("code"), rs.getInt("version"), rs.getString("display_name"), d.concept(), rs.getString("status"), d,
            rs.getObject("published_at", LocalDateTime.class));
    }

    public static JsonNode issuesJson(List<Issue> issues) {
        return MetricDefinition.JSON.valueToTree(issues);
    }
}
