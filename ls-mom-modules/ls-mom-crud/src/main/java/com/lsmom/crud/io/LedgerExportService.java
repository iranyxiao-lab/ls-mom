package com.lsmom.crud.io;

import cn.idev.excel.FastExcel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.gateway.OperationAudit;
import com.lsmom.core.error.LsBizException;
import com.lsmom.crud.CrudConfiguration;
import com.lsmom.crud.CrudProperties;
import com.lsmom.crud.GenericCrudService;
import com.lsmom.crud.query.LedgerModel;
import com.lsmom.crud.query.QuerySpec;
import com.lsmom.meta.concept.FieldDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 异步导出：提交时校验权限并按条件计数，超过上限直接拒绝并审计；否则建任务后台生成 xlsx（只含调用者可读字段、按其数据域过滤），
 * 写入对象存储；下载只允许任务创建人，下载同样审计。每人同时进行中的导出任务不超过 2 个。
 */
@Service
public class LedgerExportService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RUNNING_PER_USER = 2;

    private final GenericCrudService crud;
    private final JdbcTemplate jdbc;
    private final OperationAudit audit;
    private final ExportStorage storage;
    private final CrudProperties props;
    private final Executor executor;

    public LedgerExportService(GenericCrudService crud, JdbcTemplate jdbc, OperationAudit audit, ExportStorage storage, CrudProperties props,
                               @org.springframework.beans.factory.annotation.Qualifier(CrudConfiguration.EXPORT_EXECUTOR) Executor executor) {
        this.crud = crud;
        this.jdbc = jdbc;
        this.audit = audit;
        this.storage = storage;
        this.props = props;
        this.executor = executor;
    }

    public record Job(long id, String concept, String status, Integer rowCount, String error) {
    }

    private com.lsmom.core.task.BackgroundTasks tasks;

    /** 任务中心（可选）：导出进度与完成通知 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTasks(com.lsmom.core.task.BackgroundTasks tasks) {
        this.tasks = tasks;
    }

    public Job submit(String concept, QuerySpec spec, ActorContext actor) {
        String op = GenericCrudService.permission(concept, "export");
        crud.checkAccess(actor, concept, "export");
        QuerySpec countSpec = new QuerySpec(spec.filters(), List.of(), 1, 1, List.of(), List.of(), List.of());
        long total = crud.list(concept, countSpec, actor).total();
        String query = json(spec);
        if (total > props.exportMaxRows()) {
            Long id = jdbc.queryForObject("""
                insert into crud_export_job (concept_code, query, status, row_count, error, created_by, finished_at)
                values (?, ?::jsonb, 'REJECTED', ?, ?, ?, now()) returning id""", Long.class, concept, query, total,
                "超过导出上限 " + props.exportMaxRows(), actor.effectiveParty());
            String msg = "导出条数 " + total + " 超过上限 " + props.exportMaxRows() + "，请缩小筛选范围";
            audit.writeIndependently(new OperationAudit.Entry(op, actor, concept, String.valueOf(id), null, "FAILED", "EXPORT_LIMIT_EXCEEDED",
                msg, "", query, 0, null));
            throw new LsBizException("EXPORT_LIMIT_EXCEEDED", msg, Map.of("total", total, "limit", props.exportMaxRows(), "jobId", id));
        }
        Integer running = jdbc.queryForObject("select count(*) from crud_export_job where created_by is not distinct from ? and status in ('PENDING','RUNNING')",
            Integer.class, actor.effectiveParty());
        if (running != null && running >= MAX_RUNNING_PER_USER) {
            throw new LsBizException("EXPORT_BUSY", "已有 " + running + " 个导出任务在进行中，请稍后再试");
        }
        Long id = jdbc.queryForObject("insert into crud_export_job (concept_code, query, row_count, created_by) values (?, ?::jsonb, ?, ?) returning id",
            Long.class, concept, query, total, actor.effectiveParty());
        audit.writeIndependently(new OperationAudit.Entry(op, actor, concept, String.valueOf(id), null, "SUCCESS", null, null, "", query, 0, null));
        Long task = tasks == null || actor.userId() == null ? null
            : tasks.start("EXPORT", "导出：" + crud.model(concept).concept().displayName(), actor.userId(), (int) total);
        executor.execute(() -> run(id, concept, spec, actor, task));
        return job(id, actor);
    }

    void run(long id, String concept, QuerySpec spec, ActorContext actor) {
        run(id, concept, spec, actor, null);
    }

    void run(long id, String concept, QuerySpec spec, ActorContext actor, Long task) {
        jdbc.update("update crud_export_job set status = 'RUNNING' where id = ?", id);
        try {
            LedgerModel m = crud.model(concept);
            List<FieldDef> cols = m.fields().values().stream().filter(f -> actor.hasPermission(f.textProp("readPermission"))).toList();
            List<List<String>> head = new ArrayList<>();
            head.add(List.of("编号"));
            head.add(List.of("状态"));
            for (FieldDef f : cols) head.add(List.of(f.displayName()));
            List<List<Object>> data = new ArrayList<>();
            int page = 1;
            int batch = props.maxPageSize();
            while (true) {
                var p = crud.list(concept, new QuerySpec(spec.filters(), spec.sort(), page, batch, List.of(), List.of(), List.of()), actor);
                for (Map<String, Object> r : p.rows()) {
                    List<Object> row = new ArrayList<>();
                    row.add(r.get("id"));
                    row.add(r.get("status"));
                    for (FieldDef f : cols) row.add(cell(r.containsKey(f.code() + "Label") ? r.get(f.code() + "Label") : r.get(f.code())));
                    data.add(row);
                }
                if (task != null) tasks.progress(task, data.size(), 0);
                if (p.rows().size() < batch || data.size() >= props.exportMaxRows()) break;
                page++;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            FastExcel.write(out).head(head).sheet(m.concept().displayName()).doWrite(data);
            String key = storage.put(m.concept().displayName() + "-" + id + ".xlsx", out.toByteArray());
            jdbc.update("update crud_export_job set status = 'DONE', row_count = ?, object_key = ?, finished_at = now() where id = ?",
                data.size(), key, id);
            if (task != null) {
                tasks.finish(task, Map.of("jobId", id, "concept", concept, "rowCount", data.size(), "download", "/api/crud/export/" + id + "/download"));
            }
        } catch (Exception e) {
            if (task != null) tasks.fail(task, "导出失败：" + e.getMessage());
            jdbc.update("update crud_export_job set status = 'FAILED', error = ?, finished_at = now() where id = ?",
                String.valueOf(e.getMessage()).substring(0, Math.min(1000, String.valueOf(e.getMessage()).length())), id);
        }
    }

    public Job job(long id, ActorContext actor) {
        Map<String, Object> r = row(id, actor);
        return new Job(id, (String) r.get("concept_code"), (String) r.get("status"), (Integer) r.get("row_count"), (String) r.get("error"));
    }

    public InputStream download(long id, ActorContext actor) throws IOException {
        Map<String, Object> r = row(id, actor);
        if (!"DONE".equals(r.get("status"))) throw new LsBizException("EXPORT_NOT_READY", "导出文件尚未生成");
        audit.writeIndependently(new OperationAudit.Entry(GenericCrudService.permission((String) r.get("concept_code"), "export") + ":download",
            actor, (String) r.get("concept_code"), String.valueOf(id), null, "SUCCESS", null, null, "", "{}", 0, null));
        return storage.open((String) r.get("object_key"));
    }

    private Map<String, Object> row(long id, ActorContext actor) {
        Map<String, Object> r = jdbc.queryForList("select * from crud_export_job where id = ?", id).stream().findFirst()
            .orElseThrow(() -> LsBizException.notFound("导出任务", String.valueOf(id)));
        if (!actor.superAdmin() && (actor.effectiveParty() == null || !actor.effectiveParty().equals(r.get("created_by")))) {
            throw new LsBizException("ACCESS_DENIED", "只能查看自己的导出任务");
        }
        return r;
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** 单元格值：附件列为文件名、JSON 列为文本（Excel 只接受标量）。 */
    public static Object cell(Object v) {
        if (v instanceof java.util.Collection<?> c) {
            return String.join("、", c.stream().map(x -> x instanceof Map<?, ?> m && m.get("name") != null ? String.valueOf(m.get("name"))
                : String.valueOf(x)).toList());
        }
        if (v instanceof Map<?, ?> m) return m.containsKey("label") ? String.valueOf(m.get("label")) : String.valueOf(m);
        if (v instanceof com.fasterxml.jackson.databind.JsonNode j) return j.isValueNode() ? j.asText() : j.toString();
        return v;
    }
}
