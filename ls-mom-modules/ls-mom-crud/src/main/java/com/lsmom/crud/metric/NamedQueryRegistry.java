package com.lsmom.crud.metric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.gateway.InputSchemaValidator;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScope;
import com.lsmom.core.org.DataScopeLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 命名 Query 登记与执行：登记时校验编码、只读语句、数据域占位、参数 Schema 与样例（EXPLAIN）；
 * 执行时先按参数 Schema 校验，再在只读事务中以 statement_timeout 运行，超时取消并返回 QUERY_TIMEOUT。
 */
public class NamedQueryRegistry {

    private static final Logger log = LoggerFactory.getLogger(NamedQueryRegistry.class);
    public static final Pattern CODE = MetricRegistry.CODE;
    /** 调用者本人占位（替换为调用者的 Party id）。 */
    public static final String ACTOR = "{{actor}}";
    private static final Pattern SCOPE = Pattern.compile("\\{\\{scope:([a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*)(?:\\|([a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*))?}}");
    private static final Pattern FORBIDDEN = Pattern.compile("(?is)(;|\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|copy|call|do)\\b|pg_read|lo_import|dblink)");
    public static final int MAX_ROWS = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Info(String code, int version, String name, String description, JsonNode paramSchema, int timeoutMs, String permission) {
    }

    public record Result(String code, int version, List<String> columns, List<Map<String, Object>> rows, boolean truncated) {
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final InputSchemaValidator validator;
    private final TransactionTemplate readTx;
    private final TransactionTemplate tx;
    private final Map<String, Registered> queries = new ConcurrentHashMap<>();

    private record Registered(NamedQueryDef def, JsonNode schema, int version) {
    }

    public NamedQueryRegistry(JdbcTemplate jdbc, InputSchemaValidator validator, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.validator = validator;
        this.readTx = new TransactionTemplate(txm);
        this.readTx.setReadOnly(true);
        this.tx = new TransactionTemplate(txm);
    }

