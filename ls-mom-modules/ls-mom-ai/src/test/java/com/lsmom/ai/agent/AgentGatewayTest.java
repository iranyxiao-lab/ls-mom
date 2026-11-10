package com.lsmom.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.api.OperationResult;
import com.lsmom.action.gateway.OperationAudit;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** spec ai/agent-gateway：服务身份与 API Key、OBO 双主体审计、有效权限、暴露等级、模拟、审批绑定、永不暴露清单。 */
class AgentGatewayTest {

    static final Set<String> HUMAN_PERMS = Set.of("lsmom:work:create", "lsmom:work:execute", "lsmom:issue:raise", "lsmom:issue:handle",
        "lsmom:issue:verify", "lsmom:work:confirm");

    final ActionFixture f = new ActionFixture();
    final CoreFixture.Plant plant = f.core.plant();
    final AgentRegistry registry = new AgentRegistry(f.core.jdbc, f.core.parties, f.core.clock, f.txm);
    final AgentApprovals approvals = new AgentApprovals(f.core.jdbc, f.operations, f.core.clock);
    final AgentGateway agents = new AgentGateway(f.operations, f.gateway, approvals, new OperationAudit(f.core.jdbc, f.txm));
    final Party alice = f.core.employee("班长甲", plant.teamA());
    final ActorContext human = f.human(alice, DataScopeLevel.ORG, HUMAN_PERMS.toArray(String[]::new));

    AgentRegistry.Identity agent(String code, String... perms) {
        return registry.create(code + "-" + Long.toString(System.nanoTime(), 36), "助手" + code, Set.of(perms), 3, null, alice.id());
    }

    String code(Throwable e) {
        return ((LsBizException) e).getCode();
    }

    @Test
    void API_Key_只存摘要_限流_吊销_轮换与过期均被拒绝并记录() {
        AgentRegistry.Identity a = agent("keys", "lsmom:work:execute");
        AgentRegistry.Issued k = registry.issue(a.id(), 30, alice.id());
        assertThat(k.key()).startsWith("lsk_" + k.info().prefix() + "_");
        assertThat(f.count("select count(*) from agent_api_key where key_hash = ? or prefix = ?", k.key(), k.key())).isZero();
        assertThat(registry.authenticate(k.key()).identity().id()).isEqualTo(a.id());

        // 每分钟 3 次：第 4 次被限流（时钟固定在同一分钟）
        registry.authenticate(k.key());
        registry.authenticate(k.key());
        assertThatThrownBy(() -> registry.authenticate(k.key())).extracting(this::code).isEqualTo("AGENT_RATE_LIMITED");

        // 轮换：新 Key 可用；宽限 0 小时 → 旧 Key 立即过期
        AgentRegistry.Issued k2 = registry.rotate(k.info().id(), 0, 30, alice.id());
        assertThat(registry.authenticate(k2.key()).keyId()).isEqualTo(k2.info().id());
        assertThatThrownBy(() -> registry.authenticate(k.key())).extracting(this::code).isEqualTo("AGENT_KEY_EXPIRED");
        // 吊销：立即失效
        registry.revoke(k2.info().id(), "泄露", alice.id());
        assertThatThrownBy(() -> registry.authenticate(k2.key())).extracting(this::code).isEqualTo("AGENT_KEY_REVOKED");
        // 伪造：同前缀不同密文
        String forged = k2.key().substring(0, k2.key().length() - 4) + "AAAA";
        assertThatThrownBy(() -> registry.authenticate(forged)).extracting(this::code).isEqualTo("AGENT_KEY_INVALID");
        // 停用的身份
        AgentRegistry.Issued k3 = registry.issue(a.id(), 1, alice.id());
        registry.setStatus(a.id(), false);
        assertThatThrownBy(() -> registry.authenticate(k3.key())).extracting(this::code).isEqualTo("AGENT_DISABLED");

        List<String> events = registry.events(a.id(), 50).stream().map(m -> (String) m.get("event")).toList();
        assertThat(events).contains("ISSUED", "RATE_LIMITED", "ROTATED", "EXPIRED_USE", "REVOKED", "REVOKED_USE", "INVALID", "DISABLED_USE");
    }

