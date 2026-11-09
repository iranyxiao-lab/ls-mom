package com.lsmom.meta.concept;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 字段使用方索引：表单、流程节点策略、指标、Operation、规则在发布时登记所引用的字段，
 * 用于影响分析与"删除被引用字段"的拦截。
 */
@Service
public class FieldUsageIndex {

    public enum UserType { VIEW, FLOW, FLOW_NODE, METRIC, OPERATION, RULE }

    public record FieldRef(String conceptCode, String fieldCode, String detail) {
        public static FieldRef of(String concept, String field) {
            return new FieldRef(concept, field, null);
        }
    }

    public record Usage(String userType, String userCode, int userVersion, String detail) {
        public String label() {
            return userType + ":" + userCode + "@v" + userVersion + (detail == null ? "" : "（" + detail + "）");
        }
    }

    private final JdbcTemplate jdbc;

    public FieldUsageIndex(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 以使用方当前版本为单位整体替换其引用。 */
    @Transactional
    public void replace(UserType type, String userCode, int version, List<FieldRef> refs) {
        jdbc.update("delete from meta_field_usage where user_type = ? and user_code = ? and user_version = ?", type.name(), userCode, version);
        for (FieldRef r : refs) {
            jdbc.update("""
                insert into meta_field_usage (concept_code, field_code, user_type, user_code, user_version, detail)
                values (?, ?, ?, ?, ?, ?) on conflict do nothing""", r.conceptCode(), r.fieldCode(), type.name(), userCode, version, r.detail());
        }
    }

    /** 使用方版本退役（如表单新版本发布后旧版本无在途数据）时移除其引用。 */
    @Transactional
    public void retire(UserType type, String userCode, int version) {
        jdbc.update("delete from meta_field_usage where user_type = ? and user_code = ? and user_version = ?", type.name(), userCode, version);
    }

    public List<Usage> activeUsagesOf(String conceptCode, String fieldCode) {
        return jdbc.query("""
            select user_type, user_code, user_version, detail from meta_field_usage
            where concept_code = ? and field_code = ? order by user_type, user_code, user_version""",
            (rs, i) -> new Usage(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)), conceptCode, fieldCode);
    }
}
