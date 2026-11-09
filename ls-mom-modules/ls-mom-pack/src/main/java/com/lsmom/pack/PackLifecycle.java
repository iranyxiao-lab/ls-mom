package com.lsmom.pack;

import com.lsmom.core.error.LsBizException;
import com.lsmom.meta.pack.PackGate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 启用、停用与只读导出。停用只关闭入口（Operation、台账接口、Agent 工具），数据原样保留。 */
public class PackLifecycle {

    private static final int EXPORT_LIMIT = 100_000;

    private final JdbcTemplate jdbc;
    private final PackStore store;
    private final PackGate gate;

    public PackLifecycle(JdbcTemplate jdbc, PackStore store, PackGate gate) {
        this.jdbc = jdbc;
        this.store = store;
        this.gate = gate;
    }

    public void disable(String code) {
        PackStore.Pack p = store.get(code);
        if (!"ENABLED".equals(p.status())) throw new LsBizException("PACK_STATE_INVALID", code + " 当前状态为 " + p.status() + "，不能停用");
        List<String> users = store.dependents(code);
        if (!users.isEmpty()) {
            throw new LsBizException("PACK_IN_USE", code + " 被已启用的 " + String.join("、", users) + " 依赖，不能停用", Map.of("dependents", users));
        }
        jdbc.update("update meta_pack set status = 'DISABLED', disabled_at = now(), updated_at = now() where code = ?", code);
        gate.invalidate();
    }

    public void enable(String code) {
        PackStore.Pack p = store.get(code);
        if (!"DISABLED".equals(p.status())) throw new LsBizException("PACK_STATE_INVALID", code + " 当前状态为 " + p.status() + "，不能启用");
        for (PackManifest.Dependency d : p.dependsOn()) {
            String s = store.find(d.code()).map(PackStore.Pack::status).orElse("MISSING");
            if (!"ENABLED".equals(s)) {
                throw new LsBizException("PACK_DEPENDENCY_DISABLED", code + " 依赖的 " + d.code() + " 未启用（" + s + "），请先启用依赖");
            }
        }
        jdbc.update("update meta_pack set status = 'ENABLED', disabled_at = null, updated_at = now() where code = ?", code);
        gate.invalidate();
    }

    /** 只读导出 Pack 全部对象的数据（停用后仍可导出）。 */
    public Map<String, Object> export(String code) {
        PackStore.Pack p = store.get(code);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pack", code);
        out.put("version", p.version());
        out.put("status", p.status());
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map<String, Object> c : jdbc.queryForList(
            "select code, table_name from meta_concept where owner_pack = ? and storage = 'MANAGED_TABLE' order by parent_concept nulls first, code", code)) {
            String table = (String) c.get("table_name");
            if (!table.matches("[a-z_][a-z0-9_]*")) continue;
            data.put((String) c.get("code"), jdbc.queryForList("select * from " + table + " order by id limit " + EXPORT_LIMIT));
        }
        out.put("data", data);
        return out;
    }
}
