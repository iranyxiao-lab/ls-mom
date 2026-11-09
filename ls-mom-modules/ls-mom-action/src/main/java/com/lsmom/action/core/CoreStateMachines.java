package com.lsmom.action.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.action.state.StateMachineDefinition;
import com.lsmom.action.state.StateMachineDefinition.Transition;

import java.util.List;

/** 核心对象的状态机定义（启动时发布到 meta_flow，并注册迁移 Operation）。 */
public final class CoreStateMachines {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CoreStateMachines() {
    }

    static JsonNode schema(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String NONE = "{\"type\":\"object\"}";
    private static final String REASON_REQUIRED = """
        {"type":"object","required":["reason"],"properties":{"reason":{"type":"string","minLength":1,"maxLength":500}}}""";
    private static final String REASON_OPTIONAL = """
        {"type":"object","properties":{"reason":{"type":"string","maxLength":500}}}""";
    private static final String CONCLUSION = """
        {"type":"object","required":["conclusion"],"properties":{"conclusion":{"type":"string","minLength":1,"maxLength":1000}}}""";

    private static Transition t(String op, String name, List<String> from, String to, String perm, String schema, List<String> guards) {
        return new Transition(op, name, from, to, guards, perm, "C1", schema(schema), "DRAFT");
    }

    public static StateMachineDefinition work() {
        String w = "ls.core.Work.";
        List<String> open = List.of("DRAFT", "READY", "ASSIGNED", "IN_PROGRESS", "BLOCKED");
        return new StateMachineDefinition("ls.core.Work.lifecycle", "ls.core.Work", "工作生命周期",
            List.of("DRAFT", "READY", "ASSIGNED", "IN_PROGRESS", "PENDING_CONFIRM", "DONE", "BLOCKED", "CANCELLED"), "DRAFT", List.of(
            t(w + "ready", "就绪", List.of("DRAFT"), "READY", "lsmom:work:edit", NONE, List.of()),
            t(w + "assign", "分派", List.of("READY", "ASSIGNED"), "ASSIGNED", "lsmom:work:assign", """
                {"type":"object","required":["targets"],"properties":{"targets":{"type":"array","minItems":1,"items":{
                  "type":"object","required":["resourceType","resourceRef"],"properties":{
                    "resourceType":{"enum":["PARTY","TEAM","SITE","EQUIPMENT"]},"resourceRef":{"type":"string","minLength":1},
                    "role":{"enum":["RESPONSIBLE","ASSIST","REVIEW","CC"]},
                    "plannedStart":{"type":"string"},"plannedEnd":{"type":"string"}}}}}}""", List.of()),
            t(w + "start", "开始", List.of("ASSIGNED"), "IN_PROGRESS", "lsmom:work:execute", NONE, List.of()),
            t(w + "block", "阻塞", List.of("IN_PROGRESS"), "BLOCKED", "lsmom:work:execute", REASON_REQUIRED, List.of()),
            t(w + "unblock", "解除阻塞", List.of("BLOCKED"), "IN_PROGRESS", "lsmom:work:execute", REASON_OPTIONAL, List.of()),
            t(w + "submitConfirm", "提交确认", List.of("IN_PROGRESS"), "PENDING_CONFIRM", "lsmom:work:execute", NONE, List.of()),
            t(w + "confirm", "确认完成", List.of("PENDING_CONFIRM"), "DONE", "lsmom:work:confirm", NONE, List.of()),
            t(w + "rejectConfirm", "退回", List.of("PENDING_CONFIRM"), "IN_PROGRESS", "lsmom:work:confirm", REASON_REQUIRED, List.of()),
            t(w + "cancel", "取消", open, "CANCELLED", "lsmom:work:cancel", REASON_REQUIRED, List.of())));
    }

    public static StateMachineDefinition issue() {
        String i = "ls.core.Issue.";
        return new StateMachineDefinition("ls.core.Issue.lifecycle", "ls.core.Issue", "异常闭环",
            List.of("DRAFT", "OPEN", "IN_RECTIFICATION", "PENDING_VERIFY", "CLOSED", "REJECTED", "CANCELLED"), "DRAFT", List.of(
            t(i + "submit", "提交", List.of("DRAFT"), "OPEN", "lsmom:issue:raise", NONE, List.of(CoreGuards.EVIDENCE_REQUIRED)),
            t(i + "startRectification", "开始整改", List.of("OPEN"), "IN_RECTIFICATION", "lsmom:issue:handle", """
                {"type":"object","properties":{"ownerParty":{"type":"integer"},"dueAt":{"type":"string"}}}""", List.of()),
            t(i + "submitVerify", "提交验证", List.of("IN_RECTIFICATION"), "PENDING_VERIFY", "lsmom:issue:handle", NONE,
                List.of(CoreGuards.RECTIFICATION_DONE)),
            t(i + "verifyPass", "验证通过", List.of("PENDING_VERIFY"), "CLOSED", "lsmom:issue:verify", CONCLUSION,
                List.of(CoreGuards.VERIFIER_SEPARATION)),
            t(i + "verifyFail", "验证不通过", List.of("PENDING_VERIFY"), "IN_RECTIFICATION", "lsmom:issue:verify", CONCLUSION, List.of()),
            t(i + "reject", "驳回", List.of("OPEN"), "REJECTED", "lsmom:issue:handle", REASON_REQUIRED, List.of()),
            t(i + "cancel", "取消", List.of("DRAFT", "OPEN"), "CANCELLED", "lsmom:issue:raise", REASON_REQUIRED, List.of())));
    }
}
