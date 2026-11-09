package com.lsmom.meta.pack;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pack 可用性门禁：Concept 所属 Pack 已停用时，其 Operation、通用台账接口与 Agent 工具都不可用（数据保留）。
 * 结果缓存 10 秒；本实例内启停 Pack 时调用 {@link #invalidate()} 立即生效。
 */
public class PackGate {

    private static final long TTL_NANOS = 10_000_000_000L;

    private final JdbcTemplate jdbc;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(Optional<String> disabledPack, long at) {
    }

    public PackGate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 返回停用的所属 Pack 编码；Concept 不属于任何 Pack 或所属 Pack 已启用时为空。 */
    public Optional<String> disabledPackOf(String concept) {
        if (concept == null) return Optional.empty();
        long now = System.nanoTime();
        Entry e = cache.get(concept);
        if (e != null && now - e.at() < TTL_NANOS) return e.disabledPack();
        Optional<String> v = jdbc.queryForList("""
            select p.code from meta_concept c join meta_pack p on p.code = c.owner_pack
             where c.code = ? and p.status <> 'ENABLED'""", String.class, concept).stream().findFirst();
        cache.put(concept, new Entry(v, now));
        return v;
    }

    public void invalidate() {
        cache.clear();
    }
}
