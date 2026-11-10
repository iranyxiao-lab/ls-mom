package com.lsmom.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationHandler;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.api.OperationResult;
import com.lsmom.action.core.CoreEffects;
import com.lsmom.action.core.CoreGuards;
import com.lsmom.action.core.CoreOperations;
import com.lsmom.action.core.CoreStateMachines;
import com.lsmom.action.core.IssueStateStore;
import com.lsmom.action.core.WorkStateStore;
import com.lsmom.action.gateway.IdempotencyStore;
import com.lsmom.action.gateway.InputSchemaValidator;
import com.lsmom.action.gateway.OperationAudit;
import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.action.guard.Guard;
import com.lsmom.action.guard.GuardRegistry;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.action.state.StateMachineRegistry;
import com.lsmom.action.state.StateStores;
import com.lsmom.action.state.TransitionEffect;
import com.lsmom.core.document.AccessAuditService;
import com.lsmom.core.document.DocumentService;
import com.lsmom.core.event.EventPublisher;
import com.lsmom.core.event.OutboxDestination;
import com.lsmom.core.event.OutboxWorker;
import com.lsmom.core.issue.IssueService;
import com.lsmom.core.org.DataScope;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.org.OrgUnit;
import com.lsmom.core.party.Party;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.CoreConceptRegistrar;
import com.lsmom.meta.concept.FieldUsageIndex;
import com.lsmom.test.CoreFixture;
import com.lsmom.test.PgTestSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 手工装配的 Operation Gateway（不启动 Spring），与生产装配使用同一批类。 */
public class ActionFixture {

    public static final ObjectMapper JSON = new ObjectMapper();

    public final CoreFixture core = new CoreFixture();
    public final DataSourceTransactionManager txm = new DataSourceTransactionManager(PgTestSupport.dataSource());
    public final IssueService issues = new IssueService(core.jdbc, core.orgUnits, core.profiles, core.works, core.clock);
    public final DocumentService docs = new DocumentService(core.jdbc, core.parties, new AccessAuditService(core.jdbc),
        id -> new ByteArrayInputStream(new byte[0]));
    public final ConceptRegistry concepts = new ConceptRegistry(core.jdbc, new FieldUsageIndex(core.jdbc));
    public final OperationRegistry operations = new OperationRegistry(core.jdbc);
    public final StateMachineRegistry machines = new StateMachineRegistry(core.jdbc, operations);
    public final List<OutboxDestination> destinations;
    public final EventPublisher events;
    public final OutboxWorker outbox;
    public final OperationGateway gateway;
    public final com.lsmom.action.rule.RuleService rules;
    public final com.lsmom.action.rule.RulePublisher rulePublisher;
    public final GuardRegistry guardRegistry;
    public final StateStores stores;

    public ActionFixture() {
        this(List.of());
    }

    public ActionFixture(List<OutboxDestination> destinations) {
        this(destinations, List.of());
    }

