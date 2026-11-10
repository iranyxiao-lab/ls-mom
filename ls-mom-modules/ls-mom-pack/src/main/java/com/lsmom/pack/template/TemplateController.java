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
                            java.time.LocalDate reviewDue, Map<String, String> files, List<String> initiatorRoles, Boolean grant) {
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
        // 当场授权需要角色管理权限；没有时授权清单随草稿提交，由管理员授权后才能发布
        boolean grantNow = Boolean.TRUE.equals(b.grant());
        if (grantNow && !cn.dev33.satoken.stp.StpUtil.hasPermission("system:role:edit")) {
            throw new LsBizException("ACCESS_DENIED", "没有角色管理权限，不能当场授权；请取消勾选，由管理员在「变更发布」中授权");
        }
        List<PermissionGrants.Grant> plan = PermissionGrants.plan(fragment(b), b.ns(), b.initiatorRoles());
        ChangeSetPipeline.ChangeSet cs = pipeline.submit(PackSource.of(files), actors.current().effectiveParty(), source, null);
        jdbc.update("update meta_change_set set grant_plan = ?::jsonb where id = ?", com.lsmom.pack.PackJson.JSON.valueToTree(plan).toString(), cs.id());
        if (grantNow) applyGrants(cs.id(), b.title() == null ? b.ns() : b.title());
        return R.ok(cs);
    }

    /** 草稿的授权清单与授权状态。 */
    public record GrantState(List<PermissionGrants.Grant> plan, java.time.LocalDateTime appliedAt) {
    }

    @SaCheckPermission("lsmom:pack:install")
    @GetMapping("/changesets/{id}/grants")
    public R<GrantState> grantState(@PathVariable long id) {
        return R.ok(grantStateOf(id));
    }

    /** 管理员执行草稿的授权清单（建按钮权限并授予角色）。 */
    @SaCheckPermission("system:role:edit")
    @PostMapping("/changesets/{id}/grants")
    public R<GrantState> applyChangeSetGrants(@PathVariable long id) {
        ChangeSetPipeline.ChangeSet cs = pipeline.get(id);
        if (grantStateOf(id).plan() == null) throw LsBizException.validation(Map.of("grants", "该变更没有授权清单"));
        applyGrants(id, cs.title());
        return R.ok(grantStateOf(id));
    }

    private void applyGrants(long id, String title) {
        List<PermissionGrants.Grant> plan = grantStateOf(id).plan();
        if (plan == null || plan.isEmpty()) return;
        PermissionGrants.apply(jdbc, title, plan);
        jdbc.update("update meta_change_set set grants_applied_at = now(), grants_applied_by = ? where id = ?", actors.current().effectiveParty(), id);
        logoutRoles(plan);
    }

    /**
     * 与系统"修改角色"一致：被授权角色的在线用户需重新登录，新权限才生效（权限随登录缓存在会话中）。
     * 不踢出当前操作人自己。
     */
    private void logoutRoles(List<PermissionGrants.Grant> plan) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        plan.forEach(g -> keys.addAll(g.roles()));
        if (keys.isEmpty()) return;
        java.util.Set<Long> roleIds = new java.util.HashSet<>(jdbc.queryForList(
            "select role_id from sys_role where del_flag = '0' and role_key in (" + String.join(",", java.util.Collections.nCopies(keys.size(), "?")) + ")",
            Long.class, keys.toArray()));
        String me = cn.dev33.satoken.stp.StpUtil.getTokenValue();
        for (String key : cn.dev33.satoken.stp.StpUtil.searchTokenValue("", 0, -1, false)) {
            String token = org.dromara.common.core.utils.StringUtils.substringAfterLast(key, ":");
            if (token.equals(me) || cn.dev33.satoken.stp.StpUtil.stpLogic.getTokenActiveTimeoutByToken(token) < -1) continue;
            org.dromara.common.core.domain.model.LoginUser u = org.dromara.common.satoken.utils.LoginHelper.getLoginUser(token);
            if (u == null || u.getRoles() == null || u.getRoles().stream().noneMatch(r -> roleIds.contains(r.getRoleId()))) continue;
            try {
                cn.dev33.satoken.stp.StpUtil.logoutByTokenValue(token);
            } catch (cn.dev33.satoken.exception.NotLoginException ignored) {
                // 已过期
            }
        }
    }

    private GrantState grantStateOf(long id) {
        List<GrantState> r = jdbc.query("select grant_plan, grants_applied_at from meta_change_set where id = ?", (rs, i) -> {
            String json = rs.getString("grant_plan");
            List<PermissionGrants.Grant> plan = json == null ? null : java.util.Arrays.asList(com.lsmom.pack.PackJson.JSON.convertValue(com.lsmom.pack.PackJson.read("grants.json", json.getBytes(StandardCharsets.UTF_8)), PermissionGrants.Grant[].class));
            return new GrantState(plan, rs.getObject("grants_applied_at", java.time.LocalDateTime.class));
        }, id);
        if (r.isEmpty()) throw LsBizException.notFound("ChangeSet", String.valueOf(id));
        return r.get(0);
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

    private Map<String, byte[]> generate(DraftBody b) {
        return LedgerGenerator.generate(fragment(b), b.ns(), b.title(), new LedgerGenerator.Meta(b.owner(), b.requester(), b.reviewDue(), nextVersion(b.ns())));
    }

    /** 同一编码已安装时生成下一个次版本（1.0.0 → 1.1.0），作为升级提交；未安装为 null（1.0.0） */
    String nextVersion(String ns) {
        if (ns == null || ns.isBlank()) return null;
        List<String> v = jdbc.queryForList("select version from meta_pack where code = ? and status <> 'UNINSTALLED'", String.class, ns.trim());
        return v.isEmpty() ? null : bumpMinor(v.get(0));
    }

    static String bumpMinor(String version) {
        String[] p = version.split("[.-]");
        try {
            return Integer.parseInt(p[0]) + "." + (Integer.parseInt(p.length > 1 ? p[1] : "0") + 1) + ".0";
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
