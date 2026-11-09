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

    StateSnapshot write(String id, int expectedVersion, String from, String to, Map<String, Object> changes);
}
