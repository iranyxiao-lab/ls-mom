package com.lsmom.pack.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.core.error.LsBizException;
import com.lsmom.pack.ChangeSetPipeline;
import com.lsmom.pack.PackFixture;
import com.lsmom.pack.PackJson;
import com.lsmom.pack.PackSource;
import com.lsmom.pack.PackTester;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 模板库：每个模板生成的 Pack 通过校验阶段，安装后黄金场景与越权测试通过。 */
class TemplateGeneratorTest {

    private final PackFixture f = new PackFixture();
    private final LedgerGenerator.Meta meta = new LedgerGenerator.Meta("设备科王工", "一车间周主任", null);

    @Test
    void 模板库包含四个模板() {
        assertThat(TemplateLibrary.all()).extracting(t -> t.path("code").asText()).containsExactly("approval", "checklist", "loan", "rectify");
        assertThatThrownBy(() -> TemplateLibrary.get("nothere")).isInstanceOf(LsBizException.class);
    }

    @Test
    void 每个模板生成的草稿通过校验_安装后测试通过() {
        for (JsonNode t : TemplateLibrary.all()) {
            // 测试库没有 ls.asset：去掉设备引用字段，引用人员的样例改为真实人员
            ObjectNode frag = t.deepCopy();
            var fields = frag.withArray("fields");
            for (int i = fields.size() - 1; i >= 0; i--) {
                if (fields.get(i).path("refConcept").asText().startsWith("ls.asset.")) fields.remove(i);
            }
            ObjectNode samples = frag.with("samples");
            fields.forEach(fl -> {
                if ("ls.core.Party".equals(fl.path("refConcept").asText())) samples.put(fl.path("code").asText(), f.owner.id());
            });
            // 测试库里给模板用到的角色各配一名人员，再按授权计划建权限并授予
            frag.path("flow").path("nodes").forEach(n -> f.flow.role(n.path("role").asText(), f.owner));
            f.flow.role("tpl_starter", f.owner);
            String ns = PackFixture.code("tpl" + t.path("code").asText());
            var grants = PermissionGrants.apply(f.core.jdbc, t.path("name").asText() + ns, PermissionGrants.plan(frag, ns, java.util.List.of("tpl_starter")));
            assertThat(grants).extracting(PermissionGrants.Grant::perms).contains(ns.replace('.', ':') + ":flow", "crud:" + ns + "." + frag.path("entity").asText() + ":add");
            Map<String, byte[]> files = LedgerGenerator.generate(frag, ns, t.path("name").asText(), meta);
            assertThat(new String(files.get("pack.yaml"), StandardCharsets.UTF_8)).contains("owner:", "requester:", "reviewDue:");
            ChangeSetPipeline.ChangeSet cs = f.pipeline.submit(PackSource.of(files), 1L, "TEMPLATE", null);
            assertThat(cs.status()).as(t.path("code").asText() + " " + cs.stages().get(0).detail()).isEqualTo("DRAFT");

            f.installer.install(PackSource.of(files), 1L);
            PackTester.Report r = f.tester.run(ns);
            assertThat(r.passed()).as(t.path("code").asText() + " " + r).isTrue();
        }
    }

    @Test
    void 同一编码再次生成_版本号自动提高并作为升级提交() {
        ObjectNode frag = TemplateLibrary.get("approval").deepCopy();
        String ns = PackFixture.code("tplver");
        TemplateController ctl = new TemplateController(f.pipeline, null, f.core.jdbc);
        assertThat(ctl.nextVersion(ns)).as("未安装").isNull();
        f.installer.install(PackSource.of(LedgerGenerator.generate(frag, ns, "审批", meta)), 1L);
        assertThat(ctl.nextVersion(ns)).isEqualTo("1.1.0");
        Map<String, byte[]> v2 = LedgerGenerator.generate(frag, ns, "审批 v2", new LedgerGenerator.Meta("设备科王工", "一车间周主任", null, ctl.nextVersion(ns)));
        assertThat(new String(v2.get("pack.yaml"), StandardCharsets.UTF_8)).contains("version: 1.1.0");
        ChangeSetPipeline.ChangeSet cs = f.pipeline.submit(PackSource.of(v2), 1L, "TEMPLATE", null);
        assertThat(cs.status()).as(String.valueOf(cs.stages().get(0).detail())).isEqualTo("DRAFT");
        assertThat(TemplateController.bumpMinor("2.3.4")).isEqualTo("2.4.0");
        assertThat(TemplateController.bumpMinor("x")).isNull();
    }

    @Test
    void 借还模板依赖设备Pack_缺少依赖时校验驳回() {
        String ns = PackFixture.code("tplloan");
        Map<String, byte[]> files = LedgerGenerator.generate(TemplateLibrary.get("loan"), ns, "借还登记", meta);
        assertThat(new String(files.get("pack.yaml"), StandardCharsets.UTF_8)).contains("ls.asset");
        ChangeSetPipeline.ChangeSet cs = f.pipeline.submit(PackSource.of(files), 1L, "TEMPLATE", null);
        assertThat(cs.status()).isEqualTo("REJECTED");
        assertThat(cs.stages().get(0).detail().path("code").asText()).isEqualTo("PACK_DEPENDENCY_MISSING");
    }

    @Test
    void 授权的角色不存在时整体拒绝() {
        String ns = PackFixture.code("tplbad");
        assertThatThrownBy(() -> PermissionGrants.apply(f.core.jdbc, "x", PermissionGrants.plan(TemplateLibrary.get("approval"), ns, java.util.List.of("no_such_role"))))
            .isInstanceOf(LsBizException.class).satisfies(e -> assertThat(((LsBizException) e).fieldErrors()).containsKey("roles.no_such_role"));
        assertThat(f.core.jdbc.queryForObject("select count(*) from sys_menu where perms like ?", Integer.class, "%" + ns + "%")).isZero();
    }

    @Test
    void 片段不合法时按字段指出问题() {
        ObjectNode bad = PackJson.JSON.createObjectNode();
        bad.put("entity", "bad name");
        bad.putArray("fields").addObject().put("code", "status").put("displayName", "状态").put("dataType", "ENUM");
        assertThatThrownBy(() -> LedgerGenerator.generate(bad, "Acme", "", new LedgerGenerator.Meta("", null, null)))
            .isInstanceOf(LsBizException.class)
            .satisfies(e -> assertThat(((LsBizException) e).fieldErrors()).containsKeys("ns", "title", "entity", "owner", "requester",
                "fields.0.code", "fields.0.options"));
    }
}
