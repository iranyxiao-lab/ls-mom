package com.lsmom.meta.concept;

import com.lsmom.core.error.LsBizException;
import com.lsmom.test.PgTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 5.1 核心自注册；5.2 spec meta/concept-registry 全部场景。 */
class ConceptRegistryTest {

    static final JdbcTemplate jdbc = PgTestSupport.jdbc();
    static final FieldUsageIndex usages = new FieldUsageIndex(jdbc);
    static final ConceptRegistry registry = new ConceptRegistry(jdbc, usages);
    static final RelationRegistry relations = new RelationRegistry(jdbc);

    @BeforeAll
    static void registerCore() {
        new CoreConceptRegistrar(registry, jdbc).registerAll();
    }

    private static String code(String name) {
        return "acme." + PgTestSupport.uniq("t") + "." + name;
    }

    private static ConceptDef ledger(String code, FieldDef... fields) {
        return ConceptDef.managed(code, "安全检查", List.of(fields), null);
    }

    @Test
    void 启动后可查询核心Concept且为核心显式表() {
        ConceptDef work = registry.get("ls.core.Work");
        assertThat(work.storage()).isEqualTo(ConceptDef.Storage.CORE_TABLE);
        assertThat(work.tableName()).isEqualTo("core_work_item");
        assertThat(work.editable()).isFalse();
        assertThat(work.field("title")).isNotNull();
        assertThat(work.field("status").stateField()).isTrue();
        assertThat(work.field("orgUnitId").refConcept()).isEqualTo("ls.core.OrgUnit");
        assertThat(work.field("blockReason").displayName()).isEqualTo("block_reason");
        assertThat(registry.get("ls.core.Issue").field("sourceKey").displayName()).isEqualTo("来源去重键");
        assertThat(registry.listCodes("ls.core")).contains("ls.core.Issue", "ls.core.Party", "ls.core.Observation");

        // 再次启动幂等
        new CoreConceptRegistrar(registry, jdbc).registerAll();
        assertThat(registry.get("ls.core.Work").fields()).hasSameSizeAs(work.fields());
    }

    @Test
    void 核心Concept不能经设计器修改结构() {
        ConceptDef work = registry.get("ls.core.Work");
        assertThatThrownBy(() -> registry.publish(work)).isInstanceOfSatisfying(LsBizException.class, e ->
            assertThat(e.getMessage()).contains("ls.core"));
        assertThat(registry.validateForPublish(work)).anyMatch(p -> p.contains("核心显式表"));
    }

    @Test
    void 修改显示名不影响编码() {
        String c = code("Check");
        registry.publish(ledger(c, FieldDef.of("severity", "严重程度", DataType.STRING)));
        registry.renameField(c, "severity", "等级");
        FieldDef f = registry.get(c).field("severity");
        assertThat(f.displayName()).isEqualTo("等级");
        assertThat(f.code()).isEqualTo("severity");
        assertThat(f.columnName()).isEqualTo("severity");
        // 用新显示名再次发布仍是同一字段
        assertThat(registry.publish(ledger(c, FieldDef.of("severity", "等级", DataType.STRING))).version()).isEqualTo(2);
    }

