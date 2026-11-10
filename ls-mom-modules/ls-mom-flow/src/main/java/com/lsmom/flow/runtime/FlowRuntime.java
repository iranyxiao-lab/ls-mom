package com.lsmom.flow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.api.OperationListener;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.api.OperationResult;
import com.lsmom.action.gateway.OperationAudit;
import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.action.rule.CelRuleEngine;
import com.lsmom.action.state.StateStores;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.event.EventPublisher;
import com.lsmom.core.org.LeaderKind;
import com.lsmom.core.org.LeaderResolution;
import com.lsmom.core.org.LeaderResolver;
import com.lsmom.flow.bpmn.BpmnTemplateGenerator;
import com.lsmom.flow.engine.FlowHooks;
import com.lsmom.flow.model.FlowDefinition;
import com.lsmom.form.FormRegistry;
import com.lsmom.form.NodePolicyResolver;
import com.lsmom.form.ViewType;
import com.lsmom.meta.concept.ConceptRegistry;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 流程协同运行时。原则：业务状态只在业务表，流程只通过命名 Operation 推进业务；流程变量只放路由所需信息
 * （bizType、bizId、initiator、flowInstanceId、outcome、各节点候选人与分支取值）。
 *
 * <ul>
 *   <li>发起：业务"提交"Operation 成功后（{@link OperationListener}）登记实例（PENDING_START），事务提交后启动；失败记 PENDING_RETRY 重试。</li>
 *   <li>办理：校验调用者是当前待办的办理人/候选人（双重校验之一），节点 Operation 在节点上下文中执行（字段策略服务端强制）。</li>
 *   <li>结果：结束事件登记 onApproved/onRejected 回调（幂等键 procInstId:taskId:event），事务提交后执行。</li>
 * </ul>
 */
public class FlowRuntime implements FlowHooks, OperationListener {

    public static final String V_FLOW_INSTANCE = "flowInstanceId";
    public static final String V_BIZ_TYPE = "bizType";
    public static final String V_BIZ_ID = "bizId";
    public static final String V_INITIATOR = "initiator";
    public static final String V_OUTCOME = "outcome";
    public static final String V_LAST_TASK = "lastTaskId";
    public static final String V_TASK_OUTCOME = "ls_outcome";
    public static final String V_TASK_COMMENT = "ls_comment";
    public static final String V_TASK_BY = "ls_by";
    public static final int MAX_START_ATTEMPTS = 5;
    public static final java.time.Duration URGE_INTERVAL = java.time.Duration.ofMinutes(30);
    /** 待办生成后多久才允许催办（lsmom.flow.urge-min-age-minutes，默认 0）。 */
    private java.time.Duration urgeMinAge = java.time.Duration.ZERO;

    public void setUrgeMinAge(java.time.Duration d) {
        this.urgeMinAge = d == null ? java.time.Duration.ZERO : d;
    }

    /** 写入业务时间线的全部事件类型（docs/flow-integration.md 与此一致，由 FlowDocTest 校验）。 */
    public static final java.util.Set<String> TIMELINE_EVENTS = java.util.Set.of("ls.flow.started", "ls.flow.task.approved",
        "ls.flow.task.rejected", "ls.flow.task.resubmitted", "ls.flow.task.returned", "ls.flow.task.transferred", "ls.flow.task.urged",
        "ls.flow.task.timeout", "ls.flow.cc", "ls.flow.withdrawn", "ls.flow.approved", "ls.flow.rejected");

    /** 办理动作。 */
    public enum Action { APPROVE, REJECT, RESUBMIT }

    public record TaskView(long id, String taskId, String kind, long flowInstanceId, String procInstId, String bizType, String bizId,
                           String nodeKey, String nodeName, Long assigneeParty, List<Long> candidates, LocalDateTime dueAt, String status,
                           String outcome, String comment, Long completedBy, LocalDateTime completedAt, String exceptionReason,
                           Long orgUnitId, Integer flowVersion, Integer formVersion, LocalDateTime createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final ProcessEngine engine;
    private final FlowRegistry registry;
    private final FlowInstances instances;
    private final FlowCallbacks callbacks;
    private final ApprovalEvidence evidence;
    private final AssigneeResolver assignees;
    private final OperationGateway gateway;
    private final OperationAudit audit;
    private final StateStores stores;
    private final CelRuleEngine cel;
    private final EventPublisher events;
    private final LeaderResolver leaders;
    private final FormRegistry forms;
    private final ConceptRegistry concepts;
    private final TaskSync taskSync;
    private final TransactionTemplate tx;
    private final TransactionTemplate requiresNew;
    private final Clock clock;
    private final Map<String, FlowDefinition> definitions = new ConcurrentHashMap<>();
    private Consumer<FlowInstances.Instance> startInterceptor = i -> { };

    public FlowRuntime(JdbcTemplate jdbc, ProcessEngine engine, FlowRegistry registry, FlowInstances instances, FlowCallbacks callbacks,
                       ApprovalEvidence evidence, AssigneeResolver assignees, OperationGateway gateway, OperationAudit audit, StateStores stores,
                       CelRuleEngine cel, EventPublisher events, LeaderResolver leaders, FormRegistry forms, ConceptRegistry concepts,
                       TaskSync taskSync, PlatformTransactionManager txm, Clock clock) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.registry = registry;
        this.instances = instances;
        this.callbacks = callbacks;
        this.evidence = evidence;
        this.assignees = assignees;
        this.gateway = gateway;
        this.audit = audit;
        this.stores = stores;
        this.cel = cel;
        this.events = events;
        this.leaders = leaders;
        this.forms = forms;
        this.concepts = concepts;
        this.taskSync = taskSync;
        this.tx = new TransactionTemplate(txm);
        this.requiresNew = new TransactionTemplate(txm);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        FlowHooks.register(engine.getName(), this);
        taskSync.bind(this);
        gateway.addListener(this);
        registry.allPublished().forEach(v -> registerEffects(v.definition()));
        registry.onPublished(this::registerEffects);
    }

