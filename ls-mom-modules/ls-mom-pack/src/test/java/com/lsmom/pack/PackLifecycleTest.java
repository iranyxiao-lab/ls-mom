package com.lsmom.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.core.error.LsBizException;
import com.lsmom.crud.query.QuerySpec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** spec meta/pack-lifecycle 与 Pack 打包、安装、启停、ChangeSet、漂移、复审、升级演练门禁。 */
class PackLifecycleTest {

    private final PackFixture f = new PackFixture();

    private static String code(Throwable e) {
        return ((LsBizException) e).getCode();
    }

    private PackInstaller.Result install(String ns, Map<String, String> files) {
        return f.installer.install(PackFixture.pack(ns, files), 1L);
    }

    @Test
    void zip含sha256清单_篡改被拒_缺少负责人的Pack被拒() {
        String ns = PackFixture.code("tr");
        PackSource src = PackFixture.pack(ns, f.training("1.0.0", "2099-01-01"));
        byte[] zip = src.toZip();
        assertThat(src.toZip()).isEqualTo(zip);
        PackSource back = PackSource.fromZip(zip);
        assertThat(back.digest()).isEqualTo(src.digest());
        assertThat(back.files().keySet()).contains("pack.yaml", "concepts/record.yaml", "views/record-form.json");

        // 篡改 zip 中的文件内容（清单不变）
        PackSource tampered = src.with(Map.of("concepts/record.yaml", "code: x".getBytes(StandardCharsets.UTF_8)));
        byte[] bad = rezipWithChecksumsOf(tampered, src);
        assertThatThrownBy(() -> PackSource.fromZip(bad)).extracting(PackLifecycleTest::code).isEqualTo("PACK_CHECKSUM_MISMATCH");

        Map<String, String> noOwner = f.training("1.0.0", "2099-01-01");
        noOwner.put("pack.yaml", noOwner.get("pack.yaml").replaceAll("(?m)^owner:.*\\n", ""));
        assertThatThrownBy(() -> install(ns, noOwner)).isInstanceOf(LsBizException.class)
            .satisfies(e -> {
                assertThat(code(e)).isEqualTo("PACK_OWNER_REQUIRED");
                assertThat(e.getMessage()).contains("业务负责人");
            });
        Map<String, String> noRequester = f.training("1.0.0", "2099-01-01");
        noRequester.put("pack.yaml", noRequester.get("pack.yaml").replaceAll("(?m)^requester:.*\\n", ""));
        assertThatThrownBy(() -> install(ns, noRequester)).extracting(PackLifecycleTest::code).isEqualTo("PACK_OWNER_REQUIRED");
        assertThat(f.store.find(ns)).isEmpty();
    }

