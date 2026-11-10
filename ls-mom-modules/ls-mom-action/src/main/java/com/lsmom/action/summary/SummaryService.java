package com.lsmom.action.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.SummaryContextProvider;
import com.lsmom.action.state.StateSnapshot;
import com.lsmom.action.state.StateStore;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 摘要：上下文（带引用的事实）→ 草稿（每个要点至少一条引用，引用必须来自上下文）→ 真人发布或作废。
 * 发布只改变摘要自身状态，不修改被摘要的对象。
 */
@Component
public class SummaryService implements StateStore {

    public static final String CONCEPT = "ls.core.Summary";
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    public record Point(String text, List<SummaryContextProvider.Ref> refs) {
    }

    private final JdbcTemplate jdbc;
    private final Map<String, SummaryContextProvider> providers = new LinkedHashMap<>();

    public SummaryService(JdbcTemplate jdbc, List<SummaryContextProvider> providers) {
        this.jdbc = jdbc;
        providers.forEach(this::register);
    }

    public void register(SummaryContextProvider p) {
        providers.put(p.kind(), p);
    }

    public Set<String> kinds() {
        return providers.keySet();
    }

    public SummaryContextProvider.Context context(String kind, String subjectId, ActorContext actor) {
        SummaryContextProvider p = providers.get(kind);
        if (p == null) throw LsBizException.validation(Map.of("kind", "不支持的摘要类型：" + kind + "（可用：" + String.join("、", providers.keySet()) + "）"));
        return p.load(subjectId, actor);
    }

