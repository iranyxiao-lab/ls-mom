package com.lsmom.ui.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.crud.metric.NamedQueryDef;
import com.lsmom.crud.metric.NamedQueryRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 全局搜索：命名 Query {@code ls.ui.globalSearch}（数据域由命名 Query 机制注入）＋ 按对象类型的功能权限过滤。
 * 排序：编号完全一致 → 编号前缀 → 标题前缀 → 其余按 pg_trgm 相似度。
 */
@Configuration
public class GlobalSearch {

    public static final String QUERY = "ls.ui.globalSearch";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Bean
    public NamedQueryDef globalSearchQuery() {
        return new NamedQueryDef(QUERY, "全局搜索", "按编号、名称、拼音或首字母搜索工作、台账记录与人员；只返回调用者有权查看的对象类型与数据域内的记录",
            """
                {"type":"object","additionalProperties":false,"required":["q","types"],"properties":{
                  "q":{"type":"string","minLength":1,"maxLength":100},
                  "types":{"type":"array","minItems":1,"maxItems":500,"items":{"type":"string","maxLength":100}},
                  "limit":{"type":"integer","minimum":1,"maximum":50}}}""",
            """
                select t.object_type, t.object_id, t.code, t.title, t.subtitle,
                       case when lower(t.code) = lower(:q) then 0
                            when lower(t.code) like lower(:q) || '%' then 1
                            when lower(t.title) like lower(:q) || '%' then 2
                            else 3 end as rank,
                       similarity(t.search_text, lower(:q)) as score
                  from ui_search_index t
                 where t.object_type in (:types)
                   and (t.search_text like '%' || lower(:q) || '%' or t.search_text % lower(:q))
                   and {{scope:t.org_unit_id|t.created_by_party}}
                 order by rank, score desc, t.updated_at desc
                 limit coalesce(cast(:limit as integer), 20)""",
            3000, null, List.of(NamedQueryRegistry.json(Map.of("q", "p-101", "types", List.of("ls.core.Work"))),
                NamedQueryRegistry.json(Map.of("q", "sbtz", "types", List.of("ls.core.Work", "ls.core.Party"), "limit", 5))));
    }

    /** 启动后在后台补建索引（不阻塞就绪），之后每 30 分钟补一次（覆盖无事件的变更，如人员调动）。 */
    @Bean
    public ApplicationRunner searchIndexBackfill(SearchIndexer indexer) {
        return (ApplicationArguments args) -> {
            Thread t = new Thread(indexer::backfill, "ui-search-backfill");
            t.setDaemon(true);
            t.start();
        };
    }

    @Configuration
    static class Schedule {
        private final SearchIndexer indexer;

        Schedule(SearchIndexer indexer) {
            this.indexer = indexer;
        }

        @Scheduled(initialDelay = 30 * 60_000, fixedDelay = 30 * 60_000)
        public void backfill() {
            indexer.backfill();
        }
    }

    /** 调用者可查看的对象类型：工作与人员（按数据域），台账要求列表权限 crud:&lt;Concept&gt;:list。 */
    public static List<String> allowedTypes(List<String> ledgerConcepts, ActorContext actor) {
        List<String> out = new ArrayList<>(List.of(SearchIndexer.WORK, SearchIndexer.PARTY));
        for (String c : ledgerConcepts) if (actor.hasPermission("crud:" + c + ":list")) out.add(c);
        return out;
    }

    public static ObjectNode params(String q, List<String> types, int limit) {
        ObjectNode p = JSON.createObjectNode();
        p.put("q", q.trim().toLowerCase(Locale.ROOT));
        p.set("types", JSON.valueToTree(types));
        p.put("limit", limit);
        return p;
    }
}
