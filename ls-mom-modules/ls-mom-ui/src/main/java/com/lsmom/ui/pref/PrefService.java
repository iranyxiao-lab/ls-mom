package com.lsmom.ui.pref;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户界面偏好与收藏、最近访问（服务端保存，前端只做缓存）。
 * 只接受已知偏好项与取值；收藏、最近访问只保存站内地址与显示文字，条数有上限。
 */
@Service
public class PrefService {

    static final int RECENT_MAX = 30;
    static final int FAVORITE_MAX = 100;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 偏好项：取值白名单（null 表示布尔）。 */
    static final Map<String, Set<String>> PREFS = new LinkedHashMap<>();

    static {
        PREFS.put("theme", Set.of("system", "light", "dark"));
        PREFS.put("density", Set.of("auto", "comfortable", "compact"));
        PREFS.put("navType", Set.of("left", "top", "mix"));
        PREFS.put("tagsView", null);
        PREFS.put("hotkeyHints", null);
        // 界面模式：auto 按设备宽度与用户类型自动选择电脑版或手机版（/m），desktop / mobile 为用户手动选择
        PREFS.put("uiMode", Set.of("auto", "desktop", "mobile"));
        // 新手引导：看完（done）或跳过（skipped）后不再自动出现，可从帮助菜单重看；pending 表示重新自动出现
        for (String tour : List.of("tourWorkbench", "tourDesigner", "tourMobileReport")) PREFS.put(tour, Set.of("done", "skipped", "pending"));
    }

    private final JdbcTemplate jdbc;

    public PrefService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Prefs(JsonNode prefs, JsonNode recent, JsonNode favorites) {
    }

    public Prefs get(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select prefs::text as prefs, nav::text as nav from ui_user_pref where user_id = ?", userId);
        if (rows.isEmpty()) return new Prefs(JSON.createObjectNode(), JSON.createArrayNode(), JSON.createArrayNode());
        JsonNode nav = read((String) rows.get(0).get("nav"));
        return new Prefs(read((String) rows.get(0).get("prefs")), nav.path("recent").isArray() ? nav.get("recent") : JSON.createArrayNode(),
            nav.path("favorites").isArray() ? nav.get("favorites") : JSON.createArrayNode());
    }

    /** 合并更新偏好项（只更新传入的项）；返回合并后的全部偏好。 */
    public JsonNode updatePrefs(long userId, JsonNode body) {
        if (body == null || !body.isObject()) throw new LsBizException("VALIDATION_FAILED", "偏好必须是对象");
        ObjectNode clean = JSON.createObjectNode();
        Map<String, String> errors = new LinkedHashMap<>();
        body.fields().forEachRemaining(e -> {
            String k = e.getKey();
            JsonNode v = e.getValue();
            if (!PREFS.containsKey(k)) {
                errors.put(k, "未知的偏好项");
            } else if (PREFS.get(k) == null) {
                if (v.isBoolean()) clean.put(k, v.asBoolean());
                else errors.put(k, "应为是或否");
            } else if (v.isTextual() && PREFS.get(k).contains(v.asText())) {
                clean.put(k, v.asText());
            } else {
                errors.put(k, "取值应为 " + String.join("、", PREFS.get(k).stream().sorted().toList()));
            }
        });
        if (!errors.isEmpty()) throw LsBizException.validation(errors);
        jdbc.update("""
            insert into ui_user_pref (user_id, prefs) values (?, ?::jsonb)
            on conflict (user_id) do update set prefs = ui_user_pref.prefs || excluded.prefs, updated_at = now()""", userId, clean.toString());
        return get(userId).prefs();
    }

    /** 整体替换收藏与最近访问（前端合并后提交）。 */
    public void updateNav(long userId, JsonNode body) {
        ObjectNode nav = JSON.createObjectNode();
        nav.set("recent", entries(body.path("recent"), RECENT_MAX, "recent"));
        nav.set("favorites", entries(body.path("favorites"), FAVORITE_MAX, "favorites"));
        jdbc.update("""
            insert into ui_user_pref (user_id, nav) values (?, ?::jsonb)
            on conflict (user_id) do update set nav = excluded.nav, updated_at = now()""", userId, nav.toString());
    }

    private static ArrayNode entries(JsonNode list, int max, String field) {
        ArrayNode out = JSON.createArrayNode();
        if (list.isMissingNode() || list.isNull()) return out;
        if (!list.isArray()) throw LsBizException.validation(Map.of(field, "应为列表"));
        if (list.size() > max) throw LsBizException.validation(Map.of(field, "最多 " + max + " 条"));
        Set<String> seen = new java.util.HashSet<>();
        for (JsonNode e : list) {
            String path = e.path("path").asText("");
            String title = e.path("title").asText("");
            String kind = e.path("kind").asText("page");
            // 只接受站内地址（防止存入外链或脚本地址）
            if (!path.startsWith("/") || path.startsWith("//") || path.length() > 500 || title.isBlank() || title.length() > 200
                || !Set.of("page", "object").contains(kind)) {
                throw LsBizException.validation(Map.of(field, "条目格式不正确：" + (path.length() > 60 ? path.substring(0, 60) : path)));
            }
            if (!seen.add(path)) continue;
            ObjectNode n = out.addObject();
            n.put("kind", kind);
            n.put("path", path);
            n.put("title", title);
            if (e.hasNonNull("sub")) n.put("sub", cut(e.get("sub").asText(), 200));
            if (e.hasNonNull("icon")) n.put("icon", cut(e.get("icon").asText(), 50));
            n.put("at", e.path("at").asLong(0));
        }
        return out;
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static JsonNode read(String s) {
        try {
            return s == null ? JSON.createObjectNode() : JSON.readTree(s);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }
}
