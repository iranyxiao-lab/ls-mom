package com.lsmom.biz.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.ActionFixture;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.OrgUnit;
import com.lsmom.core.org.OrgUnitType;
import com.lsmom.core.party.Party;
import com.lsmom.action.api.ActorContext;
import com.lsmom.test.PgTestSupport;
import com.lsmom.crud.GenericCrudService;
import com.lsmom.flow.runtime.FlowInstances;
import com.lsmom.flow.runtime.FlowRuntime;
import com.lsmom.pack.PackContents;
import com.lsmom.pack.PackFixture;
import com.lsmom.pack.PackInstaller;
import com.lsmom.pack.PackSource;
import com.lsmom.pack.PackTester;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 14.3：packs/examples/acme-toolloan 只用配置（YAML/JSON，无 Java）交付"工器具借还"台账：
 * 主子表、设备/人员引用、附件、条件必填（表单规则与提交规则）、提交 → 部门审核 → 退回 → 修改后再提交 → 设备员确认归档；
 * 发布后修改布局生成 v2，按 v1 保存的记录仍冻结在 v1（FORM-07）。业务数据来自切片 D 黄金场景数据集 {@code golden/slice-d.yaml}。
 */
class ToolLoanConfigPackTest {

    private static final Path EXAMPLE = Path.of("../../..").toAbsolutePath().normalize().resolve("packs/examples/acme-toolloan");

    final PackFixture f = new PackFixture();
    /** 切片 D 黄金场景数据集。 */
    final JsonNode ds = load();