    public ActionFixture(List<OutboxDestination> destinations, List<com.lsmom.action.rule.RuleFactProvider> facts) {
        this.destinations = destinations;
        events = new EventPublisher(core.jdbc, destinations, core.clock);
        outbox = new OutboxWorker(core.jdbc, destinations, txm, core.clock, Duration.ZERO);
        new CoreConceptRegistrar(concepts, core.jdbc).registerAll();

        CoreGuards g = new CoreGuards();
        List<Guard> guards = List.of(g.issueEvidenceRequired(issues, docs), g.issueRectificationDone(issues), g.issueVerifierSeparation(issues),
            new com.lsmom.action.guard.DualSignGuard());
        CoreEffects e = new CoreEffects();
        List<TransitionEffect> effects = List.of(e.workAssignEffect(core.assignments), e.workStartEffect(core.clock), e.workBlockEffect(),
            e.workUnblockEffect(), e.workConfirmEffect(core.clock), e.workReasonEffects(), e.workCancelEffect(core.assignments),
            e.issueStartRectificationEffect(), e.issueVerifyPassEffect(core.clock), e.issueVerifyFailEffect(issues), e.issueRejectEffect());
        stores = new StateStores(List.of(new WorkStateStore(core.works), new IssueStateStore(issues)));
        rules = new com.lsmom.action.rule.RuleService(core.jdbc, new com.lsmom.action.rule.CelRuleEngine(), core.clock);
        var ruleGuards = new com.lsmom.action.rule.RuleGuardProvider(rules, stores, facts, core.clock);
        guardRegistry = new GuardRegistry(guards, List.of(ruleGuards), List.of(ruleGuards));
        rulePublisher = new com.lsmom.action.rule.RulePublisher(rules, guardRegistry, ruleGuards);
        gateway = new OperationGateway(operations, machines, stores,
            guardRegistry, new InputSchemaValidator(), List.of(), effects,
            new IdempotencyStore(core.jdbc, core.clock), new OperationAudit(core.jdbc, txm), events, txm);

        machines.publish(CoreStateMachines.work());
        machines.publish(CoreStateMachines.issue());
        operations.reconcile(handlers());
        new com.lsmom.action.rule.CoreRules(rules, rulePublisher).installAll();
    }

    /** 其他模块的测试追加的代码 Operation 处理器（如 Pack 自带的 Operation）；共享测试库中每个夹具对账时都要带上。 */
    public static volatile java.util.function.Function<ActionFixture, List<OperationHandler>> extraHandlers = f -> List.of();

    public List<OperationHandler> handlers() {
        List<OperationHandler> list = new ArrayList<>(List.of(new CoreOperations.CreateWork(core.works), new CoreOperations.SplitWork(core.relations),
            new CoreOperations.LinkWork(core.relations), new CoreOperations.SaveReport(core.reports), new CoreOperations.SubmitReport(core.reports),
            new CoreOperations.RaiseIssue(issues), new CoreOperations.CreateRectification(issues), new CoreOperations.AttachDocument(docs), new com.lsmom.action.core.ReprioritizeWork(core.works)));
        list.addAll(TestOperations.all(core));
        list.addAll(extraHandlers.apply(this));
        return list;
    }

    // —— 调用主体 ——

    public static ActorContext admin() {
        return new ActorContext(ActorContext.ActorType.HUMAN, 1L, null, null, true, Set.of(), DataScope.unrestricted(), "admin");
    }

    public ActorContext human(Party p, DataScopeLevel level, String... perms) {
        return new ActorContext(ActorContext.ActorType.HUMAN, p.userId(), p.id(), null, false, Set.of(perms),
            core.dataScopes.resolve(p.id(), level), p.name());
    }

    public ActorContext superUser(Party p) {
        return new ActorContext(ActorContext.ActorType.HUMAN, p.userId(), p.id(), null, true, Set.of(), DataScope.unrestricted(), p.name());
    }

    // —— 调用便捷方法 ——

    public static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public OperationResult op(String code, ActorContext actor, String input) {
        return gateway.execute(code, OperationRequest.of(json(input)), actor);
    }

    public OperationResult on(String code, ActorContext actor, String targetId, int version, String input) {
        return gateway.execute(code, OperationRequest.on(targetId, version, json(input)), actor);
    }

    /** 对目标执行迁移，自动取当前版本。 */
    public OperationResult step(String code, ActorContext actor, String concept, String targetId, String input) {
        int v = concept.equals("ls.core.Work") ? core.works.get(Long.parseLong(targetId)).version() : issues.get(Long.parseLong(targetId)).version();
        return on(code, actor, targetId, v, input);
    }

    public String newWork(ActorContext actor, OrgUnit org, String title) {
        return op("ls.core.Work.create", actor, "{\"workType\":\"PRODUCTION\",\"title\":\"" + title + "\",\"orgUnitId\":" + org.id() + "}").targetId();
    }

    public int count(String sql, Object... args) {
        Integer n = core.jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
