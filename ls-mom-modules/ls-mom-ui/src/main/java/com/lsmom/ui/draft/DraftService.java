package com.lsmom.ui.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 表单草稿：录入中的内容按用户与草稿键保存，只本人可见。内容与上次相同时不重复写入；
 * 每人最多保留 {@value #MAX_PER_USER} 份，超出时删掉最久未更新的。草稿不是业务数据，提交时照常经服务端校验。
 */
@Service
public class DraftService {

    static final int MAX_PER_USER = 200;
    static final int MAX_BYTES = 512 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public DraftService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Draft(String key, JsonNode content, String hash, Timestamp updatedAt) {
    }

    public Optional<Draft> get(long userId, String key) {
        check(key);
        return jdbc.query("select draft_key, content::text as content, hash, updated_at from ui_form_draft where user_id = ? and draft_key = ?",
            (rs, i) -> new Draft(rs.getString(1), read(rs.getString(2)), rs.getString(3), rs.getTimestamp(4)), userId, key).stream().findFirst();
    }

    /** 保存草稿；内容未变（hash 相同）时不写入。返回保存后的草稿。 */
    public Draft save(long userId, String key, JsonNode content, String hash) {
        check(key);
        if (content == null || !content.isObject()) throw LsBizException.validation(Map.of("content", "草稿内容必须是对象"));
        String text = content.toString();
        if (text.length() > MAX_BYTES) throw LsBizException.validation(Map.of("content", "草稿内容过大"));
        if (hash == null || !hash.matches("[0-9a-zA-Z]{1,64}")) throw LsBizException.validation(Map.of("hash", "缺少内容摘要"));
        jdbc.update("""
            insert into ui_form_draft (user_id, draft_key, content, hash) values (?, ?, ?::jsonb, ?)
            on conflict (user_id, draft_key) do update set content = excluded.content, hash = excluded.hash, updated_at = now()
            where ui_form_draft.hash <> excluded.hash""", userId, key, text, hash);
        jdbc.update("""
            delete from ui_form_draft where user_id = ? and draft_key in (
              select draft_key from ui_form_draft where user_id = ? order by updated_at desc offset ?)""", userId, userId, MAX_PER_USER);
        return get(userId, key).orElseThrow();
    }

    public void delete(long userId, String key) {
        check(key);
        jdbc.update("delete from ui_form_draft where user_id = ? and draft_key = ?", userId, key);
    }

    public List<Map<String, Object>> mine(long userId) {
        return jdbc.queryForList("select draft_key as \"key\", updated_at as \"updatedAt\" from ui_form_draft where user_id = ? order by updated_at desc", userId);
    }

    private static void check(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,200}")) throw LsBizException.validation(Map.of("key", "草稿键不合法"));
    }

    private static JsonNode read(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }
}