    private static JsonNode load() {
        try (java.io.InputStream in = new org.springframework.core.io.ClassPathResource("golden/slice-d.yaml").getInputStream()) {
            return new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    ToolLoanConfigPackTest() {
        f.installer.install(AssetPackTest.source(f), null);
    }

    @Test
    void 只用配置交付借还台账并走完审批闭环() {
        // 配置包本身：只有声明的配置目录，没有任何代码文件
        PackSource dir = PackSource.fromDirectory(EXAMPLE);
        assertThat(dir.files().keySet()).allMatch(p -> p.endsWith(".yaml") || p.endsWith(".json") || p.endsWith(".md"));
        assertThat(PackContents.parse(dir).manifest().code()).isEqualTo("acme.toolloan");

        // 共享测试库：换成唯一命名空间与角色
        String ns = PackFixture.code("toolloan");
        String leaderRole = "wl_" + ns.substring(ns.lastIndexOf('.') + 1);
        String keeperRole = "ak_" + ns.substring(ns.lastIndexOf('.') + 1);
        // 审批流发布时校验办理人可解析：先有部门审核与设备员角色的人员
        OrgUnit dept = f.plant.workshop();
        Party applicant = f.core.employee("郑师傅", dept);
        Party leader = f.core.employee("周主任", dept);
        Party keeper = f.core.employee("王工", dept);
        f.flow.role(leaderRole, leader);
        f.flow.role(keeperRole, keeper);
        grant("acme:toolloan:flow", leaderRole, keeperRole);
        Map<String, byte[]> files = new LinkedHashMap<>();
        dir.files().forEach((k, v) -> files.put(k, new String(v, StandardCharsets.UTF_8).replace("acme.toolloan", ns)
            .replace("workshop_leader", leaderRole).replace("asset_keeper", keeperRole).getBytes(StandardCharsets.UTF_8)));
        PackInstaller.Result r = f.installer.install(PackSource.fromZip(PackSource.of(files).toZip()), 1L);
        assertThat(r.status()).isEqualTo("INSTALLED");
        PackTester.Report rep = f.tester.run(ns);
        assertThat(rep.regression()).allSatisfy(c -> assertThat(c.passed()).as(c.message()).isTrue());

        String order = ns + ".LoanOrder";
        String form = ns + ".loanForm";
        ActorContext app = f.flow.actor(applicant, perm(order, "add"), perm(order, "edit"), perm(order, "query"), perm(order, "list"), "acme:toolloan:submit");
        ActorContext lead = f.flow.actor(leader, "acme:toolloan:flow", perm(order, "query"));
        ActorContext keep = f.flow.actor(keeper, "acme:toolloan:flow", perm(order, "query"));
        String assetCode = PgTestSupport.uniq("WM");
        JsonNode a = ds.get("asset");
        long asset = ((Number) f.crud.crud.create(AssetPack.ASSET, json("""
            {"assetCode":"%s","name":"%s","assetType":"%s","orgId":%d}""".formatted(assetCode, a.get("name").asText(), a.get("type").asText(),
            dept.id())), ActionFixture.admin(), null).get("id")).longValue();

        // 条件必填：外协用途不填外协单位，通用保存即被表单规则拦下
        ObjectNode body = ds.get("loan").deepCopy();
        String outsourceUnit = body.remove("outsourceUnit").asText();
        body.put("borrower", applicant.id()).put("asset", asset).put("orgId", dept.id());
        body.putObject("_form").put("code", form).put("version", 1);
        ActorContext creator = app;
        assertThatThrownBy(() -> f.crud.crud.create(order, body, creator, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getMessage()).contains("外协单位"));
        body.put("outsourceUnit", outsourceUnit);
        String id = String.valueOf(f.crud.crud.create(order, body, app, null).get("id"));
        Map<String, Object> rec = f.crud.crud.get(order, Long.parseLong(id), app);
        assertThat(rec.get("schemaVersion")).hasToString(ds.get("expect").get("formVersionBefore").asText());
        assertThat(((java.util.List<?>) rec.get("loanLine"))).hasSize(ds.get("loan").get("loanLine").size());
        // 列表筛选（按设备引用）并展开设备、人员引用
        var page = f.crud.crud.list(order, new com.lsmom.crud.query.QuerySpec(
            java.util.List.of(new com.lsmom.crud.query.QuerySpec.Filter("asset", "eq", asset)), java.util.List.of(), 1, 20,
            java.util.List.of("asset", "borrower"), null, null), ActionFixture.admin());
        assertThat(page.rows()).singleElement().satisfies(row -> {
            assertThat(((Map<?, ?>) row.get("assetRef")).get("label")).isEqualTo(assetCode);
            assertThat(((Map<?, ?>) row.get("borrowerRef")).get("label")).isEqualTo("郑师傅");
            assertThat(String.valueOf(row.get("attachments"))).contains(ds.get("loan").get("attachments").get(0).get("name").asText());
        });

        // 导入预检与通用新增同一套表单校验：外协缺外协单位被拒；主表行不要求明细行数，并冻结表单版本
        var model = f.crud.crud.model(order);
        ActorContext imp = f.flow.actor(applicant, perm(order, "import"), perm(order, "add"));
        assertThatThrownBy(() -> f.crud.crud.precheck(model, json("""
            {"loanNo":"T-I1","borrower":%d,"loanDate":"2026-11-11","purpose":"OUTSOURCE","orgId":%d}""".formatted(applicant.id(), dept.id())), imp))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getMessage()).contains("外协单位"));
        assertThat(f.crud.crud.precheck(model, json("""
            {"loanNo":"T-I2","borrower":%d,"loanDate":"2026-11-11","purpose":"MAINTENANCE","orgId":%d}""".formatted(applicant.id(), dept.id())), imp))
            .containsEntry(GenericCrudService.FORM_VERSION_KEY, 1);

        // 提交即发起审批流 → 部门审核退回 → 发起人只能改 initiator 节点允许的字段后再提交
        f.action.on(order + ".submit", app, id, version(order, id), "{}");
        FlowInstances.Instance inst = f.flow.instances.ofBiz(order, id).get(0);
        Task audit = f.flow.task(inst.procInstId(), "deptAudit");
        JsonNode ap = ds.get("approval");
        f.flow.runtime.returnTask(audit.getId(), null, ap.get("returnReason").asText(), lead);
        assertThat(status(order, id)).isEqualTo("RETURNED");
        Task back = f.flow.task(inst.procInstId(), "initiator");
        ActorContext a2 = app;
        assertThatThrownBy(() -> f.flow.runtime.complete(back.getId(), FlowRuntime.Action.RESUBMIT, "改单号", ap.get("forbiddenEdit"), a2))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("FIELD_READONLY"));
        f.flow.runtime.complete(back.getId(), FlowRuntime.Action.RESUBMIT, "已补充", ap.get("resubmitEdit"), app);
        assertThat(status(order, id)).isEqualTo("IN_REVIEW");
        assertThat(f.action.stores.of(order).attributes(id).get("returnDue")).hasToString(ap.get("resubmitEdit").get("returnDue").asText());