    @Test
    void 复用已弃用编码发布失败() {
        String c = code("Check");
        registry.publish(ledger(c, FieldDef.of("title", "标题", DataType.STRING), FieldDef.of("legacyNo", "旧编号", DataType.STRING)));
        assertThatThrownBy(() -> registry.publish(ledger(c, FieldDef.of("title", "标题", DataType.STRING))))
            .hasMessageContaining("须先弃用");
        registry.deprecateField(c, "legacyNo");
        assertThatThrownBy(() -> registry.publish(ledger(c, FieldDef.of("title", "标题", DataType.STRING),
            FieldDef.of("legacyNo", "新含义", DataType.INTEGER)))).isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("CONCEPT_PUBLISH_REJECTED");
                assertThat((List<Object>) e.getDetails().get("problems")).anySatisfy(p -> assertThat(p.toString()).contains("不能复用"));
            });
        // 不带弃用字段发布可以通过
        assertThat(registry.publish(ledger(c, FieldDef.of("title", "标题", DataType.STRING))).field("legacyNo").status())
            .isEqualTo(FieldDef.FieldStatus.DEPRECATED);
    }

    @Test
    void 类型与列名不能修改() {
        String c = code("Check");
        registry.publish(ledger(c, FieldDef.of("qty", "数量", DataType.INTEGER)));
        assertThat(registry.validateForPublish(ledger(c, FieldDef.of("qty", "数量", DataType.DECIMAL))))
            .anyMatch(p -> p.contains("类型不能"));
    }

    @Test
    void 删除被引用字段失败并列出引用方() {
        String c = code("Check");
        registry.publish(ledger(c, FieldDef.of("asset", "设备", DataType.STRING), FieldDef.of("note", "说明", DataType.TEXT)));
        usages.replace(FieldUsageIndex.UserType.VIEW, c + ".form", 3, List.of(FieldUsageIndex.FieldRef.of(c, "asset")));
        usages.replace(FieldUsageIndex.UserType.FLOW_NODE, c + ".approve", 1, List.of(new FieldUsageIndex.FieldRef(c, "asset", "节点 review 可编辑")));
        usages.replace(FieldUsageIndex.UserType.METRIC, "acme.metric.assetChecks", 1, List.of(FieldUsageIndex.FieldRef.of(c, "asset")));
        usages.replace(FieldUsageIndex.UserType.OPERATION, c + ".submit", 1, List.of(FieldUsageIndex.FieldRef.of(c, "asset")));

        // 影响分析
        assertThat(usages.activeUsagesOf(c, "asset")).extracting(FieldUsageIndex.Usage::userType)
            .containsExactly("FLOW_NODE", "METRIC", "OPERATION", "VIEW");

        assertThatThrownBy(() -> registry.removeField(c, "asset")).hasMessageContaining("须先弃用");
        registry.deprecateField(c, "asset");
        assertThatThrownBy(() -> registry.removeField(c, "asset")).isInstanceOfSatisfying(LsBizException.class, e -> {
            assertThat(e.getCode()).isEqualTo("FIELD_IN_USE");
            assertThat(e.getMessage()).contains(c + ".form@v3").contains(c + ".approve");
        });

        // 引用方退役后可以移除，且编码永久保留不可复用
        usages.retire(FieldUsageIndex.UserType.VIEW, c + ".form", 3);
        usages.retire(FieldUsageIndex.UserType.FLOW_NODE, c + ".approve", 1);
        usages.retire(FieldUsageIndex.UserType.METRIC, "acme.metric.assetChecks", 1);
        usages.retire(FieldUsageIndex.UserType.OPERATION, c + ".submit", 1);
        registry.removeField(c, "asset");
        assertThat(registry.fields(c, false)).extracting(FieldDef::code).containsExactly("note");
        assertThat(registry.validateForPublish(ledger(c, FieldDef.of("note", "说明", DataType.TEXT), FieldDef.of("asset", "x", DataType.STRING))))
            .anyMatch(p -> p.contains("已移除，不能复用"));
    }

    @Test
    void 核心命名空间污染检测() {
        assertThat(registry.validateForPublish(new ConceptDef("ls.core.Work", "ls.core", "工作", ConceptDef.Storage.MANAGED_TABLE,
            "c_ls_core_work2", true, null, null, List.of(FieldDef.of("examScore", "考试成绩", DataType.DECIMAL)), "DRAFT", 1)))
            .anyMatch(p -> p.contains("examScore"));
        ConceptDef polluted = new ConceptDef("ls.core.Party", "ls.core", "主体", ConceptDef.Storage.CORE_TABLE, "core_party", false,
            null, null, List.of(FieldDef.of("examScore", "考试成绩", DataType.DECIMAL)), "PUBLISHED", 1);
        assertThatThrownBy(() -> registry.registerCore(polluted)).extracting("code").isEqualTo("CORE_NAMESPACE_POLLUTED");
        // 扩展 Pack 命名空间允许
        assertThat(registry.validateForPublish(ConceptDef.managed("ls.training.Exam", "考试",
            List.of(FieldDef.of("examScore", "成绩", DataType.DECIMAL)), "training"))).isEmpty();
    }

    @Test
    void 关系与映射() {
        String c = code("Check");
        registry.publish(ledger(c, FieldDef.of("asset", "设备", DataType.STRING)));
        relations.defineRelation(new RelationRegistry.Relation(c + ".observedOn", "observedOn", c, "ls.core.Observation", "MANY", null));
        relations.map(new RelationRegistry.Mapping(c, "asset", "EXTERNAL", "SAP:EQUNR", "SAP"));
        assertThat(relations.relationsFrom(c)).extracting(RelationRegistry.Relation::name).containsExactly("observedOn");
        assertThat(relations.mappingsOf(c)).extracting(RelationRegistry.Mapping::targetRef).containsExactly("SAP:EQUNR");
        assertThat(registry.get(c).tableName()).startsWith("c_acme_").endsWith("_check");
    }

    @Test
    void 物理标识符白名单() {
        assertThatThrownBy(() -> MetaNames.requireIdentifier("x; drop table sys_user")).extracting("code").isEqualTo("INVALID_IDENTIFIER");
        assertThat(registry.validateForPublish(ledger(code("Check"),
            new FieldDef("bad", "坏列", DataType.STRING, "bad\"col", false, null, null, false, false, FieldDef.FieldStatus.ACTIVE, 0))))
            .anyMatch(p -> p.contains("列名不合法"));
    }
}
