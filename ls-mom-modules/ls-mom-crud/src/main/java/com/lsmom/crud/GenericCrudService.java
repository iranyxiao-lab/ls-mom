package com.lsmom.crud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationResult;
import com.lsmom.action.gateway.IdempotencyStore;
import com.lsmom.action.gateway.OperationAudit;
import com.lsmom.action.registry.OperationDefinition;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.action.risk.GenericWritePolicy;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.event.EventPublisher;
import com.lsmom.core.org.DataScope;
import com.lsmom.core.party.PartyService;
import com.lsmom.crud.query.LedgerModel;
import com.lsmom.crud.query.QuerySpec;
import com.lsmom.crud.query.SafeSqlBuilder;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.DataType;
import com.lsmom.meta.concept.FieldDef;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 通用台账增删改查。所有入口在服务端执行：
 * <ol>
 *   <li>拒绝 Agent 身份（Agent 只能调用包装后的 Operation），并审计；</li>
 *   <li>功能权限 {@code crud:<Concept>:list|query|add|edit|remove|export|import}；</li>
 *   <li>行级数据域（org_id / 本人创建）与字段权限（props.readPermission / writePermission）；</li>
 *   <li>按 Concept 定义校验输入（必填、类型、长度、枚举、字典、范围、引用可见性），子表错误定位到行；</li>
 *   <li>正式动作分流：只有草稿态可通用编辑/删除，状态字段与 C2 对象禁止通用写入（{@link GenericWritePolicy}）；</li>
 *   <li>乐观锁、幂等键防重复提交、字段级变更进入业务时间轴、写入审计。</li>
 * </ol>
 */
@Service
public class GenericCrudService {

    public static final String DRAFT = "DRAFT";

    private final JdbcTemplate jdbc;
    private final ConceptRegistry concepts;
    private final GenericWritePolicy policy;
    private final OperationRegistry operations;
    private final IdempotencyStore idempotency;
    private final OperationAudit audit;
    private final EventPublisher events;
    private final PartyService parties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final CrudProperties props;
    private List<RecordValidator> recordValidators = List.of();
    private com.lsmom.meta.pack.PackGate packGate;

    /** 所属 Pack 停用后通用台账接口不可访问（数据保留，经 Pack 只读导出）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPackGate(com.lsmom.meta.pack.PackGate packGate) {
        this.packGate = packGate;
    }

    public GenericCrudService(JdbcTemplate jdbc, ConceptRegistry concepts, GenericWritePolicy policy, OperationRegistry operations,
                              IdempotencyStore idempotency, OperationAudit audit, EventPublisher events, PartyService parties,
                              PlatformTransactionManager txm, Clock clock, CrudProperties props) {
        this.jdbc = jdbc;
        this.concepts = concepts;
        this.policy = policy;
        this.operations = operations;
        this.idempotency = idempotency;
        this.audit = audit;
        this.events = events;
        this.parties = parties;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
        this.props = props;
    }

    private Map<String, String> draftPrivacy = Map.of();

    /** 草稿私有的 Concept（业务 Pack 声明）；可选注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDraftPrivacy(List<com.lsmom.meta.concept.DraftPrivacy> list) {
        Map<String, String> m = new java.util.HashMap<>();
        if (list != null) list.forEach(d -> m.put(d.concept(), d.status()));
        this.draftPrivacy = Map.copyOf(m);
    }

    private Map<String, List<String>> partyAccess = Map.of();

    /** 指定人员可见的 Concept 字段（业务 Pack 声明）；可选注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPartyAccess(List<com.lsmom.meta.concept.PartyAccess> list) {
        Map<String, List<String>> m = new java.util.HashMap<>();
        if (list != null) list.forEach(p -> m.computeIfAbsent(p.concept(), k -> new ArrayList<>()).add(p.field()));
        this.partyAccess = Map.copyOf(m);
    }

    /** 指定人员可见的列名（字段编码 → 列）。 */
    private List<String> partyColumns(LedgerModel m) {
        List<String> fields = partyAccess.get(m.concept().code());
        return fields == null ? List.of() : fields.stream().map(m::column).toList();
    }

    /** 草稿私有条件（别名 t），无需限制时返回 null。 */
    private String privacySql(LedgerModel m, ActorContext actor, List<Object> args) {
        String st = draftPrivacy.get(m.concept().code());
        return st == null ? null : SafeSqlBuilder.privateDraftCondition("t", st, actor.effectiveParty(), args);
    }

