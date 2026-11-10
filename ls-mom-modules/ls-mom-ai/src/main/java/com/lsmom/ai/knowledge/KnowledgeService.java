package com.lsmom.ai.knowledge;

import com.lsmom.action.api.ActorContext;
import com.lsmom.core.document.Classification;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库：文档版本（修订稿 → 发布）、分块向量化、带权限的检索。
 * 可见性：文档组织为空（全公司），或在调用者数据域内，或是调用者所在组织的上级；且文档密级不高于调用者的密级许可。
 * 检索时在 SQL 中先按可见性与"当前已发布版本"过滤再排序；返回前再逐条复核文档当前状态与可见性。
 */
public class KnowledgeService {

    public static final int CHUNK_CHARS = 360;
    static final double MIN_SCORE = 0.08;

    public record Access(boolean allOrgs, Set<Long> orgs, Classification clearance) {
        boolean sees(Long org, String classification) {
            return (org == null || allOrgs || orgs.contains(org)) && Classification.valueOf(classification).allowedFor(clearance);
        }
    }

    public record Hit(long chunkId, long documentId, String documentCode, String title, int version, int chunkNo, String heading, String text,
                      double score) {
    }

    private final JdbcTemplate jdbc;
    private final Embedder embedder;
    private final TransactionTemplate tx;

    public KnowledgeService(JdbcTemplate jdbc, Embedder embedder, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.embedder = embedder;
        this.tx = new TransactionTemplate(txm);
    }

    public Embedder embedder() {
        return embedder;
    }

    // —— 权限 ——

    public Access access(ActorContext actor) {
        Classification clearance = actor.superAdmin() ? Classification.SECRET : clearanceOf(actor.effectiveParty());
        if (actor.superAdmin() || actor.dataScope().all()) return new Access(true, Set.of(), clearance);
        Set<Long> orgs = new HashSet<>(actor.dataScope().orgUnitIds());
        Long party = actor.effectiveParty();
        if (party != null) {
            orgs.addAll(jdbc.queryForList("""
                with recursive up as (
                  select o.id, o.parent_id from core_org_unit o join core_party_org po on po.org_unit_id = o.id where po.party_id = ?
                  union
                  select p.id, p.parent_id from core_org_unit p join up on p.id = up.parent_id
                ) select id from up""", Long.class, party));
        }
        return new Access(false, Set.copyOf(orgs), clearance);
    }

    private Classification clearanceOf(Long party) {
        if (party == null) return Classification.PUBLIC;
        return jdbc.queryForList("select clearance from core_party where id = ?", String.class, party).stream().findFirst()
            .filter(Objects::nonNull).map(Classification::valueOf).orElse(Classification.INTERNAL);
    }

    // —— 文档与版本 ——

    public long create(String code, String title, String category, Long orgUnitId, String classification, String content, ActorContext actor) {
        if (code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,63}")) throw LsBizException.validation(Map.of("code", "编码为 2–64 位字母数字或 . _ -"));
        if (title == null || title.isBlank()) throw LsBizException.validation(Map.of("title", "标题必填"));
        if (content == null || content.isBlank()) throw LsBizException.validation(Map.of("content", "正文必填"));
        Classification c = classification == null ? Classification.INTERNAL : Classification.valueOf(classification);
        if (orgUnitId == null ? !actor.dataScope().all() && !actor.superAdmin() : !actor.dataScope().allowsOrg(orgUnitId) && !actor.superAdmin()) {
            throw new LsBizException("ACCESS_DENIED", orgUnitId == null ? "只有全公司数据范围的人能建全公司文档" : "不能在数据范围以外的组织建文档");
        }
        if (!c.allowedFor(access(actor).clearance())) throw new LsBizException("ACCESS_DENIED", "文档密级高于你的密级许可");
        if (!jdbc.queryForList("select 1 from core_knowledge_document where code = ?", code).isEmpty()) {
            throw LsBizException.validation(Map.of("code", "编码已存在：" + code));
        }
        return tx.execute(s -> {
            Long id = jdbc.queryForObject("""
                insert into core_knowledge_document (code, title, category, org_unit_id, classification, created_by) values (?, ?, ?, ?, ?, ?) returning id""",
                Long.class, code, title.trim(), category == null ? "SOP" : category, orgUnitId, c.name(), actor.effectiveParty());
            jdbc.update("insert into core_knowledge_version (document_id, version, content, change_note, created_by) values (?, 1, ?, '初稿', ?)",
                id, content, actor.effectiveParty());
            return id;
        });
    }

