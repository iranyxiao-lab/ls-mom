package com.lsmom.action;

import com.lsmom.action.api.Risk;
import com.lsmom.action.external.ExternalSyncService;
import com.lsmom.action.external.HttpExternalDestination;
import com.lsmom.action.risk.GenericWritePolicy;
import com.lsmom.core.error.LsBizException;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.DataType;
import com.lsmom.meta.concept.FieldDef;
import com.lsmom.test.CoreFixture;
import com.lsmom.test.PgTestSupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 5.6 风险分级；5.8 外部写入待同步与补偿（模拟 ERP 端点）。 */
class RiskAndExternalTest {

    static final AtomicInteger erpStatus = new AtomicInteger(500);
    static final List<String> erpKeys = new CopyOnWriteArrayList<>();
    static HttpServer erp;
    static final ExternalSyncService sync = new ExternalSyncService(PgTestSupport.jdbc());
    static final HttpExternalDestination dest;

    static {
        try {
            erp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            erp.createContext("/posting", ex -> {
                erpKeys.add(ex.getRequestHeaders().getFirst("Idempotency-Key"));
                byte[] body = (erpStatus.get() == 200 ? "{\"doc\":\"ERP-0001\"}" : "{\"error\":\"period closed\"}").getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(erpStatus.get(), body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            erp.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        dest = new HttpExternalDestination("erp", URI.create("http://127.0.0.1:" + erp.getAddress().getPort() + "/posting"),
            Set.of("ls.core.WorkReport.submitted"), sync, Duration.ofSeconds(3), 3);
    }

    private final ActionFixture f = new ActionFixture(List.of(dest));
    private final GenericWritePolicy policy = new GenericWritePolicy(f.core.jdbc, f.operations);

    @AfterEach
    void reset() {
        erpStatus.set(500);
    }

    @Test
    void 通用接口改WorkItem状态被拒绝() {
        assertThat(policy.riskOf("ls.core.Work")).isEqualTo(Risk.C1);
        assertThatThrownBy(() -> policy.check("ls.core.Work", List.of("status")))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("GENERIC_WRITE_FORBIDDEN");
                assertThat(e.getMessage()).contains("status").contains("ls.core.Work.confirm");
            });
        assertThatThrownBy(() -> policy.check("ls.core.Work", List.of("title"))).hasMessageContaining("核心对象只能经命名 Operation");
    }

    @Test
    void C0台账可通用写入_C1台账状态字段不可() {
        String code = "acme." + PgTestSupport.uniq("r") + ".Ledger";
        f.concepts.publish(ConceptDef.managed(code, "台账", List.of(FieldDef.of("name", "名称", DataType.STRING),
            FieldDef.of("status", "状态", DataType.ENUM).asState()), null));
        assertThat(policy.riskOf(code)).isEqualTo(Risk.C0);
        policy.check(code, List.of("name", "status"));
    }

    @Test
    void 外部过账失败时填报仍为已提交并进入补偿列表_恢复后重投成功() {
        CoreFixture.Plant p = f.core.plant();
        String w = f.newWork(ActionFixture.admin(), p.teamA(), "过账");
        var r = f.on("ls.core.Work.submitReport", ActionFixture.admin(), w, 0, "{\"outputQty\":12,\"outputUnit\":\"件\",\"laborMinutes\":30}");
        String reportId = String.valueOf(((Map<?, ?>) r.data()).get("reportId"));
        assertThat(sync.of("ls.core.WorkReport", reportId)).singleElement().satisfies(s -> assertThat(s.status()).isEqualTo("PENDING"));

        drain();
        assertThat(f.core.reports.get(Long.parseLong(reportId)).status()).isEqualTo("SUBMITTED");
        ExternalSyncService.SyncRecord failed = sync.of("ls.core.WorkReport", reportId).get(0);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.attempts()).isEqualTo(3);
        assertThat(failed.lastError()).contains("500");
        assertThat(sync.compensations("erp")).extracting(ExternalSyncService.SyncRecord::objectId).contains(reportId);

        erpStatus.set(200);
        f.outbox.redeliver(failed.outboxId());
        drain();
        ExternalSyncService.SyncRecord ok = sync.of("ls.core.WorkReport", reportId).get(0);
        assertThat(ok.status()).isEqualTo("SYNCED");
        assertThat(ok.externalRef()).contains("ERP-0001");
        assertThat(sync.compensations("erp")).extracting(ExternalSyncService.SyncRecord::objectId).doesNotContain(reportId);
        // 每次重试携带同一幂等键，外部系统可去重
        assertThat(erpKeys.stream().filter(k -> k != null && k.equals(erpKeys.get(erpKeys.size() - 1))).count()).isGreaterThanOrEqualTo(2);
    }

    private void drain() {
        for (int i = 0; i < 20; i++) {
            if (f.outbox.deliverBatch(100) == 0) break;
        }
    }
}
