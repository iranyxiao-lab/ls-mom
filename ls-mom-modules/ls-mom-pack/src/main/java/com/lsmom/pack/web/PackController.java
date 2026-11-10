package com.lsmom.pack.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.core.error.LsBizException;
import com.lsmom.pack.ChangeSetPipeline;
import com.lsmom.pack.DriftDetector;
import com.lsmom.pack.PackInstaller;
import com.lsmom.pack.PackLifecycle;
import com.lsmom.pack.PackReview;
import com.lsmom.pack.PackSource;
import com.lsmom.pack.PackManifest;
import com.lsmom.pack.PackStore;
import com.lsmom.pack.PackTester;
import com.lsmom.pack.SemVer;
import org.dromara.common.core.domain.R;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 协同应用中心与 Pack 生命周期接口。正式环境只能经 ChangeSet 发布；直接安装只在沙箱 / 演练环境开启
 * （lsmom.pack.direct-install=true）。
 */
@RestController
@RequestMapping("/api")
public class PackController {

    private final PackStore store;
    private final PackInstaller installer;
    private final PackLifecycle lifecycle;
    private final PackTester tester;
    private final DriftDetector drift;
    private final PackReview review;
    private final ChangeSetPipeline pipeline;
    private final ActorResolver actors;
    private final boolean directInstall;

    public PackController(PackStore store, PackInstaller installer, PackLifecycle lifecycle, PackTester tester, DriftDetector drift, PackReview review,
                          ChangeSetPipeline pipeline, ActorResolver actors, @Value("${lsmom.pack.direct-install:false}") boolean directInstall) {
        this.store = store;
        this.installer = installer;
        this.lifecycle = lifecycle;
        this.tester = tester;
        this.drift = drift;
        this.review = review;
        this.pipeline = pipeline;
        this.actors = actors;
        this.directInstall = directInstall;
    }

    /** 协同应用中心：版本、依赖、负责人、复审、使用量、失败任务、漂移、待升级与下线状态。 */
    @GetMapping("/packs")
    public R<List<Map<String, Object>>> list() {
        return R.ok(store.list().stream().map(p -> summary(p, false)).toList());
    }

