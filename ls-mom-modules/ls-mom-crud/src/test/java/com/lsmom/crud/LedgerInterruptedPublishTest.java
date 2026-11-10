package com.lsmom.crud;

import com.lsmom.crud.ddl.DdlPlanner;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.DataType;
import com.lsmom.meta.concept.FieldDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 建表迁移已提交、但 Concept 登记前进程退出（启动安装中途被重启）：重新发布只补缺失的列，不重复建表。 */
class LedgerInterruptedPublishTest {

    @Test
    void 中途退出后重新发布可完成() {
        CrudFixture f = new CrudFixture();
        String ns = "acme.cut" + Long.toString(System.nanoTime(), 36).replaceAll("[^a-z0-9]", "");
        ConceptDef v1 = ConceptDef.managed(ns + ".Record", "中断记录", List.of(
            FieldDef.of("name", "名称", DataType.STRING).asRequired()), ns);
        // 模拟：只执行了建表迁移，没有登记 Concept
        f.migrator.register(new DdlPlanner().plan(v1, Optional.empty(), Optional.empty()), ns, null, null);
        f.migrator.migrate();
        assertThat(f.action.concepts.find(v1.code())).isEmpty();

        // 重新发布（新版本还多一个字段）
        ConceptDef v2 = ConceptDef.managed(ns + ".Record", "中断记录", List.of(
            FieldDef.of("name", "名称", DataType.STRING).asRequired(),
            FieldDef.of("qty", "数量", DataType.INTEGER)), ns);
        f.publisher.publish(v2, List.of(), ns, null);
        assertThat(f.action.concepts.get(v2.code()).fields()).extracting(FieldDef::code).contains("name", "qty");
        assertThat(f.action.core.jdbc.queryForObject(
            "select count(*) from information_schema.columns where table_name = ? and column_name = 'qty'", Integer.class,
            v2.tableName())).isEqualTo(1);
        assertThat(f.action.core.jdbc.queryForObject(
            "select count(*) from meta_ddl_script where concept_code = ? and status = 'FAILED'", Integer.class, v2.code())).isZero();
    }
}