    @Test
    void 有效权限为真人与Agent交集_工具清单不含永不暴露能力_AI09() {
        AgentRegistry.Identity a = agent("tools", "lsmom:work:execute", "lsmom:issue:raise", "lsmom:issue:verify", "lsmom:work:confirm",
            "lsmom:issue:handle", "lsmom:work:assign");
        ActorContext actor = AgentActors.compose(human, a);
        assertThat(actor.type()).isEqualTo(ActorContext.ActorType.AGENT);
        assertThat(actor.actorParty()).isEqualTo(a.partyId());
        assertThat(actor.onBehalfOf()).isEqualTo(alice.id());
        // 交集：Agent 有 work:assign 但真人没有；真人有 work:create 但 Agent 没有
        assertThat(actor.permissions()).doesNotContain("lsmom:work:assign", "lsmom:work:create").contains("lsmom:issue:raise");

        List<String> tools = agents.tools(actor).stream().map(AgentGateway.Tool::name).toList();
        assertThat(tools).contains("ls.core.Work.find", "ls.core.Issue.raise", "ls.core.Issue.submit", "ls.core.Issue.createRectification")
            .doesNotContain("ls.core.Work.create", "ls.core.Work.assign");
        // 永不暴露：代审批（确认完成、驳回）、质量终判（验证通过 / 不通过）
        assertThat(tools).doesNotContain("ls.core.Work.confirm", "ls.core.Issue.verifyPass", "ls.core.Issue.verifyFail", "ls.core.Issue.reject",
            "ls.core.Work.rejectConfirm");
        for (String t : tools) {
            var op = f.operations.require(t);
            assertThat(f.gateway.agentPolicy().denied(op, f.gateway.guardCodes(op))).as(t).isEmpty();
        }
        // 没有暴露等级的能力不在清单中
        assertThat(tools).doesNotContain("ls.core.Work.split", "ls.core.Work.link");

        // 直接调用永不暴露的能力：拒绝并审计
        String issue = f.op("ls.core.Issue.raise", human, "{\"type\":\"QUALITY\",\"title\":\"终判\",\"orgUnitId\":" + plant.teamA().id() + "}").targetId();
        assertThatThrownBy(() -> agents.call("ls.core.Issue.verifyPass", call("{\"conclusion\":\"ok\"}", issue, 0), actor, a))
            .extracting(this::code).isEqualTo("AGENT_TOOL_DENIED");
        assertThat(f.count("select count(*) from op_audit where op_code = 'ls.core.Issue.verifyPass' and actor_type = 'AGENT' and result = 'FAILED' "
            + "and error_code = 'AGENT_TOOL_DENIED' and on_behalf_of = ?", alice.id())).isEqualTo(1);
        // 绕过门面直接走网关也一样被拒（约束在网关内）
        assertThatThrownBy(() -> f.gateway.execute("ls.core.Issue.reject", OperationRequest.on(issue, 0, ActionFixture.json("{\"reason\":\"x\"}")), actor))
            .extracting(this::code).isEqualTo("AGENT_TOOL_DENIED");
    }

    @Test
    void AI05_越权查询B组工作被拒并记录_AI06_两条整改草稿为DRAFT且审计含双主体与批次() {
        AgentRegistry.Identity a = agent("draft", "lsmom:work:execute", "lsmom:issue:raise");
        ActorContext actor = AgentActors.compose(human, a);
        f.newWork(ActionFixture.admin(), plant.teamB(), "乙班工作");
        String mine = f.newWork(ActionFixture.admin(), plant.teamA(), "甲班工作");

        // AI-05：数据域只有甲班，查询乙班 → 无权限并记录
        assertThatThrownBy(() -> agents.call("ls.core.Work.find", call("{\"orgUnitId\":" + plant.teamB().id() + "}", null, null), actor, a))
            .extracting(this::code).isEqualTo("ACCESS_DENIED");
        assertThat(f.count("select count(*) from op_audit where op_code = 'ls.core.Work.find' and actor_party = ? and on_behalf_of = ? and result = 'FAILED'",
            a.partyId(), alice.id())).isEqualTo(1);
        // 不指定组织：只返回数据域内的
        OperationResult r = agents.call("ls.core.Work.find", call("{}", null, null), actor, a);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ((Map<String, Object>) r.data()).get("rows");
        assertThat(rows).extracting(m -> String.valueOf(m.get("id"))).contains(mine);
        assertThat(rows).allSatisfy(m -> assertThat(m.get("orgUnitId")).isEqualTo(plant.teamA().id()));

        // AI-06：根据交接班内容创建两条整改草稿
        String batch = "B" + System.nanoTime();
        for (String t : List.of("更换 3 号泵密封", "补充润滑脂")) {
            OperationResult d = agents.call("ls.core.Issue.raise", new AgentGateway.Call(ActionFixture.json(
                "{\"type\":\"SHIFT\",\"title\":\"" + t + "\",\"orgUnitId\":" + plant.teamA().id() + "}"), null, null, null, false, null, batch), actor, a);
            assertThat(d.status()).isEqualTo("DRAFT");
            assertThat(AgentGateway.view(d, actor)).containsEntry("source", "AGENT");
        }
        List<Map<String, Object>> audit = f.core.jdbc.queryForList("""
            select a.actor_type, a.actor_party, a.on_behalf_of, a.trace_id, a.batch_id, i.status from op_audit a
              join core_issue i on i.id = cast(a.target_id as bigint) where a.batch_id = ? order by a.id""", batch);
        assertThat(audit).hasSize(2).allSatisfy(m -> {
            assertThat(m.get("actor_type")).isEqualTo("AGENT");
            assertThat(m.get("actor_party")).isEqualTo(a.partyId());
            assertThat(m.get("on_behalf_of")).isEqualTo(alice.id());
            assertThat(m.get("status")).isEqualTo("DRAFT");
        });
    }

