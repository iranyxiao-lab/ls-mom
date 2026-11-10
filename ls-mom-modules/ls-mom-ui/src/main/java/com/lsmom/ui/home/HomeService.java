package com.lsmom.ui.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 首页卡片布局：角色默认布局（meta_view，类型 HOME，编码 home.&lt;角色键&gt;，按版本保存）＋ 个人调整（ui_user_pref.home）。
 * 生效顺序：个人布局 → 用户第一个有默认布局的角色 → home.default。
 * 布局只保存卡片编码、顺序与是否隐藏；卡片是否显示还取决于前端注册的卡片与用户权限。
 */
@Service
public class HomeService {

    static final String DEFAULT = "home.default";
    static final int MAX_CARDS = 30;
    private static final Pattern CARD = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-zA-Z][a-zA-Z0-9]*){1,4}$");
    private static final Pattern ROLE = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    public HomeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Layout(JsonNode cards, String source, String roleKey, JsonNode roleCards) {
    }

    /** 当前用户的首页布局。source：personal（个人调整）或 role（角色默认）。 */
    public Layout layout(long userId, List<String> roleKeys) {
        String roleKey = null;
        JsonNode roleCards = null;
        for (String r : roleKeys) {
            JsonNode c = roleCards(r);
            if (c != null) {
                roleKey = r;
                roleCards = c;
                break;
            }
        }
        if (roleCards == null) roleCards = roleCards(DEFAULT.substring(5));
        if (roleCards == null) roleCards = JSON.createArrayNode();
        List<String> mine = jdbc.queryForList("select home::text from ui_user_pref where user_id = ? and home is not null", String.class, userId);
        if (!mine.isEmpty()) {
            JsonNode cards = read(mine.get(0)).path("cards");
            if (cards.isArray()) return new Layout(cards, "personal", roleKey, roleCards);
        }
        return new Layout(roleCards, "role", roleKey, roleCards);
    }

    /** 角色默认布局（已发布版本）；没有时为 null。 */
    JsonNode roleCards(String roleKey) {
        List<String> rows = jdbc.queryForList("select schema_json::text from meta_view where code = ? and view_type = 'HOME' and status = 'PUBLISHED'",
            String.class, "home." + roleKey);
        return rows.isEmpty() ? null : read(rows.get(0)).path("cards");
    }

    public void saveMine(long userId, JsonNode body) {
        ObjectNode home = JSON.createObjectNode();
        home.set("cards", cards(body));
        jdbc.update("""
            insert into ui_user_pref (user_id, home) values (?, ?::jsonb)
            on conflict (user_id) do update set home = excluded.home, updated_at = now()""", userId, home.toString());
    }

    /** 恢复默认：删除个人调整。 */
    public void resetMine(long userId) {
        jdbc.update("update ui_user_pref set home = null, updated_at = now() where user_id = ?", userId);
    }

    /** 设置角色默认布局：生成新版本并发布，旧版本保留可查。roleKey 为 default 时设置通用默认。 */
    @Transactional
    public int saveRole(String roleKey, JsonNode body, Long by) {
        if (roleKey == null || !ROLE.matcher(roleKey).matches()) throw LsBizException.validation(Map.of("roleKey", "角色键格式不正确"));
        if (!"default".equals(roleKey)) {
            Integer n = jdbc.queryForObject("select count(*) from sys_role where role_key = ? and del_flag = '0'", Integer.class, roleKey);
            if (n == null || n == 0) throw LsBizException.notFound("角色", roleKey);
        }
        ObjectNode schema = JSON.createObjectNode();
        schema.set("cards", cards(body));
        String code = "home." + roleKey;
        int version = jdbc.queryForObject("select coalesce(max(version), 0) + 1 from meta_view where code = ?", Integer.class, code);
        jdbc.update("update meta_view set status = 'RETIRED' where code = ? and status = 'PUBLISHED'", code);
        jdbc.update("""
            insert into meta_view (code, version, concept_code, view_type, display_name, status, schema_json, published_at, created_by, change_note)
            values (?, ?, null, 'HOME', ?, 'PUBLISHED', ?::jsonb, now(), ?, '设置角色首页')""", code, version, "首页（" + roleKey + "）", schema.toString(), by);
        return version;
    }

    /** 校验并整理卡片列表：只保留编码与是否隐藏，去重。 */
    static ArrayNode cards(JsonNode body) {
        JsonNode list = body == null ? null : body.path("cards");
        if (list == null || !list.isArray()) throw LsBizException.validation(Map.of("cards", "应为卡片列表"));
        if (list.size() > MAX_CARDS) throw LsBizException.validation(Map.of("cards", "最多 " + MAX_CARDS + " 张卡片"));
        ArrayNode out = JSON.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode c : list) {
            String code = c.path("code").asText("");
            if (!CARD.matcher(code).matches()) throw LsBizException.validation(Map.of("cards", "卡片编码不正确：" + (code.length() > 40 ? code.substring(0, 40) : code)));
            if (!seen.add(code)) continue;
            ObjectNode n = out.addObject();
            n.put("code", code);
            if (c.path("hidden").asBoolean(false)) n.put("hidden", true);
        }
        return out;
    }

    private static JsonNode read(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }
}
