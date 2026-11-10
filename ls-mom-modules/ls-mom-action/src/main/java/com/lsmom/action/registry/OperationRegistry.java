package com.lsmom.action.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.action.api.Exposure;
import com.lsmom.action.api.IdempotencyPolicy;
import com.lsmom.action.api.LsOperation;
import com.lsmom.action.api.OperationHandler;
import com.lsmom.action.api.Risk;
import com.lsmom.core.error.LsBizException;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Operation 注册表：契约存于 meta_operation，代码处理器按编码绑定。
 * {@link #reconcile} 在启动时执行，代码注解与元数据不一致即抛出 {@link OperationContractMismatch} 使启动失败。
 */
@Service
public class OperationRegistry {

    static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final Map<String, OperationHandler> handlers = new HashMap<>();
    /** 目标 Concept 尚未注册的代码 Operation（由标准 Pack 提供 Concept），Pack 安装后经 {@link #reconcilePending()} 补登记。 */
    private final List<OperationDefinition> pending = new ArrayList<>();

    public OperationRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<OperationDefinition> mapper = (rs, i) -> new OperationDefinition(rs.getString("code"),
        rs.getString("concept_code"), rs.getString("display_name"), OperationDefinition.Kind.valueOf(rs.getString("kind")),
        Risk.valueOf(rs.getString("risk")), Exposure.valueOf(rs.getString("exposure")), rs.getString("permission"),
        readTree(rs.getString("input_schema")), readList(rs.getString("guards")), IdempotencyPolicy.valueOf(rs.getString("idempotency")),
        rs.getBoolean("requires_version"), rs.getString("source"), rs.getString("status"), rs.getInt("version"), rs.getString("description"),
        rs.getString("undo_operation"));

    public Optional<OperationDefinition> find(String code) {
        return jdbc.query("select * from meta_operation where code = ?", mapper, code).stream().findFirst();
    }

    /** 可调用的 Operation（已发布）；不存在或未发布抛 OPERATION_NOT_FOUND。 */
    public OperationDefinition require(String code) {
        return find(code).filter(o -> "PUBLISHED".equals(o.status()))
            .orElseThrow(() -> new LsBizException("OPERATION_NOT_FOUND", "Operation 不存在：" + code, Map.of("operation", code)));
    }

    public List<OperationDefinition> listPublished() {
        return jdbc.query("select * from meta_operation where status = 'PUBLISHED' order by code", mapper);
    }

    public List<OperationDefinition> listByConcept(String concept) {
        return jdbc.query("select * from meta_operation where concept_code = ? and status = 'PUBLISHED' order by code", mapper, concept);
    }

    public Optional<OperationHandler> handler(String code) {
        return Optional.ofNullable(handlers.get(code));
    }

    /** 由元数据生成的 Operation（如状态机迁移）写入或更新契约。 */
    @Transactional
    public void upsertMeta(OperationDefinition d) {
        jdbc.update("""
            insert into meta_operation (code, concept_code, display_name, kind, risk, exposure, permission, input_schema, guards,
              idempotency, requires_version, source, status, version, description, undo_operation)
            values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, 'META', 'PUBLISHED', ?, ?, ?)
            on conflict (code) do update set display_name = excluded.display_name, risk = excluded.risk, exposure = excluded.exposure,
              permission = excluded.permission, input_schema = excluded.input_schema, guards = excluded.guards,
              idempotency = excluded.idempotency, requires_version = excluded.requires_version, status = 'PUBLISHED',
              version = excluded.version, description = excluded.description, undo_operation = excluded.undo_operation, updated_at = now()
            where meta_operation.source = 'META'""",
            d.code(), d.concept(), d.name(), d.kind().name(), d.risk().name(), d.exposure().name(), d.permission(),
            write(d.inputSchema()), write(d.guards()), d.idempotency().name(), d.requiresVersion(), d.version(), d.description(),
            d.undoOperation());
        Integer src = jdbc.queryForObject("select count(*) from meta_operation where code = ? and source = 'CODE'", Integer.class, d.code());
        if (src != null && src > 0) {
            throw new LsBizException("OPERATION_CODE_TAKEN", "编码已被 Java Operation 占用：" + d.code());
        }
    }

    public static class OperationContractMismatch extends IllegalStateException {
        public OperationContractMismatch(String message) {
            super(message);
        }
    }

    /** 绑定处理器并与元数据对账。 */
    @Transactional
    public void reconcile(List<OperationHandler> beans) {
        handlers.clear();
        pending.clear();
        List<String> problems = new ArrayList<>();
        Set<String> declared = new java.util.HashSet<>();
        for (OperationHandler h : beans) {
            LsOperation a = AopUtils.getTargetClass(h).getAnnotation(LsOperation.class);
            if (a == null) {
                problems.add(AopUtils.getTargetClass(h).getName() + " 实现了 OperationHandler 但缺少 @LsOperation");
                continue;
            }
            if (!declared.add(a.code())) {
                problems.add("Operation 编码重复声明：" + a.code());
                continue;
            }
            handlers.put(a.code(), h);
            OperationDefinition wanted = fromAnnotation(a);
            Optional<OperationDefinition> existing = find(a.code());
            if (existing.isEmpty()) {
                if (conceptRegistered(wanted.concept())) insertCode(wanted);
                else pending.add(wanted);
                continue;
            }
            OperationDefinition meta = existing.get();
            if (!"CODE".equals(meta.source())) {
                problems.add(a.code() + "：元数据中为 " + meta.source() + " 来源，不能由代码声明");
            } else if (a.version() > meta.version()) {
                updateCode(wanted);
            } else if (a.version() < meta.version()) {
                problems.add(a.code() + "：代码版本 v" + a.version() + " 低于元数据 v" + meta.version());
            } else {
                List<String> diff = diff(meta, wanted);
                if (!diff.isEmpty()) problems.add(a.code() + " 契约不一致（如需修改请提高 version）：" + String.join("；", diff));
            }
        }
        List<String> orphans = jdbc.queryForList("select code from meta_operation where source = 'CODE' and status = 'PUBLISHED'", String.class)
            .stream().filter(c -> !declared.contains(c)).toList();
        orphans.forEach(c -> problems.add(c + "：元数据已发布但代码中没有对应处理器"));
        if (!problems.isEmpty()) {
            throw new OperationContractMismatch("Operation 代码与元数据对账失败：\n  - " + String.join("\n  - ", problems));
        }
    }

    /** 标准 Pack 安装后登记等待 Concept 的代码 Operation；仍缺 Concept 的视为对账失败。 */
    @Transactional
    public void reconcilePending() {
        List<String> problems = new ArrayList<>();
        for (OperationDefinition d : List.copyOf(pending)) {
            if (conceptRegistered(d.concept())) {
                if (find(d.code()).isEmpty()) insertCode(d);
                pending.remove(d);
            } else {
                problems.add("Operation " + d.code() + " 的目标 Concept 未注册：" + d.concept());
            }
        }
        if (!problems.isEmpty()) throw new OperationContractMismatch("Operation 代码与元数据对账失败：\n  - " + String.join("\n  - ", problems));
    }

    public List<String> pendingCodes() {
        return pending.stream().map(OperationDefinition::code).toList();
    }

    private boolean conceptRegistered(String concept) {
        Integer n = jdbc.queryForObject("select count(*) from meta_concept where code = ?", Integer.class, concept);
        return n != null && n > 0;
    }

    /** 下线代码 Operation（删除处理器前先执行，否则启动对账失败）。 */
    @Transactional
    public void deprecate(String code) {
        jdbc.update("update meta_operation set status = 'DEPRECATED', updated_at = now() where code = ?", code);
    }

    static OperationDefinition fromAnnotation(LsOperation a) {
        return new OperationDefinition(a.code(), a.concept(), a.name(), OperationDefinition.Kind.HANDLER, a.risk(), a.exposure(),
            a.permission().isBlank() ? null : a.permission(), readTree(a.inputSchema()), Arrays.asList(a.guards()), a.idempotency(),
            a.requiresVersion(), "CODE", "PUBLISHED", a.version(), a.description().isBlank() ? null : a.description());
    }

    private static List<String> diff(OperationDefinition m, OperationDefinition w) {
        List<String> d = new ArrayList<>();
        if (!m.concept().equals(w.concept())) d.add("concept " + m.concept() + "≠" + w.concept());
        if (m.kind() != w.kind()) d.add("kind " + m.kind() + "≠" + w.kind());
        if (m.risk() != w.risk()) d.add("risk " + m.risk() + "≠" + w.risk());
        if (m.exposure() != w.exposure()) d.add("exposure " + m.exposure() + "≠" + w.exposure());
        if (!Objects.equals(m.permission(), w.permission())) d.add("permission " + m.permission() + "≠" + w.permission());
        if (m.idempotency() != w.idempotency()) d.add("idempotency " + m.idempotency() + "≠" + w.idempotency());
        if (m.requiresVersion() != w.requiresVersion()) d.add("requiresVersion " + m.requiresVersion() + "≠" + w.requiresVersion());
        if (!m.inputSchema().equals(w.inputSchema())) d.add("inputSchema 不同");
        if (!m.guards().equals(w.guards())) d.add("guards " + m.guards() + "≠" + w.guards());
        return d;
    }

    private void insertCode(OperationDefinition d) {
        Integer concept = jdbc.queryForObject("select count(*) from meta_concept where code = ?", Integer.class, d.concept());
        if (concept == null || concept == 0) {
            throw new OperationContractMismatch("Operation " + d.code() + " 的目标 Concept 未注册：" + d.concept());
        }
        jdbc.update("""
            insert into meta_operation (code, concept_code, display_name, kind, risk, exposure, permission, input_schema, guards,
              idempotency, requires_version, source, status, version, description)
            values (?, ?, ?, 'HANDLER', ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, 'CODE', 'PUBLISHED', ?, ?)""",
            d.code(), d.concept(), d.name(), d.risk().name(), d.exposure().name(), d.permission(), write(d.inputSchema()),
            write(d.guards()), d.idempotency().name(), d.requiresVersion(), d.version(), d.description());
    }

    private void updateCode(OperationDefinition d) {
        jdbc.update("""
            update meta_operation set concept_code = ?, display_name = ?, risk = ?, exposure = ?, permission = ?, input_schema = ?::jsonb,
              guards = ?::jsonb, idempotency = ?, requires_version = ?, version = ?, description = ?, status = 'PUBLISHED', updated_at = now()
            where code = ?""", d.concept(), d.name(), d.risk().name(), d.exposure().name(), d.permission(), write(d.inputSchema()),
            write(d.guards()), d.idempotency().name(), d.requiresVersion(), d.version(), d.description(), d.code());
    }

    static JsonNode readTree(String json) {
        try {
            return JSON.readTree(json == null ? "{\"type\":\"object\"}" : json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("非法的 JSON Schema：" + json, e);
        }
    }

    static List<String> readList(String json) {
        try {
            return json == null ? List.of() : JSON.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public Set<String> handlerCodes() {
        return handlers.keySet().stream().collect(Collectors.toUnmodifiableSet());
    }
}
