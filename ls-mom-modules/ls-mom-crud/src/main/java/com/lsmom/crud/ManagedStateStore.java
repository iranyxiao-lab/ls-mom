package com.lsmom.crud;

import com.lsmom.action.state.StateSnapshot;
import com.lsmom.action.state.StateStore;
import com.lsmom.action.state.StateStores;
import com.lsmom.core.error.LsBizException;
import com.lsmom.crud.query.LedgerModel;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.FieldDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 受控物理表的通用状态存储：让 Pack 为台账声明状态机（提交、审核、退回、关闭……），迁移经 Operation Gateway 执行，
 * 状态与版本只经此处写入（乐观锁 + 原状态校验）。
 */
@Component
public class ManagedStateStore implements StateStores.Resolver {

    private final JdbcTemplate jdbc;
    private final ConceptRegistry concepts;
    private Map<String, List<String>> partyAccess = Map.of();

    public ManagedStateStore(JdbcTemplate jdbc, ConceptRegistry concepts) {
        this.jdbc = jdbc;
        this.concepts = concepts;
    }

    /** 指定人员字段（业务 Pack 声明）：字段等于调用者时，可在数据域外办理该记录的迁移。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPartyAccess(List<com.lsmom.meta.concept.PartyAccess> list) {
        Map<String, List<String>> m = new java.util.HashMap<>();
        if (list != null) list.forEach(p -> m.computeIfAbsent(p.concept(), k -> new ArrayList<>()).add(p.field()));
        this.partyAccess = Map.copyOf(m);
    }

    @Override
    public Optional<StateStore> resolve(String concept) {
        Optional<ConceptDef> c = concepts.find(concept);
        if (c.isEmpty() || c.get().storage() != ConceptDef.Storage.MANAGED_TABLE || c.get().isChild()
            || !"PUBLISHED".equals(c.get().status())) {
            return Optional.empty();
        }
        return Optional.of(store(new LedgerModel(c.get())));
    }

    private StateStore store(LedgerModel m) {
        String table = "\"" + m.table() + "\"";
        return new StateStore() {
            @Override
            public String concept() {
                return m.concept().code();
            }

            @Override
            public StateSnapshot load(String id) {
                return jdbc.query("select id, status, version, org_id, created_by from " + table + " where id = ? and del_flag = '0'",
                    (rs, i) -> new StateSnapshot(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4), (Long) rs.getObject(5)),
                    Long.parseLong(id)).stream().findFirst().orElseThrow(() -> LsBizException.notFound(m.concept().displayName(), id));
            }

            @Override
            public boolean assignedTo(String id, Long party) {
                List<String> fields = partyAccess.getOrDefault(m.concept().code(), List.of());
                if (party == null || fields.isEmpty()) return false;
                String cond = String.join(" or ", fields.stream().map(f -> "\"" + m.column(f) + "\" = ?").toList());
                List<Object> args = new ArrayList<>(List.of(Long.parseLong(id)));
                fields.forEach(f -> args.add(party));
                Integer n = jdbc.queryForObject("select count(*) from " + table + " where id = ? and del_flag = '0' and (" + cond + ")", Integer.class,
                    args.toArray());
                return n != null && n > 0;
            }

            @Override
            public Map<String, Object> attributes(String id) {
                return jdbc.queryForList("select * from " + table + " where id = ?", Long.parseLong(id)).stream().findFirst()
                    .map(r -> LedgerRows.toRow(m, r, f -> true)).orElse(Map.of());
            }

            @Override
            public StateSnapshot write(String id, int expectedVersion, String from, String to, Map<String, Object> changes) {
                List<String> sets = new ArrayList<>(List.of("status = ?", "version = version + 1", "updated_at = now()"));
                List<Object> args = new ArrayList<>(List.of(to));
                changes.forEach((field, value) -> {
                    FieldDef f = m.field(field);
                    if (f == null || f.ext()) throw new IllegalArgumentException("迁移效果只能写入显式列字段：" + field);
                    sets.add("\"" + m.column(field) + "\" = ?");
                    args.add(value);
                });
                args.add(Long.parseLong(id));
                args.add(expectedVersion);
                args.add(from);
                int n = jdbc.update("update " + table + " set " + String.join(", ", sets) + " where id = ? and version = ? and status = ?",
                    args.toArray());
                if (n == 0) throw new LsBizException("CONCURRENT_MODIFICATION", "对象已被他人修改，请刷新后重试");
                return load(id);
            }
        };
    }
}
