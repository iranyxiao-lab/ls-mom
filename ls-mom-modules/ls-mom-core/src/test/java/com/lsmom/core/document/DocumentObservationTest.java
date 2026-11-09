package com.lsmom.core.document;

import com.lsmom.core.error.LsBizException;
import com.lsmom.core.observation.Observation;
import com.lsmom.core.observation.ObservationService;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.test.CoreFixture;
import com.lsmom.test.PgTestSupport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 4.7：事实观察；附件受控下载（数据域、密级）与审计。 */
class DocumentObservationTest {

    private final CoreFixture f = new CoreFixture();
    private final AccessAuditService audit = new AccessAuditService(f.jdbc);
    private final DocumentService docs = new DocumentService(f.jdbc, f.parties, audit,
        ossId -> new ByteArrayInputStream(("file-" + ossId).getBytes(StandardCharsets.UTF_8)));
    private final ObservationService observations = new ObservationService(f.jdbc);

    private long ossFile(String name) {
        long id = System.nanoTime();
        f.jdbc.update("insert into sys_oss (oss_id, file_name, original_name, file_suffix, url, service) values (?, ?, ?, '.pdf', ?, 'minio')",
            id, "k/" + id, name, "http://s3/k/" + id);
        return id;
    }

    private int audits(long linkId, String result) {
        return f.jdbc.queryForObject("select count(*) from core_access_audit where object_type = 'DOCUMENT_LINK' and object_id = ? and result = ?",
            Integer.class, String.valueOf(linkId), result);
    }

    @Test
    void 越权下载被拒绝并审计() throws Exception {
        CoreFixture.Plant p = f.plant();
        Party owner = f.employee("甲班员工", p.teamA());
        Party outsider = f.employee("乙班员工", p.teamB());
        DocumentLink link = docs.attach(new DocumentService.AttachCommand(ossFile("点检记录.pdf"), "WORK", "1", null, null, p.teamA().id(), owner.id()));

        assertThatThrownBy(() -> docs.download(link.id(), outsider.id(), f.dataScopes.resolve(outsider.id(), DataScopeLevel.TEAM)))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("ACCESS_DENIED"));
        assertThat(audits(link.id(), "DENIED")).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("select reason from core_access_audit where object_id = ? and result = 'DENIED'",
            String.class, String.valueOf(link.id()))).contains("数据域");

        DocumentService.Download ok = docs.download(link.id(), owner.id(), f.dataScopes.resolve(owner.id(), DataScopeLevel.TEAM));
        assertThat(new String(ok.stream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("file-" + link.ossId());
        assertThat(ok.link().fileName()).isEqualTo("点检记录.pdf");
        assertThat(audits(link.id(), "ALLOWED")).isEqualTo(1);
        // 车间数据域覆盖下级班组
        docs.download(link.id(), outsider.id(), f.dataScopes.resolve(outsider.id(), DataScopeLevel.WORKSHOP));
    }

    @Test
    void 密级超出被拒绝() {
        CoreFixture.Plant p = f.plant();
        Party reader = f.employee("普通员工", p.teamA());
        DocumentLink secret = docs.attach(new DocumentService.AttachCommand(ossFile("配方.pdf"), "WORK", "2", "SPEC",
            Classification.CONFIDENTIAL, p.teamA().id(), reader.id()));
        assertThatThrownBy(() -> docs.download(secret.id(), reader.id(), f.dataScopes.resolve(reader.id(), DataScopeLevel.ALL)))
            .hasMessageContaining("密级");
        f.jdbc.update("update core_party set clearance = 'CONFIDENTIAL' where id = ?", reader.id());
        assertThat(docs.download(secret.id(), reader.id(), f.dataScopes.resolve(reader.id(), DataScopeLevel.ALL)).link().id()).isEqualTo(secret.id());
    }

    @Test
    void 审计只允许追加() {
        CoreFixture.Plant p = f.plant();
        Party reader = f.employee("x", p.teamA());
        audit.record(reader.id(), "TEST", "X", "1", true, null);
        assertThatThrownBy(() -> f.jdbc.update("delete from core_access_audit where actor_party = ?", reader.id())).rootCause().hasMessageContaining("只允许追加");
        assertThatThrownBy(() -> f.jdbc.update("update core_access_audit set result = 'DENIED' where actor_party = ?", reader.id())).rootCause()
            .hasMessageContaining("只允许追加");
    }

    @Test
    void 附件版本与移除() {
        CoreFixture.Plant p = f.plant();
        long oss = ossFile("图纸.dwg");
        DocumentLink v1 = docs.attach(new DocumentService.AttachCommand(oss, "ISSUE", "7", null, null, p.teamA().id(), null));
        DocumentLink v2 = docs.attach(new DocumentService.AttachCommand(oss, "ISSUE", "7", null, null, p.teamA().id(), null));
        assertThat(v2.id()).isEqualTo(v1.id());
        assertThat(v2.docVersion()).isEqualTo(2);
        docs.detach(v1.id());
        assertThat(docs.listByObject("ISSUE", "7")).isEmpty();
    }

    @Test
    void 人工读数生成来源为人工的观察() {
        CoreFixture.Plant p = f.plant();
        Party inspector = f.employee("点检员", p.teamA());
        String asset = PgTestSupport.uniq("EQ-");
        Observation o = observations.record(new Observation(null, "ASSET", asset, "PRESSURE", new BigDecimal("0.82"), null, "MPa",
            LocalDateTime.now(), Observation.Source.MANUAL, null, p.teamA().id(), inspector.id()));
        assertThat(o.source()).isEqualTo(Observation.Source.MANUAL);
        assertThat(o.quality()).isEqualTo("GOOD");
        assertThat(observations.listByObject("ASSET", asset, 10)).extracting(Observation::id).containsExactly(o.id());
        assertThatThrownBy(() -> observations.record(new Observation(null, "ASSET", asset, "WAVE", null, "[1,2,3]", null,
            LocalDateTime.now(), Observation.Source.DCS, null, null, null))).hasMessageContaining("时序库");
    }
}
