package com.lsmom.ui.search;

import com.lsmom.core.event.DomainEvent;
import com.lsmom.core.event.OutboxDestination;
import org.springframework.stereotype.Component;

/**
 * Outbox 订阅方：工作、台账记录的新增、修改、删除与 Operation 事件到达后重建该对象的搜索索引行。
 * 重建按对象当前状态整行覆盖，重复投递结果相同（天然幂等）。
 */
@Component
public class SearchIndexDestination implements OutboxDestination {

    private final SearchIndexer indexer;

    public SearchIndexDestination(SearchIndexer indexer) {
        this.indexer = indexer;
    }

    @Override
    public String name() {
        return "ui.search-index";
    }

    @Override
    public boolean accepts(String eventType) {
        // 事件类型无法单独判断主体是否可索引，主体在 deliver 时按 Concept 判断
        return eventType != null;
    }

    @Override
    public void deliver(DomainEvent event) {
        if (!indexer.indexable(event.subjectConcept())) return;
        indexer.reindex(event.subjectConcept(), event.subjectId());
    }

    @Override
    public int maxAttempts() {
        return 3;
    }
}