    @Test
    void 模拟关闭前检查返回缺证据且数据库无变化_SIMULATE等级不能正式执行() {
        AgentRegistry.Identity a = agent("sim", "lsmom:issue:raise");
        ActorContext actor = AgentActors.compose(human, a);
        var issue = f.op("ls.core.Issue.raise", human, "{\"type\":\"SAFETY\",\"title\":\"高处坠物\",\"severity\":\"HIGH\",\"orgUnitId\":"
            + plant.teamA().id() + "}");
        String id = issue.targetId();
        int events = f.count("select count(*) from core_domain_event");
        int outbox = f.count("select count(*) from core_outbox");
        assertThatThrownBy(() -> agents.call("ls.core.Issue.submit", call("{}", id, issue.version()), actor, a))
            .extracting(this::code).isEqualTo("GUARD_FAILED");
        assertThat(f.issues.get(Long.parseLong(id)).status().name()).isEqualTo("DRAFT");
        assertThat(f.issues.get(Long.parseLong(id)).version()).isEqualTo(issue.version());
        assertThat(f.count("select count(*) from core_domain_event")).isEqualTo(events);
        assertThat(f.count("select count(*) from core_outbox")).isEqualTo(outbox);

        // 可通过的模拟：返回结果但不落库
        var ok = f.op("ls.core.Issue.raise", human, "{\"type\":\"SAFETY\",\"title\":\"标识缺失\",\"orgUnitId\":" + plant.teamA().id() + "}");
        int eventsBefore = f.count("select count(*) from core_domain_event");
        OperationResult sim = agents.call("ls.core.Issue.submit", call("{}", ok.targetId(), ok.version()), actor, a);
        assertThat(sim.simulated()).isTrue();
        assertThat(sim.status()).isEqualTo("OPEN");
        assertThat(f.issues.get(Long.parseLong(ok.targetId())).status().name()).isEqualTo("DRAFT");
        assertThat(f.count("select count(*) from core_domain_event")).isEqualTo(eventsBefore);
        assertThat(f.count("select count(*) from op_audit where op_code = 'ls.core.Issue.submit' and result = 'SIMULATED' and actor_party = ?", a.partyId()))
            .isEqualTo(1);
        // 不经门面、不带 simulate 直接走网关：拒绝
        assertThatThrownBy(() -> f.gateway.execute("ls.core.Issue.submit", OperationRequest.on(ok.targetId(), ok.version(), ActionFixture.json("{}")), actor))
            .extracting(this::code).isEqualTo("AGENT_SIMULATE_ONLY");
    }