    public FlowInstances instances() {
        return instances;
    }

    public FlowCallbacks callbacks() {
        return callbacks;
    }

    public ProcessEngine engine() {
        return engine;
    }

    /** 测试用：在启动流程前注入故障。 */
    public void setStartInterceptor(Consumer<FlowInstances.Instance> interceptor) {
        this.startInterceptor = interceptor == null ? i -> { } : interceptor;
    }

    FlowDefinition definition(FlowInstances.Instance inst) {
        return definitions.computeIfAbsent(inst.flowCode() + ":" + inst.flowVersion(),
            k -> registry.get(inst.flowCode(), inst.flowVersion()).definition());
    }

    // ======================= 发布 =======================

    /** 发布流程，并为节点上的状态迁移类 Operation 注册字段写入效果。 */
    public FlowRegistry.Version publish(FlowDefinition def, Long by) {
        return registry.publish(def, by);
    }

    private void registerEffects(FlowDefinition def) {
        def.walk(n -> {
            if (n.human() && n.operation() != null) gateway.registerEffectIfAbsent(new FieldWriteEffect(n.operation(), def.concept(), concepts));
        });
        if (def.callbacks() != null && def.callbacks().resubmitted() != null) {
            gateway.registerEffectIfAbsent(new FieldWriteEffect(def.callbacks().resubmitted(), def.concept(), concepts));
        }
    }

    // ======================= 发起 =======================

    @Override
    public void afterSuccess(OperationContext ctx, OperationResult result) {
        if (ctx.targetId() == null || result.simulated()) return;
        registry.startedBy(ctx.operation().code()).ifPresent(v -> {
            String concept = ctx.operation().concept();
            if (instances.running(concept, ctx.targetId()).isPresent()) {
                throw new LsBizException("FLOW_RUNNING", "该业务对象已有进行中的流程");
            }
            FlowDefinition d = v.definition();
            String formCode = null;
            Integer formVersion = null;
            if (d.form() != null) {
                formCode = d.form().code();
                formVersion = d.form().version();
            } else {
                var f = forms.defaultFor(concept, ViewType.FORM);
                if (f.isPresent()) {
                    formCode = f.get().code();
                    formVersion = f.get().version();
                }
            }
            long id = instances.create(v.code(), v.version(), formCode, formVersion, concept, ctx.targetId(), v.procDefId(),
                ctx.actor().effectiveParty(), ctx.targetOrgUnitId(), null);
            afterCommit(() -> start(id));
        });
    }

    /** 启动（或重试启动）流程实例；失败记录同步状态，不抛出。 */
    public boolean start(long instanceId) {
        try {
            requiresNew.executeWithoutResult(st -> {
                FlowInstances.Instance inst = instances.get(instanceId);
                if ("SYNCED".equals(inst.syncStatus()) || !"RUNNING".equals(inst.status())) return;
                startInterceptor.accept(inst);
                Map<String, Object> vars = new HashMap<>();
                vars.put(V_FLOW_INSTANCE, inst.id());
                vars.put(V_BIZ_TYPE, inst.bizType());
                vars.put(V_BIZ_ID, inst.bizId());
                vars.put(V_INITIATOR, String.valueOf(inst.initiatorParty()));
                vars.put(V_OUTCOME, "PENDING");
                vars.put("cand_" + BpmnTemplateGenerator.INITIATOR, List.of(String.valueOf(inst.initiatorParty())));
                ProcessInstance pi = engine.getRuntimeService().createProcessInstanceBuilder().processDefinitionId(inst.procDefId())
                    .businessKey(inst.bizType() + ":" + inst.bizId()).variables(vars).start();
                instances.started(inst.id(), pi.getId());
                Task first = engine.getTaskService().createTaskQuery().processInstanceId(pi.getId())
                    .taskDefinitionKey(BpmnTemplateGenerator.INITIATOR).singleResult();
                if (first != null) {
                    engine.getTaskService().setVariablesLocal(first.getId(), Map.of(V_TASK_OUTCOME, "SUBMITTED", V_TASK_BY, inst.initiatorParty()));
                    engine.getRuntimeService().setVariable(pi.getId(), V_LAST_TASK, first.getId());
                    engine.getTaskService().complete(first.getId());
                }
                event("ls.flow.started", inst, inst.initiatorParty(), Map.of("flow", inst.flowCode(), "flowVersion", inst.flowVersion(),
                    "procInstId", pi.getId(), "label", "发起流程"));
            });
            return true;
        } catch (RuntimeException e) {
            FlowInstances.Instance inst = instances.get(instanceId);
            int attempts = inst.attempts() + 1;
            String msg = e instanceof LsBizException b ? b.getCode() + "：" + b.getMessage() : e.getClass().getSimpleName() + "：" + e.getMessage();
            requiresNew.executeWithoutResult(st -> instances.startFailed(instanceId, msg,
                now().plusSeconds(30L * (1L << Math.min(attempts, 10))), attempts >= MAX_START_ATTEMPTS));
            return false;
        }
    }

