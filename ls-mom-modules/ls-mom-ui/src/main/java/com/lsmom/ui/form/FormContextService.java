package com.lsmom.ui.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表单自动带入的上下文：当前人员（Party）、主组织、当前班次（字典 ls_shift 的时段）、当前时间，以及本人上次填写的记忆值。
 * 记忆值只保存已发布表单里标了 remember 的字段，取值限于简单值；带入的值只是建议，提交时照常经服务端校验。
 */
@Service
public class FormContextService {

    static final int FORMS_MAX = 100;
    static final int TEXT_MAX = 500;
    static final int ARRAY_MAX = 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public FormContextService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemDefaultZone());
    }

    FormContextService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Context(Long partyId, Long orgId, String shift, String now, JsonNode remembered) {
    }

    public Context context(long userId, Long partyId, String formCode) {
        Long org = partyId == null ? null : jdbc.queryForList(
            "select org_unit_id from core_party_org where party_id = ? and is_primary", Long.class, partyId).stream().findFirst().orElse(null);
        LocalDateTime now = LocalDateTime.now(clock);
        return new Context(partyId, org, shift(now.toLocalTime()), now.withNano(0).format(ISO),
            formCode == null ? JSON.createObjectNode() : remembered(userId, formCode));
    }

    /** 按字典 ls_shift 的备注时段（如 16:00-24:00）判断当前班次；跨零点的时段同样支持。 */
    String shift(LocalTime t) {
        int m = t.getHour() * 60 + t.getMinute();
        for (Map<String, Object> d : jdbc.queryForList(
            "select dict_value, remark from sys_dict_data where dict_type = 'ls_shift' order by dict_sort")) {
            String remark = String.valueOf(d.get("remark"));
            if (!remark.matches("\\d{1,2}:\\d{2}-\\d{1,2}:\\d{2}")) continue;
            String[] p = remark.split("-");
            int from = minutes(p[0]), to = minutes(p[1]);
            boolean in = from <= to ? m >= from && m < to : m >= from || m < to;
            if (in) return (String) d.get("dict_value");
        }
        return null;
    }

    private static int minutes(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    public JsonNode remembered(long userId, String formCode) {
        List<String> rows = jdbc.queryForList("select remembered::text from ui_user_pref where user_id = ?", String.class, userId);
        if (rows.isEmpty()) return JSON.createObjectNode();
        JsonNode v = read(rows.get(0)).path(formCode).path("values");
        return v.isObject() ? v : JSON.createObjectNode();
    }

    /** 保存本次提交里可记忆字段的值；只收已发布表单中 remember=true 的字段。返回保存下来的值。 */
    public JsonNode remember(long userId, String formCode, JsonNode values) {
        if (values == null || !values.isObject()) throw new LsBizException("VALIDATION_FAILED", "记忆值必须是对象");
        Set<String> allowed = rememberFields(formCode);
        ObjectNode clean = JSON.createObjectNode();
        Map<String, String> errors = new LinkedHashMap<>();
        values.fields().forEachRemaining(e -> {
            if (!allowed.contains(e.getKey())) errors.put(e.getKey(), "该字段不能记住上次填写");
            else if (!simple(e.getValue())) errors.put(e.getKey(), "只能记住简单的值");
            else if (!e.getValue().isNull()) clean.set(e.getKey(), e.getValue());
        });
        if (!errors.isEmpty()) throw LsBizException.validation(errors);
        ObjectNode all = (ObjectNode) read(jdbc.queryForList("select remembered::text from ui_user_pref where user_id = ?", String.class, userId)
            .stream().findFirst().orElse("{}"));
        ObjectNode entry = JSON.createObjectNode();
        entry.set("values", clean);
        entry.put("at", clock.millis());
        all.remove(formCode);
        all.set(formCode, entry);
        trim(all);
        jdbc.update("""
            insert into ui_user_pref (user_id, remembered) values (?, ?::jsonb)
            on conflict (user_id) do update set remembered = excluded.remembered, updated_at = now()""", userId, all.toString());
        return clean;
    }

    /** 已发布的最新版本表单中标了 remember 的字段（含子表外的主表字段）。 */
    Set<String> rememberFields(String formCode) {
        List<String> rows = jdbc.queryForList("""
            select schema_json::text from meta_view where code = ? and view_type = 'FORM' and status = 'PUBLISHED'
            order by version desc limit 1""", String.class, formCode);
        if (rows.isEmpty()) throw new LsBizException("NOT_FOUND", "表单不存在或未发布：" + formCode);
        Set<String> out = new HashSet<>();
        for (JsonNode f : read(rows.get(0)).path("fields")) if (f.path("remember").asBoolean(false)) out.add(f.path("key").asText());
        return out;
    }

    private static boolean simple(JsonNode v) {
        if (v.isNull() || v.isBoolean() || v.isNumber()) return true;
        if (v.isTextual()) return v.asText().length() <= TEXT_MAX;
        if (v.isArray() && v.size() <= ARRAY_MAX) {
            for (JsonNode x : v) if (!(x.isNumber() || (x.isTextual() && x.asText().length() <= TEXT_MAX))) return false;
            return true;
        }
        return false;
    }

    /** 超过上限时去掉最久未用的表单。 */
    private static void trim(ObjectNode all) {
        while (all.size() > FORMS_MAX) {
            String oldest = null;
            long at = Long.MAX_VALUE;
            for (Iterator<Map.Entry<String, JsonNode>> it = all.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                long a = e.getValue().path("at").asLong(0);
                if (a < at) {
                    at = a;
                    oldest = e.getKey();
                }
            }
            all.remove(oldest);
        }
    }

    private static JsonNode read(String s) {
        try {
            JsonNode n = s == null ? null : JSON.readTree(s);
            return n != null && n.isObject() ? n : JSON.createObjectNode();
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }
}
