package com.lsmom.action;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.api.OperationResult;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.event.DomainEvent;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.core.trace.TraceContext;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 5.3～5.5：统一管道、状态机、幂等、乐观锁、审计。 */
class GatewayTest {

    private final ActionFixture f = new ActionFixture();
    private final ActorContext admin = ActionFixture.admin();

    @Test
    void 调用未注册Operation返回不存在并审计() {
        assertThatThrownBy(() -> f.op("ls.core.Work.unknown", admin, "{}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("OPERATION_NOT_FOUND");
                assertThat(e.getMessage()).contains("Operation 不存在");
            });
        assertThat(f.count("select count(*) from op_audit where op_code = 'ls.core.Work.unknown' and result = 'FAILED'")).isPositive();
    }

    @Test
    void 处理器写入后失败整体回滚() {
        CoreFixture.Plant p = f.core.plant();
        String id = f.newWork(admin, p.teamA(), "原标题");
        int events = f.count("select count(*) from core_domain_event where subject_id = ?", id);
        assertThatThrownBy(() -> f.on("ls.core.Work.testWriteThenFail", admin, id, 0, "{}")).extracting("code").isEqualTo("TEST_FAIL");
        assertThat(f.core.works.get(Long.parseLong(id)).title()).isEqualTo("原标题");
        assertThat(f.count("select count(*) from core_domain_event where subject_id = ?", id)).isEqualTo(events);
        assertThat(f.count("select count(*) from op_audit where op_code = 'ls.core.Work.testWriteThenFail' and target_id = ? and error_code = 'TEST_FAIL'", id))
            .isEqualTo(1);
    }

    @Test
    void 守卫失败时业务数据事件与Outbox均无变化() {
        CoreFixture.Plant p = f.core.plant();
        OperationResult raised = f.op("ls.core.Issue.raise", admin,
            "{\"type\":\"SAFETY\",\"title\":\"管道泄漏\",\"severity\":\"HIGH\",\"orgUnitId\":" + p.teamA().id() + "}");
        String id = raised.targetId();
        int ev = f.count("select count(*) from core_domain_event where subject_id = ? and subject_concept = 'ls.core.Issue'", id);
        int ob = f.count("select count(*) from core_outbox o join core_domain_event e on e.event_id = o.event_id where e.subject_id = ?", id);

        assertThatThrownBy(() -> f.step("ls.core.Issue.submit", admin, "ls.core.Issue", id, "{}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("GUARD_FAILED");
                assertThat(e.getDetails()).containsEntry("guard", "ls.core.Issue.evidenceRequired");
                assertThat(e.getMessage()).contains("证据");
            });
        assertThat(f.issues.get(Long.parseLong(id)).status().name()).isEqualTo("DRAFT");
        assertThat(f.issues.get(Long.parseLong(id)).version()).isEqualTo(raised.version());
        assertThat(f.count("select count(*) from core_domain_event where subject_id = ? and subject_concept = 'ls.core.Issue'", id)).isEqualTo(ev);
        assertThat(f.count("select count(*) from core_outbox o join core_domain_event e on e.event_id = o.event_id where e.subject_id = ?", id)).isEqualTo(ob);

        // 上传证据后通过
        long oss = System.nanoTime();
        f.core.jdbc.update("insert into sys_oss (oss_id, file_name, original_name, file_suffix, url, service) values (?, 'k', '现场.jpg', '.jpg', 'u', 'minio')", oss);
        f.op("ls.core.DocumentLink.attach", admin, "{\"ossId\":" + oss + ",\"objectType\":\"ISSUE\",\"objectId\":\"" + id + "\",\"purpose\":\"EVIDENCE\",\"orgUnitId\":" + p.teamA().id() + "}");
        assertThat(f.step("ls.core.Issue.submit", admin, "ls.core.Issue", id, "{}").status()).isEqualTo("OPEN");
    }

