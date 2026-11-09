package com.lsmom.crud.ddl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.core.error.LsBizException;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.DataType;
import com.lsmom.meta.concept.FieldDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 台账 Concept 发布：校验 → 生成 DDL → 登记并经 Flyway 执行 → 写入元数据。
 * 非破坏性变更（新建表、新增列、新增索引）直接执行；破坏性变更（改类型、删列、改名）只能以变更集提交评审，
 * 评审通过后由 {@link #applyChangeSet} 执行。
 */
@Service
public class LedgerPublisher {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConceptRegistry concepts;
    private final DdlPlanner planner;
    private final PackMigrator migrator;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public LedgerPublisher(ConceptRegistry concepts, DdlPlanner planner, PackMigrator migrator, JdbcTemplate jdbc) {
        this.concepts = concepts;
        this.planner = planner;
        this.migrator = migrator;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public record Published(ConceptDef master, List<ConceptDef> children, List<Long> scripts) {
    }

    /** 发布主表及其子表（子表的 parentConcept 必须指向主表）。 */
    public Published publish(ConceptDef master, List<ConceptDef> children, String packCode, Long by) {
        if (master.isChild()) throw LsBizException.validation(Map.of("concept", "主表不能声明 parentConcept"));
        for (ConceptDef c : children) {
            if (!master.code().equals(c.parentConcept())) {
                throw LsBizException.validation(Map.of("children", "子表 " + c.code() + " 的 parentConcept 必须是 " + master.code()));
            }
        }
        List<String> problems = new ArrayList<>(concepts.validateForPublish(master));
        List<DdlPlan> plans = new ArrayList<>();
        DdlPlan mp = planner.plan(master, concepts.find(master.code()), Optional.empty());
        plans.add(mp);
        for (ConceptDef c : children) {
            List<String> cp = concepts.validateForPublish(c);
            // 子表的主表可能尚未发布：主表问题已在上面校验，这里忽略"主表不存在"
            cp.removeIf(p -> p.startsWith("子表所属的主表 Concept 不存在") && concepts.find(master.code()).isEmpty());
            problems.addAll(cp);
            plans.add(planner.plan(c, concepts.find(c.code()), Optional.of(master)));
        }
        List<String> breaking = plans.stream().flatMap(p -> p.breakingReasons().stream()).toList();
        problems.removeIf(p -> p.contains("破坏性变更") || p.contains("不能直接删除") || p.contains("存储位置"));
        if (!breaking.isEmpty()) {
            throw new LsBizException("BREAKING_CHANGE_REQUIRES_REVIEW", "包含破坏性变更，须提交变更集评审后执行：" + String.join("；", breaking),
                Map.of("breaking", breaking));
        }
        if (!problems.isEmpty()) {
            throw new LsBizException("CONCEPT_PUBLISH_REJECTED", "发布校验失败：" + problems.get(0), Map.of("problems", problems));
        }
        List<Long> ids = new ArrayList<>();
        for (DdlPlan p : plans) if (!p.isEmpty()) ids.add(migrator.register(p, packCode, null, by));
        migrator.migrate();
        ConceptDef[] out = new ConceptDef[1];
        List<ConceptDef> outChildren = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            out[0] = concepts.publish(master);
            for (ConceptDef c : children) outChildren.add(concepts.publish(c));
        });
        return new Published(out[0], outChildren, ids);
    }

    /** 提交改字段类型的变更集（REVIEWING），附带待评审迁移脚本；不修改现有定义与数据。 */
    public long proposeTypeChange(String conceptCode, String fieldCode, DataType newType, Long by) {
        ConceptDef c = concepts.get(conceptCode);
        FieldDef f = Optional.ofNullable(c.field(fieldCode)).orElseThrow(() -> LsBizException.notFound("字段", conceptCode + "." + fieldCode));
        if (f.dataType() == newType) throw LsBizException.validation(Map.of("dataType", "类型未变化"));
        DdlPlan plan = planner.planTypeChange(c, f, newType);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "FIELD_TYPE_CHANGE");
        item.put("concept", conceptCode);
        item.put("field", fieldCode);
        item.put("from", f.dataType().name());
        item.put("to", newType.name());
        item.put("script", plan.script());
        Long id = jdbc.queryForObject("""
            insert into meta_change_set (pack_code, title, status, breaking, items, source, created_by)
            values (?, ?, 'REVIEWING', true, ?::jsonb, 'DESIGNER', ?) returning id""", Long.class,
            c.ownerPack(), "修改字段类型 " + conceptCode + "." + fieldCode + " " + f.dataType() + " → " + newType, toJson(List.of(item)), by);
        return id;
    }

    public void review(long changeSetId, boolean approve, Long reviewer) {
        Map<String, Object> cs = changeSet(changeSetId);
        if (!"REVIEWING".equals(cs.get("status"))) throw new LsBizException("CHANGESET_STATE", "变更集不在评审中");
        if (reviewer != null && reviewer.equals(cs.get("created_by"))) {
            throw new LsBizException("CHANGESET_SELF_REVIEW", "提交人不能评审自己的破坏性变更");
        }
        jdbc.update("update meta_change_set set status = ?, reviewed_by = ? where id = ?", approve ? "APPROVED" : "REJECTED", reviewer, changeSetId);
    }

    /** 执行已批准的变更集：登记脚本 → Flyway 执行 → 同步字段定义。 */
    public void applyChangeSet(long changeSetId, Long by) {
        Map<String, Object> cs = changeSet(changeSetId);
        if (!"APPROVED".equals(cs.get("status"))) throw new LsBizException("CHANGESET_NOT_APPROVED", "变更集未经评审批准，不能执行");
        try {
            for (var item : JSON.readTree(String.valueOf(cs.get("items")))) {
                if (!"FIELD_TYPE_CHANGE".equals(item.path("type").asText())) continue;
                String concept = item.path("concept").asText();
                ConceptDef c = concepts.get(concept);
                FieldDef f = c.field(item.path("field").asText());
                DataType to = DataType.valueOf(item.path("to").asText());
                DdlPlan plan = new DdlPlan(concept, "change type " + c.tableName() + "." + f.columnName() + " (changeset " + changeSetId + ")",
                    List.of(item.path("script").asText().replaceAll(";\\s*$", "")), true, List.of());
                migrator.register(plan, c.ownerPack(), changeSetId, by);
                migrator.migrate();
                concepts.applyReviewedFieldChange(concept, f.withType(to));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("update meta_change_set set status = 'APPLIED', applied_at = now() where id = ?", changeSetId);
    }

    private Map<String, Object> changeSet(long id) {
        return jdbc.queryForList("select id, status, items::text as items, created_by from meta_change_set where id = ?", id).stream().findFirst()
            .orElseThrow(() -> LsBizException.notFound("变更集", String.valueOf(id)));
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
