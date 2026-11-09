package com.lsmom.action.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.action.guard.ConstraintLevel;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 规则存储（meta_rule）与 CEL、决策表求值。发布校验见 {@link RulePublisher}。 */
@Service
public class RuleService {

    static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final CelRuleEngine cel;
    private final Clock clock;

    public RuleService(JdbcTemplate jdbc, CelRuleEngine cel, Clock clock) {
        this.jdbc = jdbc;
        this.cel = cel;
        this.clock = clock;
    }

    public CelRuleEngine engine() {
        return cel;
    }

    private final RowMapper<RuleDefinition> mapper = (rs, i) -> {
        try {
            return new RuleDefinition(rs.getString("code"), rs.getInt("version"), rs.getString("concept_code"),
                ConstraintLevel.valueOf(rs.getString("level")), RuleKind.valueOf(rs.getString("kind")), rs.getString("display_name"),
                rs.getString("message"), JSON.readTree(rs.getString("definition")), rs.getString("status"), rs.getString("owner_pack"),
                rs.getBoolean("standard"), rs.getString("overrides"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    };

    /** 保存草稿：新版本号 = 已有最大版本 + 1。 */
    public RuleDefinition saveDraft(RuleDraft d) {
        if (d.code() == null || !d.code().matches("[a-z][a-z0-9]*(\\.[A-Za-z][A-Za-z0-9]*)+")) {
            throw LsBizException.validation(Map.of("code", "规则编码形如 <命名空间>.<对象>.<规则>"));
        }
        Integer max = jdbc.queryForObject("select max(version) from meta_rule where code = ?", Integer.class, d.code());
        int version = max == null ? 1 : max + 1;
        jdbc.update("""
            insert into meta_rule (code, version, concept_code, level, kind, display_name, message, definition, status, owner_pack, standard, overrides)
            values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'DRAFT', ?, ?, ?)""",
            d.code(), version, d.concept(), d.level().name(), d.kind().name(), d.name(), d.message(), d.definition().toString(),
            d.ownerPack(), d.standard(), d.overrides());
        return require(d.code(), version);
    }

    public record RuleDraft(String code, String concept, ConstraintLevel level, RuleKind kind, String name, String message,
                            JsonNode definition, String ownerPack, boolean standard, String overrides) {
    }

    public RuleDefinition require(String code, int version) {
        return jdbc.query("select * from meta_rule where code = ? and version = ?", mapper, code, version).stream().findFirst()
            .orElseThrow(() -> LsBizException.notFound("规则", code + "@" + version));
    }

    public Optional<RuleDefinition> published(String code) {
        return jdbc.query("select * from meta_rule where code = ? and status = 'PUBLISHED'", mapper, code).stream().findFirst();
    }

    public List<RuleDefinition> listLatest() {
        return jdbc.query("""
            select distinct on (code) * from meta_rule order by code, (status = 'PUBLISHED') desc, version desc""", mapper);
    }

    /** 加严覆盖了 code 的已发布规则。 */
    public List<RuleDefinition> overridesOf(String code) {
        return jdbc.query("select * from meta_rule where overrides = ? and status = 'PUBLISHED' order by code", mapper, code);
    }

    /** 声明 appliesTo 包含该 Operation 的已发布规则（不含覆盖规则，覆盖规则随被覆盖规则执行）。 */
    public List<String> appliesTo(String operationCode) {
        return jdbc.queryForList("""
            select code from meta_rule where status = 'PUBLISHED' and overrides is null
               and definition -> 'appliesTo' @> jsonb_build_array(?::text) order by code""", String.class, operationCode);
    }

    void markPublished(RuleDefinition r) {
        jdbc.update("update meta_rule set status = 'RETIRED' where code = ? and status = 'PUBLISHED'", r.code());
        jdbc.update("update meta_rule set status = 'PUBLISHED', published_at = ? where code = ? and version = ?",
            LocalDateTime.now(clock), r.code(), r.version());
    }

    public void retire(String code) {
        jdbc.update("update meta_rule set status = 'RETIRED' where code = ? and status = 'PUBLISHED'", code);
    }

    // —— 求值 ——

    /** 求值单条 CEL 或决策表规则（JAVA 规则由守卫提供者执行）。 */
    public RuleOutcome evaluate(RuleDefinition r, Map<String, Object> model, Map<String, Object> context) {
        try {
            return switch (r.kind()) {
                case CEL -> evaluateCel(r, model, context);
                case DECISION_TABLE -> evaluateTable(r, model, context);
                case JAVA -> throw new IllegalArgumentException("JAVA 规则由守卫执行");
            };
        } catch (LsBizException e) {
            if (e.getCode().startsWith("RULE_")) {
                // 求值失败或超限：按规则失败处理
                return new RuleOutcome(r.code(), r.level(), true, (r.message() == null ? r.name() : r.message()) + "（" + e.getMessage() + "）",
                    r.text("field"), Map.of(), e.getCode());
            }
            throw e;
        }
    }

    private RuleOutcome evaluateCel(RuleDefinition r, Map<String, Object> model, Map<String, Object> context) {
        String when = r.text("when");
        if (when != null && !cel.test(cel.compileCondition(when), model, context)) return RuleOutcome.pass(r, Map.of());
        boolean ok = cel.test(cel.compileCondition(r.text("assert")), model, context);
        return ok ? RuleOutcome.pass(r, Map.of())
            : new RuleOutcome(r.code(), r.level(), true, r.message() == null ? r.name() : r.message(), r.text("field"), Map.of(), null);
    }

    /**
     * 决策表：FIRST 返回首个命中行的输出；ALL 返回全部命中行（output.rows）并按最严格的 outcome 判定。
     * 无命中时使用 default。输出中 outcome=BLOCK/WARN 视为约束未满足，message 为提示。
     */
    @SuppressWarnings("unchecked")
    private RuleOutcome evaluateTable(RuleDefinition r, Map<String, Object> model, Map<String, Object> context) {
        String policy = r.definition().path("hitPolicy").asText("FIRST");
        List<Map<String, Object>> hits = new ArrayList<>();
        for (JsonNode row : r.definition().path("rows")) {
            if (cel.test(cel.compileCondition(row.path("when").asText()), model, context)) {
                hits.add((Map<String, Object>) CelValues.fromJson(row.path("output")));
                if (!"ALL".equals(policy)) break;
            }
        }
        if (hits.isEmpty() && r.definition().has("default")) hits.add((Map<String, Object>) CelValues.fromJson(r.definition().get("default")));
        Map<String, Object> output;
        if ("ALL".equals(policy)) {
            output = new LinkedHashMap<>();
            output.put("rows", hits);
        } else {
            output = hits.isEmpty() ? Map.of() : hits.get(0);
        }
        String worst = null;
        String message = null;
        for (Map<String, Object> h : hits) {
            String o = String.valueOf(h.getOrDefault("outcome", "PASS"));
            if (rank(o) > rank(worst)) {
                worst = o;
                message = h.get("message") == null ? null : String.valueOf(h.get("message"));
            }
        }
        if (rank(worst) == 0) return RuleOutcome.pass(r, output);
        ConstraintLevel level = "WARN".equals(worst) ? ConstraintLevel.L0 : r.level();
        return new RuleOutcome(r.code(), level, true, message != null ? message : r.message() != null ? r.message() : r.name(),
            r.text("field"), output, null);
    }

    private static int rank(String outcome) {
        if (outcome == null) return 0;
        return switch (outcome) {
            case "BLOCK" -> 2;
            case "WARN" -> 1;
            default -> 0;
        };
    }
}
