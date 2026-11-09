package com.lsmom.action.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.action.api.Exposure;
import com.lsmom.action.api.IdempotencyPolicy;
import com.lsmom.action.api.Risk;
import com.lsmom.action.registry.OperationDefinition;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 状态机注册：发布定义时校验结构、写入 meta_flow 新版本，并把每个迁移注册为 TRANSITION 类 Operation。
 */
@Service
public class StateMachineRegistry {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final OperationRegistry operations;
    private final Map<String, StateMachineDefinition> byConcept = new ConcurrentHashMap<>();

    public StateMachineRegistry(JdbcTemplate jdbc, OperationRegistry operations) {
        this.jdbc = jdbc;
        this.operations = operations;
    }

    @Transactional
    public int publish(StateMachineDefinition def) {
        List<String> problems = validate(def);
        if (!problems.isEmpty()) {
            throw new LsBizException("STATE_MACHINE_INVALID", "状态机定义不合法：" + problems.get(0), Map.of("problems", problems));
        }
        JsonNode json = JSON.valueToTree(def);
        Optional<Integer> same = jdbc.queryForList("""
            select version from meta_flow where code = ? and kind = 'STATE_MACHINE' and status = 'PUBLISHED' and definition = ?::jsonb""",
            Integer.class, def.code(), json.toString()).stream().findFirst();
        int version = same.orElseGet(() -> {
            Integer max = jdbc.queryForObject("select coalesce(max(version), 0) from meta_flow where code = ?", Integer.class, def.code());
            int v = (max == null ? 0 : max) + 1;
            jdbc.update("update meta_flow set status = 'RETIRED' where code = ? and status = 'PUBLISHED'", def.code());
            jdbc.update("""
                insert into meta_flow (code, version, concept_code, kind, display_name, status, definition, published_at)
                values (?, ?, ?, 'STATE_MACHINE', ?, 'PUBLISHED', ?::jsonb, now())""", def.code(), v, def.concept(), def.displayName(), json.toString());
            return v;
        });
        for (StateMachineDefinition.Transition t : def.transitions()) {
            operations.upsertMeta(new OperationDefinition(t.operation(), def.concept(), t.name(), OperationDefinition.Kind.TRANSITION,
                t.risk() == null ? Risk.C1 : Risk.valueOf(t.risk()), t.exposure() == null ? Exposure.NONE : Exposure.valueOf(t.exposure()),
                t.permission(), t.inputSchema() == null ? JSON.createObjectNode().put("type", "object") : t.inputSchema(),
                t.guards() == null ? List.of() : t.guards(), IdempotencyPolicy.OPTIONAL, true, "META", "PUBLISHED", version,
                "迁移 " + String.join("/", t.from()) + " → " + t.to()));
        }
        byConcept.put(def.concept(), def);
        return version;
    }

    public StateMachineDefinition forConcept(String concept) {
        StateMachineDefinition d = byConcept.get(concept);
        if (d != null) return d;
        List<String> rows = jdbc.queryForList("""
            select definition::text from meta_flow where concept_code = ? and kind = 'STATE_MACHINE' and status = 'PUBLISHED'
            order by version desc limit 1""", String.class, concept);
        if (rows.isEmpty()) throw new LsBizException("STATE_MACHINE_NOT_FOUND", "未定义状态机：" + concept);
        try {
            d = JSON.readValue(rows.get(0), StateMachineDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        byConcept.put(concept, d);
        return d;
    }

    public List<String> validate(StateMachineDefinition def) {
        List<String> p = new ArrayList<>();
        Set<String> states = new HashSet<>(def.states());
        if (!states.contains(def.initial())) p.add("初始状态不在状态列表中：" + def.initial());
        Set<String> ops = new HashSet<>();
        for (StateMachineDefinition.Transition t : def.transitions()) {
            if (!t.operation().startsWith(def.concept() + ".")) p.add("迁移 Operation 编码须以 Concept 编码开头：" + t.operation());
            if (!ops.add(t.operation())) p.add("迁移 Operation 重复：" + t.operation());
            if (!states.contains(t.to())) p.add(t.operation() + " 的目标状态未定义：" + t.to());
            t.from().stream().filter(s -> !states.contains(s)).forEach(s -> p.add(t.operation() + " 的来源状态未定义：" + s));
        }
        return p;
    }
}
