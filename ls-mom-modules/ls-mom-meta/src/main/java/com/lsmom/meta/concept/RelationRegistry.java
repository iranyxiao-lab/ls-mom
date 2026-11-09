package com.lsmom.meta.concept;

import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Concept 间命名关系与语义映射（物理表/列、外部编码、DCS 点位）。 */
@Service
public class RelationRegistry {

    public record Relation(String code, String name, String fromConcept, String toConcept, String cardinality, String viaField) {
    }

    public record Mapping(String conceptCode, String fieldCode, String targetType, String targetRef, String systemCode) {
    }

    private final JdbcTemplate jdbc;

    public RelationRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void defineRelation(Relation r) {
        if (!r.code().equals(r.fromConcept() + "." + r.name())) {
            throw new LsBizException("RELATION_CODE", "关系编码须为 <来源Concept>.<关系名>：" + r.code(), Map.of());
        }
        jdbc.update("""
            insert into meta_relation (code, name, from_concept, to_concept, cardinality, via_field) values (?, ?, ?, ?, ?, ?)
            on conflict (code) do nothing""", r.code(), r.name(), r.fromConcept(), r.toConcept(), r.cardinality(), r.viaField());
    }

    public List<Relation> relationsFrom(String concept) {
        return jdbc.query("select code, name, from_concept, to_concept, cardinality, via_field from meta_relation where from_concept = ? order by code",
            (rs, i) -> new Relation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), concept);
    }

    @Transactional
    public void map(Mapping m) {
        jdbc.update("""
            insert into meta_mapping (concept_code, field_code, target_type, target_ref, system_code) values (?, ?, ?, ?, ?)
            on conflict do nothing""", m.conceptCode(), m.fieldCode(), m.targetType(), m.targetRef(), m.systemCode());
    }

    public List<Mapping> mappingsOf(String concept) {
        return jdbc.query("select concept_code, field_code, target_type, target_ref, system_code from meta_mapping where concept_code = ? order by id",
            (rs, i) -> new Mapping(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), concept);
    }
}