    /** 同步重试：启动失败的实例与失败的回调（Worker 定时调用）。 */
    public int retryPending() {
        int n = 0;
        for (FlowInstances.Instance i : instances.dueForRetry(now())) if (start(i.id())) n++;
        return n + callbacks.retryDue();
    }

    // ======================= 引擎回调 =======================

    @Override
    public void serviceTask(String kind, String node, DelegateExecution execution) {
        FlowInstances.Instance inst = instances.get(((Number) execution.getVariable(V_FLOW_INSTANCE)).longValue());
        FlowDefinition def = definition(inst);
        FlowDefinition.Node n = def.node(node);
        if (n == null) throw new LsBizException("FLOW_NODE_NOT_FOUND", "流程节点不存在：" + node);
        switch (kind) {
            case "RESOLVE" -> {
                AssigneeResolver.Result r = assignees.resolve(n.assignee(), n.permission(), context(inst));
                List<Long> parties = r.parties();
                if (r.empty()) {
                    parties = assignees.admins(def.adminRoleOrDefault());
                    execution.setVariable("exc_" + node, r.reason());
                } else if (execution.hasVariable("exc_" + node)) {
                    execution.removeVariable("exc_" + node);
                }
                execution.setVariable("cand_" + node, parties.stream().map(String::valueOf).toList());
                execution.setVariable(V_OUTCOME, "PENDING");
            }
            case "ROUTE" -> {
                Map<String, Object> model = stores.of(inst.bizType()).attributes(inst.bizId());
                Map<String, Object> context = Map.of("initiator", String.valueOf(inst.initiatorParty()));
                int chosen = -1;
                int fallback = -1;
                for (int i = 0; i < n.branches().size(); i++) {
                    String when = n.branches().get(i).when();
                    if (when == null || when.isBlank()) {
                        fallback = i;
                        continue;
                    }
                    if (cel.test(cel.compileCondition(when), model, context)) {
                        chosen = i;
                        break;
                    }
                }
                if (chosen < 0) chosen = fallback;
                if (chosen < 0) throw new LsBizException("FLOW_NO_ROUTE", "条件分支「" + n.name() + "」没有满足的分支");
                execution.setVariable("route_" + node, chosen);
            }
            case "OPERATION" -> {
                int version = stores.of(inst.bizType()).load(inst.bizId()).version();
                gateway.execute(n.operation(), new OperationRequest(JsonNodeFactory.instance.objectNode(), inst.bizId(), version,
                    inst.procInstId() + ":" + node + ":" + execution.getId(), false), FlowCallbacks.actor());
            }
            case "CC" -> {
                AssigneeResolver.Result r = assignees.resolve(n.assignee(), null, context(inst));
                cc(inst, node, n.name(), r.parties(), null, "抄送：" + n.name());
            }
            case "TIMEOUT" -> timeout(inst, def, n);
            default -> throw new LsBizException("FLOW_INVALID", "未知服务节点：" + kind);
        }
    }

    @Override
    public void ended(String outcome, DelegateExecution execution) {
        FlowInstances.Instance inst = instances.get(((Number) execution.getVariable(V_FLOW_INSTANCE)).longValue());
        FlowDefinition def = definition(inst);
        String lastTask = (String) execution.getVariable(V_LAST_TASK);
        instances.ended(inst.id(), outcome);
        String event = "APPROVED".equals(outcome) ? "approved" : "rejected";
        String op = "APPROVED".equals(outcome) ? def.callbacks().approved() : def.callbacks().rejected();
        if ("APPROVED".equals(outcome)) {
            List<Long> approvers = jdbc.queryForList("""
                select distinct completed_by from biz_task where proc_inst_id = ? and kind in ('TASK', 'EXCEPTION') and outcome = 'APPROVED'
                  and completed_by is not null""", Long.class, inst.procInstId());
            List<String> comments = jdbc.queryForList("select coalesce(comment, '') from biz_task where proc_inst_id = ? and status = 'DONE' order by id",
                String.class, inst.procInstId());
            evidence.record(inst, approvers, ApprovalEvidence.sha256(String.join("\n", comments)), now());
        }
        event("ls.flow." + event, inst, null, Map.of("procInstId", inst.procInstId(), "label", "APPROVED".equals(outcome) ? "审批通过" : "审批驳回"));
        String key = FlowCallbacks.key(inst.procInstId(), lastTask, event);
        Long by = null;
        String comment = null;
        if (lastTask != null) {
            var row = jdbc.queryForList("select completed_by, comment from biz_task where task_id = ?", lastTask);
            if (!row.isEmpty()) {
                by = row.get(0).get("completed_by") == null ? null : ((Number) row.get(0).get("completed_by")).longValue();
                comment = (String) row.get(0).get("comment");
            }
        }
        if (callbacks.enqueue(key, inst.procInstId(), lastTask, event, op, inst.bizType(), inst.bizId(), by, comment)) {
            afterCommit(() -> callbacks.dispatch(key));
        }
    }

