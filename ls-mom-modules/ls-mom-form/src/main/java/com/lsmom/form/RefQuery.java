package com.lsmom.form;

import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.FieldDef;
import com.lsmom.meta.concept.MetaNames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 引用过滤 → 安全 SQL 片段（表别名 t）。列名只来自 Concept 元数据并经标识符校验，取值一律走参数绑定。
 * 服务端提交校验与引用远程搜索共用，保证"预览搜不到的，提交也过不去"。
 */
final class RefQuery {

    private RefQuery() {
    }

    static String table(ConceptDef target) {
        return "\"" + MetaNames.requireIdentifier(target.tableName()) + "\"";
    }

    static boolean managed(ConceptDef target) {
        return target.storage() == ConceptDef.Storage.MANAGED_TABLE;
    }

    /** 过滤字段 → 物理列；受控表系统字段按约定列名，核心表 orgId 映射到 org_unit_id。 */
    static String column(ConceptDef target, String field) {
        String col = null;
        FieldDef f = target.field(field);
        if (f != null && !f.ext()) col = f.columnName();
        else if (field.equals("id")) col = "id";
        else if (field.equals("status")) col = "status";
        else if (field.equals("orgId")) {
            col = managed(target) ? "org_id" : target.field("orgUnitId") != null ? "org_unit_id" : null;
        } else if (field.equals("createdBy")) col = "created_by";
        return col == null ? null : MetaNames.requireIdentifier(col);
    }

    /** 生成一个过滤条件；配置无效时返回 empty。 */
    static Optional<String> condition(ConceptDef target, String field, String op, Object val, List<Object> args) {
        if (field.equals("orgId") && target.code().equals("ls.core.Party")) {
            List<?> orgs = val instanceof List<?> l ? l : Collections.singletonList(val);
            if (!(op.equals("eq") || op.equals("in")) || orgs.isEmpty() || orgs.contains(null)) return Optional.empty();
            orgs.forEach(o -> args.add(Long.parseLong(String.valueOf(o))));
            return Optional.of("exists (select 1 from core_party_org po where po.party_id = t.id and po.org_unit_id in ("
                + String.join(", ", Collections.nCopies(orgs.size(), "?")) + "))");
        }
        String col = column(target, field);
        if (col == null) return Optional.empty();
        String c = "t.\"" + col + "\"";
        switch (op) {
            case "eq", "ne", "gt", "ge", "lt", "le" -> {
                if (val == null) return Optional.of(c + (op.equals("ne") ? " is not null" : " is null"));
                if (val instanceof Number && !op.equals("eq") && !op.equals("ne")) {
                    args.add(val);
                    return Optional.of(c + " " + sqlOp(op) + " ?");
                }
                args.add(String.valueOf(val));
                return Optional.of(c + "::text " + sqlOp(op) + " ?");
            }
            case "in" -> {
                List<?> list = val instanceof List<?> l ? l : Collections.singletonList(val);
                if (list.isEmpty()) return Optional.of("1 = 0");
                list.forEach(x -> args.add(String.valueOf(x)));
                return Optional.of(c + "::text in (" + String.join(", ", Collections.nCopies(list.size(), "?")) + ")");
            }
            case "like" -> {
                args.add("%" + escapeLike(String.valueOf(val)) + "%");
                return Optional.of(c + "::text like ?");
            }
            case "startsWith" -> {
                args.add(escapeLike(String.valueOf(val)) + "%");
                return Optional.of(c + "::text like ?");
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /** 显示列：labelField ＞ 标记 label 的字段 ＞ name/title/code。 */
    static String labelColumn(ConceptDef target, String labelField) {
        if (labelField != null) {
            String c = column(target, labelField);
            if (c != null) return c;
        }
        for (FieldDef f : target.fields()) if (f.flag("label") && !f.ext()) return MetaNames.requireIdentifier(f.columnName());
        for (String n : List.of("name", "title", "code")) {
            FieldDef f = target.field(n);
            if (f != null && !f.ext()) return MetaNames.requireIdentifier(f.columnName());
        }
        return "id";
    }

    private static String sqlOp(String op) {
        return switch (op) {
            case "eq" -> "=";
            case "ne" -> "<>";
            case "gt" -> ">";
            case "ge" -> ">=";
            case "lt" -> "<";
            default -> "<=";
        };
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 调用者数据范围：受控表按记录组织或本人创建，核心表按 orgUnitId；搜索、扫码与提交校验共用。 */
    static void scope(com.lsmom.core.org.DataScope scope, ConceptDef target, StringBuilder sql, List<Object> args) {
        if (scope == null || scope.all()) return;
        String orgCol = RefQuery.managed(target) ? "org_id" : target.field("orgUnitId") != null ? "org_unit_id" : null;
        if (orgCol == null) return;
        List<String> or = new ArrayList<>();
        if (scope.level() != com.lsmom.core.org.DataScopeLevel.SELF && !scope.orgUnitIds().isEmpty()) {
            or.add("t.\"" + orgCol + "\" in (" + String.join(", ", Collections.nCopies(scope.orgUnitIds().size(), "?")) + ")");
            args.addAll(scope.orgUnitIds());
        }
        if (scope.partyId() != null && RefQuery.managed(target)) {
            or.add("t.created_by = ?");
            args.add(scope.partyId());
        }
        sql.append(" and ").append(or.isEmpty() ? "1 = 0" : "(" + String.join(" or ", or) + ")");
    }
}
