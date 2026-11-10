package com.lsmom.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ChangeSet 发布链路：提交（校验与依赖分析）→ 沙箱安装 → 回归 / 越权 / 升级测试 → 人工批准 → 发布。
 * 沙箱与升级测试在临时环境执行（deploy/scripts/upgrade-drill.sh：恢复上一版本备份 → 安装制品 → 运行 Pack 测试），
 * 结果按制品摘要回写到这里；任何阶段缺失或失败、或未经批准，发布都会被拒绝。
 */
public class ChangeSetPipeline {

    public enum Stage { VALIDATE, SANDBOX, REGRESSION, PRIVILEGE, UPGRADE }

    public record StageResult(Stage stage, String status, String artifactSha, JsonNode detail, Long runBy, LocalDateTime runAt) {
    }

    public record ChangeSet(long id, String pack, String title, String status, boolean breaking, String source, JsonNode items,
                            String artifactSha, String version, Long createdBy, Long reviewedBy, String reviewNote, LocalDateTime createdAt,
                            LocalDateTime releasedAt, List<StageResult> stages) {
    }

    public record Gate(boolean ready, List<String> problems) {
    }

    private final JdbcTemplate jdbc;
    private final PackStore store;
    private final PackInstaller installer;
    private final PackDefinitions defs;

    public ChangeSetPipeline(JdbcTemplate jdbc, PackStore store, PackInstaller installer, PackDefinitions defs) {
        this.jdbc = jdbc;
        this.store = store;
        this.installer = installer;
        this.defs = defs;
    }

    /** 提交制品：保存后立即执行"校验与依赖分析"阶段；校验失败的 ChangeSet 直接驳回。 */
    public ChangeSet submit(PackSource src, Long by, String source, JsonNode extraItems) {
        PackManifest m;
        try {
            m = PackManifest.parse(src);
        } catch (LsBizException e) {
            throw e;
        }
        String sha = store.saveArtifact(src, m, by);
        Optional<PackStore.Pack> installed = store.find(m.code());
        ArrayNode items = PackJson.JSON.createArrayNode();
        ObjectNode item = items.addObject();
        item.put("type", installed.isPresent() ? "PACK_UPGRADE" : "PACK_INSTALL");
        item.put("pack", m.code());
        item.put("from", installed.map(PackStore.Pack::version).orElse(null));
        item.put("to", m.version());
        item.put("artifact", sha);
        if (extraItems != null) extraItems.forEach(items::add);

        ObjectNode detail = PackJson.JSON.createObjectNode();
        String status = "PASSED";
        boolean breaking = false;
        try {
            PackContents c = installer.check(src);
            List<String> reasons = breaking(c);
            breaking = !reasons.isEmpty();
            detail.set("breaking", PackJson.JSON.valueToTree(reasons));
            detail.set("order", PackJson.JSON.valueToTree(DependencyPlanner.plan(List.of(m), store.installed()).stream().map(PackManifest::code).toList()));
            detail.put("migrations", c.migrations().size());
            detail.put("definitions", c.concepts().size() + c.machines().size() + c.rules().size() + c.views().size() + c.metrics().size() + c.flows().size());
        } catch (LsBizException e) {
            status = "FAILED";
            detail.put("code", e.getCode());
            detail.put("message", e.getMessage());
            detail.set("details", PackJson.JSON.valueToTree(e.getDetails()));
        }
        String title = (installed.isPresent() ? "升级 " : "安装 ") + m.code() + " " + installed.map(p -> p.version() + " → ").orElse("") + m.version()
            + ("DRIFT".equals(source) ? "（漂移回灌）" : "");
        Long id = jdbc.queryForObject("""
            insert into meta_change_set (pack_code, title, status, breaking, items, source, created_by, artifact_sha)
            values (?, ?, ?, ?, ?::jsonb, ?, ?, ?) returning id""", Long.class,
            m.code(), title, "PASSED".equals(status) ? "DRAFT" : "REJECTED", breaking, items.toString(), source, by, sha);
        saveStage(id, Stage.VALIDATE, status, sha, detail, by);
        return get(id);
    }

    /** 破坏性变更：删除本 Pack 已有字段或修改字段类型（须独立审批、升级前备份与前向修复脚本）。 */
    private List<String> breaking(PackContents c) {
        List<String> out = new ArrayList<>();
        for (PackContents.Item i : c.concepts()) {
            boolean ext = i.body().hasNonNull("extends");
            Optional<PackDefinitions.Snapshot> cur = defs.current(PackDefinitions.Kind.CONCEPT, i.code(), c.manifest().code(), ext);
            if (cur.isEmpty()) continue;
            Map<String, String> before = new LinkedHashMap<>();
            cur.get().form().path("fields").forEach(f -> before.put(f.path("code").asText(), f.path("dataType").asText()));
            Map<String, String> after = new LinkedHashMap<>();
            i.body().path("fields").forEach(f -> after.put(f.path("code").asText(), f.path("dataType").asText()));
            before.forEach((k, t) -> {
                if (!after.containsKey(k)) out.add(i.code() + " 删除字段 " + k);
                else if (!after.get(k).equals(t)) out.add(i.code() + " 字段 " + k + " 类型 " + t + " → " + after.get(k));
            });
        }
        return out;
    }

