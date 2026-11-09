package com.lsmom.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.rule.RulePublisher;
import com.lsmom.action.rule.RuleKind;
import com.lsmom.action.rule.RuleService;
import com.lsmom.action.guard.ConstraintLevel;
import com.lsmom.action.state.StateMachineDefinition;
import com.lsmom.action.state.StateMachineRegistry;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.schema.SchemaFingerprintService;
import com.lsmom.crud.GenericCrudService;
import com.lsmom.crud.ddl.LedgerPublisher;
import com.lsmom.crud.metric.MetricDefinition;
import com.lsmom.crud.metric.MetricRegistry;
import com.lsmom.flow.model.FlowDefinition;
import com.lsmom.flow.runtime.FlowRegistry;
import com.lsmom.form.FormRegistry;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.DataType;
import com.lsmom.meta.concept.FieldDef;
import com.lsmom.meta.concept.MetaNames;
import com.lsmom.meta.pack.PackGate;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pack 安装器：校验 → 依赖分析 → Concept（台账表）→ 补充迁移（独立 Flyway 历史表 flyway_history_&lt;pack&gt;）→ 状态机 → 规则 → 视图 → 指标 →
 * 审批流 → 种子 → 记录基线。每一步都可重入：文件未变的定义按源哈希跳过，已执行的迁移由 Flyway 跳过，失败修复后重新安装即可。
 * 升级时保留其他 Pack 追加在本 Pack 对象上的字段与数据。
 */
public class PackInstaller {

    private static final Logger log = LoggerFactory.getLogger(PackInstaller.class);

    public record Result(String code, String version, String previousVersion, String status, String artifactSha, List<String> steps) {
    }

    private final JdbcTemplate jdbc;
    private final PackStore store;
    private final PackDefinitions defs;
    private final ConceptRegistry concepts;
    private final LedgerPublisher ledgers;
    private final StateMachineRegistry machines;
    private final RulePublisher rules;
    private final FormRegistry forms;
    private final MetricRegistry metrics;
    private final FlowRegistry flows;
    private final GenericCrudService crud;
    private final PackGate gate;
    private SchemaFingerprintService fingerprints;

    public PackInstaller(JdbcTemplate jdbc, PackStore store, PackDefinitions defs, ConceptRegistry concepts, LedgerPublisher ledgers,
                         StateMachineRegistry machines, RulePublisher rules, FormRegistry forms, MetricRegistry metrics, FlowRegistry flows,
                         GenericCrudService crud, PackGate gate) {
        this.jdbc = jdbc;
        this.store = store;
        this.defs = defs;
        this.concepts = concepts;
        this.ledgers = ledgers;
        this.machines = machines;
        this.rules = rules;
        this.forms = forms;
        this.metrics = metrics;
        this.flows = flows;
        this.crud = crud;
        this.gate = gate;
    }

    /** 迁移后记录表结构指纹，否则下次启动会把 Pack 建的表当作手工 DDL 漂移。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFingerprints(SchemaFingerprintService fingerprints) {
        this.fingerprints = fingerprints;
    }

    /** 校验 Pack（不写库）：结构、清单、命名空间、依赖。返回解析后的内容。 */
    public PackContents check(PackSource src) {
        PackContents c = PackContents.parse(src);
        PackManifest m = c.manifest();
        Optional<PackStore.Pack> existing = store.find(m.code());
        if (existing.isPresent() && !"UNINSTALLED".equals(existing.get().status())) {
            int cmp = m.semver().compareTo(SemVer.parse(existing.get().version()));
            if (cmp < 0) {
                throw new LsBizException("PACK_DOWNGRADE", m.code() + " 已安装 " + existing.get().version() + "，不能安装更低的 " + m.version()
                    + "（回退请恢复备份或发布更高版本的修复）");
            }
            if (cmp == 0 && "ENABLED".equals(existing.get().status()) && !src.digest().equals(existing.get().artifactSha())) {
                throw new LsBizException("PACK_VERSION_CONFLICT", m.code() + " " + m.version() + " 已安装且内容不同，内容变更须提高版本号");
            }
        }
        DependencyPlanner.plan(List.of(m), store.installed());
        return c;
    }

