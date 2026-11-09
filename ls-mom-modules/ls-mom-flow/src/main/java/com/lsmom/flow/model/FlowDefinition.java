package com.lsmom.flow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 审批流配置（存于 meta_flow，kind = APPROVAL）。由树形配置器编辑，经 {@code BpmnTemplateGenerator} 固定模板生成 BPMN。
 * 节点为顺序列表；条件分支与并行节点内含若干分支，每个分支又是节点列表。
 *
 * @param code      流程编码（发布后不可修改），如 {@code acme.tool.loanApproval}
 * @param concept   业务 Concept
 * @param startOn   发起流程的业务 Operation（成功后启动实例），如 {@code acme.tool.LoanOrder.submit}
 * @param form      流程使用的表单（编码＋版本），人工节点据此引用字段策略
 * @param callbacks 流程结果对应的业务 Operation：approved、rejected 必填；returned、resubmitted、withdrawn 可选
 * @param orgUnitId 适用的业务组织（发布时校验办理人可解析）
 * @param adminRole 无人可办时接收异常待办的角色，默认 {@code flow_admin}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public record FlowDefinition(String code, Integer version, String name, String concept, String startOn, FormRef form,
                             Callbacks callbacks, Long orgUnitId, String adminRole, List<Node> nodes) {

    public static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public record FormRef(String code, Integer version) {
    }

    public record Callbacks(String approved, String rejected, String returned, String resubmitted, String withdrawn) {
    }

    public enum NodeType { FILL, AUDIT, CONFIRM, CC, SERVICE, CONDITION, PARALLEL }

    /** 办理模式：单人（候选人任一认领办理）、或签（任一人完成即完成）、会签（全部完成）。 */
    public enum Mode { SINGLE, OR, AND }

    public enum TimeoutAction { REMIND, ESCALATE, REMIND_ESCALATE }

    /**
     * 办理人策略。strategy：USER、ROLE、DEPT、TEAM、FIELD、INITIATOR、ORG_LEADER、DEPT_LEADER、WORKSHOP_LEADER、SUPERIOR_LEADER、DATA_SCOPE。
     * value 依策略为主体 ID 列表、角色编码、组织单元 ID 或业务字段名；of 为负责人类策略的参照（INITIATOR 或 BIZ_ORG）。
     */
    public record Assignee(String strategy, JsonNode value, String of) {
    }

    public record Timeout(Integer hours, TimeoutAction action) {
    }

    public record Branch(String name, String when, List<Node> nodes) {
    }

    /**
     * @param policy    引用表单 nodePolicies 中的策略键，缺省为节点键
     * @param operation 人工节点：办理时携带字段输入所执行的业务 Operation；服务节点：要执行的已发布 Operation
     * @param permission 人工节点办理所需功能权限（与办理人校验叠加）
     * @param fieldPolicy 节点相对表单的字段策略差异（readonly/hidden/editable/required 字段数组）；为空时使用表单 nodePolicies[policy]
     */
    public record Node(String key, NodeType type, String name, Assignee assignee, Mode mode, String policy, JsonNode fieldPolicy,
                       String operation, String permission, Timeout timeout, List<Branch> branches) {

        public boolean human() {
            return type == NodeType.FILL || type == NodeType.AUDIT || type == NodeType.CONFIRM;
        }

        public Mode modeOrSingle() {
            return mode == null ? Mode.SINGLE : mode;
        }

        public String policyKey() {
            return policy == null ? key : policy;
        }
    }

    public static FlowDefinition parse(JsonNode json) {
        try {
            return JSON.treeToValue(json, FlowDefinition.class);
        } catch (Exception e) {
            throw new com.lsmom.core.error.LsBizException("FLOW_INVALID", "流程配置格式错误：" + e.getMessage());
        }
    }

    public static FlowDefinition parse(String json) {
        try {
            return parse(JSON.readTree(json));
        } catch (com.lsmom.core.error.LsBizException e) {
            throw e;
        } catch (Exception e) {
            throw new com.lsmom.core.error.LsBizException("FLOW_INVALID", "流程配置格式错误：" + e.getMessage());
        }
    }

    public JsonNode toJson() {
        return JSON.valueToTree(this);
    }

    public FlowDefinition withVersion(int v) {
        return new FlowDefinition(code, v, name, concept, startOn, form, callbacks, orgUnitId, adminRole, nodes);
    }

    /** BPMN 流程键：编码中的点替换为下划线。 */
    public String processKey() {
        return code.replace('.', '_');
    }

    public String adminRoleOrDefault() {
        return adminRole == null || adminRole.isBlank() ? "flow_admin" : adminRole;
    }

    /** 深度优先遍历所有节点（含分支内）。 */
    public void walk(Consumer<Node> visitor) {
        walk(nodes, visitor);
    }

    private static void walk(List<Node> list, Consumer<Node> visitor) {
        if (list == null) return;
        for (Node n : list) {
            visitor.accept(n);
            if (n.branches() != null) n.branches().forEach(b -> walk(b.nodes(), visitor));
        }
    }

    public List<Node> allNodes() {
        List<Node> out = new ArrayList<>();
        walk(out::add);
        return out;
    }

    public Node node(String key) {
        return allNodes().stream().filter(n -> n.key().equals(key)).findFirst().orElse(null);
    }

    /** 回调编码 → Operation。 */
    public Map<String, String> callbackMap() {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        if (callbacks == null) return m;
        if (callbacks.approved() != null) m.put("approved", callbacks.approved());
        if (callbacks.rejected() != null) m.put("rejected", callbacks.rejected());
        if (callbacks.returned() != null) m.put("returned", callbacks.returned());
        if (callbacks.resubmitted() != null) m.put("resubmitted", callbacks.resubmitted());
        if (callbacks.withdrawn() != null) m.put("withdrawn", callbacks.withdrawn());
        return m;
    }
}
