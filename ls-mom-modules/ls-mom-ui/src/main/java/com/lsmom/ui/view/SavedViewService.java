package com.lsmom.ui.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.core.error.LsBizException;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.FieldDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 列表保存视图：筛选＋列＋排序＋分组。个人视图只有本人可见；共享给角色的视图该角色的用户都能选用（只有创建者能改、删）。
 * 每个用户每个台账可设一个默认视图，打开列表时自动应用。
 * 保存时字段必须是该台账现有的字段；读取时原样返回，字段后来被弃用的由前端忽略并提示。
 */
@Service
public class SavedViewService {

    static final int MAX_PER_USER = 50;
    static final int MAX_BYTES = 32 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern IDENT = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");
    private static final Pattern ROLE = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");
    private static final Set<String> OPS = Set.of("eq", "ne", "gt", "ge", "lt", "le", "like", "startsWith", "in", "notIn", "between", "isNull", "notNull");
    private static final Set<String> SYSTEM_FIELDS = Set.of("id", "status", "orgId", "createdBy", "createdAt", "updatedBy", "updatedAt");

    private final JdbcTemplate jdbc;
    private final ConceptRegistry concepts;

    public SavedViewService(JdbcTemplate jdbc, ConceptRegistry concepts) {
        this.jdbc = jdbc;
        this.concepts = concepts;
    }

    public record View(long id, String name, boolean mine, String sharedRole, JsonNode definition) {
    }

    public record Views(List<View> views, Long defaultId) {
    }

    /** 本人可用的视图（自己的＋共享给本人角色的），个人视图在前。 */
    public Views list(long userId, List<String> roles, String concept) {
        List<Object> args = new ArrayList<>(List.of(userId, concept, userId));
        String roleCond = "";
        if (!roles.isEmpty()) {
            roleCond = " or shared_role in (" + String.join(", ", Collections.nCopies(roles.size(), "?")) + ")";
            args.addAll(roles);
        }
        args.add(userId);
        String sql = "select id, name, owner_user_id = ? as mine, shared_role, definition::text from ui_saved_view"
            + " where concept_code = ? and (owner_user_id = ?" + roleCond + ") order by (owner_user_id = ?) desc, name, id";
        List<View> views = jdbc.query(sql,
            (rs, i) -> new View(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getString(4), read(rs.getString(5))), args.toArray());
        List<Long> def = jdbc.queryForList("select view_id from ui_saved_view_default where user_id = ? and concept_code = ?", Long.class, userId, concept);
        Long defaultId = def.isEmpty() || views.stream().noneMatch(v -> v.id() == def.get(0)) ? null : def.get(0);
        return new Views(views, defaultId);
    }

    @Transactional
    public long create(long userId, String concept, String name, JsonNode definition, String sharedRole, boolean canShare) {
        ConceptDef c = concept(concept);
        String n = name(name);
        String role = role(sharedRole, canShare);
        String def = definition(c, definition);
        Integer count = jdbc.queryForObject("select count(*) from ui_saved_view where owner_user_id = ? and concept_code = ?", Integer.class, userId, concept);
        if (count != null && count >= MAX_PER_USER) throw new LsBizException("VIEW_LIMIT", "每个台账最多保存 " + MAX_PER_USER + " 个视图，请先删除不用的");
        return jdbc.queryForObject("""
            insert into ui_saved_view (concept_code, name, owner_user_id, shared_role, definition) values (?, ?, ?, ?, ?::jsonb) returning id""",
            Long.class, concept, n, userId, role, def);
    }

    @Transactional
    public void update(long userId, long id, String name, JsonNode definition, String sharedRole, boolean canShare) {
        Map<String, Object> row = owned(userId, id);
        ConceptDef c = concept(String.valueOf(row.get("concept_code")));
        jdbc.update("update ui_saved_view set name = ?, definition = ?::jsonb, shared_role = ?, updated_at = now() where id = ?",
            name(name), definition(c, definition), role(sharedRole, canShare), id);
    }

    @Transactional
    public void delete(long userId, long id) {
        owned(userId, id);
        jdbc.update("delete from ui_saved_view where id = ?", id);
    }

    /** 设为本人默认视图（viewId 为空时取消默认）；视图须对本人可见。 */
    @Transactional
    public void setDefault(long userId, List<String> roles, String concept, Long viewId) {
        if (viewId == null) {
            jdbc.update("delete from ui_saved_view_default where user_id = ? and concept_code = ?", userId, concept);
            return;
        }
        if (list(userId, roles, concept).views().stream().noneMatch(v -> v.id() == viewId)) {
            throw new LsBizException("NOT_FOUND", "视图不存在或你无权使用");
        }
        jdbc.update("""
            insert into ui_saved_view_default (user_id, concept_code, view_id) values (?, ?, ?)
            on conflict (user_id, concept_code) do update set view_id = excluded.view_id""", userId, concept, viewId);
    }

