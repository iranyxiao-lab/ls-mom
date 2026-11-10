package com.lsmom.action.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 摘要上下文：某类对象（交接班、异常、DCS 报警等）提供可被摘要引用的事实。每条事实都有可追溯的引用（类型＋ID）。
 * 实现须按调用者数据域判断可见性，不可见时抛 ACCESS_DENIED 或 NOT_FOUND。
 */
public interface SummaryContextProvider {

    /** 摘要类型，如 ISSUE、SHIFT_HANDOVER、DCS_ALARM。 */
    String kind();

    Context load(String subjectId, ActorContext actor);

    record Ref(String type, String id, String label) {
        public String key() {
            return type + ":" + id;
        }
    }

    record Fact(Ref ref, String text, LocalDateTime at) {
    }

    record Context(String subjectConcept, String subjectId, String title, Long orgUnitId, List<Fact> facts) {
    }
}