    /** 回写临时环境中的阶段结果。制品摘要必须与 ChangeSet 一致。 */
    public ChangeSet recordStage(long id, Stage stage, String status, String artifactSha, JsonNode detail, Long by) {
        if (stage == Stage.VALIDATE) throw new LsBizException("CHANGESET_STAGE_INVALID", "校验阶段在提交时自动执行");
        if (!"PASSED".equals(status) && !"FAILED".equals(status)) throw LsBizException.validation(Map.of("status", "只能为 PASSED 或 FAILED"));
        ChangeSet cs = get(id);
        if (!"DRAFT".equals(cs.status()) && !"REVIEWING".equals(cs.status())) {
            throw new LsBizException("CHANGESET_STATE_INVALID", "ChangeSet 当前状态为 " + cs.status() + "，不能再记录测试结果");
        }
        if (!cs.artifactSha().equals(artifactSha)) {
            throw new LsBizException("CHANGESET_ARTIFACT_MISMATCH", "测试结果对应的制品与 ChangeSet 不一致", Map.of("expected", cs.artifactSha()));
        }
        saveStage(id, stage, status, artifactSha, detail == null ? PackJson.JSON.createObjectNode() : detail, by);
        boolean allPassed = stages(id).size() == Stage.values().length && stages(id).stream().allMatch(s -> "PASSED".equals(s.status()));
        jdbc.update("update meta_change_set set status = ? where id = ?", allPassed ? "REVIEWING" : "DRAFT", id);
        return get(id);
    }

    /** 人工审批：测试全部通过（REVIEWING）后才能批准。 */
    public ChangeSet approve(long id, boolean approve, String note, Long by) {
        ChangeSet cs = get(id);
        if (!"REVIEWING".equals(cs.status())) {
            throw new LsBizException("CHANGESET_NOT_READY", "ChangeSet 状态为 " + cs.status() + "，须沙箱、回归、越权、升级测试全部通过后才能审批",
                Map.of("gate", gate(id).problems()));
        }
        jdbc.update("update meta_change_set set status = ?, reviewed_by = ?, review_note = ? where id = ?",
            approve ? "APPROVED" : "REJECTED", by, note, id);
        return get(id);
    }

    /** 发布门禁：各阶段通过且针对同一制品。 */
    public Gate gate(long id) {
        ChangeSet cs = get(id);
        Map<Stage, StageResult> byStage = new EnumMap<>(Stage.class);
        cs.stages().forEach(s -> byStage.put(s.stage(), s));
        List<String> problems = new ArrayList<>();
        String lastFailure = null;
        for (Stage s : Stage.values()) {
            StageResult r = byStage.get(s);
            if (r == null) {
                problems.add(label(s) + "未执行");
            } else if (!"PASSED".equals(r.status())) {
                String f = failure(r.detail());
                problems.add(label(s) + "失败" + (f.equals(lastFailure) && !f.isEmpty() ? "（同上）" : f));
                lastFailure = f;
            } else if (!cs.artifactSha().equals(r.artifactSha())) {
                problems.add(label(s) + "结果不是针对当前制品");
            }
        }
        problems.addAll(openManualItems(cs.artifactSha()));
        problems.addAll(pendingGrants(id));
        return new Gate(problems.isEmpty(), problems);
    }

    /** 模板 / Excel 草稿带的授权清单尚未执行：审批节点角色没有办理权限，流程无法办理。 */
    List<String> pendingGrants(long id) {
        List<Boolean> pending = jdbc.queryForList("select grant_plan is not null and jsonb_array_length(grant_plan) > 0 and grants_applied_at is null from meta_change_set where id = ?",
            Boolean.class, id);
        return !pending.isEmpty() && Boolean.TRUE.equals(pending.get(0)) ? List.of("授权未完成：需管理员在「变更发布」中执行授权清单") : List.of();
    }

    /** 制品来源记录（provenance.json）中未处理的"需人工 / 禁止自动转换"项：处理并关闭前不能发布。 */
    List<String> openManualItems(String sha) {
        if (sha == null) return List.of();
        PackSource src = store.artifact(sha);
        if (src.file(PackContents.PROVENANCE) == null) return List.of();
        JsonNode prov = PackJson.read(PackContents.PROVENANCE, src.file(PackContents.PROVENANCE));
        List<String> out = new ArrayList<>();
        for (JsonNode m : prov.path("manualRequired")) {
            if ("RESOLVED".equals(m.path("status").asText())) continue;
            out.add(("BLOCKED".equals(m.path("kind").asText()) ? "禁止自动转换项未处理：" : "需人工项未处理：") + m.path("path").asText() + "（" + m.path("reason").asText() + "）");
        }
        return out;
    }

