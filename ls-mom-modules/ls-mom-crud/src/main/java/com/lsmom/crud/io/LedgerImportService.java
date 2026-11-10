package com.lsmom.crud.io;

import cn.idev.excel.FastExcel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.gateway.OperationAudit;
import com.lsmom.core.error.LsBizException;
import com.lsmom.crud.CrudProperties;
import com.lsmom.crud.GenericCrudService;
import com.lsmom.crud.query.LedgerModel;
import com.lsmom.meta.concept.DataType;
import com.lsmom.meta.concept.FieldDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 导入：上传 → 逐行预检（与通用新增相同的服务端校验）→ 错误行报告 → 确认后只导入正确行（单一事务）。
 * 表头按字段显示名或字段编码匹配；"所属组织ID"列可选，缺省为导入人的主组织。
 */
@Service
public class LedgerImportService {

    public static final String ORG_HEADER = "所属组织ID";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final GenericCrudService crud;
    private final JdbcTemplate jdbc;
    private final OperationAudit audit;
    private final CrudProperties props;
    private final com.lsmom.core.task.BackgroundTasks tasks;
    private final java.util.concurrent.Executor executor;

    public LedgerImportService(GenericCrudService crud, JdbcTemplate jdbc, OperationAudit audit, CrudProperties props) {
        this(crud, jdbc, audit, props, null, Runnable::run);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LedgerImportService(GenericCrudService crud, JdbcTemplate jdbc, OperationAudit audit, CrudProperties props,
                               com.lsmom.core.task.BackgroundTasks tasks,
                               @org.springframework.beans.factory.annotation.Qualifier(com.lsmom.crud.CrudConfiguration.EXPORT_EXECUTOR)
                               java.util.concurrent.Executor executor) {
        this.crud = crud;
        this.jdbc = jdbc;
        this.audit = audit;
        this.props = props;
        this.tasks = tasks;
        this.executor = executor;
    }

    /**
     * 后台预检（任务中心）：大文件预检较慢，发起人可离开页面；完成后任务结果为预检报告（含错误行），可在任务中心确认导入。
     * 权限在发起时检查。
     */
    public long precheckAsync(String concept, byte[] xlsx, String fileName, ActorContext actor) {
        guard(concept, actor, GenericCrudService.permission(concept, "import"));
        String name = crud.model(concept).concept().displayName();
        long id = tasks.start("IMPORT_CHECK", "导入检查：" + name + "（" + fileName + "）", actor.userId(), 0);
        executor.execute(() -> {
            try {
                Report r = precheck(concept, new java.io.ByteArrayInputStream(xlsx), fileName, actor, n -> tasks.total(id, n), tasks.reporter(id));
                tasks.finish(id, java.util.Map.of("concept", concept, "conceptName", name, "report", r));
            } catch (LsBizException e) {
                tasks.fail(id, e.fieldErrors().isEmpty() ? e.getMessage() : String.join("；", e.fieldErrors().values()));
            } catch (RuntimeException e) {
                tasks.fail(id, "导入检查失败：" + e.getMessage());
            }
        });
        return id;
    }

    /** 后台确认导入（任务中心）：逐行重新校验后入库，完成后通知发起人导入条数。 */
    public long confirmAsync(String concept, long batchId, ActorContext actor) {
        Map<String, Object> b = batch(concept, batchId, actor);
        String name = crud.model(concept).concept().displayName();
        long id = tasks.start("IMPORT", "导入：" + name + "（批次 " + batchId + "）", actor.userId(), ((Number) b.get("valid_rows")).intValue());
        executor.execute(() -> {
            try {
                int n = confirm(concept, batchId, actor, tasks.reporter(id));
                tasks.finish(id, java.util.Map.of("concept", concept, "conceptName", name, "imported", n, "batchId", batchId));
            } catch (LsBizException e) {
                tasks.fail(id, e.getMessage());
            } catch (RuntimeException e) {
                tasks.fail(id, "导入失败：" + e.getMessage());
            }
        });
        return id;
    }

    public record RowError(int row, String field, String message) {
    }

    public record Report(long batchId, int total, int valid, int invalid, List<RowError> errors) {
    }

    /** 导入模板：可写入的显式字段（不含子表）。 */
    public byte[] template(String concept, ActorContext actor) {
        LedgerModel m = crud.model(concept);
        List<List<String>> head = new ArrayList<>();
        for (FieldDef f : writable(m, actor)) head.add(List.of(f.displayName() + (f.required() ? "*" : "")));
        head.add(List.of(ORG_HEADER));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FastExcel.write(out).head(head).sheet(m.concept().displayName()).doWrite(List.of());
        return out.toByteArray();
    }

    public Report precheck(String concept, InputStream xlsx, String fileName, ActorContext actor) {
        return precheck(concept, xlsx, fileName, actor, n -> { }, n -> { });
    }

    private Report precheck(String concept, InputStream xlsx, String fileName, ActorContext actor, java.util.function.IntConsumer onTotal,
                            java.util.function.IntConsumer onRow) {
        String op = GenericCrudService.permission(concept, "import");
        guard(concept, actor, op);
        LedgerModel m = crud.model(concept);
        List<Map<Integer, String>> rows = FastExcel.read(xlsx).headRowNumber(0).sheet().doReadSync();
        if (rows.isEmpty()) throw LsBizException.validation(Map.of("file", "文件为空"));
        Map<Integer, String> header = rows.get(0);
        Map<Integer, FieldDef> columns = new LinkedHashMap<>();
        Integer orgCol = null;
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<Integer, String> h : header.entrySet()) {
            String title = h.getValue() == null ? "" : h.getValue().trim().replaceAll("\\*$", "");
            if (title.isEmpty()) continue;
            if (title.equals(ORG_HEADER)) {
                orgCol = h.getKey();
                continue;
            }
            FieldDef f = m.fields().values().stream().filter(x -> x.displayName().equals(title) || x.code().equals(title)).findFirst().orElse(null);
            if (f == null) unknown.add(title);
            else columns.put(h.getKey(), f);
        }
        if (!unknown.isEmpty()) throw LsBizException.validation(Map.of("file", "无法识别的表头：" + String.join("、", unknown)));
        if (columns.isEmpty()) throw LsBizException.validation(Map.of("file", "没有识别到任何列，请用「下载模板」得到的文件填写后导入"));
        int total = rows.size() - 1;
        if (total > props.importMaxRows()) {
            throw LsBizException.validation(Map.of("file", "单次最多导入 " + props.importMaxRows() + " 行，当前 " + total + " 行"));
        }
        onTotal.accept(total);
        List<RowError> errors = new ArrayList<>();
        List<Map<String, Object>> valid = new ArrayList<>();
        Map<String, java.util.Set<String>> seen = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            onRow.accept(i - 1);
            int excelRow = i + 1;
            Map<Integer, String> r = rows.get(i);
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            columns.forEach((col, f) -> put(node, f, r.get(col)));
            if (orgCol != null && r.get(orgCol) != null && !r.get(orgCol).isBlank()) {
                String org = r.get(orgCol).trim();
                if (!org.matches("\\d{1,18}")) {
                    errors.add(new RowError(excelRow, "orgId", "所属组织ID 必须是数字"));
                    continue;
                }
                node.put("orgId", Long.parseLong(org));
            }
            String dupField = duplicateInFile(m, node, seen);
            if (dupField != null) {
                errors.add(new RowError(excelRow, dupField, m.field(dupField).displayName() + "与文件中前面的行重复"));
                continue;
            }
            try {
                crud.precheck(m, node, actor);
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("row", excelRow);
                ok.put("data", node);
                valid.add(ok);
            } catch (LsBizException e) {
                if (!e.fieldErrors().isEmpty()) e.fieldErrors().forEach((k, v) -> errors.add(new RowError(excelRow, k, v)));
                else errors.add(new RowError(excelRow, null, e.getMessage()));
            }
        }
        int invalidRows = (int) errors.stream().map(RowError::row).distinct().count();
        Long id = jdbc.queryForObject("""
            insert into crud_import_batch (concept_code, file_name, total_rows, valid_rows, error_rows, errors, payload, created_by)
            values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?) returning id""", Long.class,
            concept, fileName, total, valid.size(), invalidRows, json(errors), json(valid), actor.effectiveParty());
        audit.writeIndependently(new OperationAudit.Entry(op + ":precheck", actor, concept, String.valueOf(id), null, "SUCCESS", null,
            null, "", "{\"file\":\"" + fileName + "\",\"total\":" + total + ",\"invalid\":" + invalidRows + "}", 0, null));
        return new Report(id, total, valid.size(), invalidRows, errors);
    }

    /** 确认导入：只导入预检通过的行（入库前重新校验）。返回导入条数。 */
    public int confirm(String concept, long batchId, ActorContext actor) {
        return confirm(concept, batchId, actor, n -> { });
    }

    private Map<String, Object> batch(String concept, long batchId, ActorContext actor) {
        guard(concept, actor, GenericCrudService.permission(concept, "import"));
        Map<String, Object> b = jdbc.queryForList(
            "select concept_code, status, created_by, valid_rows, payload::text as payload from crud_import_batch where id = ?",
            batchId).stream().findFirst().orElseThrow(() -> LsBizException.notFound("导入批次", String.valueOf(batchId)));
        if (!concept.equals(b.get("concept_code"))) throw LsBizException.notFound("导入批次", String.valueOf(batchId));
        if (!"CHECKED".equals(b.get("status"))) throw new LsBizException("IMPORT_BATCH_STATE", "该批次已导入或已失效");
        if (actor.effectiveParty() != null && !actor.effectiveParty().equals(b.get("created_by")) && !actor.superAdmin()) {
            throw new LsBizException("ACCESS_DENIED", "只能确认自己上传的导入批次");
        }
        return b;
    }

    private int confirm(String concept, long batchId, ActorContext actor, java.util.function.IntConsumer onRow) {
        String op = GenericCrudService.permission(concept, "import");
        Map<String, Object> b = batch(concept, batchId, actor);
        LedgerModel m = crud.model(concept);
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (JsonNode item : JSON.readTree(String.valueOf(b.get("payload")))) {
                onRow.accept(rows.size());
                JsonNode data = item.get("data");
                Map<String, Object> v = crud.precheck(m, data, actor);
                if (data.hasNonNull("orgId")) v.put("orgId", data.get("orgId").asLong());
                rows.add(v);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        List<Long> ids = crud.insertValidated(concept, rows, actor, "Excel 导入（批次 " + batchId + "）");
        jdbc.update("update crud_import_batch set status = 'IMPORTED', imported_rows = ?, imported_at = now() where id = ?", ids.size(), batchId);
        audit.writeIndependently(new OperationAudit.Entry(op, actor, concept, String.valueOf(batchId), null, "SUCCESS", null, null, "",
            "{\"imported\":" + ids.size() + "}", 0, null));
        return ids.size();
    }

    private void guard(String concept, ActorContext actor, String op) {
        crud.checkAccess(actor, concept, "import");
    }

    private static String duplicateInFile(LedgerModel m, ObjectNode node, Map<String, java.util.Set<String>> seen) {
        for (FieldDef f : m.fields().values()) {
            if (!f.flag("unique") || !node.hasNonNull(f.code())) continue;
            if (!seen.computeIfAbsent(f.code(), k -> new java.util.HashSet<>()).add(node.get(f.code()).asText())) return f.code();
        }
        return null;
    }

    private static List<FieldDef> writable(LedgerModel m, ActorContext actor) {
        return m.fields().values().stream().filter(f -> actor.hasPermission(f.textProp("writePermission"))
            && actor.hasPermission(f.textProp("readPermission")) && f.dataType() != DataType.JSON && f.dataType() != DataType.FILE).toList();
    }

    private static void put(ObjectNode node, FieldDef f, String cell) {
        if (cell == null || cell.isBlank()) return;
        String v = cell.trim();
        switch (f.dataType()) {
            case INTEGER, REF -> {
                if (v.matches("-?\\d+(\\.0+)?")) node.put(f.code(), Long.parseLong(v.replaceAll("\\.0+$", "")));
                else node.put(f.code(), v);
            }
            case DECIMAL -> {
                if (v.matches("-?\\d+(\\.\\d+)?")) node.put(f.code(), new java.math.BigDecimal(v));
                else node.put(f.code(), v);
            }
            case BOOLEAN -> {
                switch (v.toLowerCase()) {
                    case "是", "true", "y", "1" -> node.put(f.code(), true);
                    case "否", "false", "n", "0" -> node.put(f.code(), false);
                    default -> node.put(f.code(), v);
                }
            }
            case DATE -> node.put(f.code(), v.length() >= 10 ? v.substring(0, 10).replace('/', '-') : v);
            default -> node.put(f.code(), v);
        }
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