    @Test
    void 审批绑定_AI07输入或版本变化被拒_AI08一次性ID不能重复执行_过期被拒() {
        AgentRegistry.Identity a = agent("appr", "lsmom:issue:handle", "lsmom:issue:raise");
        ActorContext actor = AgentActors.compose(human, a);
        var issue = f.op("ls.core.Issue.raise", human, "{\"type\":\"QUALITY\",\"title\":\"毛刺\",\"orgUnitId\":" + plant.teamA().id() + "}");
        var opened = f.on("ls.core.Issue.submit", human, issue.targetId(), issue.version(), "{}");
        String id = issue.targetId();
        int v = opened.version();
        JsonNode input = ActionFixture.json("{\"titles\":[\"打磨去毛刺\"]}");

        // 没有审批单
        assertThatThrownBy(() -> agents.call("ls.core.Issue.createRectification", call(input.toString(), id, v), actor, a))
            .extracting(this::code).isEqualTo("AGENT_APPROVAL_REQUIRED");
        AgentApprovals.Approval req = approvals.request(a, actor, "ls.core.Issue.createRectification", input, id, v, "交接班发现毛刺");
        assertThat(req.status()).isEqualTo("PENDING");
        // 未批准不能用；Agent 自己不能批准
        assertThatThrownBy(() -> agents.call("ls.core.Issue.createRectification", withApproval(input, id, v, req.id()), actor, a))
            .extracting(this::code).isEqualTo("AGENT_APPROVAL_INVALID");
        assertThatThrownBy(() -> approvals.decide(req.id(), true, 10, null, actor)).extracting(this::code).isEqualTo("AGENT_APPROVAL_INVALID");
        Party boss = f.core.employee("主任", plant.workshop());
        approvals.decide(req.id(), true, 10, "同意", f.human(boss, DataScopeLevel.ALL, "lsmom:issue:handle"));

        int works = f.count("select count(*) from core_work_item where source_type = 'ISSUE' and source_id = ?", Long.parseLong(id));
        // AI-07：批准后以不同参数执行 → 拒绝，需要重新批准
        JsonNode changed = ActionFixture.json("{\"titles\":[\"更换整套模具\"]}");
        assertThatThrownBy(() -> agents.call("ls.core.Issue.createRectification", withApproval(changed, id, v, req.id()), actor, a))
            .extracting(this::code).isEqualTo("AGENT_APPROVAL_MISMATCH");
        assertThatThrownBy(() -> agents.call("ls.core.Issue.createRectification", withApproval(input, id, v + 1, req.id()), actor, a))
            .extracting(this::code).isEqualTo("AGENT_APPROVAL_MISMATCH");
        // 按批准内容执行
        OperationResult done = agents.call("ls.core.Issue.createRectification", withApproval(input, id, v, req.id()), actor, a);
        assertThat(done.simulated()).isFalse();
        assertThat(f.count("select count(*) from core_work_item where source_type = 'ISSUE' and source_id = ?", Long.parseLong(id))).isEqualTo(works + 1);
        assertThat(f.count("select count(*) from op_audit where approval_id = ? and result = 'SUCCESS' and actor_party = ?", req.id(), a.partyId())).isEqualTo(1);
        // AI-08：同一审批单再次使用 → 拒绝，不产生第二条
        assertThatThrownBy(() -> agents.call("ls.core.Issue.createRectification", withApproval(input, id, v, req.id()), actor, a))
            .extracting(this::code).isEqualTo("AGENT_APPROVAL_USED");
        assertThat(f.count("select count(*) from core_work_item where source_type = 'ISSUE' and source_id = ?", Long.parseLong(id))).isEqualTo(works + 1);

        // 过期
        AgentApprovals.Approval r2 = approvals.request(a, actor, "ls.core.Issue.createRectification", input, id, v, null);
        approvals.decide(r2.id(), true, 5, null, f.human(boss, DataScopeLevel.ALL, "lsmom:issue:handle"));
        AgentApprovals later = new AgentApprovals(f.core.jdbc, f.operations, Clock.offset(f.core.clock, Duration.ofMinutes(6)));
        AgentGateway agentsLater = new AgentGateway(f.operations, f.gateway, later, new OperationAudit(f.core.jdbc, f.txm));
        assertThatThrownBy(() -> agentsLater.call("ls.core.Issue.createRectification", withApproval(input, id, v, r2.id()), actor, a))
            .extracting(this::code).isEqualTo("AGENT_APPROVAL_EXPIRED");
    }

    @Test
    void 无真人代理不能调用() {
        AgentRegistry.Identity a = agent("obo", "lsmom:work:execute");
        ActorContext service = new ActorContext(ActorContext.ActorType.SERVICE, null, 99L, null, true, Set.of(), com.lsmom.core.org.DataScope.unrestricted(), "svc");
        assertThatThrownBy(() -> AgentActors.compose(service, a)).extracting(this::code)
            .isEqualTo("AGENT_OBO_REQUIRED");
    }

    static AgentGateway.Call call(String input, String targetId, Integer version) {
        return new AgentGateway.Call(ActionFixture.json(input), targetId, version, null, false, null, null);
    }

    static AgentGateway.Call withApproval(JsonNode input, String targetId, Integer version, String approvalId) {
        return new AgentGateway.Call(input, targetId, version, null, false, approvalId, null);
    }
}
