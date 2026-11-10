package com.lsmom.ui.search;

import com.lsmom.crud.query.LedgerModel;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.DataType;
import com.lsmom.meta.concept.FieldDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 全局搜索读模型的维护：按对象重建一行（增量，由 Outbox 事件触发），以及补建缺失的行（启动与定时）。
 * 索引对象：工作（ls.core.Work）、人员（ls.core.Party）、全部已发布台账的主表记录。
 * 只收录编号、标题与副标题；台账中设置了读权限（readPermission）的字段不进入索引。
 */
@Service
public class SearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexer.class);

    public static final String WORK = "ls.core.Work";
    public static final String PARTY = "ls.core.Party";
    static final int BATCH = 1000;

    /** 一个可索引来源：表、编号列、标题列、数据域列。 */
    record Source(String type, String table, String idCol, String codeCol, String titleCol, String subtitleSql, String orgCol, String creatorCol,
                  String where) {

        String select() {
            return "select t." + idCol + "::text as id, " + col(codeCol) + "::text as code, " + col(titleCol) + "::text as title, "
                + (subtitleSql == null ? "null" : subtitleSql) + " as subtitle, " + col(orgCol) + "::bigint as org, " + col(creatorCol)
                + "::bigint as creator from " + table + " t where " + (where == null ? "true" : where);
        }

        /** 列名加表别名；子查询等表达式原样使用。 */
        private static String col(String c) {
            if (c == null) return "null";
            return c.matches("\"?[A-Za-z_][A-Za-z0-9_]*\"?") ? "t." + c : c;
        }
    }

    private final JdbcTemplate jdbc;
    private final ConceptRegistry concepts;

    public SearchIndexer(JdbcTemplate jdbc, ConceptRegistry concepts) {
        this.jdbc = jdbc;
        this.concepts = concepts;
    }

    static final Source WORK_SOURCE = new Source(WORK, "core_work_item", "id", "code", "title", "'工作'", "org_unit_id", "created_by_party", null);
    static final Source PARTY_SOURCE = new Source(PARTY, "core_party", "id", "code", "name",
        "(select o.name from core_party_org po join core_org_unit o on o.id = po.org_unit_id where po.party_id = t.id and (po.valid_to is null or po.valid_to >= current_date) order by po.is_primary desc, po.id limit 1)",
        "(select po.org_unit_id from core_party_org po where po.party_id = t.id and (po.valid_to is null or po.valid_to >= current_date) order by po.is_primary desc, po.id limit 1)", null,
        "t.status = 'ACTIVE' and t.party_type in ('EMPLOYEE', 'OUTSOURCE')");

    /** 台账来源：编号取唯一的文本字段，标题取 label 字段或 name / title；字段设置了读权限则不收录。 */
    Optional<Source> ledgerSource(String concept) {
        ConceptDef c = concepts.find(concept).orElse(null);
        if (c == null || c.storage() != ConceptDef.Storage.MANAGED_TABLE || c.parentConcept() != null || !"PUBLISHED".equals(c.status())) {
            return Optional.empty();
        }
        LedgerModel m = new LedgerModel(c);
        List<FieldDef> fs = c.fields().stream().filter(f -> f.status() == FieldDef.FieldStatus.ACTIVE && !f.systemField() && f.textProp("readPermission") == null).toList();
        FieldDef code = fs.stream().filter(f -> f.dataType() == DataType.STRING && f.flag("unique")).findFirst()
            .or(() -> fs.stream().filter(f -> "code".equals(f.code())).findFirst()).orElse(null);
        FieldDef title = fs.stream().filter(f -> f.flag("label")).findFirst()
            .or(() -> fs.stream().filter(f -> List.of("name", "title", "subject").contains(f.code())).findFirst())
            .or(() -> fs.stream().filter(f -> f.dataType() == DataType.STRING && !f.flag("unique")).findFirst()).orElse(code);
        if (title == null) return Optional.empty();
        String quotedTitle = "\"" + m.column(title.code()) + "\"";
        String quotedCode = code == null ? null : "\"" + m.column(code.code()) + "\"";
        return Optional.of(new Source(concept, "\"" + m.table() + "\"", "id", quotedCode, quotedTitle, "'" + c.displayName().replace("'", "''") + "'",
            "org_id", "created_by", "t.del_flag = '0'"));
    }

    Optional<Source> source(String type) {
        if (WORK.equals(type)) return Optional.of(WORK_SOURCE);
        if (PARTY.equals(type)) return Optional.of(PARTY_SOURCE);
        return ledgerSource(type);
    }

    /** 是否为可索引的对象类型（Outbox 事件分流用）。 */
    public boolean indexable(String type) {
        return type != null && source(type).isPresent();
    }

    /** 重建一个对象的索引行；对象不存在（已删除）时移除。 */
    public void reindex(String type, String id) {
        Optional<Source> s = source(type);
        if (s.isEmpty() || id == null) return;
        List<Map<String, Object>> rows = jdbc.queryForList(s.get().select() + " and t." + s.get().idCol() + "::text = ?", id);
        if (rows.isEmpty()) {
            jdbc.update("delete from ui_search_index where object_type = ? and object_id = ?", type, id);
            return;
        }
        upsert(type, List.of(rows.get(0)));
    }

    int upsert(String type, List<Map<String, Object>> rows) {
        List<Object[]> args = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String code = (String) r.get("code");
            String title = r.get("title") == null ? (code == null ? "" : code) : (String) r.get("title");
            if (title.isBlank()) continue;
            Pinyin.Spelling py = Pinyin.of(title);
            String text = String.join(" ", code == null ? "" : code, title, py.full(), py.initials()).toLowerCase(Locale.ROOT).trim();
            args.add(new Object[]{type, r.get("id"), code, cut(title, 300), cut((String) r.get("subtitle"), 300), r.get("org"), r.get("creator"), text});
        }
        if (args.isEmpty()) return 0;
        jdbc.batchUpdate("""
            insert into ui_search_index (object_type, object_id, code, title, subtitle, org_unit_id, created_by_party, search_text, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, now())
            on conflict (object_type, object_id) do update set code = excluded.code, title = excluded.title, subtitle = excluded.subtitle,
              org_unit_id = excluded.org_unit_id, created_by_party = excluded.created_by_party, search_text = excluded.search_text, updated_at = now()
            """, args);
        return args.size();
    }

    private static String cut(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }

    /** 全部来源：工作、人员、已发布台账。 */
    List<Source> sources() {
        List<Source> out = new ArrayList<>(List.of(WORK_SOURCE, PARTY_SOURCE));
        for (String code : jdbc.queryForList("select code from meta_concept where status = 'PUBLISHED' and parent_concept is null and storage = 'MANAGED_TABLE' order by code",
            String.class)) {
            ledgerSource(code).ifPresent(out::add);
        }
        return out;
    }

    /** 补建缺失的索引行并清理已删除对象的行，返回补建条数。分批执行，适用于启动时与定时任务。 */
    public int backfill() {
        int total = 0;
        for (Source s : sources()) {
            try {
                while (true) {
                    List<Map<String, Object>> rows = jdbc.queryForList(s.select() + " and not exists (select 1 from ui_search_index i where i.object_type = ? and i.object_id = t."
                        + s.idCol() + "::text) limit " + BATCH, s.type());
                    if (rows.isEmpty()) break;
                    int n = upsert(s.type(), rows);
                    total += n;
                    // 标题为空的记录不入索引；整批都为空时停止，避免反复查同一批
                    if (rows.size() < BATCH || n == 0) break;
                }
                jdbc.update("delete from ui_search_index i where i.object_type = ? and not exists (select 1 from (" + s.select() + ") x where x.id = i.object_id)",
                    s.type());
            } catch (RuntimeException e) {
                // 单个来源出错（如台账表结构正在变更）不影响其他来源，下次补建再试
                log.warn("全局搜索补建 {} 失败：{}", s.type(), e.getMessage());
            }
        }
        return total;
    }
}