    @Test
    void 非法迁移返回当前状态与允许动作() {
        CoreFixture.Plant p = f.core.plant();
        String id = f.newWork(admin, p.teamA(), "直接确认");
        assertThatThrownBy(() -> f.on("ls.core.Work.confirm", admin, id, 0, "{}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("INVALID_TRANSITION");
                assertThat(e.getDetails()).containsEntry("currentStatus", "DRAFT");
                assertThat((List<Object>) e.getDetails().get("allowedOperations")).containsExactlyInAnyOrder("ls.core.Work.ready", "ls.core.Work.cancel");
            });
    }

    @Test
    void 阻塞与恢复时间轴记录两次迁移及原因() {
        CoreFixture.Plant p = f.core.plant();
        Party worker = f.core.employee("操作工", p.teamA());
        String id = f.newWork(admin, p.teamA(), "装配");
        f.step("ls.core.Work.ready", admin, "ls.core.Work", id, "{}");
        OperationResult assigned = f.step("ls.core.Work.assign", admin, "ls.core.Work", id,
            "{\"targets\":[{\"resourceType\":\"PARTY\",\"resourceRef\":\"" + worker.id() + "\"}]}");
        assertThat(assigned.status()).isEqualTo("ASSIGNED");
        f.step("ls.core.Work.start", admin, "ls.core.Work", id, "{}");
        assertThat(f.step("ls.core.Work.block", admin, "ls.core.Work", id, "{\"reason\":\"缺料\"}").status()).isEqualTo("BLOCKED");
        assertThat(f.core.works.get(Long.parseLong(id)).blockReason()).isEqualTo("缺料");
        assertThat(f.step("ls.core.Work.unblock", admin, "ls.core.Work", id, "{\"reason\":\"物料到位\"}").status()).isEqualTo("IN_PROGRESS");
        assertThat(f.core.works.get(Long.parseLong(id)).blockReason()).isNull();

        List<DomainEvent> timeline = f.events.eventsOf("ls.core.Work", id);
        assertThat(timeline).extracting(DomainEvent::type).containsSubsequence("ls.core.Work.block", "ls.core.Work.unblock");
        DomainEvent block = timeline.stream().filter(e -> e.type().equals("ls.core.Work.block")).findFirst().orElseThrow();
        DomainEvent unblock = timeline.stream().filter(e -> e.type().equals("ls.core.Work.unblock")).findFirst().orElseThrow();
        assertThat(block.payload()).containsEntry("from", "IN_PROGRESS").containsEntry("to", "BLOCKED").containsEntry("reason", "缺料");
        assertThat(unblock.payload()).containsEntry("to", "IN_PROGRESS").containsEntry("reason", "物料到位");
    }

