package com.lsmom.pack.template;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.core.error.LsBizException;
import com.lsmom.pack.ChangeSetPipeline;
import com.lsmom.pack.PackSource;
import org.dromara.common.core.domain.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从模板（或 Excel 推断结果）生成 Pack 草稿：先预览生成的文件（可在页面里改），确认后提交为 ChangeSet，
 * 走与手写 Pack 相同的校验、沙箱、回归、权限与升级演练阶段，不会直接生效。
 */
@RestController
@RequestMapping("/api/pack-templates")
public class TemplateController {

    /** fragment 与 template 二选一；files 为预览后人工修改过的文件（路径 → 文本），提供时直接用它提交 */
    public record DraftBody(String template, JsonNode fragment, String ns, String title, String owner, String requester,
                            java.time.LocalDate reviewDue, Map<String, String> files, List<String> initiatorRoles) {
    }

    private final ChangeSetPipeline pipeline;
    private final ActorResolver actors;
    private final JdbcTemplate jdbc;

    public TemplateController(ChangeSetPipeline pipeline, ActorResolver actors, JdbcTemplate jdbc) {
        this.pipeline = pipeline;
        this.actors = actors;
        this.jdbc = jdbc;
    }

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(TemplateLibrary.all().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", t.path("code").asText());
            m.put("name", t.path("name").asText());
            m.put("category", t.path("category").asText());
            m.put("description", t.path("description").asText());
            m.put("entity", t.path("entity").asText());
            m.put("fields", t.path("fields").size());
            m.put("children", t.path("children").size());
            m.put("nodes", t.path("flow").path("nodes").size());
            return m;
        }).toList());
    }

    @GetMapping("/{code}")
    public R<JsonNode> get(@PathVariable String code) {
        return R.ok(TemplateLibrary.get(code));
    }

    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/preview")
    public R<Map<String, String>> preview(@RequestBody DraftBody b) {
        Map<String, String> out = new LinkedHashMap<>();
        generate(b).forEach((k, v) -> out.put(k, new String(v, StandardCharsets.UTF_8)));
        return R.ok(out);
    }

    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/draft")
    public R<ChangeSetPipeline.ChangeSet> draft(@RequestBody DraftBody b) {
        Map<String, byte[]> files;
        if (b.files() != null && !b.files().isEmpty()) {
            files = new LinkedHashMap<>();
            b.files().forEach((k, v) -> files.put(k, v.getBytes(StandardCharsets.UTF_8)));
        } else {
            files = generate(b);
        }
        String source = b.fragment() != null && "EXCEL".equals(b.fragment().path("provenance").path("source").asText()) ? "EXCEL" : "TEMPLATE";
        return R.ok(pipeline.submit(PackSource.of(files), actors.current().effectiveParty(), source, null));
    }

    /** Excel 表头与前 50 行（单元格文本） */
    public record InferBody(String fileName, String sheet, List<String> headers, List<List<String>> rows) {
    }

    /** 由 Excel 推断台账字段（类型、二值字典、设备 / 人员 / 组织引用、必填），输出可在向导里修改的 LS-IR 片段。 */
    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/infer")
    public R<JsonNode> infer(@RequestBody InferBody b) {
        return R.ok(new ExcelInference(new JdbcRefMatcher(jdbc)).infer(b.fileName(), b.sheet(), b.headers(), b.rows()));
    }

    /** 生成的台账需要授予哪些权限（发起角色、各审批节点角色）。 */
    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/grants/plan")
    public R<List<PermissionGrants.Grant>> grantPlan(@RequestBody DraftBody b) {
        return R.ok(PermissionGrants.plan(fragment(b), b.ns(), b.initiatorRoles()));
    }

    /** 建按钮权限并授予角色（审批流发布前需要节点办理人具备办理权限）。 */
    @SaCheckPermission("system:role:edit")
    @PostMapping("/grants")
    public R<List<PermissionGrants.Grant>> grant(@RequestBody DraftBody b) {
        return R.ok(PermissionGrants.apply(jdbc, b.title() == null ? b.ns() : b.title(), PermissionGrants.plan(fragment(b), b.ns(), b.initiatorRoles())));
    }

    private static JsonNode fragment(DraftBody b) {
        JsonNode fragment = b.fragment() != null && !b.fragment().isNull() ? b.fragment()
            : b.template() != null ? TemplateLibrary.get(b.template()) : null;
        if (fragment == null) throw LsBizException.validation(Map.of("template", "请选择模板"));
        return fragment;
    }

    private static Map<String, byte[]> generate(DraftBody b) {
        return LedgerGenerator.generate(fragment(b), b.ns(), b.title(), new LedgerGenerator.Meta(b.owner(), b.requester(), b.reviewDue()));
    }
}
