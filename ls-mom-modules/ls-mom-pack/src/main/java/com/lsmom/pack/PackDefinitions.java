package com.lsmom.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.FieldDef;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 从运行库读取定义的"源形式"（与 Pack 文件同结构），用于安装后的基线快照、漂移比较与差异回灌。
 * 直接读表，绕过任何缓存，手工改库也能发现。
 */
public class PackDefinitions {

    public enum Kind { CONCEPT, STATE_MACHINE, RULE, VIEW, METRIC, FLOW, MIGRATION, SEED }

    /** 运行库中的定义：版本（无版本概念时为空）与源形式。 */
    public record Snapshot(Integer version, JsonNode form) {
    }

    public static final String OWNER_PROP = "ownerPack";

    private final JdbcTemplate jdbc;
    private final ConceptRegistry concepts;

    public PackDefinitions(JdbcTemplate jdbc, ConceptRegistry concepts) {
        this.jdbc = jdbc;
        this.concepts = concepts;
    }

    public Optional<Snapshot> current(Kind kind, String code, String pack, boolean extension) {
        return switch (kind) {
            case CONCEPT -> concepts.find(code).map(c -> new Snapshot(c.version(), conceptForm(c, children(c.code(), pack), pack, extension)));
            case VIEW -> one("select version, schema_json::text from meta_view where code = ? and status = 'PUBLISHED'", code);
            case STATE_MACHINE -> one("select version, definition::text from meta_flow where code = ? and kind = 'STATE_MACHINE' and status = 'PUBLISHED'", code);
            case FLOW -> one("select version, definition::text from meta_flow where code = ? and kind = 'APPROVAL' and status = 'PUBLISHED'", code)
                .map(s -> {
                    ObjectNode f = ((ObjectNode) s.form()).deepCopy();
                    f.remove("version");
                    return new Snapshot(s.version(), f);
                });
            case METRIC -> one("select version, definition::text from meta_metric where code = ? and status = 'PUBLISHED'", code);
            case RULE -> jdbc.query("""
                    select version, concept_code, level, kind, display_name, message, definition::text as definition, overrides
                      from meta_rule where code = ? and status = 'PUBLISHED'""", (rs, i) -> {
                    ObjectNode r = PackJson.JSON.createObjectNode();
                    r.put("code", code);
                    r.put("concept", rs.getString("concept_code"));
                    r.put("level", rs.getString("level"));
                    r.put("kind", rs.getString("kind"));
                    r.put("name", rs.getString("display_name"));
                    r.put("message", rs.getString("message"));
                    r.set("definition", parse(rs.getString("definition")));
                    r.put("overrides", rs.getString("overrides"));
                    return new Snapshot(rs.getInt("version"), r);
                }, code).stream().findFirst();
            case MIGRATION, SEED -> Optional.empty();
        };
    }

    private Optional<Snapshot> one(String sql, String code) {
        return jdbc.query(sql, (rs, i) -> new Snapshot(rs.getInt(1), parse(rs.getString(2))), code).stream().findFirst();
    }

    private List<ConceptDef> children(String master, String pack) {
        return jdbc.queryForList("select code from meta_concept where parent_concept = ? and (owner_pack = ? or cast(? as varchar) is null) order by code",
            String.class, master, pack, pack).stream().map(concepts::get).toList();
    }

    /**
     * Concept 源形式：本 Pack 拥有的字段（extension=true 时只取本 Pack 追加到他人对象上的字段）。
     * 系统字段与已移除字段不计入；字段属性中的 ownerPack 由安装器维护，不出现在源形式中。
     */
    public static ObjectNode conceptForm(ConceptDef c, List<ConceptDef> children, String pack, boolean extension) {
        ObjectNode o = PackJson.JSON.createObjectNode();
        if (extension) {
            o.put("extends", c.code());
        } else {
            o.put("code", c.code());
            o.put("displayName", c.displayName());
            if (c.description() != null) o.put("description", c.description());
        }
        o.set("fields", fields(c, pack, extension));
        if (!extension && !children.isEmpty()) {
            ArrayNode ch = o.putArray("children");
            for (ConceptDef k : children) {
                ObjectNode co = ch.addObject();
                co.put("code", k.code());
                co.put("displayName", k.displayName());
                co.set("fields", fields(k, pack, false));
            }
        }
        return o;
    }

    private static ArrayNode fields(ConceptDef c, String pack, boolean extension) {
        ArrayNode a = PackJson.JSON.createArrayNode();
        for (FieldDef f : c.fields()) {
            if (f.systemField() || f.status() == FieldDef.FieldStatus.REMOVED) continue;
            String owner = f.textProp(OWNER_PROP);
            boolean mine = extension ? pack != null && pack.equals(owner) : owner == null || owner.equals(pack);
            if (!mine) continue;
            ObjectNode fo = a.addObject();
            fo.put("code", f.code());
            fo.put("displayName", f.displayName());
            fo.put("dataType", f.dataType().name());
            if (f.required()) fo.put("required", true);
            if (f.refConcept() != null) fo.put("refConcept", f.refConcept());
            if (f.options() != null && !f.options().isEmpty()) {
                ArrayNode op = fo.putArray("options");
                f.options().forEach(op::add);
            }
            Map<String, Object> props = new LinkedHashMap<>(f.props());
            props.remove(OWNER_PROP);
            if (!props.isEmpty()) fo.set("props", PackJson.JSON.valueToTree(props));
            if (f.status() == FieldDef.FieldStatus.DEPRECATED) fo.put("deprecated", true);
        }
        return a;
    }

    private static JsonNode parse(String s) {
        try {
            return PackJson.JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