    // ======================= 办理 =======================

    /** 办理待办：通过、驳回，或发起人再提交（被退回后）。 */
    public Map<String, Object> complete(String taskId, Action action, String comment, JsonNode input, ActorContext actor) {
        Task task = requireTask(taskId);
        FlowInstances.Instance inst = instanceOf(task);
        requireHandler(task, inst, actor, "ls.flow.task." + action.name().toLowerCase());
        FlowDefinition def = definition(inst);
        boolean initiatorTask = BpmnTemplateGenerator.INITIATOR.equals(task.getTaskDefinitionKey());
        FlowDefinition.Node node = initiatorTask ? null : def.node(task.getTaskDefinitionKey());
        if (initiatorTask && action != Action.RESUBMIT) throw new LsBizException("FLOW_ACTION_INVALID", "被退回的单据只能修改后再提交");
        if (!initiatorTask && action == Action.RESUBMIT) throw new LsBizException("FLOW_ACTION_INVALID", "只有被退回到发起人时才能再提交");
        if (node != null && !actor.hasPermission(node.permission())) {
            denied(actor, inst, "ls.flow.task." + action.name().toLowerCase(), "ACCESS_DENIED", "没有办理该节点的权限");
            throw new LsBizException("ACCESS_DENIED", "没有办理节点「" + node.name() + "」的权限", Map.of("permission", node.permission()));
        }
        boolean hasInput = input != null && input.isObject() && !input.isEmpty();
        return tx.execute(st -> {
            if (initiatorTask) {
                if (hasInput && (def.callbacks().resubmitted() == null || inst.formCode() == null)) {
                    throw new LsBizException("FLOW_INPUT_UNSUPPORTED", "流程未配置再提交 Operation 或表单，不能在再提交时修改字段");
                }
                // 发起人修改后再提交：字段按表单 nodePolicies.initiator 校验后随再提交 Operation 写入
                if (def.callbacks().resubmitted() != null) {
                    if (hasInput) runBizOp(def.callbacks().resubmitted(), inst, task, BpmnTemplateGenerator.INITIATOR, null, actor, input);
                    else runBizOp(def.callbacks().resubmitted(), inst, task, null, actor, comment);
                }
            } else if (action == Action.APPROVE && (hasInput || node.operation() != null)) {
                if (node.operation() == null) throw new LsBizException("FLOW_INPUT_UNSUPPORTED", "节点「" + node.name() + "」未配置填报 Operation，不能提交字段");
                runBizOp(node.operation(), inst, task, node, actor, hasInput ? input : null);
            }
            String outcome = switch (action) {
                case APPROVE -> "APPROVED";
                case REJECT -> "REJECTED";
                case RESUBMIT -> "RESUBMITTED";
            };
            engine.getTaskService().setVariablesLocal(task.getId(), Map.of(V_TASK_OUTCOME, outcome, V_TASK_COMMENT, comment == null ? "" : comment,
                V_TASK_BY, actor.effectiveParty()));
            engine.getRuntimeService().setVariable(task.getProcessInstanceId(), V_OUTCOME, outcome);
            engine.getRuntimeService().setVariable(task.getProcessInstanceId(), V_LAST_TASK, task.getId());
            event("ls.flow.task." + outcome.toLowerCase(), inst, actor.effectiveParty(), payload(task, comment,
                switch (action) {
                    case APPROVE -> "审核通过";
                    case REJECT -> "审核驳回";
                    case RESUBMIT -> "再提交";
                }));
            engine.getTaskService().complete(task.getId());
            return Map.of("taskId", task.getId(), "outcome", outcome);
        });
    }

