package com.lsmom.crud;

import com.lsmom.core.error.LsBizException;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.DataType;
import com.lsmom.meta.concept.FieldDef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 7.1：发布借还单主子表生成受控物理表与迁移记录；新增字段走显式列；改类型生成需评审脚本。 */
class DdlPlannerTest {

    private final CrudFixture f = new CrudFixture();

    private List<String> columns(String table) {
        return f.action.core.jdbc.queryForList("select column_name from information_schema.columns where table_name = ? order by ordinal_position",
            String.class, table);
    }

    @Test
    void 发布借还单生成主表子表与迁移记录() {
        CrudFixture.Loan loan = f.loanLedger();
        String master = loan.order().tableName();
        String child = loan.line().tableName();
        assertThat(master).startsWith("c_acme_tool").endsWith("_loan_order");
        assertThat(columns(master)).contains("tool_ref", "urgent", "deposit");
        assertThat(columns(master)).contains("id", "org_id", "status", "created_by", "created_at", "updated_by", "updated_at", "version",
            "del_flag", "schema_version", "ext_attrs", "loan_no", "borrower", "loan_date", "purpose", "remark")
            .doesNotContain("wechat");
        assertThat(columns(child)).containsExactly("id", "parent_id", "line_no", "ext_attrs", "tool", "qty", "returned");
        // 外键、索引、唯一约束
        assertThat(f.action.count("select count(*) from information_schema.table_constraints where table_name = ? and constraint_type = 'FOREIGN KEY'", child)).isEqualTo(1);
        assertThat(f.action.count("select count(*) from pg_indexes where tablename = ? and indexdef like '%UNIQUE%loan_no%'", master)).isEqualTo(1);
        // 迁移历史：meta_ddl_script 登记且 Flyway 历史表中有成功记录
        List<Map<String, Object>> history = f.migrator.history(loan.order().code());
        assertThat(history).singleElement().satisfies(h -> {
            assertThat(h.get("status")).isEqualTo("APPLIED");
            assertThat(h.get("checksum")).isNotNull();
        });
        assertThat(f.migrator.history(loan.line().code())).hasSize(1);
        assertThat(f.action.count("select count(*) from pack_schema_history where success and description like ?", "%" + master + "%")).isEqualTo(1);
        assertThat(loan.line().parentConcept()).isEqualTo(loan.order().code());
    }

    @Test
    void 新增字段默认生成显式列并建索引() {
        CrudFixture.Loan loan = f.loanLedger();
        List<FieldDef> fields = new ArrayList<>(loan.order().fields());
        fields.add(FieldDef.of("workshop", "使用车间", DataType.STRING).queryable());
        f.publisher.publish(loan.order().withFields(fields), List.of(), "acme.tool", null);
        assertThat(columns(loan.order().tableName())).contains("workshop");
        assertThat(f.action.count("select count(*) from pg_indexes where tablename = ? and indexdef like '%(workshop)%'", loan.order().tableName())).isEqualTo(1);
        assertThat(f.migrator.history(loan.order().code())).hasSize(2);
    }

    @Test
    void 改字段类型生成需评审的迁移脚本而非直接覆盖() {
        CrudFixture.Loan loan = f.loanLedger();
        String table = loan.order().tableName();
        f.action.core.jdbc.update("insert into \"" + table + "\" (org_id, loan_no, borrower, loan_date, qty_note) values (1, 'L-1', '张三', current_date, ' 12 ')");
        f.action.core.jdbc.update("insert into \"" + table + "\" (org_id, loan_no, borrower, loan_date, qty_note) values (1, 'L-2', '李四', current_date, '若干')");

        // 直接发布改类型被拒绝
        List<FieldDef> changed = loan.order().fields().stream().map(x -> x.code().equals("qtyNote") ? x.withType(DataType.INTEGER) : x).toList();
        assertThatThrownBy(() -> f.publisher.publish(loan.order().withFields(changed), List.of(), "acme.tool", null))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getCode()).isEqualTo("BREAKING_CHANGE_REQUIRES_REVIEW"));
        assertThat(f.action.concepts.get(loan.order().code()).field("qtyNote").dataType()).isEqualTo(DataType.STRING);

        long cs = f.publisher.proposeTypeChange(loan.order().code(), "qtyNote", DataType.INTEGER, 100L);
        Map<String, Object> row = f.action.core.jdbc.queryForMap("select status, breaking, items::text as items from meta_change_set where id = ?", cs);
        assertThat(row.get("status")).isEqualTo("REVIEWING");
        assertThat(row.get("breaking")).isEqualTo(true);
        assertThat(String.valueOf(row.get("items"))).contains("alter table").contains("qty_note_mig");
        // 未批准不能执行；提交人不能自己评审
        assertThatThrownBy(() -> f.publisher.applyChangeSet(cs, 100L)).extracting("code").isEqualTo("CHANGESET_NOT_APPROVED");
        assertThatThrownBy(() -> f.publisher.review(cs, true, 100L)).extracting("code").isEqualTo("CHANGESET_SELF_REVIEW");
        f.publisher.review(cs, true, 200L);
        f.publisher.applyChangeSet(cs, 200L);

        assertThat(f.action.core.jdbc.queryForObject("select data_type from information_schema.columns where table_name = ? and column_name = 'qty_note'",
            String.class, table)).isEqualTo("bigint");
        assertThat(f.action.core.jdbc.queryForList("select qty_note from \"" + table + "\" order by loan_no", Long.class)).containsExactly(12L, null);
        assertThat(f.action.concepts.get(loan.order().code()).field("qtyNote").dataType()).isEqualTo(DataType.INTEGER);
        assertThat(f.migrator.history(loan.order().code())).hasSize(2).last().satisfies(h -> assertThat(h.get("breaking")).isEqualTo(true));
    }

    @Test
    void 子表必须挂在主表下() {
        ConceptDef orphan = ConceptDef.child("acme.toolx.Line", "孤儿明细", List.of(FieldDef.of("a", "a", DataType.STRING)), "acme", "acme.toolx.None");
        assertThatThrownBy(() -> f.publisher.publish(ConceptDef.managed("acme.toolx.Order", "单", List.of(), "acme"), List.of(orphan), "acme", null))
            .isInstanceOf(LsBizException.class);
    }
}
