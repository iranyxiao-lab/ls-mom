package com.lsmom.flow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.flow.model.FlowDefinition;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEntityEvent;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.identitylink.api.IdentityLinkInfo;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 全局事件监听器：在引擎同一事务内维护待办读模型 biz_task（创建、指派、完成、删除）。
 * 监听失败使引擎操作整体回滚（isFailOnException = true），读模型不会与引擎分叉；万一分叉由对账作业重建。
 */
public class TaskSync implements FlowableEventListener {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<FlowableEngineEventType> TYPES = Set.of(FlowableEngineEventType.TASK_CREATED, FlowableEngineEventType.TASK_ASSIGNED,
        FlowableEngineEventType.TASK_COMPLETED, FlowableEngineEventType.ENTITY_DELETED);

    private final JdbcTemplate jdbc;
    private FlowRuntime runtime;

    public TaskSync(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void bind(FlowRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (!(event.getType() instanceof FlowableEngineEventType t) || !TYPES.contains(t)) return;
        if (!(event instanceof FlowableEntityEvent ee) || !(ee.getEntity() instanceof TaskEntity task)) return;
        switch (t) {
            case TASK_CREATED -> created(task);
            case TASK_ASSIGNED -> jdbc.update("update biz_task set assignee_party = ?, updated_at = now(), version = version + 1 where task_id = ?",
                party(task.getAssignee()), task.getId());
            case TASK_COMPLETED -> {
                Object outcome = task.getVariableLocal(FlowRuntime.V_TASK_OUTCOME);
                Object comment = task.getVariableLocal(FlowRuntime.V_TASK_COMMENT);
                Object by = task.getVariableLocal(FlowRuntime.V_TASK_BY);
                jdbc.update("""
                    update biz_task set status = 'DONE', outcome = ?, comment = ?, completed_by = ?, completed_at = ?, updated_at = now(),
                           version = version + 1 where task_id = ?""",
                    outcome == null ? "DONE" : outcome.toString(), comment == null ? null : comment.toString(),
                    by == null ? party(task.getAssignee()) : Long.valueOf(by.toString()), now(), task.getId());
            }
            case ENTITY_DELETED -> jdbc.update("""
                update biz_task set status = 'CANCELED', updated_at = now(), version = version + 1 where task_id = ? and status = 'TODO'""",
                task.getId());
            default -> {
            }
        }
    }

    private void created(TaskEntity task) {
        String node = task.getTaskDefinitionKey();
        Object fid = task.getVariable(FlowRuntime.V_FLOW_INSTANCE);
        if (fid == null) return; // 非 LS 流程
        FlowInstances.Instance inst = runtime.instances().get(Long.parseLong(fid.toString()));
        Object exc = task.getVariable("exc_" + node);
        Integer hours = null;
        if (!node.equals(com.lsmom.flow.bpmn.BpmnTemplateGenerator.INITIATOR)) {
            FlowDefinition.Node n = runtime.definition(inst).node(node);
            if (n != null && n.timeout() != null) hours = n.timeout().hours();
        }
        LocalDateTime created = now();
        jdbc.update("""
            insert into biz_task (task_id, kind, flow_instance_id, proc_inst_id, biz_type, biz_id, node_key, node_name, assignee_party,
                                  candidates, due_at, status, exception_reason, org_unit_id, flow_version, form_version, trace_id, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'TODO', ?, ?, ?, ?, ?, ?, ?)
            on conflict (task_id) do nothing""",
            task.getId(), exc == null ? "TASK" : "EXCEPTION", inst.id(), task.getProcessInstanceId(), inst.bizType(), inst.bizId(), node,
            task.getName(), party(task.getAssignee()), json(candidates(task)), hours == null ? null : created.plusHours(hours),
            exc == null ? null : exc.toString(), inst.orgUnitId(), inst.flowVersion(), inst.formVersion(), null, created, created);
        if (exc != null) runtime.noticeException(task.getId(), inst, candidates(task), exc.toString());
    }

    /** 重新读取引擎中的候选人（转办、升级后调用）。 */
    public void refresh(TaskEntity task) {
        jdbc.update("update biz_task set candidates = ?::jsonb, assignee_party = ?, updated_at = now(), version = version + 1 where task_id = ?",
            json(candidates(task)), party(task.getAssignee()), task.getId());
    }

    static List<Long> candidates(TaskEntity task) {
        List<Long> out = new ArrayList<>();
        for (IdentityLinkInfo l : task.getIdentityLinks()) {
            if (IdentityLinkType.CANDIDATE.equals(l.getType()) && l.getUserId() != null) out.add(Long.parseLong(l.getUserId()));
        }
        if (task.getAssignee() != null && out.isEmpty()) out.add(Long.parseLong(task.getAssignee()));
        return out;
    }

    private static Long party(String userId) {
        return userId == null ? null : Long.parseLong(userId);
    }

    static LocalDateTime now() {
        return LocalDateTime.ofInstant(CommandContextUtil.getProcessEngineConfiguration().getClock().getCurrentTime().toInstant(), ZoneId.systemDefault());
    }

    static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean isFailOnException() {
        return true;
    }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() {
        return false;
    }

    @Override
    public String getOnTransaction() {
        return null;
    }
}