    /** 退回：退回发起人（默认）或指定已经过的前序人工节点。退回发起人先经业务 Operation（returned）判定。 */
    public Map<String, Object> returnTask(String taskId, String targetNode, String reason, ActorContext actor) {
        if (reason == null || reason.isBlank()) throw LsBizException.validation(Map.of("reason", "退回原因必填"));
        Task task = requireTask(taskId);
        FlowInstances.Instance inst = instanceOf(task);
        requireHandler(task, inst, actor, "ls.flow.task.return");
        FlowDefinition def = definition(inst);
        String target = targetNode == null || targetNode.isBlank() ? BpmnTemplateGenerator.INITIATOR : targetNode;
        if (BpmnTemplateGenerator.INITIATOR.equals(task.getTaskDefinitionKey())) throw new LsBizException("FLOW_ACTION_INVALID", "发起人待办不能退回");
        if (!target.equals(BpmnTemplateGenerator.INITIATOR)) {
            FlowDefinition.Node t = def.node(target);
            if (t == null || !t.human() || target.equals(task.getTaskDefinitionKey()) || !passed(inst.procInstId(), target)) {
                throw new LsBizException("FLOW_RETURN_INVALID", "只能退回到已经过的前序人工节点", Map.of("target", target));
            }
        }
        return tx.execute(st -> {
            if (target.equals(BpmnTemplateGenerator.INITIATOR) && def.callbacks().returned() != null) {
                runBizOp(def.callbacks().returned(), inst, task, null, actor, null);
            }
            List<String> active = engine.getTaskService().createTaskQuery().processInstanceId(inst.procInstId()).list().stream()
                .map(Task::getTaskDefinitionKey).distinct().toList();
            jdbc.update("""
                update biz_task set status = 'DONE', outcome = 'RETURNED', comment = ?, completed_by = ?, completed_at = ?, updated_at = now(),
                       version = version + 1 where task_id = ?""", reason, actor.effectiveParty(), now(), task.getId());
            String to = target.equals(BpmnTemplateGenerator.INITIATOR) ? target : "resolve_" + target;
            engine.getRuntimeService().setVariable(inst.procInstId(), V_OUTCOME, "RETURNED");
            engine.getRuntimeService().createChangeActivityStateBuilder().processInstanceId(inst.procInstId())
                .moveActivityIdsToSingleActivityId(active, to).changeState();
            event("ls.flow.task.returned", inst, actor.effectiveParty(), withTarget(payload(task, reason, "退回"), target));
            return Map.of("taskId", task.getId(), "target", target);
        });
    }

    /** 撤回：发起人撤回进行中的流程，先经业务 Operation（withdrawn，含"未被办理"守卫）判定合法性。 */
    public Map<String, Object> withdraw(long instanceId, String reason, ActorContext actor) {
        FlowInstances.Instance inst = instances.get(instanceId);
        if (!"RUNNING".equals(inst.status())) throw new LsBizException("FLOW_NOT_RUNNING", "流程已结束，不能撤回");
        if (!Objects.equals(inst.initiatorParty(), actor.effectiveParty()) && !actor.superAdmin()) {
            denied(actor, inst, "ls.flow.withdraw", "ACCESS_DENIED", "只有发起人可以撤回");
            throw new LsBizException("ACCESS_DENIED", "只有发起人可以撤回");
        }
        FlowDefinition def = definition(inst);
        if (def.callbacks().withdrawn() == null) throw new LsBizException("FLOW_WITHDRAW_UNSUPPORTED", "该流程未配置撤回");
        return tx.execute(st -> {
            runBizOp(def.callbacks().withdrawn(), inst, null, null, actor, null);
            engine.getRuntimeService().deleteProcessInstance(inst.procInstId(), "WITHDRAWN");
            instances.ended(inst.id(), "WITHDRAWN");
            event("ls.flow.withdrawn", inst, actor.effectiveParty(), Map.of("procInstId", inst.procInstId(), "reason", reason == null ? "" : reason,
                "label", "撤回"));
            return Map.of("instanceId", inst.id(), "status", "WITHDRAWN");
        });
    }

    /** 转办：当前办理人把待办转给另一位可办人员。 */
    public Map<String, Object> transfer(String taskId, long toParty, String comment, ActorContext actor) {
        Task task = requireTask(taskId);
        FlowInstances.Instance inst = instanceOf(task);
        requireHandler(task, inst, actor, "ls.flow.task.transfer");
        FlowDefinition.Node node = definition(inst).node(task.getTaskDefinitionKey());
        if (assignees.usable(List.of(toParty), node == null ? null : node.permission()).isEmpty()) {
            throw new LsBizException("TRANSFER_INVALID", "转办对象已离职、停用或没有办理权限");
        }
        return tx.execute(st -> {
            for (IdentityLink l : engine.getTaskService().getIdentityLinksForTask(task.getId())) {
                if (IdentityLinkType.CANDIDATE.equals(l.getType()) && l.getUserId() != null) {
                    engine.getTaskService().deleteCandidateUser(task.getId(), l.getUserId());
                }
            }
            engine.getTaskService().setAssignee(task.getId(), String.valueOf(toParty));
            refreshCandidates(task.getId());
            notice("TRANSFER", toParty, task.getId(), inst, actor.effectiveParty(), "转办给你：" + task.getName()
                + (comment == null || comment.isBlank() ? "" : "（" + comment + "）"));
            jdbc.update("""
                insert into biz_task_transfer (task_id, biz_type, biz_id, node_name, from_party, to_party, reason, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)""", task.getId(), inst.bizType(), inst.bizId(), task.getName(), actor.effectiveParty(), toParty,
                comment, now());
            Map<String, Object> p = withTarget(payload(task, comment, "转办"), String.valueOf(toParty));
            p.put("targetName", jdbc.queryForList("select name from core_party where id = ?", String.class, toParty).stream().findFirst().orElse(""));
            event("ls.flow.task.transferred", inst, actor.effectiveParty(), p);
            return Map.of("taskId", task.getId(), "assignee", toParty);
        });
    }

