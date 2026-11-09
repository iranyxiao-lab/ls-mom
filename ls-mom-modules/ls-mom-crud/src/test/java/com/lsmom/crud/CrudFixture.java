package com.lsmom.crud;

import com.lsmom.action.ActionFixture;
import com.lsmom.action.gateway.IdempotencyStore;
import com.lsmom.action.gateway.OperationAudit;
import com.lsmom.action.risk.GenericWritePolicy;
import com.lsmom.crud.ddl.DdlPlanner;
import com.lsmom.crud.ddl.LedgerPublisher;
import com.lsmom.crud.ddl.PackMigrator;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.DataType;
import com.lsmom.meta.concept.FieldDef;

import java.util.List;

/** 通用台账测试装配：在 ActionFixture 之上装配 DDL、通用 CRUD 与受控表状态存储。 */
public class CrudFixture {

    public final ActionFixture action = new ActionFixture();
    public final PackMigrator migrator = new PackMigrator(action.core.jdbc);
    public final LedgerPublisher publisher = new LedgerPublisher(action.concepts, new DdlPlanner(), migrator, action.core.jdbc);
    public final GenericCrudService crud = new GenericCrudService(action.core.jdbc, action.concepts,
        new GenericWritePolicy(action.core.jdbc, action.operations), action.operations, new IdempotencyStore(action.core.jdbc, action.core.clock),
        new OperationAudit(action.core.jdbc, action.txm), action.events, action.core.parties, action.txm, action.core.clock,
        CrudProperties.defaults());

    public final ManagedStateStore states = new ManagedStateStore(action.core.jdbc, action.concepts);

    public CrudFixture() {
        action.stores.addResolver(states);
    }

    /** 工器具（被引用台账）、借还单（主表）+ 借出明细（子表）。命名空间带随机后缀，避免共享数据库中的测试互相影响。 */
    public record Loan(String ns, ConceptDef tool, ConceptDef order, ConceptDef line) {
    }

    public static final String AUDIT_PERM = "acme:loan:audit";
    public static final String COST_PERM = "acme:loan:cost";

    public Loan loanLedger() {
        String ns = "acme.tool" + Long.toString(System.nanoTime(), 36).replaceAll("[^a-z0-9]", "");
        ConceptDef tool = ConceptDef.managed(ns + ".Tool", "工器具", List.of(
            FieldDef.of("name", "名称", DataType.STRING).asRequired().queryable().withProp("label", true),
            FieldDef.of("cost", "采购成本", DataType.DECIMAL)
        ), "acme.tool");
        publisher.publish(tool, List.of(), "acme.tool", null);
        ConceptDef order = ConceptDef.managed(ns + ".LoanOrder", "工器具借还单", List.of(
            FieldDef.of("loanNo", "借用单号", DataType.STRING).asRequired().queryable().sortable().withProp("unique", true).withProp("maxLength", 32),
            FieldDef.of("borrower", "借用人", DataType.STRING).asRequired().queryable(),
            FieldDef.of("loanDate", "借出日期", DataType.DATE).asRequired().sortable(),
            FieldDef.of("purpose", "用途", DataType.ENUM).withOptions(List.of("MAINTENANCE", "INSPECTION", "OTHER")).queryable(),
            FieldDef.of("remark", "备注", DataType.TEXT),
            FieldDef.of("qtyNote", "数量说明", DataType.STRING),
            FieldDef.of("urgent", "是否加急", DataType.STRING).withProp("dictType", "sys_yes_no").queryable(),
            FieldDef.of("toolRef", "主要工器具", DataType.REF).ref(tool.code()).queryable(),
            FieldDef.of("auditNote", "审核意见", DataType.STRING).withProp("writePermission", AUDIT_PERM),
            FieldDef.of("deposit", "押金", DataType.DECIMAL).withProp("readPermission", COST_PERM).withProp("scale", 2).queryable(),
            FieldDef.of("wechat", "联系人微信", DataType.STRING).withProp("ext", true)
        ), "acme.tool");
        ConceptDef line = ConceptDef.child(ns + ".LoanLine", "借出明细", List.of(
            FieldDef.of("tool", "工器具", DataType.STRING).asRequired(),
            FieldDef.of("qty", "数量", DataType.INTEGER).asRequired().withProp("minimum", 1),
            FieldDef.of("returned", "已归还", DataType.BOOLEAN)
        ), "acme.tool", order.code());
        publisher.publish(order, List.of(line), "acme.tool", null);
        return new Loan(ns, action.concepts.get(tool.code()), action.concepts.get(order.code()), action.concepts.get(line.code()));
    }
}