    /**
     * 导航菜单用：已启用 Pack 的入口及其菜单分组（工作／台账／分析／配置）。
     * 菜单是否可见由前端按用户已有的路由与权限过滤；这里只返回入口清单，不含敏感信息。
     */
    @GetMapping("/packs/menu")
    public R<List<Map<String, Object>>> menu() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (PackStore.Pack p : store.list()) {
            if (!"ENABLED".equals(p.status())) continue;
            for (com.fasterxml.jackson.databind.JsonNode e : p.manifest().path("entries")) {
                PackManifest.Entry entry = new PackManifest.Entry(e.path("name").asText(), e.path("path").asText(), e.path("group").asText(null));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pack", p.code());
                m.put("packName", p.name());
                m.put("name", entry.name());
                m.put("path", entry.path());
                m.put("group", entry.effectiveGroup());
                out.add(m);
            }
        }
        return R.ok(out);
    }

    @GetMapping("/packs/{code}")
    public R<Map<String, Object>> get(@PathVariable String code) {
        return R.ok(summary(store.get(code), true));
    }

    /** 来源追溯：生成 / 转换来源、许可证、转换器与模型版本、人工修改历史；concept 指定时返回该 Concept 的来源（AI-03）。 */
    @GetMapping("/packs/{code}/provenance")
    public R<JsonNode> provenance(@PathVariable String code, @RequestParam(required = false) String concept) {
        return R.ok(com.lsmom.pack.PackProvenance.of(store, code, concept));
    }

    private Map<String, Object> summary(PackStore.Pack p, boolean detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.code());
        m.put("name", p.name());
        m.put("version", p.version());
        m.put("previousVersion", p.previousVersion());
        m.put("status", p.status());
        m.put("standard", p.standard());
        m.put("owner", p.owner());
        m.put("requester", p.requester());
        m.put("reviewDue", p.reviewDue());
        m.put("reviewStatus", p.reviewStatus());
        m.put("dependsOn", p.dependsOn());
        m.put("dependents", store.dependents(p.code()));
        m.put("entries", p.manifest().path("entries"));
        m.put("description", p.manifest().path("description").asText(null));
        m.put("installedAt", p.installedAt());
        m.put("disabledAt", p.disabledAt());
        m.put("error", p.error());
        PackReview.Usage u = review.usage(p.code());
        m.put("uses90d", u.uses90d());
        m.put("lastUsedAt", u.lastUsedAt());
        m.put("failedTasks", u.failedTasks());
        String latest = store.latestArtifactVersion(p.code()).orElse(p.version());
        m.put("latestVersion", latest);
        m.put("upgradeAvailable", SemVer.parse(latest).compareTo(SemVer.parse(p.version())) > 0);
        DriftDetector.Report d = p.artifactSha() == null ? null : drift.report(p.code());
        m.put("drifted", d != null && d.drifted());
        m.put("driftCount", d == null ? 0 : d.changed().size());
        if (detail) {
            m.put("items", store.items(p.code()).stream().map(i -> Map.of("kind", i.kind(), "code", i.code(),
                "version", i.version() == null ? "" : i.version(), "path", i.path())).toList());
            m.put("changeSets", pipeline.list(p.code()).stream().map(c -> Map.of("id", c.id(), "title", c.title(), "status", c.status(),
                "version", c.version() == null ? "" : c.version())).toList());
        }
        return m;
    }

    public record Archive(String archive) {
        byte[] bytes() {
            if (archive == null || archive.isBlank()) throw LsBizException.validation(Map.of("archive", "请上传 Pack 文件（zip，base64）"));
            try {
                return Base64.getDecoder().decode(archive.replaceFirst("^data:[^,]*,", ""));
            } catch (IllegalArgumentException e) {
                throw LsBizException.validation(Map.of("archive", "不是有效的 base64"));
            }
        }
    }

    /** 直接安装：只在沙箱 / 升级演练环境开启。 */
    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/packs/install")
    public R<PackInstaller.Result> install(@RequestBody Archive body) {
        if (!directInstall) {
            throw new LsBizException("PACK_DIRECT_INSTALL_DISABLED", "当前环境只能经 ChangeSet 审批后发布 Pack");
        }
        return R.ok(installer.install(PackSource.fromZip(body.bytes()), actors.current().effectiveParty()));
    }

    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/packs/{code}/disable")
    public R<Void> disable(@PathVariable String code) {
        lifecycle.disable(code);
        return R.ok();
    }

    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/packs/{code}/enable")
    public R<Void> enable(@PathVariable String code) {
        lifecycle.enable(code);
        return R.ok();
    }

    @SaCheckPermission("lsmom:pack:install")
    @GetMapping("/packs/{code}/export")
    public R<Map<String, Object>> export(@PathVariable String code) {
        return R.ok(lifecycle.export(code));
    }

    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/packs/{code}/test")
    public R<PackTester.Report> test(@PathVariable String code) {
        return R.ok(tester.run(code));
    }

    @GetMapping("/packs/{code}/drift")
    public R<DriftDetector.Report> drift(@PathVariable String code) {
        return R.ok(drift.report(code));
    }

    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/packs/{code}/drift/changeset")
    public R<ChangeSetPipeline.ChangeSet> driftToChangeSet(@PathVariable String code) {
        return R.ok(drift.toChangeSet(code, actors.current().effectiveParty()));
    }

    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/packs/review/run")
    public R<List<PackReview.Change>> runReview() {
        return R.ok(review.review());
    }

    // —— ChangeSet ——

    @GetMapping("/changesets")
    public R<List<ChangeSetPipeline.ChangeSet>> changeSets(@RequestParam(required = false) String pack) {
        return R.ok(pipeline.list(pack));
    }

    @GetMapping("/changesets/{id}")
    public R<ChangeSetPipeline.ChangeSet> changeSet(@PathVariable long id) {
        return R.ok(pipeline.get(id));
    }

    @GetMapping("/changesets/{id}/gate")
    public R<ChangeSetPipeline.Gate> gate(@PathVariable long id) {
        return R.ok(pipeline.gate(id));
    }

    /** 下载制品（base64），供升级演练脚本安装到临时环境。 */
    @SaCheckPermission("lsmom:pack:install")
    @GetMapping("/changesets/{id}/artifact")
    public R<Map<String, String>> artifact(@PathVariable long id) {
        ChangeSetPipeline.ChangeSet cs = pipeline.get(id);
        return R.ok(Map.of("sha256", cs.artifactSha(), "archive", Base64.getEncoder().encodeToString(store.artifactZip(cs.artifactSha()))));
    }

    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/changesets")
    public R<ChangeSetPipeline.ChangeSet> submit(@RequestBody Archive body) {
        return R.ok(pipeline.submit(PackSource.fromZip(body.bytes()), actors.current().effectiveParty(), "PACK", null));
    }

    public record StageBody(ChangeSetPipeline.Stage stage, String status, String artifactSha, JsonNode detail) {
    }

    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/changesets/{id}/stages")
    public R<ChangeSetPipeline.ChangeSet> stage(@PathVariable long id, @RequestBody StageBody b) {
        return R.ok(pipeline.recordStage(id, b.stage(), b.status(), b.artifactSha(), b.detail(), actors.current().effectiveParty()));
    }

    public record Approval(boolean approve, String note) {
    }

    @SaCheckPermission("lsmom:changeset:review")
    @PostMapping("/changesets/{id}/approve")
    public R<ChangeSetPipeline.ChangeSet> approve(@PathVariable long id, @RequestBody Approval b) {
        return R.ok(pipeline.approve(id, b.approve(), b.note(), actors.current().effectiveParty()));
    }

    private com.lsmom.core.task.BackgroundTasks tasks;
    private java.util.concurrent.Executor taskExecutor;

    /** 任务中心：Pack 发布在后台执行，进度与结果在任务中心查看 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTasks(com.lsmom.core.task.BackgroundTasks tasks,
                         @org.springframework.beans.factory.annotation.Qualifier(com.lsmom.crud.CrudConfiguration.EXPORT_EXECUTOR)
                         java.util.concurrent.Executor executor) {
        this.tasks = tasks;
        this.taskExecutor = executor;
    }

    /** 后台发布（安装 / 升级）：返回任务编号，发起人可离开页面，完成后通知。 */
    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/changesets/{id}/release/async")
    public R<Long> releaseAsync(@PathVariable long id) {
        if (tasks == null) throw new LsBizException("TASKS_UNAVAILABLE", "任务中心不可用");
        var actor = actors.current();
        ChangeSetPipeline.ChangeSet cs = pipeline.get(id);
        long task = tasks.start("PACK_INSTALL", "发布 Pack：" + cs.title(), actor.userId(), 1);
        taskExecutor.execute(() -> {
            try {
                tasks.finish(task, pipeline.release(id, actor));
            } catch (LsBizException e) {
                tasks.fail(task, e.getMessage());
            } catch (RuntimeException e) {
                tasks.fail(task, "安装失败：" + e.getMessage());
            }
        });
        return R.ok(task);
    }

    @SaCheckPermission("lsmom:pack:install")
    @PostMapping("/changesets/{id}/release")
    public R<PackInstaller.Result> release(@PathVariable long id) {
        return R.ok(pipeline.release(id, actors.current()));
    }
}