    /** 催办：发起人提醒当前办理人，同一待办 30 分钟内只能催办一次。 */
    public Map<String, Object> urge(String taskId, ActorContext actor) {
        Task task = requireTask(taskId);
        FlowInstances.Instance inst = instanceOf(task);
        if (!Objects.equals(inst.initiatorParty(), actor.effectiveParty()) && !actor.superAdmin()) {
            throw new LsBizException("ACCESS_DENIED", "只有发起人可以催办");
        }
        return tx.execute(st -> {
            LocalDateTime last = jdbc.query("select last_urged_at from biz_task where task_id = ? for update",
                (rs, i) -> rs.getObject(1, LocalDateTime.class), task.getId()).stream().filter(Objects::nonNull).findFirst().orElse(null);
            LocalDateTime now = now();
            LocalDateTime created = jdbc.queryForObject("select created_at from biz_task where task_id = ?", LocalDateTime.class, task.getId());
            if (!urgeMinAge.isZero() && created != null && created.plus(urgeMinAge).isAfter(now)) {
                throw new LsBizException("URGE_TOO_EARLY", "待办生成未满 " + urgeMinAge.toMinutes() + " 分钟，暂不能催办",
                    Map.of("nextAllowedAt", created.plus(urgeMinAge).toString()));
            }
            if (last != null && last.plus(URGE_INTERVAL).isAfter(now)) {
                throw new LsBizException("URGE_TOO_FREQUENT", "催办太频繁，请 " + java.time.Duration.between(now, last.plus(URGE_INTERVAL)).toMinutes()
                    + " 分钟后再试", Map.of("nextAllowedAt", last.plus(URGE_INTERVAL).toString()));
            }
            jdbc.update("update biz_task set last_urged_at = ?, updated_at = now() where task_id = ?", now, task.getId());
            for (Long p : handlers(task)) notice("URGE", p, task.getId(), inst, actor.effectiveParty(), "催办：" + task.getName());
            event("ls.flow.task.urged", inst, actor.effectiveParty(), payload(task, null, "催办"));
            return Map.of("taskId", task.getId(), "urgedAt", now.toString());
        });
    }

    /** 手工抄送。 */
    public Map<String, Object> ccManual(long instanceId, List<Long> parties, String message, ActorContext actor) {
        FlowInstances.Instance inst = instances.get(instanceId);
        if (parties == null || parties.isEmpty()) throw LsBizException.validation(Map.of("parties", "抄送对象必填"));
        List<Long> ok = assignees.usable(parties, null);
        return tx.execute(st -> {
            cc(inst, "cc", "抄送", ok, actor.effectiveParty(), message == null ? "抄送" : message);
            event("ls.flow.cc", inst, actor.effectiveParty(), Map.of("parties", ok, "procInstId", inst.procInstId() == null ? "" : inst.procInstId(),
                "label", "抄送"));
            return Map.of("instanceId", inst.id(), "parties", ok);
        });
    }

    // ======================= 查询 =======================

    public List<TaskView> todo(Long party) {
        return jdbc.query("""
            select * from biz_task where status = 'TODO' and (assignee_party = ? or (assignee_party is null and candidates @> to_jsonb(?::bigint)))
            order by created_at, id""", (rs, i) -> view(rs), party, party);
    }

    public List<TaskView> tasksOf(String procInstId) {
        return jdbc.query("select * from biz_task where proc_inst_id = ? order by id", (rs, i) -> view(rs), procInstId);
    }

    public List<TaskView> tasksOfBiz(String bizType, String bizId) {
        return jdbc.query("select * from biz_task where biz_type = ? and biz_id = ? order by id", (rs, i) -> view(rs), bizType, bizId);
    }

    public TaskView task(String taskId) {
        return jdbc.query("select * from biz_task where task_id = ?", (rs, i) -> view(rs), taskId).stream().findFirst()
            .orElseThrow(() -> LsBizException.notFound("待办", taskId));
    }

    /** 业务对象的流程实例（含当前节点）。 */
    public List<Map<String, Object>> instancesOf(String bizType, String bizId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FlowInstances.Instance i : instances.ofBiz(bizType, bizId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("instance", i);
            m.put("currentNodes", i.procInstId() == null ? List.of() : jdbc.queryForList(
                "select distinct node_key from biz_task where proc_inst_id = ? and status = 'TODO' and kind in ('TASK', 'EXCEPTION')", String.class,
                i.procInstId()));
            out.add(m);
        }
        return out;
    }

    // ======================= 内部 =======================

    private void runBizOp(String operation, FlowInstances.Instance inst, Task task, FlowDefinition.Node node, ActorContext actor, Object inputOrComment) {
        runBizOp(operation, inst, task, node == null ? null : node.policyKey(), node == null ? null : node.fieldPolicy(), actor, inputOrComment);
    }

    private void runBizOp(String operation, FlowInstances.Instance inst, Task task, String policyKey, JsonNode fieldPolicy, ActorContext actor,
                          Object inputOrComment) {
        JsonNode input = inputOrComment instanceof JsonNode j ? j : JsonNodeFactory.instance.objectNode();
        int version = stores.of(inst.bizType()).load(inst.bizId()).version();
        NodePolicyResolver.Binding binding = policyKey == null || inst.formCode() == null ? null
            : new NodePolicyResolver.Binding(inst.formCode(), inst.formVersion(), policyKey, fieldPolicy);
        NodeContext.Current cur = new NodeContext.Current(inst.bizType(), inst.bizId(), task == null ? null : task.getId(), binding);
        String key = inst.procInstId() + ":" + (task == null ? "-" : task.getId()) + ":" + operation;
        NodeContext.with(cur, () -> gateway.execute(operation, new OperationRequest(input, inst.bizId(), version, key, false), actor));
    }

