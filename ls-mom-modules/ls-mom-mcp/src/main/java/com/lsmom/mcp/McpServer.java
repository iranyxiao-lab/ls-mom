package com.lsmom.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.ai.agent.AgentApprovals;
import com.lsmom.ai.agent.AgentGateway;
import com.lsmom.ai.agent.AgentRegistry;
import com.lsmom.core.error.LsBizException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP 协议处理（与传输无关）：initialize、ping、tools/list、tools/call。
 * 工具即 Agent 门面的白名单 Operation（暴露等级改为 DENY 并发布后自动从清单消失），另有两个审批辅助工具。
 */
@Component
public class McpServer {

    public static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final String REQUEST_APPROVAL = "ls.agent.requestApproval";
    static final String APPROVAL_STATUS = "ls.agent.approvalStatus";

    /** 调用主体：Agent 身份＋组合后的 OBO 调用主体。 */
    public record Caller(AgentRegistry.Identity agent, ActorContext actor) {
    }

    private final AgentGateway agents;
    private final AgentApprovals approvals;

    public McpServer(AgentGateway agents, AgentApprovals approvals) {
        this.agents = agents;
        this.approvals = approvals;
    }

    /** 处理一个 JSON-RPC 请求，返回响应对象。 */
    public ObjectNode handle(JsonNode msg, Caller caller) {
        JsonNode id = msg.get("id");
        String method = msg.path("method").asText();
        JsonNode params = msg.path("params");
        try {
            return switch (method) {
                case "initialize" -> ok(id, initialize(params));
                case "ping" -> ok(id, JsonNodeFactory.instance.objectNode());
                case "tools/list" -> ok(id, listTools(caller));
                case "tools/call" -> ok(id, callTool(params, caller));
                default -> error(id, -32601, "Method not found: " + method, null);
            };
        } catch (InvalidParams e) {
            return error(id, -32602, e.getMessage(), null);
        }
    }

    ObjectNode initialize(JsonNode params) {
        String asked = params.path("protocolVersion").asText("");
        ObjectNode r = JsonNodeFactory.instance.objectNode();
        r.put("protocolVersion", VERSIONS.contains(asked) ? asked : VERSIONS.get(0));
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        r.putObject("serverInfo").put("name", "ls-mom").put("title", "LS-MOM 运行期 Agent 工具").put("version", "1.0.0");
        r.put("instructions", "只能调用列出的工具；QUERY 只读，DRAFT 只产生草稿（由真人提交），SIMULATE 只模拟，"
            + "APPROVAL_REQUIRED 须先用 " + REQUEST_APPROVAL + " 申请、经真人批准后凭 approvalId 执行。");
        return r;
    }

    ObjectNode listTools(Caller caller) {
        ObjectNode r = JsonNodeFactory.instance.objectNode();
        ArrayNode tools = r.putArray("tools");
        for (AgentGateway.Tool t : agents.tools(caller.actor())) {
            ObjectNode o = tools.addObject();
            o.put("name", t.name());
            o.put("title", t.title());
            o.put("description", t.description() + "（暴露等级 " + t.exposure() + "，风险 " + t.risk() + "）");
            o.set("inputSchema", t.inputSchema());
            ObjectNode ann = o.putObject("annotations");
            boolean readOnly = "QUERY".equals(t.exposure()) || "SIMULATE".equals(t.exposure());
            ann.put("readOnlyHint", readOnly);
            ann.put("destructiveHint", false);
            ann.put("idempotentHint", readOnly);
            ann.put("openWorldHint", false);
        }
        ObjectNode req = tools.addObject();
        req.put("name", REQUEST_APPROVAL);
        req.put("title", "申请真人批准");
        req.put("description", "为 APPROVAL_REQUIRED 级工具申请一次性审批单；批准内容绑定工具、输入、目标与版本，变化须重新申请。");
        req.set("inputSchema", schema("""
            {"type":"object","required":["operation","input"],"properties":{"operation":{"type":"string"},"input":{"type":"object"},
             "targetId":{"type":"string"},"version":{"type":"integer"},"reason":{"type":"string","maxLength":500}}}"""));
        ObjectNode st = tools.addObject();
        st.put("name", APPROVAL_STATUS);
        st.put("title", "查询审批状态");
        st.put("description", "查询本 Agent 申请的审批单状态（PENDING / APPROVED / REJECTED / USED）。");
        st.set("inputSchema", schema("{\"type\":\"object\",\"required\":[\"approvalId\"],\"properties\":{\"approvalId\":{\"type\":\"string\"}}}"));
        return r;
    }

    ObjectNode callTool(JsonNode params, Caller caller) {
        String name = params.path("name").asText(null);
        if (name == null || name.isBlank()) throw new InvalidParams("缺少工具名称");
        JsonNode args = params.path("arguments").isObject() ? params.path("arguments") : JsonNodeFactory.instance.objectNode();
        try {
            Object data;
            if (REQUEST_APPROVAL.equals(name)) {
                data = approvals.request(caller.agent(), caller.actor(), args.path("operation").asText(), args.path("input"),
                    text(args, "targetId"), args.hasNonNull("version") ? args.get("version").asInt() : null, text(args, "reason"));
            } else if (APPROVAL_STATUS.equals(name)) {
                AgentApprovals.Approval a = approvals.get(args.path("approvalId").asText());
                if (a.agentId() != caller.agent().id()) throw LsBizException.notFound("审批单", args.path("approvalId").asText());
                data = Map.of("approvalId", a.id(), "status", a.status(), "expiresAt", String.valueOf(a.expiresAt()));
            } else {
                var r = agents.call(name, new AgentGateway.Call(args.path("input").isMissingNode() ? null : args.path("input"), text(args, "targetId"),
                    args.hasNonNull("version") ? args.get("version").asInt() : null, text(args, "idempotencyKey"), false, text(args, "approvalId"),
                    text(args, "batchId")), caller.actor(), caller.agent());
                data = AgentGateway.view(r, caller.actor());
            }
            return result(JSON.valueToTree(data), false);
        } catch (LsBizException e) {
            if ("OPERATION_NOT_FOUND".equals(e.getCode())) throw new InvalidParams("未知工具：" + name);
            ObjectNode err = JsonNodeFactory.instance.objectNode();
            err.put("errorCode", e.getCode());
            err.put("message", e.getMessage());
            err.set("details", JSON.valueToTree(e.getDetails()));
            return result(err, true);
        }
    }

    private static ObjectNode result(JsonNode structured, boolean isError) {
        ObjectNode r = JsonNodeFactory.instance.objectNode();
        r.putArray("content").addObject().put("type", "text").put("text", structured.toString());
        r.set("structuredContent", structured);
        r.put("isError", isError);
        return r;
    }

    static ObjectNode ok(JsonNode id, JsonNode result) {
        ObjectNode r = JsonNodeFactory.instance.objectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    static ObjectNode error(JsonNode id, int code, String message, JsonNode data) {
        ObjectNode r = JsonNodeFactory.instance.objectNode();
        r.put("jsonrpc", "2.0");
        if (id != null) r.set("id", id);
        ObjectNode e = r.putObject("error");
        e.put("code", code);
        e.put("message", message);
        if (data != null) e.set("data", data);
        return r;
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asText() : null;
    }

    private static JsonNode schema(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final class InvalidParams extends RuntimeException {
        InvalidParams(String m) {
            super(m);
        }
    }

}
