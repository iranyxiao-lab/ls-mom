package com.lsmom.ai.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.ai.agent.AgentApprovals;
import com.lsmom.ai.agent.AgentGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 调用入口（REST）：须同时携带真人登录令牌（Agent 代表的人）与 {@code X-Agent-Key}。
 * 同样的能力也以 MCP 工具发布（/mcp）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentToolController {

    public record CallBody(JsonNode input, String targetId, Integer version, String idempotencyKey, Boolean simulate, String approvalId,
                           String batchId) {
    }

    public record ApprovalBody(String operation, JsonNode input, String targetId, Integer version, String reason) {
    }

    private final AgentRequests requests;
    private final AgentGateway agents;
    private final AgentApprovals approvals;

    public AgentToolController(AgentRequests requests, AgentGateway agents, AgentApprovals approvals) {
        this.requests = requests;
        this.agents = agents;
        this.approvals = approvals;
    }

    @GetMapping("/tools")
    public R<List<AgentGateway.Tool>> tools(HttpServletRequest req) {
        return R.ok(agents.tools(requests.resolve(req).actor()));
    }

    @PostMapping("/tools/{code}/call")
    public R<Map<String, Object>> call(@PathVariable String code, @RequestBody CallBody b, HttpServletRequest req) {
        AgentRequests.Resolved r = requests.resolve(req);
        var res = agents.call(code, new AgentGateway.Call(b.input(), b.targetId(), b.version(), b.idempotencyKey(), Boolean.TRUE.equals(b.simulate()),
            b.approvalId(), b.batchId()), r.actor(), r.principal().identity());
        return R.ok(AgentGateway.view(res, r.actor()));
    }

    @PostMapping("/approvals")
    public R<AgentApprovals.Approval> requestApproval(@RequestBody ApprovalBody b, HttpServletRequest req) {
        AgentRequests.Resolved r = requests.resolve(req);
        return R.ok(approvals.request(r.principal().identity(), r.actor(), b.operation(), b.input(), b.targetId(), b.version(), b.reason()));
    }

    @GetMapping("/approvals/{id}")
    public R<AgentApprovals.Approval> approval(@PathVariable String id, HttpServletRequest req) {
        AgentRequests.Resolved r = requests.resolve(req);
        AgentApprovals.Approval a = approvals.get(id);
        if (a.agentId() != r.principal().identity().id()) throw com.lsmom.core.error.LsBizException.notFound("审批单", id);
        return R.ok(a);
    }
}