    /** 登记（启动时对所有 NamedQueryDef Bean 调用）；SQL 或参数变化时版本加一。 */
    public Info register(NamedQueryDef d) {
        List<String> problems = check(d);
        if (!problems.isEmpty()) throw new LsBizException("NAMED_QUERY_INVALID", "命名 Query " + d.code() + " 不合格：" + String.join("；", problems),
            Map.of("problems", problems));
        JsonNode schema = readJson(d.paramSchema());
        String hash = sha256(d.sql() + "\n" + schema + "\n" + d.timeoutMs() + "\n" + d.permission());
        int version = tx.execute(st -> {
            jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?))", "namedQuery:" + d.code());
            var cur = jdbc.queryForList("select version, sql_hash from meta_named_query where code = ? and status = 'PUBLISHED'", d.code());
            if (!cur.isEmpty() && hash.equals(cur.get(0).get("sql_hash"))) return ((Number) cur.get(0).get("version")).intValue();
            int v = cur.isEmpty() ? jdbc.queryForObject("select coalesce(max(version), 0) + 1 from meta_named_query where code = ?", Integer.class, d.code())
                : ((Number) cur.get(0).get("version")).intValue() + 1;
            jdbc.update("update meta_named_query set status = 'RETIRED' where code = ? and status = 'PUBLISHED'", d.code());
            jdbc.update("""
                insert into meta_named_query (code, version, display_name, description, param_schema, timeout_ms, permission, sql_hash, examples)
                values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb)""", d.code(), v, d.name(), d.description(), schema.toString(), d.timeoutMs(),
                d.permission(), hash, JSON.valueToTree(d.examples()).toString());
            return v;
        });
        queries.put(d.code(), new Registered(d, schema, version));
        return info(queries.get(d.code()));
    }

    /** 登记前检查；样例参数须通过参数 Schema，且语句可 EXPLAIN。 */
    public List<String> check(NamedQueryDef d) {
        List<String> p = new ArrayList<>();
        if (d.code() == null || !CODE.matcher(d.code()).matches()) p.add("编码须形如 ls.core.openIssuesByOrg");
        if (d.name() == null || d.name().isBlank()) p.add("名称必填");
        if (d.timeoutMs() < 100 || d.timeoutMs() > 60000) p.add("超时须在 100～60000 毫秒之间");
        String sql = d.sql() == null ? "" : d.sql().trim();
        String lower = sql.toLowerCase();
        if (!(lower.startsWith("select") || lower.startsWith("with"))) p.add("只能是 select / with 查询");
        String scrubbed = SCOPE.matcher(sql).replaceAll("true").replace(ACTOR, "0").replaceAll("'[^']*'", "''");
        if (FORBIDDEN.matcher(scrubbed).find()) p.add("语句含写操作、多语句或危险函数");
        if (!SCOPE.matcher(sql).find()) p.add("缺少数据域占位 {{scope:别名.组织列}}");
        JsonNode schema = null;
        try {
            schema = readJson(d.paramSchema());
            if (!"object".equals(schema.path("type").asText())) p.add("参数 Schema 的 type 须为 object");
        } catch (RuntimeException e) {
            p.add("参数 Schema 不是合法 JSON");
        }
        if (d.examples() == null || d.examples().isEmpty()) p.add("至少需要一组样例参数");
        if (!p.isEmpty() || schema == null) return p;
        for (String ex : d.examples()) {
            JsonNode params = readJson(ex);
            try {
                validator.validate(schema, params);
            } catch (LsBizException e) {
                p.add("样例参数不符合参数 Schema：" + ex);
                continue;
            }
            try {
                Bound b = bind(d, schema, params, DataScope.unrestricted(), null);
                readTx.execute(st -> named.queryForList("explain " + b.sql(), b.params()));
            } catch (DataAccessException e) {
                p.add("样例无法执行：" + rootMessage(e));
            }
        }
        return p;
    }

    public Result execute(String code, JsonNode params, ActorContext actor) {
        Registered r = queries.get(code);
        if (r == null) throw LsBizException.notFound("命名 Query", code);
        NamedQueryDef d = r.def();
        if (d.permission() != null && !actor.hasPermission(d.permission())) {
            throw new LsBizException("ACCESS_DENIED", "没有执行查询「" + d.name() + "」的权限", Map.of("permission", d.permission()));
        }
        JsonNode p = params == null || params.isNull() ? JSON.createObjectNode() : params;
        validator.validate(r.schema(), p);
        Bound b = bind(d, r.schema(), p, actor.dataScope(), actor.effectiveParty());
        try {
            return readTx.execute(st -> {
                jdbc.execute("set local statement_timeout = " + d.timeoutMs());
                List<Map<String, Object>> rows = named.queryForList("select * from (" + b.sql() + ") q limit " + (MAX_ROWS + 1), b.params());
                boolean truncated = rows.size() > MAX_ROWS;
                if (truncated) rows = rows.subList(0, MAX_ROWS);
                List<String> cols = rows.isEmpty() ? List.of() : List.copyOf(rows.get(0).keySet());
                return new Result(code, r.version(), cols, rows, truncated);
            });
        } catch (QueryTimeoutException e) {
            throw timeout(d);
        } catch (DataAccessException e) {
            if (isTimeout(e)) throw timeout(d);
            log.warn("命名 Query {} 执行失败：{}", code, rootMessage(e));
            throw new LsBizException("QUERY_FAILED", "查询执行失败");
        }
    }

    private static LsBizException timeout(NamedQueryDef d) {
        return new LsBizException("QUERY_TIMEOUT", "查询「" + d.name() + "」超过 " + d.timeoutMs() + " 毫秒，已取消", Map.of("timeoutMs", d.timeoutMs()));
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && "57014".equals(s.getSQLState())) return true;
        }
        return false;
    }

    public List<Info> list() {
        return queries.values().stream().map(NamedQueryRegistry::info).sorted(java.util.Comparator.comparing(Info::code)).toList();
    }

    public boolean exists(String code) {
        return queries.containsKey(code);
    }

    public Info info(String code) {
        Registered r = queries.get(code);
        if (r == null) throw LsBizException.notFound("命名 Query", code);
        return info(r);
    }

    private static Info info(Registered r) {
        NamedQueryDef d = r.def();
        return new Info(d.code(), r.version(), d.name(), d.description(), r.schema(), d.timeoutMs(), d.permission());
    }

    private record Bound(String sql, MapSqlParameterSource params) {
    }

    /** 参数按 Schema 声明的类型转换后绑定；数据域占位替换为条件（参数名带双下划线前缀，不与业务参数冲突）。 */
    private static Bound bind(NamedQueryDef d, JsonNode schema, JsonNode params, DataScope scope, Long actorParty) {
        MapSqlParameterSource ps = new MapSqlParameterSource();
        // {{actor}}：调用者本人（Party id），用于"我的待办"等只看本人的查询
        ps.addValue("__actor", actorParty);
        JsonNode props = schema.path("properties");
        for (Iterator<String> it = props.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            JsonNode v = params.get(k);
            ps.addValue(k, convert(props.get(k), v));
        }
        Matcher m = SCOPE.matcher(d.sql());
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (m.find()) {
            String cond;
            if (scope == null || scope.all()) {
                cond = "true";
            } else {
                List<String> or = new ArrayList<>();
                if (scope.level() != DataScopeLevel.SELF && !scope.orgUnitIds().isEmpty()) {
                    or.add(m.group(1) + " in (:__orgs" + i + ")");
                    ps.addValue("__orgs" + i, new ArrayList<>(scope.orgUnitIds()));
                }
                if (m.group(2) != null && scope.partyId() != null) {
                    or.add(m.group(2) + " = :__party" + i);
                    ps.addValue("__party" + i, scope.partyId());
                }
                cond = or.isEmpty() ? "false" : "(" + String.join(" or ", or) + ")";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(cond));
            i++;
        }
        m.appendTail(sb);
        return new Bound(sb.toString().replace(ACTOR, "cast(:__actor as bigint)"), ps);
    }

    private static Object convert(JsonNode prop, JsonNode v) {
        if (v == null || v.isNull()) return null;
        String type = prop.path("type").asText();
        String format = prop.path("format").asText();
        return switch (type) {
            case "integer" -> v.asLong();
            case "number" -> v.decimalValue();
            case "boolean" -> v.asBoolean();
            case "array" -> {
                List<Object> l = new ArrayList<>();
                v.forEach(x -> l.add(convert(prop.path("items"), x)));
                yield l;
            }
            default -> switch (format) {
                case "date" -> LocalDate.parse(v.asText());
                case "date-time" -> LocalDateTime.parse(v.asText().replace(' ', 'T').replaceFirst("Z$", ""));
                default -> v.asText();
            };
        };
    }

    private static JsonNode readJson(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 方便定义：样例参数的 Map 写法。 */
    public static String json(Map<String, ?> m) {
        try {
            return JSON.writeValueAsString(new LinkedHashMap<>(m));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
