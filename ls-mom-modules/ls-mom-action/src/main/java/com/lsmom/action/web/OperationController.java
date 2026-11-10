package com.lsmom.action.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.api.OperationResult;
import com.lsmom.action.registry.OperationDefinition;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.action.state.StateMachineDefinition;
import com.lsmom.action.state.StateMachineRegistry;
import com.lsmom.action.state.StateSnapshot;
import com.lsmom.action.state.StateStores;
import com.lsmom.action.timeline.TimelineService;
import com.lsmom.core.error.LsBizException;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 命名 Operation 统一入口。所有受控写入只能经 {@code POST /api/op/{operationCode}}。
 */
@RestController
@RequestMapping("/api")
public class OperationController {

    private final OperationGateway gateway;
    private final OperationRegistry registry;
    private final StateMachineRegistry machines;
    private final StateStores stores;
    private final TimelineService timeline;
    private final ActorResolver actors;

    public OperationController(OperationGateway gateway, OperationRegistry registry, StateMachineRegistry machines, StateStores stores,
                               TimelineService timeline, ActorResolver actors) {
        this.gateway = gateway;
        this.registry = registry;
        this.machines = machines;
        this.stores = stores;
        this.timeline = timeline;
        this.actors = actors;
    }

    /** 请求体：input 为业务输入；修改已有对象时带 targetId 与 version。 */
    public record OperationCall(JsonNode input, String targetId, Integer version, String idempotencyKey, Boolean simulate) {
    }

    @PostMapping("/op/{code:.+}")
    public R<OperationResult> execute(@PathVariable String code, @RequestBody(required = false) OperationCall call,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idemHeader) {
        OperationCall c = call == null ? new OperationCall(null, null, null, null, false) : call;
        String key = c.idempotencyKey() != null ? c.idempotencyKey() : idemHeader;
        OperationResult r = gateway.execute(code, new OperationRequest(c.input(), c.targetId(), c.version(), key,
            Boolean.TRUE.equals(c.simulate())), actors.current());
        return R.ok(r.warnings().isEmpty() ? "操作成功" : String.join("；", r.warnings()), r);
    }

    public record ActionView(String operation, String name, String risk, boolean permitted, String toStatus, JsonNode inputSchema) {
    }

    public record AllowedActions(String concept, String id, String status, int version, List<ActionView> actions) {
    }

    /** 对象当前可执行的动作（界面据此只显示可用按钮）。 */
    @GetMapping("/op/allowed")
    public R<AllowedActions> allowed(@RequestParam String concept, @RequestParam String id) {
        ActorContext actor = actors.current();
        StateSnapshot s = stores.of(concept).load(id);
        if (!actor.dataScope().all() && !actor.dataScope().allowsOrg(s.orgUnitId())) {
            throw new LsBizException("ACCESS_DENIED", "目标对象不在你的数据范围内");
        }
        StateMachineDefinition sm = machines.forConcept(concept);
        List<ActionView> transitions = sm.allowedFrom(s.status()).stream().map(t -> {
            OperationDefinition d = registry.require(t.operation());
            return new ActionView(t.operation(), t.name(), d.risk().name(), actor.hasPermission(d.permission()), t.to(), d.inputSchema());
        }).toList();
        List<ActionView> handlers = registry.listByConcept(concept).stream()
            .filter(d -> d.kind() == OperationDefinition.Kind.HANDLER && d.requiresVersion())
            .map(d -> new ActionView(d.code(), d.name(), d.risk().name(), actor.hasPermission(d.permission()), null, d.inputSchema()))
            .toList();
        List<ActionView> all = new java.util.ArrayList<>(transitions);
        all.addAll(handlers);
        return R.ok(new AllowedActions(concept, id, s.status(), s.version(), all));
    }

    @GetMapping("/op/catalog")
    public R<List<Map<String, Object>>> catalog() {
        ActorContext actor = actors.current();
        return R.ok(registry.listPublished().stream().filter(d -> actor.hasPermission(d.permission()))
            .map(d -> Map.<String, Object>of("code", d.code(), "name", d.name(), "concept", d.concept(), "kind", d.kind().name(),
                "risk", d.risk().name(), "exposure", d.exposure().name()))
            .toList());
    }

    @GetMapping("/timeline/{concept}/{id}")
    public R<List<TimelineService.Entry>> timeline(@PathVariable String concept, @PathVariable String id) {
        ActorContext actor = actors.current();
        if (stores.has(concept)) {
            StateSnapshot s = stores.of(concept).load(id);
            if (!actor.dataScope().all() && !actor.dataScope().allowsOrg(s.orgUnitId())) {
                throw new LsBizException("ACCESS_DENIED", "目标对象不在你的数据范围内");
            }
        }
        return R.ok(timeline.of(concept, id, actor));
    }
}
