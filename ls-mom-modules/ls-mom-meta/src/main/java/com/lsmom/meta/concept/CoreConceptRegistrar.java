package com.lsmom.meta.concept;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动时把核心显式表注册为 {@code ls.core.*} Concept。字段直接从实际表结构读取，
 * 因此元数据与核心迁移脚本始终一致；核心 Concept 不可经设计器修改。
 */
@Component
@Order(100)
public class CoreConceptRegistrar implements ApplicationRunner {

    /** Concept 编码 → [表名, 显示名]。 */
    public static final Map<String, String[]> CORE = new LinkedHashMap<>();

    static {
        CORE.put("ls.core.Party", new String[]{"core_party", "主体"});
        CORE.put("ls.core.OrgUnit", new String[]{"core_org_unit", "组织单元"});
        CORE.put("ls.core.Work", new String[]{"core_work_item", "工作"});
        CORE.put("ls.core.WorkAssignment", new String[]{"core_work_assignment", "工作分派"});
        CORE.put("ls.core.WorkReport", new String[]{"core_work_report", "工作填报"});
        CORE.put("ls.core.WorkRelation", new String[]{"core_work_relation", "工作关系"});
        CORE.put("ls.core.Issue", new String[]{"core_issue", "异常与整改"});
        CORE.put("ls.core.Observation", new String[]{"core_observation", "事实观察"});
        CORE.put("ls.core.DocumentLink", new String[]{"core_document_link", "附件"});
    }

    private static final Set<String> SYSTEM_COLUMNS = Set.of("id", "created_at", "updated_at", "version");

    private final ConceptRegistry registry;
    private final JdbcTemplate jdbc;

    public CoreConceptRegistrar(ConceptRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        registerAll();
    }

    public void registerAll() {
        CORE.forEach((code, meta) -> registry.registerCore(new ConceptDef(code, ConceptRegistry.CORE_NAMESPACE, meta[1],
            ConceptDef.Storage.CORE_TABLE, meta[0], false, null, "核心显式表 " + meta[0], readFields(meta[0]), "PUBLISHED", 1)));
    }

    private List<FieldDef> readFields(String table) {
        List<FieldDef> fields = new ArrayList<>();
        jdbc.query("""
            select c.column_name, c.data_type, c.is_nullable,
                   col_description((quote_ident(c.table_schema) || '.' || quote_ident(c.table_name))::regclass, c.ordinal_position)
            from information_schema.columns c
            where c.table_schema = current_schema() and c.table_name = ? order by c.ordinal_position""", rs -> {
            String col = rs.getString(1);
            DataType type = switch (rs.getString(2)) {
                case "bigint", "integer", "smallint" -> col.endsWith("_id") || col.endsWith("_party") ? DataType.REF : DataType.INTEGER;
                case "numeric" -> DataType.DECIMAL;
                case "boolean" -> DataType.BOOLEAN;
                case "date" -> DataType.DATE;
                case "timestamp without time zone", "timestamp with time zone" -> DataType.DATETIME;
                case "jsonb", "json" -> DataType.JSON;
                case "text" -> DataType.TEXT;
                default -> DataType.STRING;
            };
            boolean state = col.equals("status");
            String refConcept = type == DataType.REF ? refOf(col) : null;
            if (type == DataType.REF && refConcept == null) type = DataType.INTEGER;
            String comment = rs.getString(4);
            String label = comment == null || comment.isBlank() ? col : comment.split("[：；（(，,]")[0].trim();
            fields.add(new FieldDef(MetaNames.toFieldCode(col), label.length() > 100 ? label.substring(0, 100) : label, type, col, "NO".equals(rs.getString(3)) && !SYSTEM_COLUMNS.contains(col),
                refConcept, null, state, SYSTEM_COLUMNS.contains(col), FieldDef.FieldStatus.ACTIVE, 0));
        }, table);
        return fields;
    }

    private static String refOf(String col) {
        if (col.endsWith("_party") || col.equals("party_id") || col.equals("leader_party_id")) return "ls.core.Party";
        if (col.equals("org_unit_id")) return "ls.core.OrgUnit";
        if (col.equals("work_item_id") || col.equals("from_id") || col.equals("to_id")) return "ls.core.Work";
        if (col.equals("issue_id")) return "ls.core.Issue";
        return null;
    }
}