    private static String failure(JsonNode d) {
        if (d == null) return "";
        String mg = d.path("migration").asText("");
        String msg = d.path("message").asText(d.path("error").asText(""));
        return (mg.isEmpty() ? "" : "：迁移 " + mg) + (msg.isEmpty() ? "" : "：" + msg);
    }

    private static String label(Stage s) {
        return switch (s) {
            case VALIDATE -> "校验与依赖分析";
            case SANDBOX -> "沙箱安装";
            case REGRESSION -> "回归测试";
            case PRIVILEGE -> "越权测试";
            case UPGRADE -> "升级演练";
        };
    }

    /**
     * 发布到当前环境：只有真人可以发布（生成流水线、Agent、服务账号均无发布权限）；门禁通过且已批准，才执行安装。
     */
    public PackInstaller.Result release(long id, com.lsmom.action.api.ActorContext actor) {
        if (actor == null || actor.type() != com.lsmom.action.api.ActorContext.ActorType.HUMAN) {
            throw new LsBizException("CHANGESET_RELEASE_HUMAN_ONLY", "只有真人可以发布 ChangeSet，生成流水线与 Agent 不能直接发布",
                Map.of("actorType", actor == null ? "NONE" : actor.type().name()));
        }
        Long by = actor.effectiveParty();
        ChangeSet cs = get(id);
        if ("APPLIED".equals(cs.status())) throw new LsBizException("CHANGESET_STATE_INVALID", "ChangeSet 已发布");
        Gate g = gate(id);
        if (!g.ready()) {
            throw new LsBizException("CHANGESET_GATE_FAILED", "发布门禁未通过：" + String.join("；", g.problems()), Map.of("problems", g.problems()));
        }
        if (!"APPROVED".equals(cs.status())) {
            throw new LsBizException("CHANGESET_NOT_APPROVED", "ChangeSet 未经人工批准，不能发布（当前 " + cs.status() + "）");
        }
        PackInstaller.Result r = installer.install(store.artifact(cs.artifactSha()), by);
        jdbc.update("update meta_change_set set status = 'APPLIED', applied_at = now(), released_at = now(), released_by = ? where id = ?", by, id);
        return r;
    }

    private void saveStage(long id, Stage stage, String status, String sha, JsonNode detail, Long by) {
        jdbc.update("""
            insert into meta_change_set_stage (change_set_id, stage, status, artifact_sha, detail, run_by) values (?, ?, ?, ?, ?::jsonb, ?)
            on conflict (change_set_id, stage) do update set status = excluded.status, artifact_sha = excluded.artifact_sha,
                detail = excluded.detail, run_by = excluded.run_by, run_at = now()""", id, stage.name(), status, sha, detail.toString(), by);
    }

    private List<StageResult> stages(long id) {
        return jdbc.query("select * from meta_change_set_stage where change_set_id = ? order by run_at", (rs, i) -> new StageResult(
            Stage.valueOf(rs.getString("stage")), rs.getString("status"), rs.getString("artifact_sha"), PackStore.read(rs.getString("detail")),
            (Long) rs.getObject("run_by"), rs.getObject("run_at", LocalDateTime.class)), id);
    }

    public ChangeSet get(long id) {
        return jdbc.query("""
                select c.*, a.version as artifact_version from meta_change_set c left join pack_artifact a on a.sha256 = c.artifact_sha
                 where c.id = ?""", (rs, i) -> new ChangeSet(rs.getLong("id"), rs.getString("pack_code"), rs.getString("title"),
                rs.getString("status"), rs.getBoolean("breaking"), rs.getString("source"), PackStore.read(rs.getString("items")),
                rs.getString("artifact_sha"), rs.getString("artifact_version"), (Long) rs.getObject("created_by"), (Long) rs.getObject("reviewed_by"),
                rs.getString("review_note"), rs.getObject("created_at", LocalDateTime.class), rs.getObject("released_at", LocalDateTime.class),
                stages(rs.getLong("id"))), id)
            .stream().findFirst().orElseThrow(() -> LsBizException.notFound("ChangeSet", id));
    }

    public List<ChangeSet> list(String pack) {
        List<Long> ids = jdbc.queryForList("""
            select id from meta_change_set where artifact_sha is not null and (cast(? as varchar) is null or pack_code = ?) order by id desc limit 200""",
            Long.class, pack, pack);
        return ids.stream().map(this::get).toList();
    }
}
