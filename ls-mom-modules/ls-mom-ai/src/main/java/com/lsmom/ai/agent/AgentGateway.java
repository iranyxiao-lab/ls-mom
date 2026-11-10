package com.lsmom.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.AgentCall;
import com.lsmom.action.api.Exposure;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.api.OperationResult;
import com.lsmom.action.gateway.OperationAudit;
import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.action.registry.OperationDefinition;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.core.error.LsBizException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 运行期 Agent 门面：工具清单由 Operation 注册表按暴露等级自动生成；调用一律经 Operation 网关
 * （权限 ∩、数据域、暴露等级、禁用清单、草稿与审批检查都在网关内执行），Agent 不能访问通用 CRUD、流程引擎或数据库。
 */
@Service
public class AgentGateway {

    /** 工具描述（MCP 与 REST 共用）。 */
    public record Tool(String name, String title, String description, String concept, String exposure, String risk, boolean targetRequired,
                       boolean versionRequired, JsonNode inputSchema) {
    }

    public record Call(JsonNode input, String targetId, Integer version, String idempotencyKey, boolean simulate, String approvalId, String batchId) {
    }

    private final OperationRegistry operations;
    private final OperationGateway gateway;
    private final AgentApprovals approvals;
    private final OperationAudit audit;

    public AgentGateway(OperationRegistry operations, OperationGateway gateway, AgentApprovals approvals, OperationAudit audit) {
        this.operations = operations;
        this.gateway = gateway;
        this.approvals = approvals;
        this.audit = audit;
    }

    /** 当前调用主体可用的工具：暴露等级在白名单内、未命中禁用清单、且有效权限包含其权限点。 */
    public List<Tool> tools(ActorContext actor) {
        List<Tool> out = new ArrayList<>();
        for (OperationDefinition op : operations.listPublished()) {
            if (!gateway.agentPolicy().isTool(op, gateway.guardCodes(op))) continue;
            if (!actor.hasPermission(op.permission())) continue;
            out.add(tool(op));
        }
        out.sort(Comparator.comparing(Tool::name));
        return out;
    }

    public Tool tool(OperationDefinition op) {
        boolean transition = op.kind() == OperationDefinition.Kind.TRANSITION;
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.set("input", op.inputSchema() == null ? JsonNodeFactory.instance.objectNode().put("type", "object") : op.inputSchema());
        props.putObject("targetId").put("type", "string").put("description", "目标对象 ID");
        props.putObject("version").put("type", "integer").put("description", "目标对象当前版本（乐观锁）");
        props.putObject("idempotencyKey").put("type", "string");
        props.putObject("batchId").put("type", "string").put("description", "批量草稿的批次号");
        if (op.exposure() == Exposure.APPROVAL_REQUIRED) props.putObject("approvalId").put("type", "string").put("description", "真人批准的一次性审批单 ID");
        var req = schema.putArray("required");
        req.add("input");
        if (transition) req.add("targetId").add("version");
        String hint = switch (op.exposure()) {
            case QUERY -> "只读查询。";
            case DRAFT -> "只产生草稿，须由真人提交。";
            case SIMULATE -> "只能模拟：执行全部校验与守卫并返回结果，不写入任何数据。";
            case APPROVAL_REQUIRED -> "须先申请审批，经真人批准后凭一次性审批单执行；输入或版本变化需重新批准。";
            default -> "";
        };
        String desc = (op.description() == null || op.description().isBlank() ? "" : op.description() + "。") + hint;
        return new Tool(op.code(), op.name(), desc, op.concept(), op.exposure().name(), op.risk().name(), transition, transition || op.requiresVersion(),
            schema);
    }

    /** 调用工具。SIMULATE 等级强制模拟；APPROVAL_REQUIRED 等级先核对并消费审批单。 */
    public OperationResult call(String code, Call c, ActorContext actor, AgentRegistry.Identity agent) {
        OperationDefinition op = operations.find(code).orElse(null);
        JsonNode input = c.input() == null ? JsonNodeFactory.instance.objectNode() : c.input();
        boolean simulate = c.simulate() || (op != null && op.exposure() == Exposure.SIMULATE);
        String approvalId = null;
        if (op != null && op.exposure() == Exposure.APPROVAL_REQUIRED && !simulate
            && gateway.agentPolicy().denied(op, gateway.guardCodes(op)).isEmpty()) {
            try {
                approvalId = approvals.consume(c.approvalId(), agent, actor, code, input, c.targetId(), c.version()).id();
            } catch (LsBizException e) {
                audit.writeIndependently(new OperationAudit.Entry(code, actor, op.concept(), c.targetId(), c.version(), "FAILED", e.getCode(),
                    e.getMessage(), OperationGateway.inputDigest(input), null, 0, c.idempotencyKey()));
                throw e;
            }
        }
        OperationRequest req = new OperationRequest(input, c.targetId(), c.version(), c.idempotencyKey(), simulate);
        String batch = c.batchId() == null || c.batchId().isBlank() ? null : c.batchId().length() > 64 ? c.batchId().substring(0, 64) : c.batchId();
        AgentCall call = new AgentCall(batch, approvalId);
        return AgentCall.with(call, () -> gateway.execute(code, req, actor));
    }

    /** 工具结果的统一形态（来源标注为 Agent）。 */
    public static Map<String, Object> view(OperationResult r, ActorContext actor) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("operation", r.operation());
        m.put("targetId", r.targetId());
        m.put("version", r.version());
        m.put("status", r.status());
        m.put("simulated", r.simulated());
        m.put("replayed", r.replayed());
        m.put("data", r.data());
        m.put("warnings", r.warnings());
        m.put("source", "AGENT");
        m.put("agentParty", actor.actorParty());
        m.put("onBehalfOf", actor.onBehalfOf());
        return m;
    }
}