    private Task requireTask(String taskId) {
        Task t = engine.getTaskService().createTaskQuery().taskId(taskId).singleResult();
        if (t == null) throw new LsBizException("TASK_NOT_FOUND", "待办不存在或已办理", Map.of("taskId", taskId));
        return t;
    }

    private FlowInstances.Instance instanceOf(Task task) {
        return instances.byProcInst(task.getProcessInstanceId())
            .orElseThrow(() -> new LsBizException("FLOW_NOT_FOUND", "流程实例不存在：" + task.getProcessInstanceId()));
    }

    /** 当前待办的办理人：指派人，或候选人。 */
    private Set<Long> handlers(Task task) {
        Set<Long> s = new LinkedHashSet<>();
        if (task.getAssignee() != null) s.add(Long.parseLong(task.getAssignee()));
        else for (IdentityLink l : engine.getTaskService().getIdentityLinksForTask(task.getId())) {
            if (IdentityLinkType.CANDIDATE.equals(l.getType()) && l.getUserId() != null) s.add(Long.parseLong(l.getUserId()));
        }
        return s;
    }

    private void requireHandler(Task task, FlowInstances.Instance inst, ActorContext actor, String op) {
        Long p = actor.effectiveParty();
        if (p != null && handlers(task).contains(p)) return;
        denied(actor, inst, op, "TASK_NOT_ASSIGNED", "不是该待办的办理人或候选人");
        throw new LsBizException("TASK_NOT_ASSIGNED", "你不是该待办的办理人或候选人", Map.of("taskId", task.getId()));
    }

    private void denied(ActorContext actor, FlowInstances.Instance inst, String op, String code, String msg) {
        audit.writeIndependently(new OperationAudit.Entry(op, actor, inst.bizType(), inst.bizId(), null, "FAILED", code, msg, "-", "{}", 0, null));
    }

    private boolean passed(String procInstId, String nodeKey) {
        List<HistoricActivityInstance> list = engine.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(procInstId)
            .activityId(nodeKey).finished().list();
        return !list.isEmpty();
    }

    private AssigneeResolver.Context context(FlowInstances.Instance inst) {
        return new AssigneeResolver.Context(inst.initiatorParty(), inst.orgUnitId(), stores.of(inst.bizType()).attributes(inst.bizId()));
    }

    private void timeout(FlowInstances.Instance inst, FlowDefinition def, FlowDefinition.Node n) {
        FlowDefinition.TimeoutAction action = n.timeout().action();
        List<Task> tasks = engine.getTaskService().createTaskQuery().processInstanceId(inst.procInstId()).taskDefinitionKey(n.key()).list();
        if (action == FlowDefinition.TimeoutAction.REMIND || action == FlowDefinition.TimeoutAction.REMIND_ESCALATE) {
            for (Task t : tasks) for (Long p : handlers(t)) notice("REMIND", p, t.getId(), inst, null, "待办已超时：" + t.getName());
        }
        if (action == FlowDefinition.TimeoutAction.ESCALATE || action == FlowDefinition.TimeoutAction.REMIND_ESCALATE) {
            for (Task t : tasks) {
                Long base = handlers(t).stream().findFirst().orElse(null);
                Long org = base == null ? inst.orgUnitId() : jdbc.queryForList(
                    "select org_unit_id from core_party_org where party_id = ? and is_primary", Long.class, base).stream().findFirst()
                    .orElse(inst.orgUnitId());
                LeaderResolution r = org == null ? LeaderResolution.noAssignee(null, "无组织") : leaders.resolve(org, LeaderKind.SUPERIOR);
                if (!r.resolved()) {
                    for (Long a : assignees.admins(def.adminRoleOrDefault())) {
                        notice("EXCEPTION", a, t.getId(), inst, null, "待办超时且无法升级（" + r.reason() + "）：" + t.getName());
                    }
                    continue;
                }
                engine.getTaskService().addCandidateUser(t.getId(), String.valueOf(r.partyId()));
                refreshCandidates(t.getId());
                jdbc.update("""
                    insert into biz_task (task_id, kind, flow_instance_id, proc_inst_id, biz_type, biz_id, node_key, node_name, assignee_party,
                                          candidates, status, org_unit_id, flow_version, form_version, created_at, updated_at)
                    values (null, 'ESCALATION', ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'TODO', ?, ?, ?, ?, ?)""",
                    inst.id(), inst.procInstId(), inst.bizType(), inst.bizId(), n.key(), "超时升级：" + n.name(), r.partyId(),
                    TaskSync.json(List.of(r.partyId())), inst.orgUnitId(), inst.flowVersion(), inst.formVersion(), now(), now());
                notice("ESCALATE", r.partyId(), t.getId(), inst, null, "下属待办超时，已升级给你：" + t.getName());
            }
        }
        event("ls.flow.task.timeout", inst, null, Map.of("node", n.key(), "nodeName", n.name(), "action", action.name(), "label", "超时" +
            (action == FlowDefinition.TimeoutAction.REMIND ? "提醒" : "升级")));
    }

