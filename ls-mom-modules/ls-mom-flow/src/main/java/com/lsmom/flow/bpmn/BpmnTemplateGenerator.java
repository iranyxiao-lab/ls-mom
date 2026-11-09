package com.lsmom.flow.bpmn;

import com.lsmom.flow.engine.LsEndListener;
import com.lsmom.flow.engine.LsServiceTask;
import com.lsmom.flow.model.FlowDefinition;
import com.lsmom.flow.model.FlowDefinition.Node;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowableListener;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.TerminateEventDefinition;
import org.flowable.bpmn.model.TimerEventDefinition;
import org.flowable.bpmn.model.UserTask;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 固定模板：流程配置 → BPMN 2.0。只产生开始/结束事件、人工任务、排他/并行网关、非中断定时边界事件，
 * 服务任务一律为 {@link LsServiceTask}，结果结束事件挂 {@link LsEndListener}，表达式只用白名单形式。
 *
 * <pre>
 * start → initiator（发起人，启动时自动完成；退回时回到这里）→ 节点… → end_approved
 * 人工节点 n：resolve_n（解析办理人 → cand_n）→ n（单人：候选人；或签/会签：多实例）→ gw_n（outcome == REJECTED → end_rejected）
 * 条件分支：route_n（计算 route_n）→ split_n → 各分支 → join_n；并行：fork_n → 各分支 → join_n
 * 超时：n 上的非中断定时边界 timer_n → timeout_n（提醒/升级）→ timeout_end_n
 * end_rejected 为终止结束事件（并行分支中驳回也结束整个流程）
 * </pre>
 */
public class BpmnTemplateGenerator {

    public static final String INITIATOR = "initiator";
    public static final String END_APPROVED = "end_approved";
    public static final String END_REJECTED = "end_rejected";

    private Process process;
    private final Set<String> outcomeGateways = new HashSet<>();
    private int flowSeq;

    public synchronized String generate(FlowDefinition def) {
        return new String(new BpmnXMLConverter().convertToXML(model(def)), StandardCharsets.UTF_8);
    }

    public synchronized BpmnModel model(FlowDefinition def) {
        BpmnModel model = new BpmnModel();
        model.setTargetNamespace("https://lsmom.local/flow");
        process = new Process();
        process.setId(def.processKey());
        process.setName(def.name());
        process.setExecutable(true);
        model.addProcess(process);
        outcomeGateways.clear();
        flowSeq = 0;

        StartEvent start = add(new StartEvent(), "start", "开始");
        UserTask initiator = add(new UserTask(), INITIATOR, "发起人");
        initiator.setCandidateUsers(List.of("${cand_initiator}"));
        connect(start.getId(), initiator.getId());

        EndEvent approved = add(new EndEvent(), END_APPROVED, "通过");
        approved.getExecutionListeners().add(endListener("APPROVED"));
        EndEvent rejected = add(new EndEvent(), END_REJECTED, "驳回");
        rejected.getEventDefinitions().add(new TerminateEventDefinition());
        rejected.getExecutionListeners().add(endListener("REJECTED"));

        String exit = chain(def.nodes(), initiator.getId());
        connect(exit, approved.getId());
        return model;
    }

    private String chain(List<Node> nodes, String from) {
        String prev = from;
        if (nodes == null) return prev;
        for (Node n : nodes) {
            String[] io = node(n);
            connect(prev, io[0]);
            prev = io[1];
        }
        return prev;
    }

