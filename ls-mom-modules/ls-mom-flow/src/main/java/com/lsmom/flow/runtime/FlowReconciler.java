package com.lsmom.flow.runtime;

import com.lsmom.flow.model.FlowDefinition;
import org.flowable.engine.ProcessEngine;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对账：比对引擎运行时（ACT_RU_TASK、流程实例）与待办读模型、流程实例绑定、回调日志，输出差异；
 * {@link #repair()} 按引擎运行时重建读模型（补齐缺失待办、关闭多余待办）。定时作业每 10 分钟执行一次只读对账。
 */
public class FlowReconciler {

    public enum DiffType {
        /** 引擎有活动任务而读模型缺失 */
        TASK_MISSING,
        /** 读模型待办在引擎中已不存在 */
        TASK_STALE,
        /** 实例为进行中但引擎中已无流程实例 */
        INSTANCE_ORPHAN,
        /** 流程实例未成功启动（待重试或失败） */
        START_PENDING,
        /** 流程已结束但业务回调未完成 */
        CALLBACK_PENDING
    }

    public record Diff(DiffType type, Long flowInstanceId, String procInstId, String taskId, String bizType, String bizId, String detail) {
    }

    public record Report(int checkedInstances, List<Diff> diffs, int repaired) {
    }

    private final JdbcTemplate jdbc;
    private final ProcessEngine engine;
    private final FlowRuntime runtime;
    private final TransactionTemplate tx;

    public FlowReconciler(JdbcTemplate jdbc, ProcessEngine engine, FlowRuntime runtime, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.runtime = runtime;
        this.tx = new TransactionTemplate(txm);
    }

    public Report check() {
        List<Diff> diffs = new ArrayList<>();
        List<FlowInstances.Instance> running = runtime.instances().runningAll();
        for (FlowInstances.Instance i : running) {
            boolean alive = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(i.procInstId()).count() > 0;
            if (!alive) {
                diffs.add(new Diff(DiffType.INSTANCE_ORPHAN, i.id(), i.procInstId(), null, i.bizType(), i.bizId(), "引擎中流程实例已不存在"));
                continue;
            }
            Set<String> engineTasks = new HashSet<>();
            for (Task t : engine.getTaskService().createTaskQuery().processInstanceId(i.procInstId()).list()) {
                engineTasks.add(t.getId());
                Integer n = jdbc.queryForObject("select count(*) from biz_task where task_id = ? and status = 'TODO'", Integer.class, t.getId());
                if (n == null || n == 0) {
                    diffs.add(new Diff(DiffType.TASK_MISSING, i.id(), i.procInstId(), t.getId(), i.bizType(), i.bizId(),
                        "节点「" + t.getName() + "」的待办在读模型中缺失"));
                }
            }
            for (Map<String, Object> r : jdbc.queryForList("""
                select task_id from biz_task where proc_inst_id = ? and status = 'TODO' and task_id is not null""", i.procInstId())) {
                String tid = (String) r.get("task_id");
                if (!engineTasks.contains(tid)) {
                    diffs.add(new Diff(DiffType.TASK_STALE, i.id(), i.procInstId(), tid, i.bizType(), i.bizId(), "读模型待办在引擎中已不存在"));
                }
            }
        }
        jdbc.query("select * from biz_flow_instance where sync_status <> 'SYNCED' and status = 'RUNNING'", rs -> {
            diffs.add(new Diff(DiffType.START_PENDING, rs.getLong("id"), rs.getString("proc_inst_id"), null, rs.getString("biz_type"),
                rs.getString("biz_id"), rs.getString("sync_status") + "：" + rs.getString("last_error")));
        });
        jdbc.query("select * from biz_flow_callback_log where status <> 'DONE'", rs -> {
            diffs.add(new Diff(DiffType.CALLBACK_PENDING, null, rs.getString("proc_inst_id"), rs.getString("task_id"), rs.getString("biz_type"),
                rs.getString("biz_id"), rs.getString("operation") + " " + rs.getString("status") + "：" + rs.getString("last_error")));
        });
        return new Report(running.size(), diffs, 0);
    }

    /** 按引擎运行时修复读模型；启动与回调类差异交给重试，不在此改写。 */
    public Report repair() {
        Report r = check();
        int fixed = 0;
        for (Diff d : r.diffs()) {
            switch (d.type()) {
                case TASK_MISSING -> {
                    Task t = engine.getTaskService().createTaskQuery().taskId(d.taskId()).singleResult();
                    if (t != null) {
                        tx.executeWithoutResult(st -> rebuild(t, d.flowInstanceId()));
                        fixed++;
                    }
                }
                case TASK_STALE -> {
                    fixed += jdbc.update("update biz_task set status = 'CANCELED', updated_at = now(), version = version + 1 where task_id = ? and status = 'TODO'",
                        d.taskId());
                }
                default -> {
                }
            }
        }
        if (fixed > 0) runtime.retryPending();
        return new Report(r.checkedInstances(), r.diffs(), fixed);
    }

    private void rebuild(Task t, long flowInstanceId) {
        FlowInstances.Instance inst = runtime.instances().get(flowInstanceId);
        List<Long> cands = new ArrayList<>();
        for (IdentityLink l : engine.getTaskService().getIdentityLinksForTask(t.getId())) {
            if (IdentityLinkType.CANDIDATE.equals(l.getType()) && l.getUserId() != null) cands.add(Long.parseLong(l.getUserId()));
        }
        if (cands.isEmpty() && t.getAssignee() != null) cands.add(Long.parseLong(t.getAssignee()));
        Object exc = engine.getRuntimeService().getVariable(t.getExecutionId(), "exc_" + t.getTaskDefinitionKey());
        FlowDefinition.Node n = runtime.definition(inst).node(t.getTaskDefinitionKey());
        java.time.LocalDateTime created = java.time.LocalDateTime.ofInstant(t.getCreateTime().toInstant(), java.time.ZoneId.systemDefault());
        jdbc.update("""
            insert into biz_task (task_id, kind, flow_instance_id, proc_inst_id, biz_type, biz_id, node_key, node_name, assignee_party, candidates,
                                  due_at, status, exception_reason, org_unit_id, flow_version, form_version, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'TODO', ?, ?, ?, ?, ?, now())
            on conflict (task_id) do update set status = 'TODO', candidates = excluded.candidates, assignee_party = excluded.assignee_party,
                updated_at = now(), version = biz_task.version + 1""",
            t.getId(), exc == null ? "TASK" : "EXCEPTION", inst.id(), inst.procInstId(), inst.bizType(), inst.bizId(), t.getTaskDefinitionKey(),
            t.getName(), t.getAssignee() == null ? null : Long.parseLong(t.getAssignee()), TaskSync.json(cands),
            n != null && n.timeout() != null ? created.plusHours(n.timeout().hours()) : null, exc == null ? null : exc.toString(),
            inst.orgUnitId(), inst.flowVersion(), inst.formVersion(), created);
    }
}