    /** 候选人变化后（转办、升级）同步读模型。 */
    void refreshCandidates(String taskId) {
        Task t = engine.getTaskService().createTaskQuery().taskId(taskId).singleResult();
        List<Long> cands = new ArrayList<>();
        for (IdentityLink l : engine.getTaskService().getIdentityLinksForTask(taskId)) {
            if (IdentityLinkType.CANDIDATE.equals(l.getType()) && l.getUserId() != null) cands.add(Long.parseLong(l.getUserId()));
        }
        if (cands.isEmpty() && t != null && t.getAssignee() != null) cands.add(Long.parseLong(t.getAssignee()));
        jdbc.update("update biz_task set candidates = ?::jsonb, assignee_party = ?, updated_at = now(), version = version + 1 where task_id = ?",
            TaskSync.json(cands), t == null || t.getAssignee() == null ? null : Long.parseLong(t.getAssignee()), taskId);
    }

    private void cc(FlowInstances.Instance inst, String node, String name, List<Long> parties, Long by, String message) {
        for (Long p : parties) {
            jdbc.update("""
                insert into biz_task (task_id, kind, flow_instance_id, proc_inst_id, biz_type, biz_id, node_key, node_name, assignee_party,
                                      candidates, status, org_unit_id, flow_version, form_version, created_at, updated_at)
                values (null, 'CC', ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'TODO', ?, ?, ?, ?, ?)""",
                inst.id(), inst.procInstId(), inst.bizType(), inst.bizId(), node, name, p, TaskSync.json(List.of(p)), inst.orgUnitId(),
                inst.flowVersion(), inst.formVersion(), now(), now());
            notice("CC", p, null, inst, by, message);
        }
    }

    /** 抄送/知会待办标记已读。 */
    public void markRead(long bizTaskId, ActorContext actor) {
        int n = jdbc.update("""
            update biz_task set status = 'DONE', completed_by = ?, completed_at = now(), updated_at = now(), version = version + 1
            where id = ? and kind in ('CC', 'ESCALATION') and assignee_party = ? and status = 'TODO'""",
            actor.effectiveParty(), bizTaskId, actor.effectiveParty());
        if (n == 0) throw LsBizException.notFound("知会待办", bizTaskId);
    }

    void noticeException(String taskId, FlowInstances.Instance inst, List<Long> admins, String reason) {
        for (Long a : admins) notice("EXCEPTION", a, taskId, inst, null, "无人可办，已转流程管理员：" + reason);
    }

    private void notice(String kind, Long party, String taskId, FlowInstances.Instance inst, Long by, String message) {
        jdbc.update("""
            insert into biz_flow_notice (kind, party_id, task_id, proc_inst_id, biz_type, biz_id, message, created_by, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", kind, party, taskId, inst.procInstId(), inst.bizType(), inst.bizId(),
            message.length() > 500 ? message.substring(0, 500) : message, by, now());
    }

    public List<Map<String, Object>> notices(Long party) {
        return jdbc.queryForList("select * from biz_flow_notice where party_id = ? order by id desc limit 200", party);
    }

    private void event(String type, FlowInstances.Instance inst, Long actorParty, Map<String, Object> payload) {
        if (!TIMELINE_EVENTS.contains(type)) throw new IllegalStateException("未登记的流程事件：" + type);
        events.publish(new EventPublisher.NewEvent(type, inst.bizType(), inst.bizId(), inst.orgUnitId(), actorParty, null,
            type + ":" + UUID.randomUUID(), payload));
    }

    private static Map<String, Object> payload(Task task, String comment, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", task.getId());
        m.put("procInstId", task.getProcessInstanceId());
        m.put("node", task.getTaskDefinitionKey());
        m.put("nodeName", task.getName());
        m.put("label", label);
        if (comment != null && !comment.isBlank()) m.put("comment", comment);
        return m;
    }

    private static Map<String, Object> withTarget(Map<String, Object> m, String target) {
        m.put("target", target);
        return m;
    }

    LocalDateTime now() {
        return LocalDateTime.ofInstant(engine.getProcessEngineConfiguration().getClock().getCurrentTime().toInstant(), ZoneId.systemDefault());
    }

    static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    public static TaskView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<Long> cands;
        try {
            cands = List.of(new com.fasterxml.jackson.databind.ObjectMapper().readValue(rs.getString("candidates"), Long[].class));
        } catch (Exception e) {
            cands = List.of();
        }
        return new TaskView(rs.getLong("id"), rs.getString("task_id"), rs.getString("kind"), rs.getLong("flow_instance_id"),
            rs.getString("proc_inst_id"), rs.getString("biz_type"), rs.getString("biz_id"), rs.getString("node_key"), rs.getString("node_name"),
            (Long) rs.getObject("assignee_party"), cands, rs.getObject("due_at", LocalDateTime.class), rs.getString("status"),
            rs.getString("outcome"), rs.getString("comment"), (Long) rs.getObject("completed_by"), rs.getObject("completed_at", LocalDateTime.class),
            rs.getString("exception_reason"), (Long) rs.getObject("org_unit_id"), (Integer) rs.getObject("flow_version"),
            (Integer) rs.getObject("form_version"), rs.getObject("created_at", LocalDateTime.class));
    }
}
