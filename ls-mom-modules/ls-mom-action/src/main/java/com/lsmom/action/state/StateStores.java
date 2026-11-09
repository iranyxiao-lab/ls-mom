package com.lsmom.action.state;

import com.lsmom.core.error.LsBizException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按 Concept 查找状态读写适配器（核心对象、受控物理表都通过此入口）。 */
@Component
public class StateStores {

    /** 动态解析（如受控物理表的通用状态存储）。 */
    public interface Resolver {
        java.util.Optional<StateStore> resolve(String concept);
    }

    private final Map<String, StateStore> stores = new ConcurrentHashMap<>();
    private final List<Resolver> resolvers = new java.util.concurrent.CopyOnWriteArrayList<>();

    public StateStores(List<StateStore> beans) {
        beans.forEach(this::register);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StateStores(List<StateStore> beans, List<Resolver> resolvers) {
        beans.forEach(this::register);
        this.resolvers.addAll(resolvers);
    }

    public void addResolver(Resolver r) {
        resolvers.add(r);
    }

    private StateStore find(String concept) {
        StateStore s = stores.get(concept);
        if (s != null) return s;
        for (Resolver r : resolvers) {
            java.util.Optional<StateStore> x = r.resolve(concept);
            if (x.isPresent()) return x.get();
        }
        return null;
    }

    public void register(StateStore s) {
        stores.put(s.concept(), s);
    }

    public StateStore of(String concept) {
        StateStore s = find(concept);
        if (s == null) throw new LsBizException("STATE_STORE_MISSING", "没有为 " + concept + " 注册状态存储");
        return s;
    }

    public boolean has(String concept) {
        return find(concept) != null;
    }
}