    /** 表单模块等提供的记录级校验（按冻结的表单版本）；可选注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecordValidators(List<RecordValidator> validators) {
        this.recordValidators = validators == null ? List.of() : List.copyOf(validators);
    }

    public record Page(List<Map<String, Object>> rows, long total, int page, int size) {
    }

    public static String permission(String concept, String action) {
        return "crud:" + concept + ":" + action;
    }

    // —— 读取 ——

    public LedgerModel model(String concept) {
        return new LedgerModel(concepts.get(concept));
    }

    public List<LedgerModel> children(String concept) {
        return jdbc.queryForList("select code from meta_concept where parent_concept = ? and status = 'PUBLISHED' order by code",
            String.class, concept).stream().map(this::model).toList();
    }

    public Page list(String concept, QuerySpec spec, ActorContext actor) {
        guard(actor, concept, "list", null, spec);
        LedgerModel m = master(concept);
        SafeSqlBuilder b = builder(m, actor);
        SafeSqlBuilder.Sql page = b.page(spec, props.maxPageSize());
        SafeSqlBuilder.Sql count = b.count(spec);
        Predicate<String> readable = readable(m, actor);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw : jdbc.queryForList(page.sql(), page.args().toArray())) rows.add(LedgerRows.toRow(m, raw, readable));
        decorate(m, rows, actor, spec.expand());
        Long total = jdbc.queryForObject(count.sql(), Long.class, count.args().toArray());
        return new Page(rows, total == null ? 0 : total, spec.pageOrDefault(), spec.sizeOrDefault(props.maxPageSize()));
    }

    /** 分组聚合（字段、函数、别名均为白名单）。 */
    public List<Map<String, Object>> aggregate(String concept, QuerySpec spec, ActorContext actor) {
        guard(actor, concept, "list", null, spec);
        LedgerModel m = master(concept);
        SafeSqlBuilder.Sql sql = builder(m, actor).aggregate(spec);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql.sql(), sql.args().toArray())) {
            Map<String, Object> row = new LinkedHashMap<>();
            r.forEach((k, v) -> row.put(com.lsmom.meta.concept.MetaNames.toFieldCode(k), LedgerRows.value(v)));
            out.add(row);
        }
        return out;
    }

    /** 详情：主表 + 子表行 + 字典翻译 + 引用展开。越域读取返回无权限。 */
    public Map<String, Object> get(String concept, long id, ActorContext actor) {
        guard(actor, concept, "query", String.valueOf(id), null);
        LedgerModel m = master(concept);
        Map<String, Object> raw = loadVisible(m, id, actor);
        Predicate<String> readable = readable(m, actor);
        Map<String, Object> row = LedgerRows.toRow(m, raw, readable);
        List<Map<String, Object>> one = new ArrayList<>(List.of(row));
        decorate(m, one, actor, m.fields().values().stream().filter(f -> f.dataType() == DataType.REF).map(FieldDef::code).toList());
        for (LedgerModel c : children(concept)) {
            Predicate<String> cr = readable(c, actor);
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Map<String, Object> r : jdbc.queryForList("select * from \"" + c.table() + "\" where parent_id = ? order by line_no", id)) {
                lines.add(LedgerRows.toRow(c, r, cr));
            }
            decorate(c, lines, actor, List.of());
            row.put(LedgerModel.childKey(c.concept()), lines);
        }
        row.put("_allowedOperations", allowedOperations(concept, String.valueOf(row.get("status")), actor));
        return row;
    }

    private List<Map<String, Object>> allowedOperations(String concept, String status, ActorContext actor) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OperationDefinition d : operations.listByConcept(concept)) {
            if (d.kind() != OperationDefinition.Kind.TRANSITION) continue;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("operation", d.code());
            o.put("name", d.name());
            o.put("permitted", actor.hasPermission(d.permission()));
            out.add(o);
        }
        return out;
    }

    // —— 写入 ——

    public Map<String, Object> create(String concept, JsonNode body, ActorContext actor, String idempotencyKey) {
        String op = permission(concept, "add");
        guard(actor, concept, "add", null, body);
        LedgerModel m = master(concept);
        return write(op, concept, null, body, actor, idempotencyKey, () -> {
            Map<String, Object> values = validate(m, body, actor, true, "");
            Map<String, List<Map<String, Object>>> lines = validateChildren(m, body, actor, true);
            policy.check(concept, touched(values, lines));
            Long org = resolveOrg(body, actor);
            Integer formVersion = recordCheck(m, body, null, org, actor, values);
            long id = insertMaster(m, values, org, actor);
            insertChildren(m, lines, id);
            freezeForm(m, id, formVersion);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("summary", "新建" + m.concept().displayName());
            publish("crud.created", m, id, org, actor, idempotencyKey, payload);
            return result(op, id, 0, DRAFT);
        });
    }

    /** 部分更新：只修改报文中出现的字段；出现子表键时整体替换该子表的行。 */
    public Map<String, Object> update(String concept, long id, int version, JsonNode body, ActorContext actor, String idempotencyKey) {
        String op = permission(concept, "edit");
        guard(actor, concept, "edit", String.valueOf(id), body);
        LedgerModel m = master(concept);
        return write(op, concept, String.valueOf(id), body, actor, idempotencyKey, () -> {
            Map<String, Object> current = loadVisible(m, id, actor);
            requireDraft(m, current, "编辑");
            requireVersion(current, version);
            Map<String, Object> values = validate(m, body, actor, false, "");
            Map<String, List<Map<String, Object>>> lines = validateChildren(m, body, actor, false);
            policy.check(concept, touched(values, lines));
            Map<String, Object> before = LedgerRows.toRow(m, current, f -> true);
            Integer formVersion = recordCheck(m, body, current, (Long) current.get("org_id"), actor, values);
            List<Map<String, Object>> changes = diff(m, before, values);
            updateMaster(m, id, version, values, actor);
            freezeForm(m, id, formVersion);
            for (Map.Entry<String, List<Map<String, Object>>> e : lines.entrySet()) {
                LedgerModel c = childModel(m, e.getKey());
                Long oldCount = jdbc.queryForObject("select count(*) from \"" + c.table() + "\" where parent_id = ?", Long.class, id);
                jdbc.update("delete from \"" + c.table() + "\" where parent_id = ?", id);
                insertLines(c, e.getValue(), id);
                Map<String, Object> ch = new LinkedHashMap<>();
                ch.put("field", e.getKey());
                ch.put("label", c.concept().displayName());
                ch.put("from", oldCount + " 行");
                ch.put("to", e.getValue().size() + " 行");
                changes.add(ch);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("summary", "修改" + m.concept().displayName());
            payload.put("changes", changes);
            publish("crud.updated", m, id, (Long) current.get("org_id"), actor, idempotencyKey, payload);
            return result(op, id, version + 1, DRAFT);
        });
    }

    /** 复制为新草稿（含子表行）；唯一字段清空待填写。 */
    public Map<String, Object> copy(String concept, long id, ActorContext actor, String idempotencyKey) {
        String op = permission(concept, "add");
        guard(actor, concept, "add", String.valueOf(id), null);
        LedgerModel m = master(concept);
        return write(op + ":copy", concept, String.valueOf(id), null, actor, idempotencyKey, () -> {
            Map<String, Object> src = loadVisible(m, id, actor);
            Map<String, Object> values = new LinkedHashMap<>();
            Map<String, Object> row = LedgerRows.toRow(m, src, f -> true);
            for (FieldDef f : m.fields().values()) {
                if (f.flag("unique") || f.stateField()) continue;
                values.put(f.code(), toDb(f, row.get(f.code())));
            }
            long newId = insertMaster(m, values, (Long) src.get("org_id"), actor);
            for (LedgerModel c : children(concept)) {
                List<Map<String, Object>> lines = new ArrayList<>();
                for (Map<String, Object> r : jdbc.queryForList("select * from \"" + c.table() + "\" where parent_id = ? order by line_no", id)) {
                    Map<String, Object> line = new LinkedHashMap<>();
                    Map<String, Object> lr = LedgerRows.toRow(c, r, f -> true);
                    for (FieldDef f : c.fields().values()) line.put(f.code(), toDb(f, lr.get(f.code())));
                    lines.add(line);
                }
                insertLines(c, lines, newId);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("summary", "复制自 " + id);
            payload.put("copiedFrom", id);
            publish("crud.copied", m, newId, (Long) src.get("org_id"), actor, idempotencyKey, payload);
            return result(op, newId, 0, DRAFT);
        });
    }

    /** 逻辑删除（仅草稿）。 */
    public Map<String, Object> delete(String concept, long id, int version, ActorContext actor) {
        String op = permission(concept, "remove");
        guard(actor, concept, "remove", String.valueOf(id), null);
        LedgerModel m = master(concept);
        return write(op, concept, String.valueOf(id), null, actor, null, () -> {
            Map<String, Object> current = loadVisible(m, id, actor);
            requireDraft(m, current, "删除");
            requireVersion(current, version);
            int n = jdbc.update("update \"" + m.table() + "\" set del_flag = '2', version = version + 1, updated_by = ?, updated_at = ? "
                + "where id = ? and version = ?", actor.effectiveParty(), LocalDateTime.now(clock), id, version);
            if (n == 0) throw conflict(m, id);
            publish("crud.deleted", m, id, (Long) current.get("org_id"), actor, null, Map.of("summary", "删除" + m.concept().displayName()));
            return result(op, id, version + 1, DRAFT);
        });
    }

    /** 批量插入已校验的行（导入用）：每行一个新草稿，单一事务。 */
    public List<Long> insertValidated(String concept, List<Map<String, Object>> rows, ActorContext actor, String source) {
        LedgerModel m = master(concept);
        return tx.execute(s -> {
            List<Long> ids = new ArrayList<>();
            for (Map<String, Object> values : rows) {
                Long org = values.containsKey("orgId") ? ((Number) values.get("orgId")).longValue() : resolveOrg(null, actor);
                Map<String, Object> v = new LinkedHashMap<>(values);
                v.remove("orgId");
                long id = insertMaster(m, v, org, actor);
                publish("crud.created", m, id, org, actor, null, Map.of("summary", source));
                ids.add(id);
            }
            return ids;
        });
    }

    /** 导入预检：与通用新增相同的校验 + 组织数据域 + 写入门禁 + 唯一字段查重。返回转换后的值。 */
    public Map<String, Object> precheck(LedgerModel m, JsonNode node, ActorContext actor) {
        Map<String, Object> values = new LinkedHashMap<>(validate(m, node, actor, true, ""));
        resolveOrg(node, actor);
        policy.check(m.concept().code(), values.keySet());
        Map<String, String> dup = new LinkedHashMap<>();
        for (FieldDef f : m.fields().values()) {
            Object v = values.get(f.code());
            if (!f.flag("unique") || f.ext() || v == null) continue;
            Integer n = jdbc.queryForObject("select count(*) from \"" + m.table() + "\" where \"" + f.columnName() + "\" = ? and del_flag = '0'",
                Integer.class, v);
            if (n != null && n > 0) dup.put(f.code(), f.displayName() + "已存在");
        }
        if (!dup.isEmpty()) throw validationFailed(dup);
        return values;
    }

    /** Agent 拒绝与功能权限（供导入导出等入口复用）。 */
    public void checkAccess(ActorContext actor, String concept, String action) {
        guard(actor, concept, action, null, null);
    }

    // —— 管道 ——

    interface Body {
        Map<String, Object> run();
    }

    private Map<String, Object> write(String op, String concept, String targetId, JsonNode body, ActorContext actor, String key, Body work) {
        long started = System.nanoTime();
        String digest = digest(body);
        String summary = body == null ? "{}" : abbreviate(body.toString());
        try {
            Map<String, Object> r = tx.execute(s -> {
                if (key != null) {
                    Optional<OperationResult> prior = idempotency.lookup(key, op, digest);
                    if (prior.isPresent()) {
                        audit.write(new OperationAudit.Entry(op, actor, concept, prior.get().targetId(), prior.get().version(), "REPLAYED",
                            null, null, digest, summary, ms(started), key));
                        Map<String, Object> replay = new LinkedHashMap<>(result(op, Long.parseLong(prior.get().targetId()),
                            prior.get().version(), prior.get().status()));
                        replay.put("replayed", true);
                        return replay;
                    }
                }
                Map<String, Object> out = work.run();
                audit.write(new OperationAudit.Entry(op, actor, concept, String.valueOf(out.get("id")), (Integer) out.get("version"), "SUCCESS",
                    null, null, digest, summary, ms(started), key));
                if (key != null) {
                    idempotency.save(key, op, actor.actorParty(), digest, new OperationResult(op, String.valueOf(out.get("id")),
                        (Integer) out.get("version"), (String) out.get("status"), null, List.of(), List.of(), false, false));
                }
                return out;
            });
            return r;
        } catch (DuplicateKeyException e) {
            LsBizException ex = uniqueViolation(concept, e);
            audit.writeIndependently(new OperationAudit.Entry(op, actor, concept, targetId, null, "FAILED", ex.getCode(), ex.getMessage(),
                digest, summary, ms(started), key));
            throw ex;
        } catch (LsBizException e) {
            audit.writeIndependently(new OperationAudit.Entry(op, actor, concept, targetId, null, "FAILED", e.getCode(), e.getMessage(),
                digest, summary, ms(started), key));
            throw e;
        }
    }

    /** Agent 拒绝（并审计）与功能权限。 */
    void guard(ActorContext actor, String concept, String action, String targetId, Object body) {
        String op = permission(concept, action);
        if (packGate != null) {
            String root = Optional.ofNullable(concepts.find(concept).map(ConceptDef::parentConcept).orElse(null)).orElse(concept);
            packGate.disabledPackOf(root).ifPresent(p -> {
                throw new LsBizException("PACK_DISABLED", concept + " 所属 Pack " + p + " 已停用", Map.of("pack", p));
            });
        }
        if (actor.type() == ActorContext.ActorType.AGENT) {
            String msg = "Agent 不能调用通用台账接口，请使用包装后的 Operation";
            audit.writeIndependently(new OperationAudit.Entry(op, actor, concept, targetId, null, "FAILED", "AGENT_FORBIDDEN", msg,
                digest(body instanceof JsonNode j ? j : null), body == null ? "{}" : abbreviate(String.valueOf(body)), 0, null));
            throw new LsBizException("ACCESS_DENIED", msg, Map.of("reason", "AGENT_FORBIDDEN"));
        }
        if (!actor.hasPermission(op)) {
            throw new LsBizException("ACCESS_DENIED", "没有「" + action + "」" + concept + " 的权限", Map.of("permission", op));
        }
    }

    private LedgerModel master(String concept) {
        LedgerModel m = model(concept);
        if (m.child()) throw new LsBizException("CONCEPT_NOT_LEDGER", "子表只能随主表读写：" + concept);
        return m;
    }

    private LedgerModel childModel(LedgerModel master, String key) {
        return children(master.concept().code()).stream().filter(c -> LedgerModel.childKey(c.concept()).equals(key)).findFirst()
            .orElseThrow();
    }

    SafeSqlBuilder builder(LedgerModel m, ActorContext actor) {
        SafeSqlBuilder b = new SafeSqlBuilder(m, actor.dataScope(), readable(m, actor), ref -> {
            Optional<ConceptDef> target = concepts.find(ref.refConcept());
            if (target.isEmpty() || target.get().storage() != ConceptDef.Storage.MANAGED_TABLE) return null;
            if (!actor.hasPermission(permission(ref.refConcept(), "list"))) return null;
            return new SafeSqlBuilder.RefTarget(new LedgerModel(target.get()), actor.dataScope());
        });
        b.partyAccess(partyColumns(m));
        String st = draftPrivacy.get(m.concept().code());
        return st == null ? b : b.privateDraft(st, actor.effectiveParty());
    }

    static Predicate<String> readable(LedgerModel m, ActorContext actor) {
        return code -> {
            FieldDef f = m.field(code);
            return f == null || actor.hasPermission(f.textProp("readPermission"));
        };
    }

    private Map<String, Object> loadVisible(LedgerModel m, long id, ActorContext actor) {
        List<Object> args = new ArrayList<>();
        args.add(id);
        String sc = SafeSqlBuilder.scopeCondition("t", actor.dataScope(), args, partyColumns(m));
        String pv = privacySql(m, actor, args);
        List<Map<String, Object>> rows = jdbc.queryForList("select * from \"" + m.table() + "\" t where t.id = ? and t.del_flag = '0'"
            + (sc == null ? "" : " and " + sc) + (pv == null ? "" : " and " + pv), args.toArray());
        if (!rows.isEmpty()) return rows.get(0);
        if (pv != null) {
            List<Object> a2 = new ArrayList<>(List.of(id));
            String sc2 = SafeSqlBuilder.scopeCondition("t", actor.dataScope(), a2, partyColumns(m));
            Integer inScope = jdbc.queryForObject("select count(*) from \"" + m.table() + "\" t where t.id = ? and t.del_flag = '0'"
                + (sc2 == null ? "" : " and " + sc2), Integer.class, a2.toArray());
            // 别人的私有草稿按不存在处理，不暴露其存在
            if (inScope != null && inScope > 0) throw LsBizException.notFound(m.concept().displayName(), String.valueOf(id));
        }
        Integer exists = jdbc.queryForObject("select count(*) from \"" + m.table() + "\" where id = ? and del_flag = '0'", Integer.class, id);
        if (exists != null && exists > 0) {
            throw new LsBizException("ACCESS_DENIED", "记录不在你的数据范围内", Map.of("id", id));
        }
        throw LsBizException.notFound(m.concept().displayName(), String.valueOf(id));
    }

    private void requireDraft(LedgerModel m, Map<String, Object> row, String action) {
        if (!DRAFT.equals(row.get("status"))) {
            List<String> ops = operations.listByConcept(m.concept().code()).stream().filter(o -> o.kind() == OperationDefinition.Kind.TRANSITION)
                .map(OperationDefinition::code).toList();
            throw new LsBizException("GENERIC_EDIT_FORBIDDEN", "「" + row.get("status") + "」状态的记录不能通用" + action
                + "，请使用对应的业务动作" + (ops.isEmpty() ? "" : "：" + String.join("、", ops)),
                Map.of("status", String.valueOf(row.get("status")), "operations", ops));
        }
    }

    private static void requireVersion(Map<String, Object> row, int version) {
        int current = ((Number) row.get("version")).intValue();
        if (current != version) {
            throw new LsBizException("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试", Map.of("currentVersion", current));
        }
    }

    private LsBizException conflict(LedgerModel m, long id) {
        Integer v = jdbc.queryForObject("select version from \"" + m.table() + "\" where id = ?", Integer.class, id);
        return new LsBizException("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试", Map.of("currentVersion", v == null ? -1 : v));
    }

    private Long resolveOrg(JsonNode body, ActorContext actor) {
        Long org = body != null && body.hasNonNull("orgId") ? body.get("orgId").asLong() : null;
        if (org == null && actor.effectiveParty() != null) org = parties.primaryOrgUnitId(actor.effectiveParty()).orElse(null);
        if (org == null) throw LsBizException.validation(Map.of("orgId", "请选择所属组织"));
        if (!actor.dataScope().all() && !actor.dataScope().allowsOrg(org)) {
            throw new LsBizException("ACCESS_DENIED", "所属组织不在你的数据范围内", Map.of("orgId", org));
        }
        return org;
    }

    // —— 校验 ——

    /** 记录级校验：当前行（字段编码键，含子表行）与本次报文合并后交给各 RecordValidator；返回需冻结的表单版本。 */
    private Integer recordCheck(LedgerModel m, JsonNode body, Map<String, Object> currentRaw, Long org, ActorContext actor,
                                Map<String, Object> values) {
        if (recordValidators.isEmpty()) return null;
        Map<String, Object> current = null;
        if (currentRaw != null) {
            current = new LinkedHashMap<>(LedgerRows.toRow(m, currentRaw, f -> true));
            long id = ((Number) currentRaw.get("id")).longValue();
            for (LedgerModel c : children(m.concept().code())) {
                List<Map<String, Object>> lines = new ArrayList<>();
                for (Map<String, Object> r : jdbc.queryForList("select * from \"" + c.table() + "\" where parent_id = ? order by line_no", id)) {
                    lines.add(LedgerRows.toRow(c, r, f -> true));
                }
                current.put(LedgerModel.childKey(c.concept()), lines);
            }
        }
        Integer frozen = null;
        for (RecordValidator v : recordValidators) {
            RecordValidator.Outcome o = v.validate(new RecordValidator.Context(m.concept().code(), body, current, org, actor));
            if (o == null) continue;
            if (o.formVersion() != null) frozen = o.formVersion();
            o.overrides().forEach((code, node) -> {
                FieldDef f = m.field(code);
                if (f == null) return;
                String err = node == null || node.isNull() ? null : check(f, node, actor);
                if (err != null) throw validationFailed(Map.of(code, err));
                values.put(code, node == null || node.isNull() ? null : convert(f, node));
            });
        }
        return frozen;
    }

    private void freezeForm(LedgerModel m, long id, Integer formVersion) {
        if (formVersion != null) jdbc.update("update \"" + m.table() + "\" set schema_version = ? where id = ?", formVersion, id);
    }

    private static final Set<String> SYSTEM_INPUT = Set.of("id", "orgId", "status", "version", "createdBy", "createdAt", "updatedBy",
        "updatedAt", "lineNo", "parentId");

    /** 校验并转换输入；creating=true 时检查必填。字段错误汇总后一次抛出。 */
    Map<String, Object> validate(LedgerModel m, JsonNode body, ActorContext actor, boolean creating, String prefix) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        ObjectNode in = body != null && body.isObject() ? (ObjectNode) body : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        Set<String> childKeys = new java.util.HashSet<>();
        if (!m.child()) children(m.concept().code()).forEach(c -> childKeys.add(LedgerModel.childKey(c.concept())));
        Iterator<String> names = in.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (name.equals("status")) {
                errors.put(prefix + name, "状态只能经业务动作（Operation）修改");
                continue;
            }
            if (SYSTEM_INPUT.contains(name) || childKeys.contains(name) || name.startsWith("_")) continue;
            FieldDef f = m.field(name);
            if (f == null) {
                errors.put(prefix + name, "未知字段");
                continue;
            }
            if (!actor.hasPermission(f.textProp("writePermission")) || !actor.hasPermission(f.textProp("readPermission"))) {
                errors.put(prefix + name, "没有修改「" + f.displayName() + "」的权限");
                continue;
            }
            JsonNode v = in.get(name);
            if (v == null || v.isNull() || (v.isTextual() && v.asText().isEmpty() && f.dataType() != DataType.STRING && f.dataType() != DataType.TEXT)) {
                if (f.required()) errors.put(prefix + name, f.displayName() + "必填");
                else values.put(name, null);
                continue;
            }
            String err = check(f, v, actor);
            if (err != null) errors.put(prefix + name, err);
            else values.put(name, convert(f, v));
        }
        if (creating) {
            for (FieldDef f : m.fields().values()) {
                if (f.required() && !values.containsKey(f.code()) && !errors.containsKey(prefix + f.code())) {
                    errors.put(prefix + f.code(), f.displayName() + "必填");
                }
            }
        }
        if (!errors.isEmpty()) throw validationFailed(errors);
        return values;
    }

    private Map<String, List<Map<String, Object>>> validateChildren(LedgerModel m, JsonNode body, ActorContext actor, boolean creating) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (LedgerModel c : children(m.concept().code())) {
            String key = LedgerModel.childKey(c.concept());
            JsonNode arr = body == null ? null : body.get(key);
            if (arr == null || arr.isNull()) {
                if (creating) out.put(key, List.of());
                continue;
            }
            if (!arr.isArray()) {
                errors.put(key, c.concept().displayName() + "必须是数组");
                continue;
            }
            if (arr.size() > 1000) {
                errors.put(key, c.concept().displayName() + "最多 1000 行");
                continue;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                try {
                    rows.add(validate(c, arr.get(i), actor, true, key + "[" + i + "]."));
                } catch (LsBizException e) {
                    int line = i + 1;
                    e.fieldErrors().forEach((k, v) -> errors.put(k, "第 " + line + " 行：" + v));
                }
            }
            out.put(key, rows);
        }
        if (!errors.isEmpty()) throw validationFailed(errors);
        return out;
    }

    private static LsBizException validationFailed(Map<String, String> errors) {
        String first = errors.entrySet().iterator().next().getValue();
        return new LsBizException("VALIDATION_FAILED", "输入校验失败：" + first, Map.of("fields", errors));
    }

    private String check(FieldDef f, JsonNode v, ActorContext actor) {
        String label = f.displayName();
        switch (f.dataType()) {
            case STRING, TEXT -> {
                if (!v.isTextual() && !v.isNumber()) return label + "必须是文本";
                String s = v.asText();
                if (f.required() && s.isBlank()) return label + "必填";
                int max = f.dataType() == DataType.STRING ? Optional.ofNullable(f.intProp("maxLength")).orElse(255) : 100_000;
                if (s.codePointCount(0, s.length()) > max) return label + "最多 " + max + " 个字符";
                String pattern = f.textProp("pattern");
                if (pattern != null && !s.matches(pattern)) return label + "格式不正确";
            }
            case INTEGER -> {
                if (!(v.isIntegralNumber() || (v.isTextual() && v.asText().trim().matches("-?\\d{1,18}")))) return label + "必须是整数";
                String r = range(f, new BigDecimal(v.asText().trim()));
                if (r != null) return r;
            }
            case DECIMAL -> {
                if (!(v.isNumber() || (v.isTextual() && v.asText().trim().matches("-?\\d+(\\.\\d+)?")))) return label + "必须是数字";
                BigDecimal d = new BigDecimal(v.asText().trim());
                int scale = Optional.ofNullable(f.intProp("scale")).orElse(4);
                if (d.stripTrailingZeros().scale() > scale) return label + "最多 " + scale + " 位小数";
                String r = range(f, d);
                if (r != null) return r;
            }
            case BOOLEAN -> {
                if (!v.isBoolean()) return label + "必须是是/否";
            }
            case DATE -> {
                try {
                    LocalDate.parse(v.asText());
                } catch (RuntimeException e) {
                    return label + "必须是日期（yyyy-MM-dd）";
                }
            }
            case DATETIME -> {
                try {
                    LocalDateTime.parse(v.asText().replace(' ', 'T'));
                } catch (RuntimeException e) {
                    return label + "必须是时间（yyyy-MM-ddTHH:mm:ss）";
                }
            }
            case ENUM -> {
                if (f.options() != null && !f.options().contains(v.asText())) return label + "的取值不在可选范围内";
            }
            case REF -> {
                if (!(v.isIntegralNumber() || (v.isTextual() && v.asText().matches("\\d{1,18}")))) return label + "必须是对象编号";
                String r = refVisible(f, v.asLong(), actor);
                if (r != null) return r;
            }
            case JSON -> {
            }
            case FILE -> {
                if (!v.isArray() && !v.isTextual() && !v.isNumber()) return label + "必须是附件编号";
            }
        }
        String dict = f.textProp("dictType");
        if (dict != null && !dictValues(dict).containsKey(v.asText())) return label + "的取值不在字典「" + dict + "」中";
        return null;
    }

    private static String range(FieldDef f, BigDecimal d) {
        Object min = f.props().get("minimum");
        Object max = f.props().get("maximum");
        if (min instanceof Number n && d.compareTo(new BigDecimal(n.toString())) < 0) return f.displayName() + "不能小于 " + n;
        if (max instanceof Number n && d.compareTo(new BigDecimal(n.toString())) > 0) return f.displayName() + "不能大于 " + n;
        return null;
    }

    /** 引用的台账记录必须存在且在调用者数据域内（防止借引用越权）。 */
    private String refVisible(FieldDef f, long refId, ActorContext actor) {
        Optional<ConceptDef> target = concepts.find(f.refConcept());
        if (target.isEmpty() || target.get().storage() != ConceptDef.Storage.MANAGED_TABLE) return null;
        LedgerModel rm = new LedgerModel(target.get());
        List<Object> args = new ArrayList<>(List.of(refId));
        String sc = SafeSqlBuilder.scopeCondition("t", actor.dataScope(), args, partyColumns(rm));
        String pv = privacySql(rm, actor, args);
        Integer n = jdbc.queryForObject("select count(*) from \"" + rm.table() + "\" t where t.id = ? and t.del_flag = '0'"
            + (sc == null ? "" : " and " + sc) + (pv == null ? "" : " and " + pv), Integer.class, args.toArray());
        return n != null && n > 0 ? null : f.displayName() + "引用的记录不存在或不在你的数据范围内";
    }

    private static Object convert(FieldDef f, JsonNode v) {
        return switch (f.dataType()) {
            case INTEGER, REF -> Long.parseLong(v.asText().trim());
            case DECIMAL -> new BigDecimal(v.asText().trim());
            case BOOLEAN -> v.asBoolean();
            case DATE -> LocalDate.parse(v.asText());
            case DATETIME -> LocalDateTime.parse(v.asText().replace(' ', 'T'));
            case JSON, FILE -> v.toString();
            default -> v.asText();
        };
    }

    /** 读出的业务值 → 写库值（复制时使用）。 */
    private static Object toDb(FieldDef f, Object v) {
        if (v == null) return null;
        return switch (f.dataType()) {
            case DATE -> LocalDate.parse(v.toString());
            case DATETIME -> LocalDateTime.parse(v.toString());
            case JSON, FILE -> toJson(v);
            default -> v;
        };
    }

    private static Set<String> touched(Map<String, Object> values, Map<String, List<Map<String, Object>>> lines) {
        Set<String> t = new java.util.HashSet<>(values.keySet());
        t.addAll(lines.keySet());
        return t;
    }

    // —— SQL 写入 ——

    private long insertMaster(LedgerModel m, Map<String, Object> values, Long org, ActorContext actor) {
        List<String> cols = new ArrayList<>(List.of("org_id", "status", "created_by", "created_at", "version", "ext_attrs"));
        List<Object> args = new ArrayList<>(List.of(org, DRAFT, actor.effectiveParty() == null ? 0L : actor.effectiveParty(),
            LocalDateTime.now(clock), 0, extJson(m, values, Map.of())));
        values.forEach((code, v) -> {
            FieldDef f = m.field(code);
            if (f.ext()) return;
            cols.add(f.columnName());
            args.add(v);
        });
        return jdbc.queryForObject("insert into \"" + m.table() + "\" (" + quoteAll(cols) + ") values (" + marks(cols) + ") returning id",
            Long.class, args.toArray());
    }

    private void insertChildren(LedgerModel master, Map<String, List<Map<String, Object>>> lines, long parentId) {
        lines.forEach((key, rows) -> insertLines(childModel(master, key), rows, parentId));
    }

    private void insertLines(LedgerModel c, List<Map<String, Object>> rows, long parentId) {
        int no = 1;
        for (Map<String, Object> values : rows) {
            List<String> cols = new ArrayList<>(List.of("parent_id", "line_no", "ext_attrs"));
            List<Object> args = new ArrayList<>(List.of(parentId, no++, extJson(c, values, Map.of())));
            values.forEach((code, v) -> {
                FieldDef f = c.field(code);
                if (f.ext()) return;
                cols.add(f.columnName());
                args.add(v);
            });
            jdbc.update("insert into \"" + c.table() + "\" (" + quoteAll(cols) + ") values (" + marks(cols) + ")", args.toArray());
        }
    }

    private void updateMaster(LedgerModel m, long id, int version, Map<String, Object> values, ActorContext actor) {
        List<String> sets = new ArrayList<>(List.of("\"version\" = \"version\" + 1", "\"updated_by\" = ?", "\"updated_at\" = ?"));
        List<Object> args = new ArrayList<>(List.of(actor.effectiveParty() == null ? 0L : actor.effectiveParty(), LocalDateTime.now(clock)));
        Map<String, Object> ext = new LinkedHashMap<>();
        values.forEach((code, v) -> {
            FieldDef f = m.field(code);
            if (f.ext()) {
                ext.put(code, v);
                return;
            }
            sets.add("\"" + f.columnName() + "\" = ?");
            args.add(v);
        });
        if (!ext.isEmpty()) {
            sets.add("\"ext_attrs\" = \"ext_attrs\" || ?::jsonb");
            args.add(toJson(ext));
        }
        args.add(id);
        args.add(version);
        int n = jdbc.update("update \"" + m.table() + "\" set " + String.join(", ", sets) + " where id = ? and version = ? and del_flag = '0'",
            args.toArray());
        if (n == 0) throw conflict(m, id);
    }

    private static String extJson(LedgerModel m, Map<String, Object> values, Map<String, Object> base) {
        Map<String, Object> ext = new LinkedHashMap<>(base);
        values.forEach((code, v) -> {
            if (m.field(code).ext()) ext.put(code, v);
        });
        return toJson(ext);
    }

    private static String quoteAll(List<String> cols) {
        return String.join(", ", cols.stream().map(c -> "\"" + com.lsmom.meta.concept.MetaNames.requireIdentifier(c) + "\"").toList());
    }

    private static String marks(List<String> cols) {
        List<String> m = new ArrayList<>();
        for (String c : cols) m.add(c.equals("ext_attrs") ? "?::jsonb" : "?");
        return String.join(", ", m);
    }

    // —— 输出装饰：字典翻译、引用展开 ——

    private void decorate(LedgerModel m, List<Map<String, Object>> rows, ActorContext actor, List<String> expand) {
        for (FieldDef f : m.fields().values()) {
            String dict = f.textProp("dictType");
            if (dict != null) {
                Map<String, String> labels = dictValues(dict);
                for (Map<String, Object> r : rows) if (r.get(f.code()) != null) r.put(f.code() + "Label", labels.get(String.valueOf(r.get(f.code()))));
            }
            if (f.dataType() == DataType.ENUM && f.options() != null) {
                Object labels = f.props().get("optionLabels");
                if (labels instanceof Map<?, ?> lm) {
                    for (Map<String, Object> r : rows) if (r.get(f.code()) != null) r.put(f.code() + "Label", lm.get(String.valueOf(r.get(f.code()))));
                }
            }
        }
        for (String e : expand) {
            FieldDef f = m.field(e);
            if (f == null || f.dataType() != DataType.REF || !readable(m, actor).test(e)) {
                throw new LsBizException("QUERY_REJECTED", "不能展开字段：" + e, Map.of("fields", Map.of("expand", "不能展开字段：" + e)));
            }
            for (Map<String, Object> r : rows) {
                Object id = r.get(e);
                if (id != null) r.put(e + "Ref", refLabel(f, ((Number) id).longValue(), actor));
            }
        }
    }

    private Map<String, Object> refLabel(FieldDef f, long id, ActorContext actor) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", id);
        String concept = f.refConcept();
        switch (concept) {
            case "ls.core.Party" -> ref.put("label", single("select name from core_party where id = ?", id));
            case "ls.core.OrgUnit" -> ref.put("label", single("select name from core_org_unit where id = ?", id));
            case "ls.core.Work" -> ref.put("label", single("select code || ' ' || title from core_work_item where id = ?", id));
            default -> {
                Optional<ConceptDef> target = concepts.find(concept);
                if (target.isEmpty() || target.get().storage() != ConceptDef.Storage.MANAGED_TABLE) {
                    ref.put("label", String.valueOf(id));
                    break;
                }
                if (!actor.hasPermission(permission(concept, "list"))) {
                    ref.put("label", null);
                    ref.put("restricted", true);
                    break;
                }
                LedgerModel rm = new LedgerModel(target.get());
                FieldDef labelField = rm.fields().values().stream().filter(x -> x.flag("label")).findFirst()
                    .orElse(rm.fields().values().stream().filter(x -> x.dataType() == DataType.STRING && !x.ext()).findFirst().orElse(null));
                List<Object> args = new ArrayList<>(List.of(id));
                String sc = SafeSqlBuilder.scopeCondition("t", actor.dataScope(), args, partyColumns(rm));
                String pv = privacySql(rm, actor, args);
                List<Map<String, Object>> rows = jdbc.queryForList("select * from \"" + rm.table() + "\" t where t.id = ? and t.del_flag = '0'"
                    + (sc == null ? "" : " and " + sc) + (pv == null ? "" : " and " + pv), args.toArray());
                if (rows.isEmpty()) {
                    ref.put("label", null);
                    ref.put("restricted", true);
                } else {
                    ref.put("label", labelField == null ? String.valueOf(id) : rows.get(0).get(labelField.columnName()));
                }
            }
        }
        return ref;
    }

    private String single(String sql, long id) {
        return jdbc.queryForList(sql, String.class, id).stream().findFirst().orElse(null);
    }

    private Map<String, String> dictValues(String dictType) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("select dict_value, dict_label from sys_dict_data where dict_type = ? order by dict_sort", rs -> {
            out.put(rs.getString(1), rs.getString(2));
        }, dictType);
        return out;
    }

    // —— 时间轴与结果 ——

    private List<Map<String, Object>> diff(LedgerModel m, Map<String, Object> before, Map<String, Object> values) {
        List<Map<String, Object>> changes = new ArrayList<>();
        values.forEach((code, v) -> {
            Object old = before.get(code);
            Object now = v instanceof java.time.temporal.TemporalAccessor ? v.toString() : v;
            if (!same(old, now)) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("field", code);
                c.put("label", m.field(code).displayName());
                c.put("from", old);
                c.put("to", now);
                changes.add(c);
            }
        });
        return changes;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        return Objects.equals(a == null ? null : a.toString(), b == null ? null : b.toString());
    }

    private void publish(String type, LedgerModel m, long id, Long org, ActorContext actor, String key, Map<String, Object> payload) {
        events.publish(new EventPublisher.NewEvent(type, m.concept().code(), String.valueOf(id), org, actor.actorParty(), actor.onBehalfOf(),
            key, payload));
    }

    private static Map<String, Object> result(String op, long id, int version, String status) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("version", version);
        r.put("status", status);
        r.put("replayed", false);
        return r;
    }

    private LsBizException uniqueViolation(String concept, DuplicateKeyException e) {
        LedgerModel m = model(concept);
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        for (FieldDef f : m.fields().values()) {
            if (f.flag("unique") && msg.contains(f.columnName())) {
                return LsBizException.validation(Map.of(f.code(), f.displayName() + "已存在"));
            }
        }
        return new LsBizException("DUPLICATE", "记录重复");
    }

    private static String toJson(Object o) {
        try {
            return LedgerRows.JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String digest(JsonNode body) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((body == null ? "" : body.toString()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    private static long ms(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
