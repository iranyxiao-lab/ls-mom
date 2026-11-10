package com.lsmom.crud;

import cn.idev.excel.FastExcel;
import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.gateway.OperationAudit;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.crud.io.ExportStorage;
import com.lsmom.crud.io.LedgerExportService;
import com.lsmom.crud.io.LedgerImportService;
import com.lsmom.crud.query.QuerySpec;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 7.5：Excel 导入预检（错误行报告、仅导入正确行）与异步导出（条数上限、对象存储、审计）。 */
class ImportExportTest {

    static final class MemoryStorage implements ExportStorage {
        final Map<String, byte[]> files = new ConcurrentHashMap<>();

        @Override
        public String put(String fileName, byte[] content) {
            String key = "export/" + files.size() + "/" + fileName;
            files.put(key, content);
            return key;
        }

        @Override
        public InputStream open(String key) {
            return new ByteArrayInputStream(files.get(key));
        }
    }

    private final CrudFixture f = new CrudFixture();
    private final ActorContext admin = ActionFixture.admin();
    private final MemoryStorage storage = new MemoryStorage();
    private final OperationAudit audit = new OperationAudit(f.action.core.jdbc, f.action.txm);
    private final LedgerImportService imports = new LedgerImportService(f.crud, f.action.core.jdbc, audit, CrudProperties.defaults());
    private final com.lsmom.core.task.BackgroundTasks tasks = new com.lsmom.core.task.BackgroundTasks(f.action.core.jdbc, f.action.txm);
    private final LedgerImportService asyncImports = new LedgerImportService(f.crud, f.action.core.jdbc, audit, CrudProperties.defaults(), tasks, Runnable::run);
    private CrudFixture.Loan loan;
    private String order;
    private CoreFixture.Plant plant;

    @BeforeEach
    void setUp() {
        loan = f.loanLedger();
        order = loan.order().code();
        plant = f.action.core.plant();
    }

    private byte[] xlsx(List<List<String>> head, List<List<Object>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FastExcel.write(out).head(head).sheet("借还单").doWrite(rows);
        return out.toByteArray();
    }

    @Test
    void 导入100行其中3行字典值非法_预检列出3行错误并仅导入97条() {
        List<List<String>> head = List.of(List.of("借用单号*"), List.of("借用人*"), List.of("借出日期*"), List.of("是否加急"), List.of("用途"),
            List.of(LedgerImportService.ORG_HEADER));
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            String urgent = (i == 10 || i == 50 || i == 99) ? "MAYBE" : (i % 2 == 0 ? "Y" : "N");
            rows.add(List.of("IMP-" + loan.ns().hashCode() + "-" + i, "员工" + i, "2026-11-0" + (1 + i % 9), urgent, "MAINTENANCE",
                String.valueOf(plant.teamA().id())));
        }
        LedgerImportService.Report report = imports.precheck(order, new ByteArrayInputStream(xlsx(head, rows)), "借还单.xlsx", admin);
        assertThat(report.total()).isEqualTo(100);
        assertThat(report.invalid()).isEqualTo(3);
        assertThat(report.valid()).isEqualTo(97);
        assertThat(report.errors()).extracting(LedgerImportService.RowError::row).containsExactly(11, 51, 100);
        assertThat(report.errors()).allSatisfy(e -> {
            assertThat(e.field()).isEqualTo("urgent");
            assertThat(e.message()).contains("sys_yes_no");
        });
        // 预检不入库
        assertThat(f.action.count("select count(*) from \"" + loan.order().tableName() + "\"")).isZero();

