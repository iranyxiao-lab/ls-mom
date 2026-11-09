package com.lsmom.biz.production;

import com.lsmom.core.error.LsBizException;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.FieldDef;
import com.lsmom.meta.concept.MetaNames;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 轻生产托管台账的读写（迁移效果内部使用，与 Operation 同一事务）。列名一律取自 Concept 元数据。
 * 业务 Pack 不依赖通用台账模块，所以这里直接按元数据写表；对外的增删改仍走通用台账接口与 Operation。
 */
public class ProductionRecords {

    private final JdbcTemplate jdbc;
    private final ConceptRegistry concepts;

    public ProductionRecords(JdbcTemplate jdbc, ConceptRegistry concepts) {
        this.jdbc = jdbc;
        this.concepts = concepts;
    }

    public String table(String concept) {
        return "\"" + MetaNames.managedTable(concept) + "\"";
    }

    public String column(String concept, String field) {
        FieldDef f = concepts.get(concept).field(field);
        if (f == null) throw new IllegalArgumentException(concept + " 没有字段 " + field);
        return "\"" + MetaNames.requireIdentifier(f.columnName()) + "\"";
    }

    /** 按字段编码返回一行（含 id、status、version、orgId、createdBy）。 */
    public Map<String, Object> row(String concept, long id) {
        return find(concept, id).orElseThrow(() -> LsBizException.notFound(concepts.get(concept).displayName(), String.valueOf(id)));
    }

    public Optional<Map<String, Object>> find(String concept, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from " + table(concept) + " where id = ? and del_flag = '0'", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toFields(concept, rows.get(0)));
    }

    public List<Map<String, Object>> where(String concept, String field, Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select * from " + table(concept) + " where " + column(concept, field)
            + " = ? and del_flag = '0' order by id", value)) {
            out.add(toFields(concept, r));
        }
        return out;
    }

    private Map<String, Object> toFields(String concept, Map<String, Object> raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        raw.forEach((k, v) -> m.put(MetaNames.toFieldCode(k), v));
        return m;
    }

    public long insert(String concept, Long orgId, String status, Long createdBy, Map<String, Object> values) {
        ConceptDef c = concepts.get(concept);
        List<String> cols = new ArrayList<>(List.of("org_id", "status", "created_by", "created_at", "version", "ext_attrs"));
        List<Object> args = new ArrayList<>(List.of(orgId, status, createdBy == null ? 0L : createdBy, LocalDateTime.now(), 0, "{}"));
        values.forEach((k, v) -> {
            if (v == null) return;
            cols.add(MetaNames.requireIdentifier(c.field(k).columnName()));
            args.add(v);
        });
        return jdbc.queryForObject("insert into " + table(concept) + " (" + quoted(cols) + ") values (" + marks(cols) + ") returning id",
            Long.class, args.toArray());
    }

    /** 修改非状态字段（版本 + 1）。 */
    public void update(String concept, long id, Map<String, Object> values) {
        List<String> sets = new ArrayList<>(List.of("version = version + 1", "updated_at = now()"));
        List<Object> args = new ArrayList<>();
        values.forEach((k, v) -> {
            sets.add(column(concept, k) + " = ?");
            args.add(v);
        });
        args.add(id);
        jdbc.update("update " + table(concept) + " set " + String.join(", ", sets) + " where id = ?", args.toArray());
    }

    public void replaceChildren(String childConcept, long parentId, List<Map<String, Object>> rows) {
        jdbc.update("delete from " + table(childConcept) + " where parent_id = ?", parentId);
        ConceptDef c = concepts.get(childConcept);
        int no = 1;
        for (Map<String, Object> values : rows) {
            List<String> cols = new ArrayList<>(List.of("parent_id", "line_no", "ext_attrs"));
            List<Object> args = new ArrayList<>(List.of(parentId, no++, "{}"));
            values.forEach((k, v) -> {
                if (v == null) return;
                cols.add(MetaNames.requireIdentifier(c.field(k).columnName()));
                args.add(v);
            });
            jdbc.update("insert into " + table(childConcept) + " (" + quoted(cols) + ") values (" + marks(cols) + ")", args.toArray());
        }
    }

    public List<Map<String, Object>> children(String childConcept, long parentId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select * from " + table(childConcept) + " where parent_id = ? order by line_no", parentId)) {
            out.add(toFields(childConcept, r));
        }
        return out;
    }

    private static String quoted(List<String> cols) {
        return String.join(", ", cols.stream().map(c -> "\"" + c + "\"").toList());
    }

    private static String marks(List<String> cols) {
        return String.join(", ", cols.stream().map(c -> c.equals("ext_attrs") ? "?::jsonb" : "?").toList());
    }
}
