package com.lsmom.ui.search;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.crud.metric.NamedQueryRegistry;
import org.dromara.common.core.domain.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 全局搜索接口（命令面板使用）：结果只含调用者有权查看的对象。 */
@RestController
@RequestMapping("/api/ui/search")
public class SearchController {

    private final NamedQueryRegistry queries;
    private final ActorResolver actors;
    private final JdbcTemplate jdbc;

    public SearchController(NamedQueryRegistry queries, ActorResolver actors, JdbcTemplate jdbc) {
        this.queries = queries;
        this.actors = actors;
        this.jdbc = jdbc;
    }

    @GetMapping
    public R<List<Map<String, Object>>> search(@RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
        String kw = q == null ? "" : q.trim();
        if (kw.isEmpty()) return R.ok(List.of());
        if (kw.length() > 100) kw = kw.substring(0, 100);
        ActorContext actor = actors.current();
        List<String> ledgers = jdbc.queryForList("select distinct object_type from ui_search_index where object_type not in (?, ?)", String.class,
            SearchIndexer.WORK, SearchIndexer.PARTY);
        List<String> types = GlobalSearch.allowedTypes(ledgers, actor);
        return R.ok(queries.execute(GlobalSearch.QUERY, GlobalSearch.params(kw, types, Math.max(1, Math.min(limit, 50))), actor).rows());
    }
}
