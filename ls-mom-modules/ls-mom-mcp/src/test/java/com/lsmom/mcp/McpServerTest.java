package com.lsmom.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.core.CoreStateMachines;
import com.lsmom.action.gateway.OperationAudit;
import com.lsmom.action.state.StateMachineDefinition;
import com.lsmom.ai.agent.AgentActors;
import com.lsmom.ai.agent.AgentApprovals;
import com.lsmom.ai.agent.AgentGateway;
import com.lsmom.ai.agent.AgentRegistry;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** spec ai/agent-gateway「MCP 适配」：工具清单由暴露等级自动生成；暴露等级改为 DENY 并发布后工具消失。 */
class McpServerTest {

    final ActionFixture f = new ActionFixture();
    final CoreFixture.Plant plant = f.core.plant();
    final AgentRegistry registry = new AgentRegistry(f.core.jdbc, f.core.parties, f.core.clock, f.txm);
    final AgentApprovals approvals = new AgentApprovals(f.core.jdbc, f.operations, f.core.clock);
    final McpServer mcp = new McpServer(new AgentGateway(f.operations, f.gateway, approvals, new OperationAudit(f.core.jdbc, f.txm)), approvals);
    final Party user = f.core.employee("值班长", plant.teamA());
    final AgentRegistry.Identity agent = registry.create("mcp-" + Long.toString(System.nanoTime(), 36), "MCP 助手",
        Set.of("lsmom:work:execute", "lsmom:work:edit", "lsmom:issue:raise"), 60, null, user.id());
    final ActorContext actor = AgentActors.compose(f.human(user, DataScopeLevel.ORG, "lsmom:work:execute", "lsmom:work:edit", "lsmom:issue:raise"), agent);
    final McpServer.Caller caller = new McpServer.Caller(agent, actor);

    JsonNode rpc(String method, String params) {
        return mcp.handle(ActionFixture.json("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":" + params + "}"), caller);
    }

    List<String> toolNames() {
        List<String> out = new ArrayList<>();
        rpc("tools/list", "{}").path("result").path("tools").forEach(t -> out.add(t.path("name").asText()));
        return out;
    }

    @Test
    void 握手_工具清单_调用与错误() {
        JsonNode init = rpc("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}");
        assertThat(init.path("id").asInt()).isEqualTo(7);
        assertThat(init.path("result").path("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(init.path("result").path("capabilities").path("tools").isObject()).isTrue();
        assertThat(rpc("initialize", "{\"protocolVersion\":\"1999-01-01\"}").path("result").path("protocolVersion").asText()).isEqualTo("2025-11-25");
        assertThat(rpc("ping", "{}").path("result").isObject()).isTrue();
        assertThat(rpc("resources/list", "{}").path("error").path("code").asInt()).isEqualTo(-32601);

        JsonNode tools = rpc("tools/list", "{}").path("result").path("tools");
        JsonNode find = null;
        for (JsonNode t : tools) if (t.path("name").asText().equals("ls.core.Work.find")) find = t;
        assertThat(find).isNotNull();
        assertThat(find.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
        assertThat(find.path("inputSchema").path("properties").path("input").path("properties").has("orgUnitId")).isTrue();
        assertThat(toolNames()).contains("ls.core.Issue.raise", McpServer.REQUEST_APPROVAL).doesNotContain("ls.core.Work.confirm", "ls.core.Work.create");

        // 调用：草稿
        JsonNode r = rpc("tools/call", "{\"name\":\"ls.core.Issue.raise\",\"arguments\":{\"input\":{\"type\":\"SHIFT\",\"title\":\"MCP 草稿\",\"orgUnitId\":"
            + plant.teamA().id() + "}}}");
        assertThat(r.path("result").path("isError").asBoolean()).isFalse();
        assertThat(r.path("result").path("structuredContent").path("status").asText()).isEqualTo("DRAFT");
        assertThat(r.path("result").path("structuredContent").path("source").asText()).isEqualTo("AGENT");
        assertThat(r.path("result").path("content").get(0).path("type").asText()).isEqualTo("text");
        // 含日期字段的结果可序列化
        f.op("ls.core.Work.create", ActionFixture.admin(), "{\"workType\":\"PRODUCTION\",\"title\":\"带计划时间\",\"orgUnitId\":" + plant.teamA().id()
            + ",\"plannedStart\":\"2026-11-10T08:00:00\",\"plannedEnd\":\"2026-11-10T16:00:00\"}");
        JsonNode found = rpc("tools/call", "{\"name\":\"ls.core.Work.find\",\"arguments\":{\"input\":{}}}");
        assertThat(found.path("result").path("isError").asBoolean()).as(found.toString()).isFalse();
        assertThat(found.path("result").path("structuredContent").path("data").path("total").asInt()).isPositive();
        // 工具执行错误以 isError 返回（越权组织）
        JsonNode denied = rpc("tools/call", "{\"name\":\"ls.core.Work.find\",\"arguments\":{\"input\":{\"orgUnitId\":" + plant.teamB().id() + "}}}");
        assertThat(denied.path("result").path("isError").asBoolean()).isTrue();
        assertThat(denied.path("result").path("structuredContent").path("errorCode").asText()).isEqualTo("ACCESS_DENIED");
        // 未列出的能力
        JsonNode deny = rpc("tools/call", "{\"name\":\"ls.core.Work.confirm\",\"arguments\":{\"input\":{},\"targetId\":\"1\",\"version\":0}}");
        assertThat(deny.path("result").path("structuredContent").path("errorCode").asText()).isEqualTo("AGENT_TOOL_DENIED");
        // 未知工具：协议错误
        assertThat(rpc("tools/call", "{\"name\":\"ls.nope.X.y\",\"arguments\":{}}").path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void 暴露等级由DRAFT改为DENY并发布后工具从清单消失() {
        StateMachineDefinition base = CoreStateMachines.work();
        try {
            f.machines.publish(withExposure(base, "ls.core.Work.ready", "DRAFT"));
            assertThat(toolNames()).contains("ls.core.Work.ready");
            assertThat(rpc("tools/list", "{}").path("result").path("tools").toString()).contains("暴露等级 DRAFT");
            f.machines.publish(withExposure(base, "ls.core.Work.ready", "DENY"));
            assertThat(toolNames()).doesNotContain("ls.core.Work.ready");
        } finally {
            f.machines.publish(base);
        }
    }

    static StateMachineDefinition withExposure(StateMachineDefinition d, String op, String exposure) {
        List<StateMachineDefinition.Transition> ts = d.transitions().stream().map(t -> !t.operation().equals(op) ? t
            : new StateMachineDefinition.Transition(t.operation(), t.name(), t.from(), t.to(), t.guards(), t.permission(), t.risk(), t.inputSchema(),
            exposure)).toList();
        return new StateMachineDefinition(d.code(), d.concept(), d.displayName(), d.states(), d.initial(), ts);
    }
}