    // ---------- 校验 ----------

    private Map<String, Object> owned(long userId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("select concept_code, owner_user_id from ui_saved_view where id = ?", id);
        if (rows.isEmpty()) throw new LsBizException("NOT_FOUND", "视图不存在");
        if (((Number) rows.get(0).get("owner_user_id")).longValue() != userId) throw new LsBizException("ACCESS_DENIED", "只有创建者可以修改或删除该视图");
        return rows.get(0);
    }

    private ConceptDef concept(String code) {
        return concepts.find(code == null ? "" : code).orElseThrow(() -> new LsBizException("NOT_FOUND", "台账不存在：" + code));
    }

    private static String name(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.codePointCount(0, n.length()) > 64) throw LsBizException.validation(Map.of("name", "视图名称为 1～64 个字符"));
        return n;
    }

    private String role(String role, boolean canShare) {
        if (role == null || role.isBlank()) return null;
        if (!canShare) throw new LsBizException("ACCESS_DENIED", "没有把视图共享给角色的权限");
        if (!ROLE.matcher(role).matches()) throw LsBizException.validation(Map.of("sharedRole", "角色无效"));
        Integer n = jdbc.queryForObject("select count(*) from sys_role where role_key = ? and status = '0' and del_flag = '0'", Integer.class, role);
        if (n == null || n == 0) throw LsBizException.validation(Map.of("sharedRole", "角色不存在：" + role));
        return role;
    }

    /** 规整视图定义：只保留已知的键，字段必须属于该台账。 */
    String definition(ConceptDef c, JsonNode def) {
        if (def == null || !def.isObject()) throw LsBizException.validation(Map.of("definition", "视图定义必须是对象"));
        Set<String> fields = new HashSet<>(SYSTEM_FIELDS);
        for (FieldDef f : c.fields()) fields.add(f.code());
        ObjectNode out = JSON.createObjectNode();
        ArrayNode filters = out.putArray("filters");
        for (JsonNode f : array(def, "filters", 50)) {
            String field = field(f.path("field").asText(), fields);
            String op = f.path("op").asText();
            if (!OPS.contains(op)) throw LsBizException.validation(Map.of("definition", "不支持的筛选条件：" + op));
            ObjectNode o = filters.addObject().put("field", field).put("op", op);
            if (f.has("value")) o.set("value", f.get("value"));
        }
        ArrayNode columns = out.putArray("columns");
        for (JsonNode col : array(def, "columns", 200)) {
            ObjectNode o = columns.addObject().put("code", field(col.path("code").asText(), fields)).put("visible", col.path("visible").asBoolean(true));
            if (col.hasNonNull("width")) o.put("width", Math.max(40, Math.min(2000, col.get("width").asInt())));
            String fixed = col.path("fixed").asText("");
            if (fixed.equals("left") || fixed.equals("right")) o.put("fixed", fixed);
        }
        ArrayNode sort = out.putArray("sort");
        for (JsonNode s : array(def, "sort", 5)) {
            String dir = s.path("dir").asText("asc");
            sort.addObject().put("field", field(s.path("field").asText(), fields)).put("dir", dir.equals("desc") ? "desc" : "asc");
        }
        if (def.hasNonNull("groupBy")) out.put("groupBy", field(def.get("groupBy").asText(), fields));
        if (def.hasNonNull("keyword")) {
            String k = def.get("keyword").asText();
            if (k.length() > 100) throw LsBizException.validation(Map.of("definition", "关键字最多 100 个字符"));
            out.put("keyword", k);
        }
        String text = out.toString();
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) throw LsBizException.validation(Map.of("definition", "视图定义过大"));
        return text;
    }

    private static Iterable<JsonNode> array(JsonNode def, String key, int max) {
        JsonNode a = def.path(key);
        if (a.isMissingNode() || a.isNull()) return List.of();
        if (!a.isArray() || a.size() > max) throw LsBizException.validation(Map.of("definition", key + " 必须是不超过 " + max + " 项的数组"));
        return a;
    }

    private static String field(String code, Set<String> fields) {
        if (!IDENT.matcher(code).matches() || !fields.contains(code)) throw LsBizException.validation(Map.of("definition", "字段不存在：" + code));
        return code;
    }

    private static JsonNode read(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }
}
