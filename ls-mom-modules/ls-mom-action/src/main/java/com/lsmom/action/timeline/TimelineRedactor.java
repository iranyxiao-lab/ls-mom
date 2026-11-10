package com.lsmom.action.timeline;

import com.lsmom.action.api.ActorContext;

import java.util.List;

/**
 * 时间轴脱敏：Pack 可按对象与查看人隐藏事件中的操作人（如匿名意见的提交人）。事件本身与审计记录不变，只影响时间轴接口的输出。
 */
public interface TimelineRedactor {

    /** 返回查看人可见的条目；不处理该 Concept 时原样返回。 */
    List<TimelineService.Entry> redact(String concept, String id, List<TimelineService.Entry> entries, ActorContext viewer);
}