    public Result install(PackSource src, Long by) {
        PackContents c = check(src);
        PackManifest m = c.manifest();
        Optional<PackStore.Pack> existing = store.find(m.code()).filter(p -> !"UNINSTALLED".equals(p.status()));
        String sha = store.saveArtifact(src, m, by);
        if (existing.isPresent() && "ENABLED".equals(existing.get().status()) && sha.equals(existing.get().artifactSha())) {
            return new Result(m.code(), m.version(), existing.get().previousVersion(), "UNCHANGED", sha, List.of());
        }
        boolean upgrade = existing.isPresent() && !existing.get().version().equals(m.version());
        String previous = upgrade ? existing.get().version() : existing.map(PackStore.Pack::previousVersion).orElse(null);
        begin(m, existing.isEmpty() || !"ENABLED".equals(existing.get().status()));
        List<String> steps = new ArrayList<>();
        Map<String, PackStore.Item> baseline = new LinkedHashMap<>();
        try {
            installConcepts(c, by, steps, baseline);
            migrate(c, steps, baseline);
            for (PackContents.Item i : c.machines()) {
                StateMachineDefinition d = PackJson.JSON.treeToValue(i.body(), StateMachineDefinition.class);
                if (unchanged(m, PackDefinitions.Kind.STATE_MACHINE, i)) {
                    keep(m, PackDefinitions.Kind.STATE_MACHINE, i, baseline);
                    continue;
                }
                int v = machines.publish(d);
                steps.add("状态机 " + d.code() + " v" + v);
                snapshot(m, PackDefinitions.Kind.STATE_MACHINE, i, false, baseline);
            }
            for (PackContents.Item i : c.rules()) {
                if (unchanged(m, PackDefinitions.Kind.RULE, i)) {
                    keep(m, PackDefinitions.Kind.RULE, i, baseline);
                    continue;
                }
                JsonNode b = i.body();
                rules.install(new RuleService.RuleDraft(i.code(), b.path("concept").asText(null), ConstraintLevel.valueOf(b.path("level").asText()),
                    RuleKind.valueOf(b.path("kind").asText("CEL")), b.path("name").asText(i.code()), b.path("message").asText(null),
                    b.path("definition"), m.code(), m.standard(), b.path("overrides").asText(null)));
                steps.add("规则 " + i.code());
                snapshot(m, PackDefinitions.Kind.RULE, i, false, baseline);
            }
            for (PackContents.Item i : c.views()) installView(m, i, by, steps, baseline);
            for (PackContents.Item i : c.metrics()) {
                if (unchanged(m, PackDefinitions.Kind.METRIC, i)) {
                    keep(m, PackDefinitions.Kind.METRIC, i, baseline);
                    continue;
                }
                MetricRegistry.Version v = metrics.publish(MetricDefinition.parse(i.body()), by);
                steps.add("指标 " + i.code() + " v" + v.version());
                snapshot(m, PackDefinitions.Kind.METRIC, i, false, baseline);
            }
            for (PackContents.Item i : c.flows()) {
                if (unchanged(m, PackDefinitions.Kind.FLOW, i)) {
                    keep(m, PackDefinitions.Kind.FLOW, i, baseline);
                    continue;
                }
                FlowRegistry.Version v = flows.publish(FlowDefinition.parse(i.body()), by);
                steps.add("审批流 " + i.code() + " v" + v.version());
                snapshot(m, PackDefinitions.Kind.FLOW, i, false, baseline);
            }
            for (PackContents.Item i : c.seeds()) seed(m, i, steps, baseline);
            finish(m, sha, previous, baseline);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            fail(m, existing.isEmpty() || !"ENABLED".equals(existing.get().status()), e);
            if (e instanceof LsBizException b) throw b;
            throw new LsBizException("PACK_INSTALL_FAILED", m.code() + " 安装失败：" + rootMessage(e), Map.of("pack", m.code()));
        }
        gate.invalidate();
        log.info("Pack {} {} 安装完成：{}", m.code(), m.version(), steps);
        return new Result(m.code(), m.version(), previous, upgrade ? "UPGRADED" : "INSTALLED", sha, steps);
    }

    // —— 迁移 ——

    public static String historyTable(String pack) {
        String t = "flyway_history_" + pack.replace('.', '_');
        if (t.length() > 63) throw new LsBizException("PACK_INVALID", "Pack 编码过长，迁移历史表名超过 63 字符：" + pack);
        return t;
    }