        int imported = imports.confirm(order, report.batchId(), admin);
        assertThat(imported).isEqualTo(97);
        assertThat(f.action.count("select count(*) from \"" + loan.order().tableName() + "\" where status = 'DRAFT'")).isEqualTo(97);
        assertThat(f.action.count("select count(*) from \"" + loan.order().tableName() + "\" where urgent = 'MAYBE'")).isZero();
        assertThatThrownBy(() -> imports.confirm(order, report.batchId(), admin)).extracting("code").isEqualTo("IMPORT_BATCH_STATE");
        assertThat(f.action.count("select count(*) from op_audit where op_code = ? and result = 'SUCCESS'",
            GenericCrudService.permission(order, "import"))).isEqualTo(1);
    }

    @Test
    void 后台导入_任务中心记录进度与结果_仅发起人可见() {
        List<List<String>> head = List.of(List.of("借用单号*"), List.of("借用人*"), List.of("借出日期*"), List.of("是否加急"),
            List.of(LedgerImportService.ORG_HEADER));
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 50; i++)
            rows.add(List.of("BG-" + loan.ns().hashCode() + "-" + i, "员工" + i, "2026-11-02", i == 7 ? "MAYBE" : "N", String.valueOf(plant.teamA().id())));
        long checkId = asyncImports.precheckAsync(order, xlsx(head, rows), "借还单.xlsx", admin);
        var check = tasks.get(checkId, admin.userId());
        assertThat(check.kind()).isEqualTo("IMPORT_CHECK");
        assertThat(check.status()).isEqualTo("DONE");
        assertThat(check.total()).isEqualTo(50);
        assertThat(check.done()).isEqualTo(50);
        assertThat(check.title()).contains("借还单.xlsx");
        var report = check.result().get("report");
        assertThat(report.get("invalid").asInt()).isEqualTo(1);
        assertThat(report.get("errors").get(0).get("row").asInt()).isEqualTo(8);
        assertThat(tasks.mine(admin.userId(), 10)).extracting(com.lsmom.core.task.BackgroundTasks.Task::id).contains(checkId);
        assertThatThrownBy(() -> tasks.get(checkId, admin.userId() + 999_999)).isInstanceOf(LsBizException.class);

        long importId = asyncImports.confirmAsync(order, report.get("batchId").asLong(), admin);
        var imp = tasks.get(importId, admin.userId());
        assertThat(imp.status()).isEqualTo("DONE");
        assertThat(imp.result().get("imported").asInt()).isEqualTo(49);
        assertThat(imp.done()).isEqualTo(49);
        assertThat(f.action.count("select count(*) from \"" + loan.order().tableName() + "\"")).isEqualTo(49);
        assertThat(imp.notified()).isFalse();
        tasks.notified(importId, admin.userId());
        assertThat(tasks.get(importId, admin.userId()).notified()).isTrue();

        // 失败任务：记下原因
        long failId = asyncImports.precheckAsync(order, new byte[]{1, 2, 3}, "坏文件.xlsx", admin);
        var fail = tasks.get(failId, admin.userId());
        assertThat(fail.status()).isEqualTo("FAILED");
        assertThat(fail.error()).isNotBlank();
    }

    @Test
    void 导入识别表头校验唯一与权限() {
        assertThatThrownBy(() -> imports.precheck(order, new ByteArrayInputStream(xlsx(List.of(List.of("借用单号"), List.of("工资")),
            List.of(List.of("A", "1")))), "x.xlsx", admin))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.getMessage()).contains("工资"));
        f.crud.create(order, json("{\"loanNo\":\"DUP-1\",\"borrower\":\"甲\",\"loanDate\":\"2026-11-01\",\"orgId\":" + plant.teamA().id() + "}"), admin, null);
        List<List<String>> head = List.of(List.of("借用单号"), List.of("借用人"), List.of("借出日期"), List.of(LedgerImportService.ORG_HEADER));
        String org = String.valueOf(plant.teamA().id());
        var report = imports.precheck(order, new ByteArrayInputStream(xlsx(head, List.of(
            List.of("DUP-1", "乙", "2026-11-02", org), List.of("NEW-1", "丙", "2026-11-02", org), List.of("NEW-1", "丁", "2026-11-02", org),
            List.of("NEW-2", "", "2026/11/03", org)))), "x.xlsx", admin);
        assertThat(report.errors()).extracting(e -> e.row() + ":" + e.field())
            .containsExactly("2:loanNo", "4:loanNo", "5:borrower");
        ActorContext noImport = f.action.human(f.action.core.employee("imp", plant.teamA()), DataScopeLevel.ORG,
            GenericCrudService.permission(order, "add"));
        assertThatThrownBy(() -> imports.precheck(order, new ByteArrayInputStream(xlsx(head, List.of())), "x.xlsx", noImport))
            .extracting("code").isEqualTo("ACCESS_DENIED");
    }

    @Test
    void 超量导出被拒绝并审计_正常导出异步写入对象存储() throws Exception {
        for (int i = 0; i < 6; i++) {
            f.crud.create(order, json("{\"loanNo\":\"EXP-" + i + "\",\"borrower\":\"员工" + i + "\",\"loanDate\":\"2026-11-01\",\"urgent\":\"Y\",\"orgId\":"
                + plant.teamA().id() + "}"), admin, null);
        }
        LedgerExportService limited = new LedgerExportService(f.crud, f.action.core.jdbc, audit, storage, new CrudProperties(null, 5, null), Runnable::run);
        assertThatThrownBy(() -> limited.submit(order, QuerySpec.all(), admin))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("EXPORT_LIMIT_EXCEEDED");
                assertThat(e.getDetails()).containsEntry("total", 6L).containsEntry("limit", 5);
            });
        assertThat(f.action.count("select count(*) from op_audit where op_code = ? and error_code = 'EXPORT_LIMIT_EXCEEDED'",
            GenericCrudService.permission(order, "export"))).isEqualTo(1);
        assertThat(f.action.count("select count(*) from crud_export_job where concept_code = ? and status = 'REJECTED'", order)).isEqualTo(1);

        // 缩小范围后导出
        LedgerExportService.Job job = limited.submit(order, new QuerySpec(List.of(new QuerySpec.Filter("loanNo", "startsWith", "EXP-")),
            List.of(new QuerySpec.Sort("loanNo", "asc")), 1, 20, List.of(), List.of(),
            List.of()).withFilters(List.of(new QuerySpec.Filter("loanNo", "in", List.of("EXP-1", "EXP-2", "EXP-3")))), admin);
        assertThat(job.status()).isEqualTo("DONE");
        assertThat(job.rowCount()).isEqualTo(3);
        List<Map<Integer, String>> rows;
        try (InputStream in = limited.download(job.id(), admin)) {
            rows = FastExcel.read(in).headRowNumber(0).sheet().doReadSync();
        }
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).values()).contains("借用单号", "借用人", "是否加急");
        assertThat(rows.get(1).values()).contains("EXP-1", "是");
        assertThat(f.action.count("select count(*) from op_audit where op_code = ? and result = 'SUCCESS'",
            GenericCrudService.permission(order, "export") + ":download")).isEqualTo(1);
        // 他人不能下载
        ActorContext other = f.action.human(f.action.core.employee("other", plant.teamA()), DataScopeLevel.ORG,
            GenericCrudService.permission(order, "export"), GenericCrudService.permission(order, "list"));
        assertThatThrownBy(() -> limited.download(job.id(), other)).extracting("code").isEqualTo("ACCESS_DENIED");
        // 没有导出权限
        ActorContext listOnly = f.action.human(f.action.core.employee("lo", plant.teamA()), DataScopeLevel.ORG,
            GenericCrudService.permission(order, "list"));
        assertThatThrownBy(() -> limited.submit(order, QuerySpec.all(), listOnly)).extracting("code").isEqualTo("ACCESS_DENIED");
    }

    @org.junit.jupiter.api.Test
    void 导出单元格把附件列表与引用写成文本() {
        org.assertj.core.api.Assertions.assertThat(com.lsmom.crud.io.LedgerExportService.cell(
            java.util.List.of(java.util.Map.of("ossId", "1", "name", "委托书.pdf"), java.util.Map.of("ossId", "2", "name", "照片.png"))))
            .isEqualTo("委托书.pdf、照片.png");
        org.assertj.core.api.Assertions.assertThat(com.lsmom.crud.io.LedgerExportService.cell(java.util.Map.of("id", 3, "label", "焊机"))).isEqualTo("焊机");
        org.assertj.core.api.Assertions.assertThat(com.lsmom.crud.io.LedgerExportService.cell(7L)).isEqualTo(7L);
    }
}