    @Test
    void 缺少原因返回字段校验错误() {
        CoreFixture.Plant p = f.core.plant();
        String id = f.newWork(admin, p.teamA(), "x");
        assertThatThrownBy(() -> f.step("ls.core.Work.cancel", admin, "ls.core.Work", id, "{}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.fieldErrors()).containsKey("reason"));
        assertThatThrownBy(() -> f.op("ls.core.Work.create", admin, "{\"workType\":\"PRODUCTION\",\"orgUnitId\":" + p.teamA().id() + "}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.fieldErrors()).containsKey("title"));
    }

    @Test
    void 重复提交只产生一次迁移与一个事件() {
        CoreFixture.Plant p = f.core.plant();
        String id = f.newWork(admin, p.teamA(), "幂等");
        OperationRequest req = OperationRequest.on(id, 0, json("{}")).withKey("retry-" + id);
        OperationResult first = f.gateway.execute("ls.core.Work.ready", req, admin);
        OperationResult second = f.gateway.execute("ls.core.Work.ready", req, admin);
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.version()).isEqualTo(first.version());
        assertThat(second.events()).isEqualTo(first.events());
        assertThat(f.count("select count(*) from core_domain_event where subject_id = ? and type = 'ls.core.Work.ready'", id)).isEqualTo(1);
        assertThat(f.core.works.get(Long.parseLong(id)).version()).isEqualTo(1);
        assertThat(f.count("select count(*) from op_audit where idempotency_key = ? and result = 'REPLAYED'", "retry-" + id)).isEqualTo(1);

        // 同键不同输入 → 冲突
        assertThatThrownBy(() -> f.gateway.execute("ls.core.Work.cancel", OperationRequest.on(id, 1, json("{\"reason\":\"x\"}")).withKey("retry-" + id), admin))
            .extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        // 必须幂等键
        assertThatThrownBy(() -> f.on("ls.core.Work.testRequiresKey", admin, id, 1, "{}")).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.fieldErrors()).containsKey("idempotencyKey"));
    }

    @Test
    void 并发编辑后到者返回版本冲突与最新版本() {
        CoreFixture.Plant p = f.core.plant();
        String id = f.newWork(admin, p.teamA(), "并发");
        f.on("ls.core.Work.ready", admin, id, 0, "{}");
        assertThatThrownBy(() -> f.on("ls.core.Work.cancel", admin, id, 0, "{\"reason\":\"重复\"}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("VERSION_CONFLICT");
                assertThat(e.getDetails()).containsEntry("currentVersion", 1);
            });
        assertThatThrownBy(() -> f.gateway.execute("ls.core.Work.cancel", OperationRequest.of(json("{\"reason\":\"x\"}")), admin))
            .extracting("code").isEqualTo("TARGET_REQUIRED");
    }

    @Test
    void 权限不足失败也审计() {
        CoreFixture.Plant p = f.core.plant();
        Party clerk = f.core.employee("办事员", p.teamA());
        ActorContext noVerify = f.human(clerk, DataScopeLevel.ALL, "lsmom:issue:raise");
        String id = f.op("ls.core.Issue.raise", noVerify, "{\"type\":\"ABNORMAL\",\"title\":\"x\",\"orgUnitId\":" + p.teamA().id() + "}").targetId();
        assertThatThrownBy(() -> f.step("ls.core.Issue.cancel", f.human(clerk, DataScopeLevel.ALL), "ls.core.Issue", id, "{\"reason\":\"r\"}"))
            .extracting("code").isEqualTo("ACCESS_DENIED");
        Map<String, Object> audit = f.core.jdbc.queryForMap("""
            select result, error_code, error_message, actor_party, target_id from op_audit
            where op_code = 'ls.core.Issue.cancel' and target_id = ? order by id desc limit 1""", id);
        assertThat(audit).containsEntry("result", "FAILED").containsEntry("error_code", "ACCESS_DENIED").containsEntry("actor_party", clerk.id());
        assertThat((String) audit.get("error_message")).contains("权限");
        assertThatThrownBy(() -> f.core.jdbc.update("delete from op_audit where target_id = ?", id)).rootCause().hasMessageContaining("只允许追加");
    }

    @Test
    void 数据域外的目标被拒绝() {
        CoreFixture.Plant p = f.core.plant();
        Party a = f.core.employee("甲班长", p.teamA());
        String inB = f.newWork(admin, p.teamB(), "乙班工作");
        ActorContext teamA = f.human(a, DataScopeLevel.TEAM, "lsmom:work:edit", "lsmom:work:create");
        assertThatThrownBy(() -> f.on("ls.core.Work.ready", teamA, inB, 0, "{}")).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.getMessage()).contains("数据范围"));
        assertThatThrownBy(() -> f.newWork(teamA, p.teamB(), "越权创建")).extracting("code").isEqualTo("ACCESS_DENIED");
        assertThat(f.newWork(teamA, p.teamA(), "本班创建")).isNotNull();
    }

    @Test
    void 仅本人数据域_可办理分派给自己的工作_不能办理别人的() {
        CoreFixture.Plant p = f.core.plant();
        Party op = f.core.employee("操作工", p.teamA());
        Party other = f.core.employee("另一个操作工", p.teamA());
        String w = f.newWork(admin, p.teamA(), "分派给操作工");
        f.step("ls.core.Work.ready", admin, "ls.core.Work", w, "{}");
        f.step("ls.core.Work.assign", admin, "ls.core.Work", w, "{\"targets\":[{\"resourceType\":\"PARTY\",\"resourceRef\":\"" + op.id() + "\"}]}");
        ActorContext self = f.human(op, DataScopeLevel.SELF, "lsmom:work:execute");
        assertThat(f.step("ls.core.Work.start", self, "ls.core.Work", w, "{}").status()).isEqualTo("IN_PROGRESS");
        assertThat(f.step("ls.core.Work.submitReport", self, "ls.core.Work", w, "{\"progress\":50}").status()).isNotNull();
        ActorContext stranger = f.human(other, DataScopeLevel.SELF, "lsmom:work:execute");
        assertThatThrownBy(() -> f.step("ls.core.Work.block", stranger, "ls.core.Work", w, "{\"reason\":\"x\"}"))
            .extracting("code").isEqualTo("ACCESS_DENIED");
    }

    @Test
    void 代理执行记录代理主体() {
        CoreFixture.Plant p = f.core.plant();
        Party boss = f.core.employee("车间主任", p.workshop());
        ActorContext agent = new ActorContext(ActorContext.ActorType.AGENT, null, 999_999L, boss.id(), false, Set.of("lsmom:work:create"),
            f.core.dataScopes.resolve(boss.id(), DataScopeLevel.WORKSHOP), "助手");
        String id = f.newWork(agent, p.teamA(), "Agent 起草");
        assertThat(f.core.works.get(Long.parseLong(id)).createdByParty()).isEqualTo(boss.id());
        Map<String, Object> audit = f.core.jdbc.queryForMap("select actor_type, actor_party, on_behalf_of from op_audit where target_id = ? and op_code = 'ls.core.Work.create'", id);
        assertThat(audit).containsEntry("actor_type", "AGENT").containsEntry("actor_party", 999_999L).containsEntry("on_behalf_of", boss.id());
    }

    @Test
    void 模拟执行完整校验后回滚() {
        CoreFixture.Plant p = f.core.plant();
        String id = f.newWork(admin, p.teamA(), "模拟");
        OperationResult r = f.gateway.execute("ls.core.Work.ready", OperationRequest.on(id, 0, json("{}")).simulated(), admin);
        assertThat(r.simulated()).isTrue();
        assertThat(r.status()).isEqualTo("READY");
        assertThat(f.core.works.get(Long.parseLong(id)).status().name()).isEqualTo("DRAFT");
        assertThat(f.count("select count(*) from core_domain_event where subject_id = ? and type = 'ls.core.Work.ready'", id)).isZero();
        assertThat(f.count("select count(*) from op_audit where target_id = ? and result = 'SIMULATED'", id)).isEqualTo(1);
    }

    @Test
    void 事件字段齐全且trace与请求一致() {
        CoreFixture.Plant p = f.core.plant();
        Party worker = f.core.employee("分派对象", p.teamA());
        String id = f.newWork(admin, p.teamA(), "事件");
        f.on("ls.core.Work.ready", admin, id, 0, "{}");
        TraceContext.runWith("trace-assign-001", () -> f.gateway.execute("ls.core.Work.assign",
            OperationRequest.on(id, 1, json("{\"targets\":[{\"resourceType\":\"PARTY\",\"resourceRef\":\"" + worker.id() + "\"}]}")).withKey("assign-" + id), admin));
        DomainEvent e = f.events.eventsOf("ls.core.Work", id).stream().filter(x -> x.type().equals("ls.core.Work.assign")).findFirst().orElseThrow();
        assertThat(e.eventId()).isNotNull();
        assertThat(e.version()).isEqualTo(1);
        assertThat(e.occurredAt()).isNotNull();
        assertThat(e.subjectConcept()).isEqualTo("ls.core.Work");
        assertThat(e.orgUnitId()).isEqualTo(p.teamA().id());
        assertThat(e.traceId()).isEqualTo("trace-assign-001");
        assertThat(e.idempotencyKey()).isEqualTo("assign-" + id);
        assertThat(f.core.jdbc.queryForObject("select trace_id from op_audit where target_id = ? and op_code = 'ls.core.Work.assign'", String.class, id))
            .isEqualTo("trace-assign-001");
    }
}