    private void migrate(PackContents c, List<String> steps, Map<String, PackStore.Item> baseline) {
        String pack = c.manifest().code();
        for (PackContents.Migration mg : c.migrations()) {
            String h = PackSource.sha256(mg.sql());
            ObjectNode d = PackJson.JSON.createObjectNode().put("file", mg.filename()).put("checksum", h);
            baseline.put("MIGRATION:" + mg.version(), new PackStore.Item(pack, PackDefinitions.Kind.MIGRATION, mg.version(), null, mg.path(), h, h, d, null));
        }
        if (c.migrations().isEmpty()) return;
        Flyway flyway = Flyway.configure()
            .dataSource(jdbc.getDataSource())
            .table(historyTable(pack))
            .resourceProvider(new PackMigrationResources(c.migrations()))
            .locations("db/pack-runtime-none")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .load();
        try {
            MigrateResult r = flyway.migrate();
            if (r.migrationsExecuted > 0) {
                steps.add("迁移 " + r.migrationsExecuted + " 个脚本（" + historyTable(pack) + "）");
                if (fingerprints != null) fingerprints.record(pack + "-" + c.manifest().version(), "pack-install");
            }
        } catch (RuntimeException e) {
            String msg = rootMessage(e);
            String failed = c.migrations().stream().map(PackContents.Migration::filename).filter(f -> String.valueOf(e.getMessage()).contains(f))
                .findFirst().orElseGet(() -> pendingMigration(flyway));
            throw new LsBizException("PACK_MIGRATION_FAILED", "迁移 " + failed + " 执行失败：" + msg,
                Map.of("pack", pack, "migration", failed == null ? "" : failed, "error", msg));
        }
    }

