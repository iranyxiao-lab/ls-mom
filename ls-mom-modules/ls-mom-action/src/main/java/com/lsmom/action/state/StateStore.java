package com.lsmom.action.state;

import java.util.Map;

/** 某个 Concept 的状态读写适配器。写入必须校验版本与原状态（乐观锁）。 */
public interface StateStore {

    String concept();

    StateSnapshot load(String id);

    /** 目标对象的业务字段（供规则 {@code $model} 使用），键为字段名。 */
    default Map<String, Object> attributes(String id) {
        return Map.of();
    }

    /** 该主体是否是对象的当前承办人（如工作的有效分派人）；承办人即使数据域只有"仅本人"也可办理。 */
    default boolean assignedTo(String id, Long party) {
        return false;
    }

    StateSnapshot write(String id, int expectedVersion, String from, String to, Map<String, Object> changes);
}