    /** 保存修订稿：已有修订稿则覆盖，否则在最新版本号上加一。修订稿在发布前不可被检索。 */
    public int saveDraft(long documentId, String content, String note, ActorContext actor) {
        Map<String, Object> d = visibleDocument(documentId, actor);
        if (content == null || content.isBlank()) throw LsBizException.validation(Map.of("content", "正文必填"));
        return tx.execute(s -> {
            List<Integer> draft = jdbc.queryForList("select version from core_knowledge_version where document_id = ? and status = 'DRAFT'", Integer.class, d.get("id"));
            if (!draft.isEmpty()) {
                jdbc.update("update core_knowledge_version set content = ?, change_note = ?, created_by = ?, created_at = now() where document_id = ? and version = ?",
                    content, note, actor.effectiveParty(), documentId, draft.get(0));
                return draft.get(0);
            }
            Integer next = jdbc.queryForObject("select coalesce(max(version), 0) + 1 from core_knowledge_version where document_id = ?", Integer.class, documentId);
            jdbc.update("insert into core_knowledge_version (document_id, version, content, change_note, created_by) values (?, ?, ?, ?, ?)",
                documentId, next, content, note, actor.effectiveParty());
            return next;
        });
    }

    /** 发布修订稿（须真人）：旧发布版本改为已替代，分块整体替换为新版本。 */
    public int publish(long documentId, ActorContext actor) {
        if (actor.type() != ActorContext.ActorType.HUMAN) throw new LsBizException("AGENT_TOOL_DENIED", "知识库发布须由真人执行");
        Map<String, Object> d = visibleDocument(documentId, actor);
        return tx.execute(s -> {
            List<Map<String, Object>> draft = jdbc.queryForList("select version, content from core_knowledge_version where document_id = ? and status = 'DRAFT' for update", documentId);
            if (draft.isEmpty()) throw new LsBizException("KB_NO_DRAFT", "没有待发布的修订稿");
            int v = ((Number) draft.get(0).get("version")).intValue();
            jdbc.update("update core_knowledge_version set status = 'SUPERSEDED' where document_id = ? and status = 'PUBLISHED'", documentId);
            jdbc.update("update core_knowledge_version set status = 'PUBLISHED', published_by = ?, published_at = now() where document_id = ? and version = ?",
                actor.effectiveParty(), documentId, v);
            jdbc.update("update core_knowledge_document set published_version = ?, updated_at = now() where id = ?", v, documentId);
            index(documentId, v, (String) draft.get(0).get("content"), d);
            return v;
        });
    }

    private void index(long documentId, int version, String content, Map<String, Object> d) {
        jdbc.update("delete from core_knowledge_chunk where document_id = ?", documentId);
        int no = 0;
        for (String[] c : chunks(content)) {
            jdbc.update("""
                insert into core_knowledge_chunk (document_id, version, chunk_no, org_unit_id, classification, heading, text, embed_model, embedding)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::vector)""", documentId, version, ++no, d.get("org_unit_id"), d.get("classification"), c[0], c[1],
                embedder.model(), vector(embedder.embed((c[0] == null ? "" : c[0] + "\n") + c[1])));
        }
    }

    /**
     * 补建索引：已发布版本缺少当前向量模型的分块（初始化数据导入、更换向量模型、组织或密级调整后）时重建。返回重建的文档数。
     */
    public int reindexStale() {
        List<Map<String, Object>> stale = jdbc.queryForList("""
            select d.*, v.content from core_knowledge_document d
              join core_knowledge_version v on v.document_id = d.id and v.version = d.published_version and v.status = 'PUBLISHED'
             where not exists (select 1 from core_knowledge_chunk c where c.document_id = d.id and c.version = d.published_version
                                 and c.embed_model = ? and c.classification = d.classification
                                 and c.org_unit_id is not distinct from d.org_unit_id)""", embedder.model());
        for (Map<String, Object> d : stale) {
            tx.executeWithoutResult(s -> index(((Number) d.get("id")).longValue(), ((Number) d.get("published_version")).intValue(), (String) d.get("content"), d));
        }
        return stale.size();
    }

    public List<Map<String, Object>> list(ActorContext actor) {
        Access a = access(actor);
        return jdbc.queryForList("""
            select d.id, d.code, d.title, d.category, d.org_unit_id, o.name as org_name, d.classification, d.published_version, d.updated_at,
                   (select v.version from core_knowledge_version v where v.document_id = d.id and v.status = 'DRAFT') as draft_version,
                   (select count(*) from core_knowledge_chunk c where c.document_id = d.id) as chunks
              from core_knowledge_document d left join core_org_unit o on o.id = d.org_unit_id order by d.code""").stream()
            .filter(r -> a.sees(num(r.get("org_unit_id")), (String) r.get("classification"))).map(KnowledgeService::camel).toList();
    }

    public Map<String, Object> get(long documentId, ActorContext actor) {
        Map<String, Object> d = camel(visibleDocument(documentId, actor));
        d.put("versions", jdbc.queryForList("""
            select version, status, change_note, content, created_at, published_at from core_knowledge_version where document_id = ? order by version desc""",
            documentId).stream().map(KnowledgeService::camel).toList());
        return d;
    }