    /** 返回 {入口, 出口}。 */
    private String[] node(Node n) {
        String k = n.key();
        switch (n.type()) {
            case FILL, AUDIT, CONFIRM -> {
                ServiceTask resolve = service("resolve_" + k, "解析办理人：" + n.name(), "RESOLVE", k);
                UserTask task = add(new UserTask(), k, n.name());
                task.setCategory(n.type().name());
                if (n.modeOrSingle() == FlowDefinition.Mode.SINGLE) {
                    task.setCandidateUsers(List.of("${cand_" + k + "}"));
                } else {
                    MultiInstanceLoopCharacteristics mi = new MultiInstanceLoopCharacteristics();
                    mi.setSequential(false);
                    mi.setInputDataItem("${cand_" + k + "}");
                    mi.setElementVariable("assignee");
                    mi.setCompletionCondition(n.modeOrSingle() == FlowDefinition.Mode.OR ? "${nrOfCompletedInstances >= 1}"
                        : "${outcome == 'REJECTED'}");
                    task.setLoopCharacteristics(mi);
                    task.setAssignee("${assignee}");
                }
                connect(resolve.getId(), task.getId());
                if (n.timeout() != null && n.timeout().hours() != null && n.timeout().hours() > 0) {
                    BoundaryEvent timer = add(new BoundaryEvent(), "timer_" + k, "超时");
                    timer.setAttachedToRef(task);
                    timer.setAttachedToRefId(task.getId());
                    timer.setCancelActivity(false);
                    TimerEventDefinition def = new TimerEventDefinition();
                    def.setTimeDuration("PT" + n.timeout().hours() + "H");
                    timer.getEventDefinitions().add(def);
                    task.getBoundaryEvents().add(timer);
                    ServiceTask onTimeout = service("timeout_" + k, "超时处理：" + n.name(), "TIMEOUT", k);
                    EndEvent end = add(new EndEvent(), "timeout_end_" + k, "超时处理完成");
                    connect(timer.getId(), onTimeout.getId());
                    connect(onTimeout.getId(), end.getId());
                }
                ExclusiveGateway gw = add(new ExclusiveGateway(), "gw_" + k, "审核结果");
                outcomeGateways.add(gw.getId());
                connect(task.getId(), gw.getId());
                conditional(gw.getId(), END_REJECTED, "${outcome == 'REJECTED'}");
                return new String[]{resolve.getId(), gw.getId()};
            }
            case CC -> {
                ServiceTask t = service(k, n.name(), "CC", k);
                return new String[]{t.getId(), t.getId()};
            }
            case SERVICE -> {
                ServiceTask t = service(k, n.name(), "OPERATION", k);
                return new String[]{t.getId(), t.getId()};
            }
            case CONDITION -> {
                ServiceTask route = service("route_" + k, "条件判断：" + n.name(), "ROUTE", k);
                ExclusiveGateway split = add(new ExclusiveGateway(), "split_" + k, n.name());
                ExclusiveGateway join = add(new ExclusiveGateway(), "join_" + k, n.name() + "（汇合）");
                connect(route.getId(), split.getId());
                List<FlowDefinition.Branch> branches = n.branches();
                for (int i = 0; i < branches.size(); i++) {
                    FlowDefinition.Branch b = branches.get(i);
                    String first = b.nodes() == null || b.nodes().isEmpty() ? join.getId() : null;
                    String entry;
                    String exit;
                    if (first != null) {
                        entry = join.getId();
                        exit = null;
                    } else {
                        String[] io0 = node(b.nodes().get(0));
                        entry = io0[0];
                        exit = chain(b.nodes().subList(1, b.nodes().size()), io0[1]);
                    }
                    SequenceFlow f = conditional(split.getId(), entry, b.when() == null || b.when().isBlank() ? null : "${route_" + k + " == " + i + "}");
                    if (b.when() == null || b.when().isBlank()) split.setDefaultFlow(f.getId());
                    if (exit != null) connect(exit, join.getId());
                }
                return new String[]{route.getId(), join.getId()};
            }
            case PARALLEL -> {
                ParallelGateway fork = add(new ParallelGateway(), "fork_" + k, n.name());
                ParallelGateway join = add(new ParallelGateway(), "join_" + k, n.name() + "（汇合）");
                for (FlowDefinition.Branch b : n.branches()) {
                    String exit = chain(b.nodes(), fork.getId());
                    connect(exit, join.getId());
                }
                return new String[]{fork.getId(), join.getId()};
            }
            default -> throw new IllegalArgumentException("不支持的节点类型：" + n.type());
        }
    }

    private ServiceTask service(String id, String name, String kind, String node) {
        ServiceTask t = add(new ServiceTask(), id, name);
        t.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_CLASS);
        t.setImplementation(LsServiceTask.class.getName());
        t.getFieldExtensions().add(field("kind", kind));
        t.getFieldExtensions().add(field("node", node));
        return t;
    }

    private static FieldExtension field(String name, String value) {
        FieldExtension f = new FieldExtension();
        f.setFieldName(name);
        f.setStringValue(value);
        return f;
    }

    private static FlowableListener endListener(String outcome) {
        FlowableListener l = new FlowableListener();
        l.setEvent("start"); // 到达结束事件即触发（终止结束事件不会触发 end）
        l.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_CLASS);
        l.setImplementation(LsEndListener.class.getName());
        l.getFieldExtensions().add(field("outcome", outcome));
        return l;
    }

    private <T extends FlowElement> T add(T e, String id, String name) {
        e.setId(id);
        e.setName(name);
        process.addFlowElement(e);
        return e;
    }

    private SequenceFlow connect(String from, String to) {
        SequenceFlow f = conditional(from, to, null);
        if (outcomeGateways.contains(from)) {
            ExclusiveGateway gw = (ExclusiveGateway) process.getFlowElement(from);
            if (gw.getDefaultFlow() == null) gw.setDefaultFlow(f.getId());
        }
        return f;
    }

    private SequenceFlow conditional(String from, String to, String condition) {
        SequenceFlow f = new SequenceFlow(from, to);
        f.setId("flow_" + (++flowSeq));
        if (condition != null) f.setConditionExpression(condition);
        process.addFlowElement(f);
        return f;
    }
}
