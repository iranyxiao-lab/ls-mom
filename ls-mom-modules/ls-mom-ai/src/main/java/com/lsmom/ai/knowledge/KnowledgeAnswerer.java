package com.lsmom.ai.knowledge;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.MetricPort;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识问答：业务数字一律来自已发布指标（返回数值、指标编码与版本、可钻取的记录引用）；
 * 其他问题从调用者可见的已发布文档中摘取相关句子作答，每句标注引用。不生成没有来源的内容。
 */
public class KnowledgeAnswerer {

    public static final int DRILL_REFS = 20;

    private final KnowledgeService knowledge;
    private final MetricPort metrics;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public KnowledgeAnswerer(KnowledgeService knowledge, MetricPort metrics, JdbcTemplate jdbc, Clock clock) {
        this.knowledge = knowledge;
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Map<String, Object> ask(String question, ActorContext actor) {
        if (question == null || question.isBlank()) throw LsBizException.validation(Map.of("question", "请输入问题"));
        if (metrics != null) {
            var parsed = MetricQuestion.parse(question, metrics.metrics(), orgs(), LocalDate.now(clock));
            if (parsed.isPresent()) return metricAnswer(question, parsed.get(), actor);
        }
        return documentAnswer(question, actor);
    }

    private List<MetricQuestion.Org> orgs() {
        return jdbc.query("select id, name from core_org_unit where valid_to is null or valid_to >= current_date",
            (rs, i) -> new MetricQuestion.Org(rs.getLong(1), rs.getString(2)));
    }

    private Map<String, Object> metricAnswer(String question, MetricQuestion.Parsed p, ActorContext actor) {
        List<Map<String, Object>> filters = new ArrayList<>();
        if (p.org() != null) {
            if (!actor.dataScope().all() && !actor.dataScope().allowsOrg(p.org().id())) {
                throw new LsBizException("ACCESS_DENIED", "「" + p.org().name() + "」不在你的数据范围内");
            }
            filters.add(MetricQuestion.filterOrg(jdbc.queryForList("""
                with recursive down as (select id from core_org_unit where id = ? union all
                  select c.id from core_org_unit c join down on c.parent_id = down.id) select id from down""", Long.class, p.org().id())));
        }
        String from = p.from() == null ? null : p.from().atStartOfDay().toString();
        String to = p.to() == null ? null : p.to().atStartOfDay().toString();
        MetricPort.Query q = new MetricPort.Query(p.metric().code(), from, to, filters);
        MetricPort.Value v = metrics.value(q, actor);
        MetricPort.Drill d = metrics.drill(q, DRILL_REFS, actor);
        String scope = (p.rangeLabel() == null ? "" : p.rangeLabel() + "（" + p.from() + " 至 " + p.to().minusDays(1) + "）")
            + (p.org() == null ? "" : p.org().name());
        String unit = v.unit() == null ? "" : " " + v.unit();
        String text = (scope.isEmpty() ? "" : scope) + v.name() + "：" + format(v.value()) + unit + "（指标 " + v.code() + " v" + v.version()
            + "，口径：" + p.metric().description() + "）";
        List<Map<String, Object>> refs = d.rows().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", d.concept());
            m.put("id", String.valueOf(r.get("id")));
            m.put("label", String.valueOf(r.getOrDefault("code", r.get("id"))) + (r.get("title") == null ? "" : " " + r.get("title")));
            return m;
        }).toList();
        Map<String, Object> drill = new LinkedHashMap<>();
        drill.put("metric", v.code());
        drill.put("version", v.version());
        drill.put("from", from);
        drill.put("to", to);
        drill.put("filters", filters);
        drill.put("total", d.total());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "METRIC");
        out.put("question", question);
        out.put("answer", text);
        out.put("value", v.value());
        out.put("unit", v.unit());
        out.put("metric", Map.of("code", v.code(), "version", v.version(), "name", v.name()));
        out.put("refs", refs);
        out.put("drill", drill);
        out.put("citations", List.of());
        return out;
    }

    private Map<String, Object> documentAnswer(String question, ActorContext actor) {
        List<KnowledgeService.Hit> hits = knowledge.search(question, 4, actor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "DOCUMENT");
        out.put("question", question);
        if (hits.isEmpty()) {
            out.put("answer", "知识库中没有找到你有权查看的、已发布的相关内容。");
            out.put("citations", List.of());
            return out;
        }
        Set<String> qTokens = new HashSet<>(HashingEmbedder.tokens(question));
        record Sentence(String text, int cite, double score) {
        }
        List<Sentence> all = new ArrayList<>();
        List<Map<String, Object>> citations = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeService.Hit h = hits.get(i);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("n", i + 1);
            c.put("documentId", h.documentId());
            c.put("documentCode", h.documentCode());
            c.put("title", h.title());
            c.put("version", h.version());
            c.put("chunkNo", h.chunkNo());
            c.put("heading", h.heading());
            c.put("excerpt", h.text());
            c.put("score", Math.round(h.score() * 1000) / 1000.0);
            citations.add(c);
            for (String s : h.text().split("(?<=[。；！？])|\\n")) {
                String t = s.strip();
                if (t.length() < 4) continue;
                List<String> tk = HashingEmbedder.tokens(t);
                long overlap = tk.stream().filter(qTokens::contains).count();
                if (overlap == 0) continue;
                all.add(new Sentence(t, i + 1, overlap / Math.sqrt(tk.size()) + h.score()));
            }
        }
        List<Sentence> top = all.stream().sorted(Comparator.comparingDouble(Sentence::score).reversed()).limit(3).toList();
        if (top.isEmpty()) top = List.of(new Sentence(hits.get(0).text(), 1, 0));
        StringBuilder a = new StringBuilder();
        for (Sentence s : top) a.append(s.text()).append(" [").append(s.cite()).append("]\n");
        out.put("answer", a.toString().strip());
        out.put("citations", citations);
        return out;
    }

    private static String format(Object v) {
        if (v instanceof java.math.BigDecimal b) return b.stripTrailingZeros().toPlainString();
        return String.valueOf(v);
    }
}