    private Map<String, Object> visibleDocument(long id, ActorContext actor) {
        Map<String, Object> d = jdbc.queryForList("select * from core_knowledge_document where id = ?", id).stream().findFirst()
            .orElseThrow(() -> LsBizException.notFound("文档", id));
        if (!access(actor).sees(num(d.get("org_unit_id")), (String) d.get("classification"))) throw LsBizException.notFound("文档", id);
        return d;
    }

    // —— 检索 ——

    /** 检索前过滤：只在调用者可见、当前已发布版本、同一向量模型的分块中排序；返回前逐条复核。 */
    public List<Hit> search(String query, int k, ActorContext actor) {
        Access a = access(actor);
        String vec = vector(embedder.embed(query));
        List<Object> args = new ArrayList<>(List.of(vec, embedder.model()));
        List<String> levels = Arrays.stream(Classification.values()).filter(c -> c.allowedFor(a.clearance())).map(Enum::name).toList();
        StringBuilder where = new StringBuilder(" and c.classification in (" + levels.stream().map(x -> "?").collect(Collectors.joining(",")) + ")");
        args.addAll(levels);
        if (!a.allOrgs()) {
            where.append(" and (c.org_unit_id is null");
            if (!a.orgs().isEmpty()) {
                where.append(" or c.org_unit_id in (").append(a.orgs().stream().map(x -> "?").collect(Collectors.joining(","))).append(")");
                args.addAll(a.orgs());
            }
            where.append(")");
        }
        args.add(vec);
        args.add(Math.max(1, Math.min(k, 20)) * 3);
        List<Hit> hits = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
            select c.id, c.document_id, d.code, d.title, c.version, c.chunk_no, c.heading, c.text, 1 - (c.embedding <=> ?::vector) as score
              from core_knowledge_chunk c join core_knowledge_document d on d.id = c.document_id
             where c.version = d.published_version and c.embed_model = ?""" + where + " order by c.embedding <=> ?::vector limit ?", args.toArray())) {
            double score = ((Number) r.get("score")).doubleValue();
            if (score < MIN_SCORE) continue;
            long doc = ((Number) r.get("document_id")).longValue();
            int version = ((Number) r.get("version")).intValue();
            if (!stillVisible(doc, version, a)) continue;
            hits.add(new Hit(((Number) r.get("id")).longValue(), doc, (String) r.get("code"), (String) r.get("title"), version,
                ((Number) r.get("chunk_no")).intValue(), (String) r.get("heading"), (String) r.get("text"), score));
            if (hits.size() >= k) break;
        }
        return hits;
    }

    /** 返回前复核：文档仍可见，且该版本仍是已发布状态。 */
    private boolean stillVisible(long doc, int version, Access a) {
        return jdbc.queryForList("""
            select d.org_unit_id, d.classification from core_knowledge_document d join core_knowledge_version v on v.document_id = d.id
             where d.id = ? and v.version = ? and v.status = 'PUBLISHED' and d.published_version = v.version""", doc, version).stream()
            .anyMatch(r -> a.sees(num(r.get("org_unit_id")), (String) r.get("classification")));
    }

    // —— 分块 ——

    /** 按标题（# 开头）与空行分段，每块不超过 {@link #CHUNK_CHARS} 字；返回 [标题, 正文]。 */
    static List<String[]> chunks(String content) {
        List<String[]> out = new ArrayList<>();
        String heading = null;
        StringBuilder cur = new StringBuilder();
        for (String line : content.replace("\r", "").split("\n")) {
            String t = line.strip();
            if (t.startsWith("#")) {
                emit(out, heading, cur);
                heading = t.replaceFirst("^#+\\s*", "");
                continue;
            }
            if (t.isEmpty()) {
                if (cur.length() > CHUNK_CHARS / 2) emit(out, heading, cur);
                continue;
            }
            if (cur.length() + t.length() > CHUNK_CHARS) emit(out, heading, cur);
            if (!cur.isEmpty()) cur.append('\n');
            cur.append(t);
        }
        emit(out, heading, cur);
        return out;
    }

    private static void emit(List<String[]> out, String heading, StringBuilder cur) {
        if (cur.isEmpty()) return;
        String s = cur.toString();
        while (s.length() > CHUNK_CHARS) {
            out.add(new String[]{heading, s.substring(0, CHUNK_CHARS)});
            s = s.substring(CHUNK_CHARS);
        }
        out.add(new String[]{heading, s});
        cur.setLength(0);
    }

    static String vector(float[] v) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) b.append(',');
            b.append(v[i]);
        }
        return b.append(']').toString();
    }

    static Long num(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    static Map<String, Object> camel(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        r.forEach((k, v) -> {
            StringBuilder b = new StringBuilder();
            boolean up = false;
            for (char c : k.toCharArray()) {
                if (c == '_') up = true;
                else {
                    b.append(up ? Character.toUpperCase(c) : c);
                    up = false;
                }
            }
            m.put(b.toString(), v);
        });
        return m;
    }
}
