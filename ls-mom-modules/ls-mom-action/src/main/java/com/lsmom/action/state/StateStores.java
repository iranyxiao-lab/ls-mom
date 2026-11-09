package com.lsmom.action.state;

import com.lsmom.core.error.LsBizException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按 Concept 查找状态读写适配器（核心对象、受控物理表都通过此入口）。 */
@Component
public class StateStores {

    private final Map<String, StateStore> stores = new ConcurrentHashMap<>();

    public StateStores(List<StateStore> beans) {
        beans.forEach(this::register);
    }

    public void register(StateStore s) {
        stores.put(s.concept(), s);
    }

    public StateStore of(String concept) {
        StateStore s = stores.get(concept);
        if (s == null) throw new LsBizException("STATE_STORE_MISSING", "没有为 " + concept + " 注册状态存储");
        return s;
    }

    public boolean has(String concept) {
        return stores.containsKey(concept);
    }
}