        // 部门审核必须填审核意见 → 设备员确认归档
        Task audit2 = f.flow.task(inst.procInstId(), "deptAudit");
        assertThatThrownBy(() -> f.flow.runtime.complete(audit2.getId(), FlowRuntime.Action.APPROVE, "同意", json("{}"), lead))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED"));
        String auditNote = ap.get("auditNote").asText();
        f.flow.runtime.complete(audit2.getId(), FlowRuntime.Action.APPROVE, "同意", json("{\"auditNote\":\"" + auditNote + "\"}"), lead);
        Task confirm = f.flow.task(inst.procInstId(), "confirm");
        f.flow.runtime.complete(confirm.getId(), FlowRuntime.Action.APPROVE, ap.get("confirmComment").asText(), null, keep);
        assertThat(status(order, id)).isEqualTo(ds.get("expect").get("finalStatus").asText());
        assertThat(f.action.stores.of(order).attributes(id).get("auditNote")).isEqualTo(auditNote);

        // 已归档记录不能通用编辑
        assertThatThrownBy(() -> f.crud.crud.update(order, Long.parseLong(id), version(order, id), json("{\"remark\":\"改\"}"), creator, null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("GENERIC_EDIT_FORBIDDEN"));

        // FORM-07：改布局发布 v2（新增"领用班组"说明文字、调整顺序），旧单仍冻结在 v1
        var v2 = f.form.forms.derive(form, 1L);
        ObjectNode s2 = v2.schema().deepCopy();
        ArrayNode layout = (ArrayNode) s2.get("layout");
        layout.insert(0, json("{\"type\":\"text\",\"text\":\"借用前请核对工器具编号\"}"));
        f.form.forms.saveDraft(s2, 1L, "调整布局");
        assertThat(f.form.forms.publish(form, v2.version(), 1L).status()).isEqualTo("APPLIED");
        assertThat(v2.version()).isEqualTo(ds.get("expect").get("formVersionAfter").asInt());
        assertThat(f.crud.crud.get(order, Long.parseLong(id), app).get("schemaVersion")).hasToString("1");
        JsonNode v1 = f.form.forms.get(form, 1).schema();
        assertThat(v1.get("layout").get(0).path("key").asText()).isEqualTo("loanNo");
        assertThat(f.form.forms.published(form).orElseThrow().schema().get("layout").get(0).path("type").asText()).isEqualTo("text");
        assertThat(f.form.forms.resolveForEdit(form, 1)).containsEntry("outdated", true).containsEntry("latestVersion", 2);
    }

    private int version(String concept, String id) {
        return f.action.stores.of(concept).load(id).version();
    }

    private String status(String concept, String id) {
        return f.action.stores.of(concept).load(id).status();
    }

    private static String perm(String concept, String action) {
        return GenericCrudService.permission(concept, action);
    }

    /** 实施配置：建按钮权限并授予角色（等同系统管理里的菜单与角色配置）。 */
    private void grant(String perms, String... roleKeys) {
        var jdbc = f.core.jdbc;
        Long menu = jdbc.query("select menu_id from sys_menu where perms = ?", (rs, n) -> rs.getLong(1), perms).stream().findFirst().orElseGet(() -> {
            long id = jdbc.queryForObject("select greatest(max(menu_id), 990000) + 1 from sys_menu", Long.class);
            jdbc.update("""
                insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible,
                                      status, perms, icon, create_dept, create_by, create_time)
                values (?, ?, 0, 99, '', null, '', '1', '0', 'F', '0', '0', ?, '#', 103, 1, now())""", id, perms, perms);
            return id;
        });
        for (String k : roleKeys) {
            jdbc.update("""
                insert into sys_role_menu (role_id, menu_id) select role_id, ? from sys_role where role_key = ? on conflict do nothing""", menu, k);
        }
    }
}