    /** 草拟：校验引用后保存为 DRAFT，返回 ID。 */
    public long draft(String kind, String subjectId, String content, List<Point> points, String model, ActorContext actor) {
        SummaryContextProvider.Context ctx = context(kind, subjectId, actor);
        if (content == null || content.isBlank()) throw LsBizException.validation(Map.of("content", "摘要正文必填"));
        if (points == null || points.isEmpty()) throw LsBizException.validation(Map.of("points", "至少一个要点"));
        Map<String, SummaryContextProvider.Ref> known = new LinkedHashMap<>();
        ctx.facts().forEach(f -> known.put(f.ref().key(), f.ref()));
        Map<String, SummaryContextProvider.Ref> cited = new LinkedHashMap<>();
        List<Map<String, Object>> stored = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            if (p.text() == null || p.text().isBlank()) errors.put("points[" + i + "].text", "要点内容必填");
            if (p.refs() == null || p.refs().isEmpty()) {
                errors.put("points[" + i + "].refs", "每个要点至少引用一条记录");
                continue;
            }
            List<SummaryContextProvider.Ref> refs = new ArrayList<>();
            for (SummaryContextProvider.Ref r : p.refs()) {
                SummaryContextProvider.Ref k = known.get(r.type() + ":" + r.id());
                if (k == null) {
                    errors.put("points[" + i + "].refs", "引用 " + r.type() + " #" + r.id() + " 不在该对象的上下文中");
                    break;
                }
                refs.add(k);
                cited.putIfAbsent(k.key(), k);
            }
            stored.add(Map.of("text", p.text() == null ? "" : p.text().trim(), "refs", refs));
        }
        if (!errors.isEmpty()) throw new LsBizException("SUMMARY_CITATION_INVALID", "摘要引用不合法：" + String.join("；", errors.values()), Map.of("errors", errors));
        boolean agent = actor.type() == ActorContext.ActorType.AGENT;
        return jdbc.queryForObject("""
            insert into core_summary (kind, subject_concept, subject_id, title, content, points, citations, org_unit_id, source, model,
              created_by_party, on_behalf_of)
            values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?) returning id""", Long.class,
            kind, ctx.subjectConcept(), ctx.subjectId(), ctx.title(), content.trim(), json(stored), json(new ArrayList<>(cited.values())), ctx.orgUnitId(),
            agent ? "AGENT" : "HUMAN", model, actor.actorParty(), actor.onBehalfOf());
    }

    public Optional<Map<String, Object>> find(long id) {
        return jdbc.queryForList("select * from core_summary where id = ?", id).stream().findFirst().map(SummaryService::view);
    }

    /** 列表（按调用者数据域过滤所属组织）。 */
    public List<Map<String, Object>> list(String kind, String status, String subjectConcept, String subjectId, ActorContext actor) {
        StringBuilder sql = new StringBuilder("select * from core_summary where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (kind != null) { sql.append(" and kind = ?"); args.add(kind); }
        if (status != null) { sql.append(" and status = ?"); args.add(status); }
        if (subjectConcept != null) { sql.append(" and subject_concept = ?"); args.add(subjectConcept); }
        if (subjectId != null) { sql.append(" and subject_id = ?"); args.add(subjectId); }
        if (!actor.dataScope().all()) {
            if (actor.dataScope().orgUnitIds().isEmpty()) sql.append(" and false");
            else {
                sql.append(" and org_unit_id in (").append(String.join(",", actor.dataScope().orgUnitIds().stream().map(x -> "?").toList())).append(")");
                args.addAll(actor.dataScope().orgUnitIds());
            }
        }
        sql.append(" order by id desc limit 200");
        return jdbc.queryForList(sql.toString(), args.toArray()).stream().map(SummaryService::view).toList();
    }

    static Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("kind", r.get("kind"));
        m.put("subjectConcept", r.get("subject_concept"));
        m.put("subjectId", r.get("subject_id"));
        m.put("title", r.get("title"));
        m.put("content", r.get("content"));
        m.put("points", read(r.get("points")));
        m.put("citations", read(r.get("citations")));
        m.put("status", r.get("status"));
        m.put("orgUnitId", r.get("org_unit_id"));
        m.put("source", r.get("source"));
        m.put("model", r.get("model"));
        m.put("createdByParty", r.get("created_by_party"));
        m.put("onBehalfOf", r.get("on_behalf_of"));
        m.put("publishedBy", r.get("published_by"));
        m.put("publishedAt", r.get("published_at"));
        m.put("discardReason", r.get("discard_reason"));
        m.put("createdAt", r.get("created_at"));
        m.put("version", r.get("version"));
        return m;
    }

    // —— 状态读写（发布 / 作废经 Operation 网关）——

    @Override
    public String concept() {
        return CONCEPT;
    }

    @Override
    public StateSnapshot load(String id) {
        Map<String, Object> r = jdbc.queryForList("select id, status, version, org_unit_id, created_by_party, on_behalf_of from core_summary where id = ?",
            Long.parseLong(id)).stream().findFirst().orElseThrow(() -> LsBizException.notFound("摘要", id));
        Object owner = r.get("on_behalf_of") != null ? r.get("on_behalf_of") : r.get("created_by_party");
        return new StateSnapshot(id, (String) r.get("status"), ((Number) r.get("version")).intValue(),
            r.get("org_unit_id") == null ? null : ((Number) r.get("org_unit_id")).longValue(), owner == null ? null : ((Number) owner).longValue());
    }

    @Override
    public StateSnapshot write(String id, int expectedVersion, String from, String to, Map<String, Object> changes) {
        List<Object> args = new ArrayList<>(List.of(to));
        StringBuilder set = new StringBuilder("status = ?, version = version + 1, updated_at = now()");
        for (var e : changes.entrySet()) {
            String col = switch (e.getKey()) {
                case "publishedBy" -> "published_by";
                case "publishedAt" -> "published_at";
                case "discardReason" -> "discard_reason";
                default -> throw new IllegalArgumentException("摘要不能修改字段 " + e.getKey());
            };
            set.append(", ").append(col).append(" = ?");
            args.add(e.getValue());
        }
        args.add(Long.parseLong(id));
        args.add(expectedVersion);
        args.add(from);
        int n = jdbc.update("update core_summary set " + set + " where id = ? and version = ? and status = ?", args.toArray());
        if (n == 0) throw new LsBizException("CONCURRENT_MODIFICATION", "摘要已被他人修改，请刷新后重试");
        return load(id);
    }

    private static Object read(Object json) {
        try {
            return json == null ? List.of() : JSON.readValue(json.toString(), Object.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
