package com.lsmom.action.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.IdempotencyPolicy;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.api.OperationHandler;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.api.OperationResult;
import com.lsmom.action.guard.GuardRegistry;
import com.lsmom.action.registry.OperationDefinition;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.action.state.StateMachineDefinition;
import com.lsmom.action.state.StateMachineRegistry;
import com.lsmom.action.state.StateSnapshot;
import com.lsmom.action.state.StateStore;
import com.lsmom.action.state.StateStores;
import com.lsmom.action.state.TransitionEffect;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.event.DomainEvent;
import com.lsmom.core.event.EventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Operation 统一执行入口。在一个事务内依次：功能权限 → 数据域 → 输入 Schema → 字段策略 → 守卫 → 版本校验 →
 * 状态机迁移或处理器 → 事件与 Outbox → 审计与幂等记录。任一步失败整体回滚，失败审计单独提交。
 */
@Service
public class OperationGateway {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|secret|token|credential|apikey|api_key).*");

    private final OperationRegistry registry;
    private final StateMachineRegistry machines;
    private final StateStores stores;
    private final GuardRegistry guards;
    private final InputSchemaValidator schemas;
    private final List<FieldPolicyCheck> fieldPolicies;
    private final Map<String, TransitionEffect> effects = new HashMap<>();
    private final IdempotencyStore idempotency;
    private final OperationAudit audit;
    private final EventPublisher events;
    private final TransactionTemplate tx;

    public OperationGateway(OperationRegistry registry, StateMachineRegistry machines, StateStores stores, GuardRegistry guards,
                            InputSchemaValidator schemas, List<FieldPolicyCheck> fieldPolicies, List<TransitionEffect> effects,
                            IdempotencyStore idempotency, OperationAudit audit, EventPublisher events, PlatformTransactionManager txm) {
        this.registry = registry;
        this.machines = machines;
        this.stores = stores;
        this.guards = guards;
        this.schemas = schemas;
        this.fieldPolicies = fieldPolicies;
        effects.forEach(e -> this.effects.put(e.operation(), e));
        this.idempotency = idempotency;
        this.audit = audit;
        this.events = events;
        this.tx = new TransactionTemplate(txm);
    }

    public OperationResult execute(String code, OperationRequest request, ActorContext actor) {
        long started = System.nanoTime();
        JsonNode input = request.input() == null ? JsonNodeFactory.instance.objectNode() : request.input();
        OperationRequest req = new OperationRequest(input, request.targetId(), request.version(), blankToNull(request.idempotencyKey()),
            request.simulate());
        String digest = sha256(canonical(input));
        String summary = mask(input).toString();
        OperationDefinition op;
        try {
            op = registry.require(code);
        } catch (LsBizException e) {
            audit.writeIndependently(new OperationAudit.Entry(code, actor, null, req.targetId(), null, "FAILED", e.getCode(),
                e.getMessage(), digest, summary, elapsed(started), req.idempotencyKey()));
            throw e;
        }
        final OperationDefinition def = op;
        String[] auditTarget = {req.targetId()};
        try {
            OperationResult result = tx.execute(status -> {
                if (req.idempotencyKey() != null && !req.simulate()) {
                    Optional<OperationResult> prior = idempotency.lookup(req.idempotencyKey(), def.code(), digest);
                    if (prior.isPresent()) {
                        audit.write(new OperationAudit.Entry(def.code(), actor, def.concept(), prior.get().targetId(), prior.get().version(),
                            "REPLAYED", null, null, digest, summary, elapsed(started), req.idempotencyKey()));
                        return prior.get().asReplay();
                    }
                } else if (def.idempotency() == IdempotencyPolicy.REQUIRED && !req.simulate()) {
                    throw LsBizException.validation(Map.of("idempotencyKey", "该操作必须携带幂等键"));
                }
                OperationResult r = run(def, req, actor);
                auditTarget[0] = r.targetId();
                if (req.simulate()) {
                    status.setRollbackOnly();
                    return r;
                }
                audit.write(new OperationAudit.Entry(def.code(), actor, def.concept(), r.targetId(), r.version(), "SUCCESS", null, null,
                    digest, summary, elapsed(started), req.idempotencyKey()));
                if (req.idempotencyKey() != null) {
                    idempotency.save(req.idempotencyKey(), def.code(), actor.actorParty(), digest, r);
                }
                return r;
            });
            if (req.simulate()) {
                audit.writeIndependently(new OperationAudit.Entry(def.code(), actor, def.concept(), auditTarget[0], null, "SIMULATED",
                    null, null, digest, summary, elapsed(started), null));
            }
            return result;
        } catch (LsBizException e) {
            audit.writeIndependently(new OperationAudit.Entry(def.code(), actor, def.concept(), auditTarget[0], null, "FAILED",
                e.getCode(), e.getMessage(), digest, summary, elapsed(started), req.idempotencyKey()));
            throw e;
        } catch (RuntimeException e) {
            audit.writeIndependently(new OperationAudit.Entry(def.code(), actor, def.concept(), auditTarget[0], null, "FAILED",
                "INTERNAL_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage(), digest, summary, elapsed(started), req.idempotencyKey()));
            throw e;
        }
    }

    private OperationResult run(OperationDefinition op, OperationRequest req, ActorContext actor) {
        if (!actor.hasPermission(op.permission())) {
            throw new LsBizException("ACCESS_DENIED", "没有执行「" + op.name() + "」的权限", Map.of("permission", op.permission()));
        }
        OperationContext ctx = new OperationContext(op, actor, req);
        StateSnapshot before = null;
        StateMachineDefinition.Transition transition = null;
        if (op.kind() == OperationDefinition.Kind.TRANSITION) {
            transition = machines.forConcept(op.concept()).transition(op.code());
            if (transition == null) throw new LsBizException("OPERATION_NOT_FOUND", "状态机中没有该迁移：" + op.code());
        }
        if (req.targetId() != null && stores.has(op.concept())) {
            before = stores.of(op.concept()).load(req.targetId());
            ctx.target(before.id(), before.version(), before.status(), before.orgUnitId());
            if (!actor.dataScope().all() && !actor.dataScope().allowsOrg(before.orgUnitId())
                && !(actor.dataScope().partyId() != null && actor.dataScope().partyId().equals(before.ownerParty()))) {
                throw new LsBizException("ACCESS_DENIED", "目标对象不在你的数据范围内", Map.of("targetId", req.targetId()));
            }
        } else if (transition != null) {
            throw new LsBizException("TARGET_REQUIRED", "该操作需要指定目标对象");
        }
        schemas.validate(op.inputSchema(), req.input());
        for (FieldPolicyCheck p : fieldPolicies) p.check(ctx);

        List<String> guardCodes = new ArrayList<>(op.guards());
        if (transition != null) {
            if (!transition.from().contains(before.status())) {
                StateMachineDefinition sm = machines.forConcept(op.concept());
                List<String> allowed = sm.allowedFrom(before.status()).stream().map(StateMachineDefinition.Transition::operation).toList();
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("currentStatus", before.status());
                details.put("allowedOperations", allowed);
                details.put("allowedActions", sm.allowedFrom(before.status()).stream()
                    .map(t -> Map.of("operation", t.operation(), "name", t.name())).toList());
                throw new LsBizException("INVALID_TRANSITION", "当前状态「" + before.status() + "」不能执行「" + op.name() + "」", details);
            }
            if (transition.guards() != null) {
                transition.guards().stream().filter(g -> !guardCodes.contains(g)).forEach(guardCodes::add);
            }
        }
        if (before != null && (op.requiresVersion() || transition != null) && actor.type() != ActorContext.ActorType.SYSTEM) {
            if (req.version() == null) throw LsBizException.validation(Map.of("version", "version 必填（对象当前版本）"));
            if (req.version() != before.version()) {
                throw new LsBizException("VERSION_CONFLICT", "对象已被他人修改，请刷新后重试",
                    Map.of("currentVersion", before.version(), "currentStatus", before.status()));
            }
        }
        guards.attachedFor(op.code()).stream().filter(g -> !guardCodes.contains(g)).forEach(guardCodes::add);
        guards.runAll(guardCodes, ctx);

        Object data;
        if (transition != null) {
            TransitionEffect effect = effects.get(op.code());
            Map<String, Object> changes = effect == null ? Map.of() : effect.apply(ctx, before);
            StateStore store = stores.of(op.concept());
            StateSnapshot after = store.write(before.id(), before.version(), before.status(), transition.to(), changes);
            ctx.target(after.id(), after.version(), after.status(), after.orgUnitId());
            ctx.eventPayload().put("from", before.status());
            ctx.eventPayload().put("to", after.status());
            data = Map.of("status", after.status(), "version", after.version());
        } else {
            OperationHandler handler = registry.handler(op.code())
                .orElseThrow(() -> new LsBizException("OPERATION_NOT_FOUND", "Operation 没有处理器：" + op.code()));
            data = handler.handle(ctx);
        }

        String key = req.idempotencyKey() != null ? req.idempotencyKey() : "op:" + op.code() + ":" + UUID.randomUUID();
        List<String> eventIds = new ArrayList<>();
        if (ctx.targetId() != null) {
            DomainEvent main = events.publish(new EventPublisher.NewEvent(op.code(), op.concept(), ctx.targetId(), ctx.targetOrgUnitId(),
                actor.actorParty(), actor.onBehalfOf(), key, withInput(ctx.eventPayload(), req.input())));
            eventIds.add(main.eventId().toString());
        }
        int n = 0;
        for (OperationContext.PendingEvent pe : ctx.extraEvents()) {
            DomainEvent e = events.publish(new EventPublisher.NewEvent(pe.type(), pe.subjectConcept(), pe.subjectId(), pe.orgUnitId(),
                actor.actorParty(), actor.onBehalfOf(), key + "#" + (++n), pe.payload()));
            eventIds.add(e.eventId().toString());
        }
        return new OperationResult(op.code(), ctx.targetId(), ctx.targetVersion(), ctx.targetStatus(), data,
            List.copyOf(ctx.warnings()), eventIds, false, req.simulate());
    }

    public void registerEffect(TransitionEffect effect) {
        effects.put(effect.operation(), effect);
    }

    private static Map<String, Object> withInput(Map<String, Object> payload, JsonNode input) {
        Map<String, Object> p = new LinkedHashMap<>(payload);
        if (input != null && input.size() > 0 && !p.containsKey("input")) {
            p.put("input", JSON.convertValue(mask(input), Map.class));
        }
        return p;
    }

    static JsonNode mask(JsonNode node) {
        if (node == null || !node.isObject()) return node;
        ObjectNode copy = node.deepCopy();
        Iterator<String> it = copy.fieldNames();
        List<String> names = new ArrayList<>();
        it.forEachRemaining(names::add);
        for (String f : names) {
            if (SECRET_KEY.matcher(f).matches()) copy.put(f, "***");
            else if (copy.get(f).isObject()) copy.set(f, mask(copy.get(f)));
        }
        return copy;
    }

    static String canonical(JsonNode node) {
        try {
            return JSON.copy().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsString(JSON.convertValue(node, Object.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
