package com.lsmom.flow.bpmn;

import com.lsmom.core.error.LsBizException;
import com.lsmom.flow.engine.ExpressionWhitelist;
import com.lsmom.flow.engine.LsEndListener;
import com.lsmom.flow.engine.LsServiceTask;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.EventDefinition;
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

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 部署前静态扫描（设计器生成物与代码库中的研发流程都经过这里）。只允许：开始/结束（含终止）事件、人工任务、
 * LS 服务任务、排他/并行网关、顺序流、定时边界事件；拒绝脚本任务、任意委托类、委托表达式与方法表达式、
 * 带 type 的服务任务（http/mail/shell……）、调用活动与子流程、外部 URL；表达式只能是白名单形式。
 */
public class BpmnSafetyScanner {

    private static final Set<Class<?>> ALLOWED = Set.of(StartEvent.class, EndEvent.class, UserTask.class, ServiceTask.class,
        ExclusiveGateway.class, ParallelGateway.class, SequenceFlow.class, BoundaryEvent.class);
    private static final Set<String> ALLOWED_CLASSES = Set.of(LsServiceTask.class.getName(), LsEndListener.class.getName());
    private static final Pattern URL = Pattern.compile("(?i)\\b(https?|ftp|file|jdbc|ldap)://");

    public List<String> scan(String xml) {
        List<String> issues = new ArrayList<>();
        String withoutNs = xml.replaceAll("xmlns(:\\w+)?=\"[^\"]*\"", "").replaceAll("(targetNamespace|typeLanguage|expressionLanguage)=\"[^\"]*\"", "");
        if (URL.matcher(withoutNs).find()) issues.add("流程定义包含外部 URL");
        BpmnModel model;
        try {
            XMLInputFactory f = XMLInputFactory.newInstance();
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            XMLStreamReader r = f.createXMLStreamReader(new StringReader(xml));
            model = new BpmnXMLConverter().convertToBpmnModel(r);
        } catch (Exception e) {
            issues.add("无法解析流程定义：" + e.getMessage());
            return issues;
        }
        if (model.getProcesses().isEmpty()) issues.add("流程定义中没有流程");
        for (Process p : model.getProcesses()) {
            for (FlowElement e : p.getFlowElements()) check(e, issues);
            p.getExecutionListeners().forEach(l -> listener(p.getId(), l, issues));
        }
        return issues;
    }

    public void require(String xml) {
        List<String> issues = scan(xml);
        if (!issues.isEmpty()) {
            throw new LsBizException("FLOW_UNSAFE", "流程定义未通过安全扫描：" + issues.get(0), Map.of("issues", issues));
        }
    }

    private void check(FlowElement e, List<String> issues) {
        String id = e.getId();
        if (!ALLOWED.contains(e.getClass())) {
            issues.add(id + "：不允许的元素类型 " + e.getClass().getSimpleName());
            return;
        }
        e.getExecutionListeners().forEach(l -> listener(id, l, issues));
        if (e instanceof Activity a) {
            if (a.getLoopCharacteristics() != null) {
                MultiInstanceLoopCharacteristics mi = a.getLoopCharacteristics();
                expr(id, mi.getInputDataItem(), issues);
                expr(id, mi.getCollectionString(), issues);
                expr(id, mi.getLoopCardinality(), issues);
                expr(id, mi.getCompletionCondition(), issues);
            }
        }
        if (e instanceof UserTask t) {
            expr(id, t.getAssignee(), issues);
            expr(id, t.getOwner(), issues);
            expr(id, t.getDueDate(), issues);
            expr(id, t.getPriority(), issues);
            expr(id, t.getFormKey(), issues);
            t.getCandidateUsers().forEach(c -> expr(id, c, issues));
            t.getCandidateGroups().forEach(c -> expr(id, c, issues));
            t.getTaskListeners().forEach(l -> listener(id, l, issues));
            if (t.getSkipExpression() != null) issues.add(id + "：不允许跳过表达式");
        } else if (e instanceof ServiceTask s) {
            if (s.getSkipExpression() != null) issues.add(id + "：不允许跳过表达式");
            if (s.getType() != null && !s.getType().isBlank()) issues.add(id + "：不允许 " + s.getType() + " 类型的服务任务");
            if (!ImplementationType.IMPLEMENTATION_TYPE_CLASS.equals(s.getImplementationType()) || !ALLOWED_CLASSES.contains(s.getImplementation())) {
                issues.add(id + "：服务任务只能使用平台服务节点（" + s.getImplementationType() + " " + s.getImplementation() + "）");
            }
            for (FieldExtension f : s.getFieldExtensions()) {
                if (f.getExpression() != null) expr(id, f.getExpression(), issues);
            }
        } else if (e instanceof SequenceFlow f) {
            expr(id, f.getConditionExpression(), issues);
            if (f.getSkipExpression() != null) issues.add(id + "：不允许跳过表达式");
        } else if (e instanceof BoundaryEvent b) {
            for (EventDefinition d : b.getEventDefinitions()) {
                if (d instanceof TimerEventDefinition t) {
                    expr(id, t.getTimeDuration(), issues);
                    expr(id, t.getTimeDate(), issues);
                    expr(id, t.getTimeCycle(), issues);
                } else issues.add(id + "：边界事件只允许定时器");
            }
        } else if (e instanceof EndEvent end) {
            for (EventDefinition d : end.getEventDefinitions()) {
                if (!(d instanceof TerminateEventDefinition)) issues.add(id + "：结束事件只允许普通或终止");
            }
        } else if (e instanceof StartEvent s) {
            if (!s.getEventDefinitions().isEmpty()) issues.add(id + "：开始事件不允许事件定义");
        }
    }

    private void listener(String id, FlowableListener l, List<String> issues) {
        if (!ImplementationType.IMPLEMENTATION_TYPE_CLASS.equals(l.getImplementationType()) || !ALLOWED_CLASSES.contains(l.getImplementation())) {
            issues.add(id + "：监听器只能使用平台监听器（" + l.getImplementationType() + " " + l.getImplementation() + "）");
        }
    }

    private void expr(String id, String text, List<String> issues) {
        if (text != null && !ExpressionWhitelist.allowed(text)) issues.add(id + "：表达式不在白名单内 " + text);
    }
}