    private static String pendingMigration(Flyway flyway) {
        try {
            var p = flyway.info().pending();
            return p.length == 0 ? null : p[0].getScript();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // —— Concept ——

    private void installConcepts(PackContents c, Long by, List<String> steps, Map<String, PackStore.Item> baseline) {
        PackManifest m = c.manifest();
        Set<String> deps = m.dependsOn().stream().map(PackManifest.Dependency::code).collect(Collectors.toSet());
        for (PackContents.Item i : c.concepts()) {
            boolean ext = i.body().hasNonNull("extends");
            if (unchanged(m, PackDefinitions.Kind.CONCEPT, i)) {
                keep(m, PackDefinitions.Kind.CONCEPT, i, baseline);
                continue;
            }
            if (ext) {
                ConceptDef target = concepts.find(i.code()).orElseThrow(() -> LsBizException.notFound("Concept", i.code()));
                if (target.storage() != ConceptDef.Storage.MANAGED_TABLE || target.ownerPack() == null || !deps.contains(target.ownerPack())) {
                    throw new LsBizException("EXTENDS_NOT_DEPENDENCY", "只能为依赖 Pack 中的台账对象追加字段：" + i.code());
                }
                List<FieldDef> fields = new ArrayList<>();
                target.fields().stream().filter(f -> !m.code().equals(f.textProp(PackDefinitions.OWNER_PROP))).forEach(fields::add);
                for (JsonNode f : i.body().path("fields")) fields.add(field(f, m.code()));
                ConceptDef def = ConceptDef.managed(target.code(), target.displayName(), fields, target.ownerPack());
                ledgers.publish(def, List.of(), m.code(), by);
                steps.add("为 " + target.code() + " 追加字段");
            } else {
                ConceptDef master = own(i.code(), i.body(), m.code(), null);
                List<ConceptDef> children = new ArrayList<>();
                for (JsonNode ch : i.body().path("children")) children.add(own(ch.path("code").asText(), ch, m.code(), master.code()));
                ledgers.publish(master, children, m.code(), by);
                steps.add("台账 " + master.code() + (children.isEmpty() ? "" : "（含 " + children.size() + " 个子表）"));
            }
            snapshot(m, PackDefinitions.Kind.CONCEPT, i, ext, baseline);
        }
    }

    /** 本 Pack 的对象：文件字段＋其他 Pack 追加的字段（升级时保留）。 */
    private ConceptDef own(String code, JsonNode body, String pack, String parent) {
        List<FieldDef> fields = new ArrayList<>();
        for (JsonNode f : body.path("fields")) fields.add(field(f, null));
        Set<String> mine = fields.stream().map(FieldDef::code).collect(Collectors.toSet());
        concepts.find(code).ifPresent(old -> old.fields().stream()
            .filter(f -> f.textProp(PackDefinitions.OWNER_PROP) != null && !pack.equals(f.textProp(PackDefinitions.OWNER_PROP)))
            .filter(f -> !mine.contains(f.code())).forEach(fields::add));
        String name = body.path("displayName").asText(code);
        ConceptDef d = parent == null ? ConceptDef.managed(code, name, fields, pack) : ConceptDef.child(code, name, fields, pack, parent);
        if (body.hasNonNull("description")) {
            d = new ConceptDef(d.code(), d.namespace(), d.displayName(), d.storage(), d.tableName(), d.editable(), d.ownerPack(),
                body.path("description").asText(), d.fields(), d.status(), d.version(), d.parentConcept());
        }
        return d;
    }

    private static FieldDef field(JsonNode f, String ownerPack) {
        String code = f.path("code").asText(null);
        if (code == null) throw LsBizException.validation(Map.of("fields", "字段缺少 code"));
        DataType type;
        try {
            type = DataType.valueOf(f.path("dataType").asText());
        } catch (IllegalArgumentException e) {
            throw LsBizException.validation(Map.of(code, "未知数据类型：" + f.path("dataType").asText()));
        }
        Map<String, Object> props = f.has("props") ? PackJson.JSON.convertValue(f.get("props"), Map.class) : new LinkedHashMap<>();
        props = new LinkedHashMap<>(props);
        if (ownerPack != null) props.put(PackDefinitions.OWNER_PROP, ownerPack);
        List<String> options = new ArrayList<>();
        f.path("options").forEach(o -> options.add(o.asText()));
        return new FieldDef(code, f.path("displayName").asText(code), type, MetaNames.toColumn(code), f.path("required").asBoolean(false),
            f.path("refConcept").asText(null), options.isEmpty() ? null : options, false, false,
            f.path("deprecated").asBoolean(false) ? FieldDef.FieldStatus.DEPRECATED : FieldDef.FieldStatus.ACTIVE, 0, props);
    }

    // —— 视图 ——

    private void installView(PackManifest m, PackContents.Item i, Long by, List<String> steps, Map<String, PackStore.Item> baseline) {
        if (unchanged(m, PackDefinitions.Kind.VIEW, i)) {
            keep(m, PackDefinitions.Kind.VIEW, i, baseline);
            return;
        }
        int version = i.body().path("version").asInt();
        Optional<FormRegistry.View> same = forms.find(i.code(), version);
        if (same.isPresent() && !"DRAFT".equals(same.get().status())) {
            if (!PackJson.hash(same.get().schema()).equals(PackJson.hash(i.body()))) {
                throw new LsBizException("VIEW_VERSION_CONFLICT", "视图 " + i.code() + " v" + version + " 已发布且内容不同，请在 Pack 中提高 version");
            }
        } else {
            forms.saveDraft(i.body(), by, "Pack " + m.code() + " " + m.version());
            FormRegistry.PublishResult r = forms.publish(i.code(), version, by);
            if (!"APPLIED".equals(r.status())) {
                // Pack 已经过 ChangeSet 审批：移除字段的视图版本在安装时直接生效
                forms.review(r.changeSetId(), true, by);
                forms.applyChangeSet(r.changeSetId());
            }
            steps.add("视图 " + i.code() + " v" + version);
        }
        snapshot(m, PackDefinitions.Kind.VIEW, i, false, baseline);
    }

    // —— 种子 ——

    private void seed(PackManifest m, PackContents.Item i, List<String> steps, Map<String, PackStore.Item> baseline) {
        String concept = i.body().path("concept").asText();
        String key = i.body().path("key").asText();
        ConceptDef def = concepts.get(concept);
        FieldDef kf = def.field(key);
        if (kf == null) throw LsBizException.validation(Map.of(i.path(), "判重字段不存在：" + key));
        Long defaultOrg = jdbc.queryForList("select id from core_org_unit where parent_id is null order by id limit 1", Long.class).stream()
            .findFirst().orElse(null);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : i.body().path("rows")) {
            Object kv = PackJson.JSON.convertValue(r.get(key), Object.class);
            Integer n = jdbc.queryForObject("select count(*) from " + def.tableName() + " where " + kf.columnName() + " = ?", Integer.class, kv);
            if (n != null && n > 0) continue;
            Map<String, Object> v = new LinkedHashMap<>(PackJson.JSON.convertValue(r, Map.class));
            if (!v.containsKey("orgId") && defaultOrg != null) v.put("orgId", defaultOrg);
            rows.add(v);
        }
        if (!rows.isEmpty()) {
            crud.insertValidated(concept, rows, ActorContext.system("pack:" + m.code()), "Pack " + m.code() + " 种子");
            steps.add("种子 " + concept + " " + rows.size() + " 条");
        }
        String h = PackJson.hash(i.body());
        baseline.put("SEED:" + i.code(), new PackStore.Item(m.code(), PackDefinitions.Kind.SEED, i.code(), null, i.path(), h, h, i.body(), null));
    }

    // —— 基线 ——

    private boolean unchanged(PackManifest m, PackDefinitions.Kind kind, PackContents.Item i) {
        return store.item(m.code(), kind, i.code()).map(x -> x.sourceHash().equals(PackJson.hash(i.body()))).orElse(false)
            && defs.current(kind, i.code(), m.code(), i.body().hasNonNull("extends")).isPresent();
    }

    private void keep(PackManifest m, PackDefinitions.Kind kind, PackContents.Item i, Map<String, PackStore.Item> baseline) {
        PackStore.Item old = store.item(m.code(), kind, i.code()).orElseThrow();
        baseline.put(kind + ":" + i.code(), new PackStore.Item(m.code(), kind, i.code(), old.version(), i.path(), old.hash(), old.sourceHash(),
            old.definition(), null));
    }

    private void snapshot(PackManifest m, PackDefinitions.Kind kind, PackContents.Item i, boolean ext, Map<String, PackStore.Item> baseline) {
        PackDefinitions.Snapshot s = defs.current(kind, i.code(), m.code(), ext)
            .orElseThrow(() -> new LsBizException("PACK_INSTALL_FAILED", kind + " " + i.code() + " 安装后未找到已发布定义"));
        baseline.put(kind + ":" + i.code(), new PackStore.Item(m.code(), kind, i.code(), s.version(), i.path(), PackJson.hash(s.form()),
            PackJson.hash(i.body()), PackJson.canonical(s.form()), null));
    }

    // —— 状态 ——

    private void begin(PackManifest m, boolean fresh) {
        Long ownerParty = m.owner().party() == null ? null : jdbc.queryForList("select id from core_party where code = ?", Long.class,
            m.owner().party()).stream().findFirst().orElse(null);
        jdbc.update("""
            insert into meta_pack (code, version, display_name, status, owner, review_due, manifest, manifest_hash, requester, owner_party,
                                   standard, depends_on, updated_at)
            values (?, ?, ?, 'INSTALLING', ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, now())
            on conflict (code) do update set status = case when ? then 'INSTALLING' else meta_pack.status end, error = null, updated_at = now()""",
            m.code(), m.version(), m.name(), m.owner().name(), m.reviewDue(), m.toJson().toString(), "0".repeat(64), m.requester().name(),
            ownerParty, m.standard(), PackJson.JSON.valueToTree(m.dependsOn()).toString(), fresh);
    }

    private void finish(PackManifest m, String sha, String previous, Map<String, PackStore.Item> baseline) {
        Long ownerParty = m.owner().party() == null ? null : jdbc.queryForList("select id from core_party where code = ?", Long.class,
            m.owner().party()).stream().findFirst().orElse(null);
        baseline.values().forEach(store::saveItem);
        store.removeItemsNotIn(m.code(), new ArrayList<>(baseline.keySet()));
        jdbc.update("""
            update meta_pack set version = ?, display_name = ?, status = 'ENABLED', owner = ?, review_due = ?, manifest = ?::jsonb,
                   manifest_hash = ?, requester = ?, owner_party = ?, standard = ?, depends_on = ?::jsonb, artifact_sha = ?, error = null,
                   previous_version = ?, installed_at = now(), updated_at = now(), disabled_at = null,
                   review_status = case when review_due is distinct from ? then 'OK' else review_status end
             where code = ?""",
            m.version(), m.name(), m.owner().name(), m.reviewDue(), m.toJson().toString(), sha, m.requester().name(), ownerParty, m.standard(),
            PackJson.JSON.valueToTree(m.dependsOn()).toString(), sha, previous, m.reviewDue(), m.code());
    }

    private void fail(PackManifest m, boolean fresh, Exception e) {
        try {
            jdbc.update("update meta_pack set status = case when ? then 'FAILED' else status end, error = ?, updated_at = now() where code = ?",
                fresh, truncate(rootMessage(e)), m.code());
        } catch (RuntimeException ex) {
            log.warn("记录 Pack {} 安装失败状态时出错", m.code(), ex);
        }
    }

    static String rootMessage(Throwable t) {
        if (t instanceof LsBizException) return t.getMessage();
        Throwable x = t;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        return x.getMessage() == null ? t.toString() : x.getMessage();
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
