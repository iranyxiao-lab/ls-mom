package com.lsmom.crud.ddl;

import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.DataType;
import com.lsmom.meta.concept.FieldDef;
import com.lsmom.meta.concept.MetaNames;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 比较 Concept 新旧定义生成 DDL。只用元数据中经白名单校验的标识符，并统一加双引号。
 * <ul>
 *   <li>主表 {@code c_<ns>_<concept>}：保留字段 id、org_id、status、created_by、created_at、updated_by、updated_at、version、
 *       del_flag、schema_version、ext_attrs；</li>
 *   <li>子表：id、parent_id（外键）、line_no 与字段列；</li>
 *   <li>新增字段默认生成显式列（ext 字段进入 ext_attrs，不建列），可查询/可排序/indexed 字段建索引，unique 字段建部分唯一索引；</li>
 *   <li>改类型、删列、改名标记 BREAKING，只生成待评审脚本（{@link #planTypeChange}）。</li>
 * </ul>
 */
@Component
public class DdlPlanner {

    public static String quote(String identifier) {
        return "\"" + MetaNames.requireIdentifier(identifier) + "\"";
    }

    public DdlPlan plan(ConceptDef next, Optional<ConceptDef> previous, Optional<ConceptDef> parent) {
        List<String> sql = new ArrayList<>();
        List<String> breaking = new ArrayList<>();
        String table = next.tableName();
        if (previous.isEmpty() || !"PUBLISHED".equals(previous.get().status())) {
            sql.add(createTable(next, parent));
            sql.add("comment on table " + quote(table) + " is " + literal(next.displayName()));
            for (FieldDef f : columns(next.fields())) sql.addAll(fieldExtras(table, f, next.isChild()));
            if (next.isChild()) {
                sql.add("create index " + quote(idx(table, "parent")) + " on " + quote(table) + " (\"parent_id\", \"line_no\")");
            } else {
                sql.add("create index " + quote(idx(table, "org")) + " on " + quote(table) + " (\"org_id\") where \"del_flag\" = '0'");
                sql.add("create index " + quote(idx(table, "status")) + " on " + quote(table) + " (\"status\") where \"del_flag\" = '0'");
            }
            return new DdlPlan(next.code(), "create " + table, sql, false, List.of());
        }
        ConceptDef old = previous.get();
        Map<String, FieldDef> oldFields = new LinkedHashMap<>();
        old.fields().forEach(f -> oldFields.put(f.code(), f));
        for (FieldDef f : next.fields()) {
            FieldDef prev = oldFields.get(f.code());
            if (prev == null) {
                if (f.ext()) continue;
                sql.add("alter table " + quote(table) + " add column " + quote(f.columnName()) + " " + sqlType(f));
                sql.addAll(fieldExtras(table, f, next.isChild()));
                continue;
            }
            if (prev.dataType() != f.dataType()) breaking.add("字段 " + f.code() + " 类型 " + prev.dataType() + " → " + f.dataType());
            if (!prev.columnName().equals(f.columnName())) breaking.add("字段 " + f.code() + " 改列名");
            if (prev.ext() != f.ext()) breaking.add("字段 " + f.code() + " 存储位置变化");
            boolean wasIndexed = indexed(prev);
            if (!prev.ext() && !f.ext() && indexed(f) && !wasIndexed) {
                sql.add("create index " + quote(idx(table, f.columnName())) + " on " + quote(table) + " (" + quote(f.columnName()) + ")");
            }
            if (!prev.ext() && !f.ext() && f.flag("unique") && !prev.flag("unique")) sql.add(uniqueIndex(table, f, next.isChild()));
        }
        for (FieldDef prev : oldFields.values()) {
            if (next.field(prev.code()) == null && prev.status() == FieldDef.FieldStatus.ACTIVE) breaking.add("删除字段 " + prev.code());
        }
        if (!breaking.isEmpty()) return new DdlPlan(next.code(), "alter " + table, List.of(), true, breaking);
        return new DdlPlan(next.code(), "alter " + table, sql, false, List.of());
    }

    /** 改字段类型：生成需评审的迁移脚本（新列 → 转换 → 替换），不直接覆盖旧定义。 */
    public DdlPlan planTypeChange(ConceptDef concept, FieldDef field, DataType newType) {
        if (field.ext()) throw new IllegalArgumentException("ext_attrs 字段无需物理迁移");
        String table = quote(concept.tableName());
        String col = quote(field.columnName());
        FieldDef target = field.withType(newType);
        String tmp = quote(MetaNames.requireIdentifier(truncate(field.columnName() + "_mig", 63)));
        List<String> sql = new ArrayList<>();
        sql.add("alter table " + table + " add column " + tmp + " " + sqlType(target));
        sql.add("update " + table + " set " + tmp + " = " + convert(col, field.dataType(), newType));
        sql.add("alter table " + table + " drop column " + col);
        sql.add("alter table " + table + " rename column " + tmp + " to " + col);
        sql.add("comment on column " + table + "." + col + " is " + literal(field.displayName()));
        if (indexed(field)) sql.add("create index " + quote(idx(concept.tableName(), field.columnName())) + " on " + table + " (" + col + ")");
        return new DdlPlan(concept.code(), "change type " + concept.tableName() + "." + field.columnName() + " " + field.dataType() + "→" + newType,
            sql, true, List.of("字段 " + field.code() + " 类型 " + field.dataType() + " → " + newType));
    }

    private static String convert(String col, DataType from, DataType to) {
        String text = from == DataType.STRING || from == DataType.TEXT || from == DataType.ENUM ? col : col + "::text";
        return switch (to) {
            case INTEGER, REF -> "case when trim(" + text + ") ~ '^-?[0-9]+$' then trim(" + text + ")::bigint end";
            case DECIMAL -> "case when trim(" + text + ") ~ '^-?[0-9]+(\\.[0-9]+)?$' then trim(" + text + ")::numeric end";
            case BOOLEAN -> "case when lower(" + text + ") in ('true','1','y','yes','是') then true when lower(" + text
                + ") in ('false','0','n','no','否') then false end";
            case DATE -> "case when " + text + " ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}' then substr(" + text + ", 1, 10)::date end";
            case DATETIME -> "case when " + text + " ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}' then " + text + "::timestamp end";
            case JSON, FILE -> "to_jsonb(" + col + ")";
            default -> text;
        };
    }

    private String createTable(ConceptDef c, Optional<ConceptDef> parent) {
        List<String> cols = new ArrayList<>();
        cols.add("\"id\" bigserial primary key");
        if (c.isChild()) {
            String parentTable = parent.orElseThrow(() -> new IllegalArgumentException("子表缺少主表定义")).tableName();
            cols.add("\"parent_id\" bigint not null references " + quote(parentTable) + " (\"id\")");
            cols.add("\"line_no\" int not null");
        } else {
            cols.add("\"org_id\" bigint not null");
            cols.add("\"status\" varchar(32) not null default 'DRAFT'");
            cols.add("\"created_by\" bigint");
            cols.add("\"created_at\" timestamp not null default current_timestamp");
            cols.add("\"updated_by\" bigint");
            cols.add("\"updated_at\" timestamp");
            cols.add("\"version\" int not null default 0");
            cols.add("\"del_flag\" char(1) not null default '0'");
            cols.add("\"schema_version\" int not null default 1");
        }
        cols.add("\"ext_attrs\" jsonb not null default '{}'::jsonb");
        for (FieldDef f : columns(c.fields())) cols.add(quote(f.columnName()) + " " + sqlType(f));
        return "create table " + quote(c.tableName()) + " (\n  " + String.join(",\n  ", cols) + "\n)";
    }

    private List<String> fieldExtras(String table, FieldDef f, boolean child) {
        List<String> sql = new ArrayList<>();
        sql.add("comment on column " + quote(table) + "." + quote(f.columnName()) + " is " + literal(f.displayName()));
        if (indexed(f)) sql.add("create index " + quote(idx(table, f.columnName())) + " on " + quote(table) + " (" + quote(f.columnName()) + ")");
        if (f.flag("unique")) sql.add(uniqueIndex(table, f, child));
        return sql;
    }

    private String uniqueIndex(String table, FieldDef f, boolean child) {
        return "create unique index " + quote(idx(table, f.columnName() + "_uq")) + " on " + quote(table) + " (" + quote(f.columnName())
            + ")" + (child ? "" : " where \"del_flag\" = '0'");
    }

    private static List<FieldDef> columns(List<FieldDef> fields) {
        return fields.stream().filter(f -> !f.ext() && !f.systemField() && f.status() != FieldDef.FieldStatus.REMOVED).toList();
    }

    private static boolean indexed(FieldDef f) {
        return !f.ext() && (f.flag("indexed") || f.flag("queryable") || f.flag("sortable") || f.dataType() == DataType.REF);
    }

    static String sqlType(FieldDef f) {
        return switch (f.dataType()) {
            case STRING -> "varchar(" + Math.min(Optional.ofNullable(f.intProp("maxLength")).orElse(255), 4000) + ")";
            case TEXT -> "text";
            case INTEGER, REF -> "bigint";
            case DECIMAL -> "numeric(" + Optional.ofNullable(f.intProp("precision")).orElse(18) + ","
                + Optional.ofNullable(f.intProp("scale")).orElse(4) + ")";
            case BOOLEAN -> "boolean";
            case DATE -> "date";
            case DATETIME -> "timestamp";
            case ENUM -> "varchar(64)";
            case JSON, FILE -> "jsonb";
        };
    }

    /** 索引名：表名_列名_idx，超长时截断并加散列后缀，保证 63 字节内且唯一。 */
    static String idx(String table, String suffix) {
        String name = table + "_" + suffix + "_idx";
        if (name.length() <= 63) return name;
        String hash = Integer.toHexString(name.hashCode() & 0x7fffffff);
        return truncate(table, 63 - hash.length() - suffix.length() - 7) + "_" + suffix + "_" + hash + "_idx";
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, Math.max(1, n));
    }

    private static String literal(String text) {
        return "'" + (text == null ? "" : text.replace("'", "''")) + "'";
    }
}