    /** 用原清单重新打包（模拟传输中内容被改）。 */
    private static byte[] rezipWithChecksumsOf(PackSource content, PackSource original) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(out)) {
            StringBuilder sums = new StringBuilder();
            original.files().forEach((k, v) -> sums.append(PackSource.sha256(v)).append("  ").append(k).append('\n'));
            for (var e : content.files().entrySet()) {
                z.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                z.write(e.getValue());
            }
            z.putNextEntry(new java.util.zip.ZipEntry(PackSource.CHECKSUMS));
            z.write(sums.toString().getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    @Test
    void 版本范围与循环依赖() {
        assertThat(SemVer.range(">=1.0.0 <2.0.0").test(SemVer.parse("1.4.2"))).isTrue();
        assertThat(SemVer.range(">=1.0.0 <2.0.0").test(SemVer.parse("2.0.0"))).isFalse();
        assertThat(SemVer.range("^1.2.0").test(SemVer.parse("1.9.0"))).isTrue();
        assertThat(SemVer.range("~1.2.0").test(SemVer.parse("1.3.0"))).isFalse();
        assertThat(SemVer.range("1.x").test(SemVer.parse("1.7.3"))).isTrue();

        String a = PackFixture.code("a");
        String b = PackFixture.code("b");
        PackManifest ma = manifest(a, "1.0.0", b);
        PackManifest mb = manifest(b, "1.0.0", a);
        assertThatThrownBy(() -> DependencyPlanner.plan(List.of(ma, mb), List.of())).isInstanceOf(LsBizException.class)
            .satisfies(e -> {
                assertThat(code(e)).isEqualTo("PACK_DEPENDENCY_CYCLE");
                assertThat(e.getMessage()).contains(a + " → " + b + " → " + a);
            });
        assertThatThrownBy(() -> DependencyPlanner.plan(List.of(ma), List.of())).extracting(PackLifecycleTest::code)
            .isEqualTo("PACK_DEPENDENCY_MISSING");
        // 依赖在前
        PackManifest mb2 = manifest(b, "1.0.0", null);
        assertThat(DependencyPlanner.plan(List.of(ma, mb2), List.of())).extracting(PackManifest::code).containsExactly(b, a);
        assertThatThrownBy(() -> DependencyPlanner.plan(List.of(manifest(a, "1.0.0", b + "@>=2.0.0"), mb2), List.of()))
            .extracting(PackLifecycleTest::code).isEqualTo("PACK_DEPENDENCY_VERSION");
    }

    private static PackManifest manifest(String code, String version, String dep) {
        List<PackManifest.Dependency> deps = dep == null ? List.of() : List.of(dep.contains("@")
            ? new PackManifest.Dependency(dep.substring(0, dep.indexOf('@')), dep.substring(dep.indexOf('@') + 1))
            : new PackManifest.Dependency(dep, ">=1.0.0"));
        return new PackManifest(code, version, code, null, false, new PackManifest.Person("负责人", null), new PackManifest.Person("提出人", null),
            java.time.LocalDate.of(2099, 1, 1), deps, null, null);
    }

    @Test
    void 安装培训Pack_独立迁移历史_停用后入口不可用数据保留_被依赖不可停用() {
        String ns = PackFixture.code("tr");
        Map<String, String> files = f.training("1.0.0", "2099-01-01");
        files.put("migrations/V1__trainee_index.sql", "create index if not exists idx_{tbl}_trainee on {tbl} (trainee);");
        String table = com.lsmom.meta.concept.MetaNames.managedTable(ns + ".Record");
        files.put("migrations/V1__trainee_index.sql", files.get("migrations/V1__trainee_index.sql").replace("{tbl}", table));
        PackInstaller.Result r = install(ns, files);
        assertThat(r.status()).isEqualTo("INSTALLED");
        assertThat(f.store.get(ns).status()).isEqualTo("ENABLED");
        assertThat(f.store.get(ns).ownerParty()).isEqualTo(f.owner.id());
        String history = PackInstaller.historyTable(ns);
        assertThat(f.core.jdbc.queryForObject("select count(*) from " + history + " where success and script = 'V1__trainee_index.sql'", Integer.class))
            .isEqualTo(1);
        assertThat(f.core.jdbc.queryForObject("select count(*) from pg_indexes where indexname = ?", Integer.class, "idx_" + table + "_trainee"))
            .isEqualTo(1);
        // 种子与幂等重装
        assertThat(f.core.jdbc.queryForObject("select count(*) from " + table, Integer.class)).isEqualTo(1);
        assertThat(install(ns, files).status()).isEqualTo("UNCHANGED");
        assertThat(f.core.jdbc.queryForObject("select count(*) from " + table, Integer.class)).isEqualTo(1);

        // 黄金场景与越权测试
        PackTester.Report rep = f.tester.run(ns);
        assertThat(rep.regression()).allSatisfy(c -> assertThat(c.passed()).as(c.name() + "：" + c.message()).isTrue());
        assertThat(rep.privilege()).hasSize(2).allSatisfy(c -> assertThat(c.passed()).as(c.name() + "：" + c.message()).isTrue());
        assertThat(rep.passed()).isTrue();
        assertThat(f.core.jdbc.queryForObject("select count(*) from " + table, Integer.class)).as("场景数据已回滚").isEqualTo(1);

        // 客户 Pack 依赖培训 Pack：培训 Pack 不可停用
        String cust = PackFixture.code("cust");
        install(cust, customer(ns, "1.0.0"));
        assertThatThrownBy(() -> f.lifecycle.disable(ns)).isInstanceOf(LsBizException.class)
            .satisfies(e -> {
                assertThat(code(e)).isEqualTo("PACK_IN_USE");
                assertThat(e.getMessage()).contains(cust);
            });
        f.lifecycle.disable(cust);
        f.lifecycle.disable(ns);

        // 停用：页面（台账接口）与 Operation 不可访问，历史记录仍在
        assertThatThrownBy(() -> f.crud.crud.list(ns + ".Record", QuerySpec.all(), ActionFixture.admin()))
            .extracting(PackLifecycleTest::code).isEqualTo("PACK_DISABLED");
        long id = f.core.jdbc.queryForObject("select id from " + table + " limit 1", Long.class);
        assertThatThrownBy(() -> f.action.gateway.execute(ns + ".Record.complete",
            new OperationRequest(f.action.json("{}"), String.valueOf(id), 0, null, false), ActionFixture.admin()))
            .extracting(PackLifecycleTest::code).isEqualTo("PACK_DISABLED");
        assertThat(f.core.jdbc.queryForObject("select count(*) from " + table, Integer.class)).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, List<?>> data = (Map<String, List<?>>) f.lifecycle.export(ns).get("data");
        assertThat(data.get(ns + ".Record")).hasSize(1);
        assertThatThrownBy(() -> f.lifecycle.enable(cust)).extracting(PackLifecycleTest::code).isEqualTo("PACK_DEPENDENCY_DISABLED");
        f.lifecycle.enable(ns);
        assertThat(f.crud.crud.list(ns + ".Record", QuerySpec.all(), ActionFixture.admin()).total()).isEqualTo(1);
    }

    /** 客户 Pack：为培训记录追加两个字段，带验收场景。 */
    private Map<String, String> customer(String std, String stdRange) {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("pack.yaml", """
            code: {ns}
            version: 1.0.0
            name: 客户培训扩展
            owner: { name: 赵客户 }
            requester: { name: 钱主管 }
            reviewDue: 2099-01-01
            dependsOn:
              - { code: %s, version: ">=%s <2.0.0" }
            """.formatted(std, stdRange));
        c.put("concepts/record-ext.yaml", """
            extends: %s.Record
            fields:
              - { code: certNo, displayName: 证书编号, dataType: STRING }
              - { code: validUntil, displayName: 证书有效期, dataType: DATE }
            """.formatted(std));
        c.put("tests/scenarios/cert.yaml", """
            name: 登记带证书的培训
            steps:
              - create: { concept: %1$s.Record, as: r, values: { title: 特种作业, certNo: TZ-001, validUntil: "2030-01-01" } }
              - expect: { concept: %1$s.Record, target: r, values: { certNo: TZ-001 } }
            """.formatted(std));
        return c;
    }

    @Test
    void 标准Pack升级保留客户字段与数据_客户验收测试通过() {
        String ns = PackFixture.code("std");
        install(ns, f.training("1.0.0", "2099-01-01"));
        String cust = PackFixture.code("cust");
        install(cust, customer(ns, "1.0.0"));
        String table = com.lsmom.meta.concept.MetaNames.managedTable(ns + ".Record");
        Map<String, Object> rec = f.crud.crud.create(ns + ".Record", f.action.json("""
            {"title":"高处作业","certNo":"GC-9","validUntil":"2031-06-30","orgId":%d}""".formatted(f.plant.company().id())), ActionFixture.admin(), null);

        // 标准 Pack 1.1.0：新增字段、表单 v2
        Map<String, String> v11 = f.training("1.1.0", "2099-01-01");
        v11.put("concepts/record.yaml", v11.get("concepts/record.yaml") + "  - { code: hours, displayName: 学时, dataType: DECIMAL }\n");
        v11.put("views/record-form.json", v11.get("views/record-form.json").replace("\"version\":1", "\"version\":2")
            .replace("{\"type\":\"field\",\"key\":\"score\"}", "{\"type\":\"field\",\"key\":\"score\"},{\"type\":\"field\",\"key\":\"hours\"}")
            .replace("\"widget\":\"number\"}]", "\"widget\":\"number\"},{\"key\":\"hours\",\"label\":\"学时\",\"widget\":\"number\"}]"));
        PackInstaller.Result up = install(ns, v11);
        assertThat(up.status()).isEqualTo("UPGRADED");
        assertThat(up.previousVersion()).isEqualTo("1.0.0");
        assertThat(f.store.get(ns).version()).isEqualTo("1.1.0");

        var concept = f.action.concepts.get(ns + ".Record");
        assertThat(concept.fields()).extracting(com.lsmom.meta.concept.FieldDef::code).contains("title", "hours", "certNo", "validUntil");
        Map<String, Object> after = f.crud.crud.get(ns + ".Record", ((Number) rec.get("id")).longValue(), ActionFixture.admin());
        assertThat(after.get("certNo")).isEqualTo("GC-9");
        assertThat(String.valueOf(after.get("validUntil"))).startsWith("2031-06-30");
        assertThat(f.tester.run(cust).passed()).isTrue();
        assertThat(f.tester.run(ns).passed()).isTrue();
        // 漂移基线随升级更新：没有漂移
        assertThat(f.drift.report(ns).drifted()).isFalse();
        assertThat(f.drift.report(cust).drifted()).isFalse();

        // 降级与同版本改内容被拒；破坏已安装依赖方的升级被拒
        assertThatThrownBy(() -> install(ns, f.training("1.0.0", "2099-01-01"))).extracting(PackLifecycleTest::code).isEqualTo("PACK_DOWNGRADE");
        Map<String, String> changed = new LinkedHashMap<>(v11);
        changed.put("seed/records.yaml", changed.get("seed/records.yaml") + "  - { title: 新增种子 }\n");
        assertThatThrownBy(() -> install(ns, changed)).extracting(PackLifecycleTest::code).isEqualTo("PACK_VERSION_CONFLICT");
        assertThatThrownBy(() -> install(ns, f.training("2.0.0", "2099-01-01"))).extracting(PackLifecycleTest::code)
            .isEqualTo("PACK_DEPENDENT_BROKEN");
        assertThat(table).isNotBlank();
    }

    private void passStages(long id, String sha) {
        for (ChangeSetPipeline.Stage s : List.of(ChangeSetPipeline.Stage.SANDBOX, ChangeSetPipeline.Stage.REGRESSION,
            ChangeSetPipeline.Stage.PRIVILEGE, ChangeSetPipeline.Stage.UPGRADE)) {
            f.pipeline.recordStage(id, s, "PASSED", sha, null, 1L);
        }
    }

    @Test
    void ChangeSet流水线_未批准发布被拒_批准后发布() {
        String ns = PackFixture.code("cs");
        PackSource src = PackFixture.pack(ns, f.training("1.0.0", "2099-01-01"));
        ChangeSetPipeline.ChangeSet cs = f.pipeline.submit(src, 1L, "PACK", null);
        assertThat(cs.status()).isEqualTo("DRAFT");
        assertThat(cs.stages()).extracting(ChangeSetPipeline.StageResult::stage, ChangeSetPipeline.StageResult::status)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(ChangeSetPipeline.Stage.VALIDATE, "PASSED"));
        assertThat(cs.items().get(0).path("type").asText()).isEqualTo("PACK_INSTALL");

        assertThatThrownBy(() -> f.pipeline.approve(cs.id(), true, null, 2L)).extracting(PackLifecycleTest::code).isEqualTo("CHANGESET_NOT_READY");
        assertThatThrownBy(() -> f.pipeline.recordStage(cs.id(), ChangeSetPipeline.Stage.SANDBOX, "PASSED", "0".repeat(64), null, 1L))
            .extracting(PackLifecycleTest::code).isEqualTo("CHANGESET_ARTIFACT_MISMATCH");
        passStages(cs.id(), cs.artifactSha());
        assertThat(f.pipeline.get(cs.id()).status()).isEqualTo("REVIEWING");
        assertThat(f.pipeline.gate(cs.id()).ready()).isTrue();

        // 测试通过但未经批准 → 发布被拒
        assertThatThrownBy(() -> f.pipeline.release(cs.id(), 1L)).isInstanceOf(LsBizException.class)
            .satisfies(e -> assertThat(code(e)).isEqualTo("CHANGESET_NOT_APPROVED"));
        assertThat(f.store.find(ns)).isEmpty();

        f.pipeline.approve(cs.id(), true, "同意上线", 2L);
        PackInstaller.Result r = f.pipeline.release(cs.id(), 1L);
        assertThat(r.status()).isEqualTo("INSTALLED");
        assertThat(f.pipeline.get(cs.id()).status()).isEqualTo("APPLIED");
        assertThat(f.store.get(ns).artifactSha()).isEqualTo(cs.artifactSha());
        assertThat(f.pipeline.list(null)).extracting(ChangeSetPipeline.ChangeSet::id).contains(cs.id());
        assertThat(f.pipeline.list(ns)).extracting(ChangeSetPipeline.ChangeSet::id).containsExactly(cs.id());

        // 校验失败的提交直接驳回
        Map<String, String> bad = f.training("1.1.0", "2099-01-01");
        bad.put("pack.yaml", bad.get("pack.yaml").replace("dependsOn", "x") + "dependsOn:\n  - { code: acme.nothere, version: \">=1.0.0\" }\n");
        ChangeSetPipeline.ChangeSet rejected = f.pipeline.submit(PackFixture.pack(ns, bad), 1L, "PACK", null);
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.stages().get(0).detail().path("code").asText()).isEqualTo("PACK_DEPENDENCY_MISSING");
    }

    @Test
    void 手工改表单标签_漂移报告列出差异并可回灌为ChangeSet() {
        String ns = PackFixture.code("dr");
        install(ns, f.training("1.0.0", "2099-01-01"));
        assertThat(f.drift.report(ns).drifted()).isFalse();

        f.core.jdbc.update("""
            update meta_view set schema_json = jsonb_set(schema_json, '{fields,1,label}', '"受训人"') where code = ? and status = 'PUBLISHED'""",
            ns + ".recordForm");
        DriftDetector.Report rep = f.drift.report(ns);
        assertThat(rep.drifted()).isTrue();
        DriftDetector.ItemDrift d = rep.changed().get(0);
        assertThat(d.kind()).isEqualTo(PackDefinitions.Kind.VIEW);
        assertThat(d.path()).isEqualTo("views/record-form.json");
        assertThat(d.diffs()).extracting(PackJson.Diff::path).containsExactly("/fields/1/label");
        assertThat(d.diffs().get(0).expected().asText()).isEqualTo("学员");
        assertThat(d.diffs().get(0).actual().asText()).isEqualTo("受训人");

        ChangeSetPipeline.ChangeSet cs = f.drift.toChangeSet(ns, 1L);
        assertThat(cs.source()).isEqualTo("DRIFT");
        assertThat(cs.version()).isEqualTo("1.0.1");
        assertThat(cs.items()).anySatisfy(i -> {
            assertThat(i.path("type").asText()).isEqualTo("DRIFT");
            assertThat(i.path("diffs").get(0).path("path").asText()).isEqualTo("/fields/1/label");
        });
        assertThat(cs.stages().get(0).status()).isEqualTo("PASSED");
        PackSource next = f.store.artifact(cs.artifactSha());
        JsonNode view = PackJson.read("views/record-form.json", next.file("views/record-form.json"));
        assertThat(view.path("fields").get(1).path("label").asText()).isEqualTo("受训人");

        // 回灌版本发布后漂移消失
        passStages(cs.id(), cs.artifactSha());
        f.pipeline.approve(cs.id(), true, null, 2L);
        f.pipeline.release(cs.id(), 1L);
        assertThat(f.store.get(ns).version()).isEqualTo("1.0.1");
        assertThat(f.drift.report(ns).drifted()).isFalse();
        assertThatThrownBy(() -> f.drift.toChangeSet(ns, 1L)).extracting(PackLifecycleTest::code).isEqualTo("PACK_NO_DRIFT");
    }

    @Test
    void 超过复审日期且90天无使用_标记建议下线并通知负责人() {
        String idle = PackFixture.code("idle");
        install(idle, f.training("1.0.0", "2020-01-01"));
        f.core.jdbc.update("update meta_pack set installed_at = now() - interval '120 days' where code = ?", idle);
        String busy = PackFixture.code("busy");
        install(busy, f.training("1.0.0", "2020-01-01"));
        // busy 最近有使用
        f.core.jdbc.update("""
            insert into op_audit (op_code, actor_type, target_concept, result, duration_ms) values (?, 'HUMAN', ?, 'SUCCESS', 1)""",
            busy + ".Record.complete", busy + ".Record");
        String fresh = PackFixture.code("fresh");
        install(fresh, f.training("1.0.0", "2099-01-01"));

        List<PackReview.Change> changes = f.review.review();
        assertThat(changes).filteredOn(c -> c.pack().equals(idle)).singleElement()
            .satisfies(c -> {
                assertThat(c.to()).isEqualTo("SUGGEST_RETIRE");
                assertThat(c.notified()).isTrue();
            });
        assertThat(changes).filteredOn(c -> c.pack().equals(busy)).singleElement().extracting(PackReview.Change::to).isEqualTo("REVIEW_DUE");
        assertThat(changes).noneMatch(c -> c.pack().equals(fresh));
        assertThat(f.store.get(idle).reviewStatus()).isEqualTo("SUGGEST_RETIRE");
        assertThat(f.core.jdbc.queryForList("select message from biz_flow_notice where kind = 'PACK_REVIEW' and party_id = ? and biz_id = ?",
            String.class, f.owner.id(), idle)).singleElement().asString().contains("建议下线");
        assertThat(f.review.usage(busy).uses90d()).isEqualTo(1);
        // 状态不变时不重复通知
        assertThat(f.review.review()).noneMatch(c -> c.pack().equals(idle));
    }

    @Test
    void 升级演练中迁移失败_门禁阻止发布并输出失败迁移() {
        String ns = PackFixture.code("mg");
        install(ns, f.training("1.0.0", "2099-01-01"));
        Map<String, String> v11 = f.training("1.1.0", "2099-01-01");
        v11.put("migrations/V1__ok.sql", "select 1;");
        v11.put("migrations/V2__bad_column.sql", "alter table no_such_table add column x int;");
        assertThatThrownBy(() -> install(ns, v11)).isInstanceOf(LsBizException.class)
            .satisfies(e -> {
                assertThat(code(e)).isEqualTo("PACK_MIGRATION_FAILED");
                assertThat(((LsBizException) e).getDetails()).containsEntry("migration", "V2__bad_column.sql");
            });
        // 升级失败不影响已安装版本
        assertThat(f.store.get(ns).status()).isEqualTo("ENABLED");
        assertThat(f.store.get(ns).version()).isEqualTo("1.0.0");
        assertThat(f.store.get(ns).error()).contains("V2__bad_column.sql");

        ChangeSetPipeline.ChangeSet cs = f.pipeline.submit(PackFixture.pack(ns, v11), 1L, "PACK", null);
        f.pipeline.recordStage(cs.id(), ChangeSetPipeline.Stage.SANDBOX, "FAILED", cs.artifactSha(),
            PackJson.JSON.valueToTree(Map.of("migration", "V2__bad_column.sql", "message", "relation \"no_such_table\" does not exist")), 1L);
        f.pipeline.recordStage(cs.id(), ChangeSetPipeline.Stage.UPGRADE, "FAILED", cs.artifactSha(),
            PackJson.JSON.valueToTree(Map.of("migration", "V2__bad_column.sql")), 1L);
        ChangeSetPipeline.Gate g = f.pipeline.gate(cs.id());
        assertThat(g.ready()).isFalse();
        assertThat(String.join("；", g.problems())).contains("升级演练失败：迁移 V2__bad_column.sql");
        assertThatThrownBy(() -> f.pipeline.release(cs.id(), 1L)).isInstanceOf(LsBizException.class)
            .satisfies(e -> {
                assertThat(code(e)).isEqualTo("CHANGESET_GATE_FAILED");
                assertThat(e.getMessage()).contains("V2__bad_column.sql");
            });
        assertThatThrownBy(() -> f.pipeline.approve(cs.id(), true, null, 2L)).extracting(PackLifecycleTest::code).isEqualTo("CHANGESET_NOT_READY");
    }
}
