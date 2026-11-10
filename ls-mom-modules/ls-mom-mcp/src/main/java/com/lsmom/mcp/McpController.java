package com.lsmom.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.ai.agent.web.AgentRequests;
import com.lsmom.core.error.LsBizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * MCP Streamable HTTP 端点（{@code /mcp}）：每个 JSON-RPC 消息一个 POST，请求返回 application/json，通知返回 202；
 * 不提供服务端 SSE 流（GET / DELETE 返回 405），无会话。鉴权：真人登录令牌（Agent 代表的人）＋ {@code X-Agent-Key}。
 */
@RestController
public class McpController {

    private final McpServer server;
    private final AgentRequests requests;
    private final List<String> allowedOrigins;

    public McpController(McpServer server, AgentRequests requests, @Value("${lsmom.mcp.allowed-origins:}") String allowedOrigins) {
        this.server = server;
        this.requests = requests;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> post(@RequestBody JsonNode msg, HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin != null && !allowedOrigins.contains(origin) && !sameOrigin(origin, req)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(McpServer.error(null, -32600, "Origin 不允许：" + origin, null));
        }
        String version = req.getHeader("MCP-Protocol-Version");
        if (version != null && !McpServer.VERSIONS.contains(version)) {
            return ResponseEntity.badRequest().body(McpServer.error(null, -32600, "不支持的 MCP 协议版本：" + version, null));
        }
        if (!msg.isObject() || !"2.0".equals(msg.path("jsonrpc").asText())) {
            return ResponseEntity.badRequest().body(McpServer.error(null, -32600, "Invalid Request", null));
        }
        // 通知或响应：接受即 202
        if (!msg.has("method") || !msg.has("id")) return ResponseEntity.accepted().build();
        McpServer.Caller caller;
        try {
            AgentRequests.Resolved r = requests.resolve(req);
            caller = new McpServer.Caller(r.principal().identity(), r.actor());
        } catch (LsBizException e) {
            HttpStatus status = switch (e.getCode()) {
                case "AGENT_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
                case "UNAUTHENTICATED", "AGENT_KEY_INVALID", "AGENT_KEY_EXPIRED", "AGENT_KEY_REVOKED", "AGENT_DISABLED" -> HttpStatus.UNAUTHORIZED;
                default -> HttpStatus.FORBIDDEN;
            };
            return ResponseEntity.status(status).body(McpServer.error(msg.get("id"), -32001, e.getMessage(),
                McpServer.JSON.createObjectNode().put("errorCode", e.getCode())));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(server.handle(msg, caller));
    }

    /** 浏览器同源调用：Origin 的主机与请求的 Host（经反向代理时为 X-Forwarded-Host）一致。 */
    static boolean sameOrigin(String origin, HttpServletRequest req) {
        String host = req.getHeader("X-Forwarded-Host") != null ? req.getHeader("X-Forwarded-Host") : req.getHeader("Host");
        if (host == null) return false;
        try {
            java.net.URI u = java.net.URI.create(origin);
            String o = u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            return host.equalsIgnoreCase(o) || host.equalsIgnoreCase(u.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @GetMapping("/mcp")
    public ResponseEntity<Void> stream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @DeleteMapping("/mcp")
    public ResponseEntity<Void> terminate() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }
}
