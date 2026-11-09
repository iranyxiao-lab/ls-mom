package com.lsmom.crud.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.registry.OperationDefinition;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.core.error.LsBizException;
import com.lsmom.crud.GenericCrudService;
import com.lsmom.crud.io.LedgerExportService;
import com.lsmom.crud.io.LedgerImportService;
import com.lsmom.crud.query.LedgerModel;
import com.lsmom.crud.query.QuerySpec;
import com.lsmom.meta.concept.FieldDef;
import jakarta.servlet.http.HttpServletResponse;
import org.dromara.common.core.domain.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用台账接口。权限、数据域、字段权限、校验、草稿态限制全部在 {@link GenericCrudService} 中执行，
 * 绕过前端直接调用同样生效；Agent 身份一律拒绝。正式动作（提交、审核……）走 {@code /api/op/{code}}。
 */
@RestController
@RequestMapping("/api/crud")
public class GenericCrudController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final GenericCrudService crud;
    private final LedgerImportService imports;
    private final LedgerExportService exports;
    private final OperationRegistry operations;
    private final ActorResolver actors;
    private final JdbcTemplate jdbc;
    private final com.lsmom.action.state.StateMachineRegistry machines;

    public GenericCrudController(GenericCrudService crud, LedgerImportService imports, LedgerExportService exports, OperationRegistry operations,
                                 ActorResolver actors, JdbcTemplate jdbc, com.lsmom.action.state.StateMachineRegistry machines) {
        this.machines = machines;
        this.crud = crud;
        this.imports = imports;
        this.exports = exports;
        this.operations = operations;
        this.actors = actors;
        this.jdbc = jdbc;
    }

    /** 列表界面描述（ListSchema）：字段、筛选/排序白名单、子表、绑定的 Operation 与当前用户的按钮权限。 */
    @GetMapping("/{concept}/schema")
    public R<Map<String, Object>> schema(@PathVariable String concept) {
        ActorContext actor = actors.current();
        crud.checkAccess(actor, concept, "list");
        LedgerModel m = crud.model(concept);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("concept", concept);
        s.put("displayName", m.concept().displayName());
        s.put("version", m.concept().version());
        s.put("fields", fields(m, actor));
        List<Map<String, Object>> children = new ArrayList<>();
        for (LedgerModel c : crud.children(concept)) {
            children.add(Map.of("key", LedgerModel.childKey(c.concept()), "concept", c.concept().code(), "displayName", c.concept().displayName(),
                "fields", fields(c, actor)));
        }
        s.put("children", children);
        List<Map<String, Object>> ops = new ArrayList<>();
        for (OperationDefinition d : operations.listByConcept(concept)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("operation", d.code());
            o.put("name", d.name());
            o.put("kind", d.kind().name());
            o.put("risk", d.risk().name());
            o.put("permitted", actor.hasPermission(d.permission()));
            o.put("inputSchema", d.inputSchema());
            if (d.kind() == OperationDefinition.Kind.TRANSITION) {
                try {
                    var t = machines.forConcept(concept).transition(d.code());
                    if (t != null) o.put("from", t.from());
                } catch (RuntimeException ignored) {
                    // 未发布状态机：不限制起始状态，由网关裁决
                }
            }
            ops.add(o);
        }
        s.put("operations", ops);
        Map<String, Boolean> perms = new LinkedHashMap<>();
        for (String a : List.of("list", "query", "add", "edit", "remove", "export", "import")) {
            perms.put(a, actor.hasPermission(GenericCrudService.permission(concept, a)));
        }
        s.put("permissions", perms);
        jdbc.queryForList("""
            select schema_json::text from meta_view where concept_code = ? and view_type = 'LIST' and status = 'PUBLISHED'
             order by version desc limit 1""", String.class, concept).stream().findFirst().ifPresent(v -> s.put("view", v));
        return R.ok(s);
    }

    private static List<Map<String, Object>> fields(LedgerModel m, ActorContext actor) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FieldDef f : m.fields().values()) {
            if (!actor.hasPermission(f.textProp("readPermission"))) continue;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", f.code());
            x.put("label", f.displayName());
            x.put("type", f.dataType().name());
            x.put("required", f.required());
            x.put("queryable", m.queryable(f.code()));
            x.put("sortable", m.sortable(f.code()));
            x.put("writable", actor.hasPermission(f.textProp("writePermission")) && !f.stateField());
            if (f.options() != null) x.put("options", f.options());
            if (f.refConcept() != null) x.put("refConcept", f.refConcept());
            for (String p : List.of("dictType", "maxLength", "minimum", "maximum", "scale", "optionLabels", "ext", "readPermission", "writePermission")) {
                if (f.props().containsKey(p)) x.put(p, f.props().get(p));
            }
            out.add(x);
        }
        return out;
    }

    @PostMapping("/{concept}/query")
    public R<GenericCrudService.Page> query(@PathVariable String concept, @RequestBody(required = false) QuerySpec spec) {
        return R.ok(crud.list(concept, spec == null ? QuerySpec.all() : spec, actors.current()));
    }

    @PostMapping("/{concept}/aggregate")
    public R<List<Map<String, Object>>> aggregate(@PathVariable String concept, @RequestBody QuerySpec spec) {
        return R.ok(crud.aggregate(concept, spec, actors.current()));
    }

    @GetMapping("/{concept}/{id:\\d+}")
    public R<Map<String, Object>> get(@PathVariable String concept, @PathVariable long id) {
        return R.ok(crud.get(concept, id, actors.current()));
    }

    @PostMapping("/{concept}")
    public R<Map<String, Object>> create(@PathVariable String concept, @RequestBody JsonNode body,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return R.ok(crud.create(concept, body, actors.current(), key));
    }

    @PutMapping("/{concept}/{id:\\d+}")
    public R<Map<String, Object>> update(@PathVariable String concept, @PathVariable long id, @RequestParam int version,
                                         @RequestBody JsonNode body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return R.ok(crud.update(concept, id, version, body, actors.current(), key));
    }

    @PostMapping("/{concept}/{id:\\d+}/copy")
    public R<Map<String, Object>> copy(@PathVariable String concept, @PathVariable long id,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return R.ok(crud.copy(concept, id, actors.current(), key));
    }

    @DeleteMapping("/{concept}/{id:\\d+}")
    public R<Map<String, Object>> delete(@PathVariable String concept, @PathVariable long id, @RequestParam int version) {
        return R.ok(crud.delete(concept, id, version, actors.current()));
    }

    @GetMapping("/{concept}/import/template")
    public void template(@PathVariable String concept, HttpServletResponse response) throws IOException {
        ActorContext actor = actors.current();
        crud.checkAccess(actor, concept, "import");
        byte[] data = imports.template(concept, actor);
        attachment(response, crud.model(concept).concept().displayName() + "-导入模板.xlsx");
        response.getOutputStream().write(data);
    }

    @PostMapping("/{concept}/import")
    public R<LedgerImportService.Report> precheck(@PathVariable String concept, @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw LsBizException.validation(Map.of("file", "请选择文件"));
        try (InputStream in = file.getInputStream()) {
            return R.ok(imports.precheck(concept, in, file.getOriginalFilename(), actors.current()));
        }
    }

    @PostMapping("/{concept}/import/{batchId}/confirm")
    public R<Integer> confirm(@PathVariable String concept, @PathVariable long batchId) {
        return R.ok(imports.confirm(concept, batchId, actors.current()));
    }

    @PostMapping("/{concept}/export")
    public R<LedgerExportService.Job> export(@PathVariable String concept, @RequestBody(required = false) QuerySpec spec) {
        return R.ok(exports.submit(concept, spec == null ? QuerySpec.all() : spec, actors.current()));
    }

    @GetMapping("/export/{jobId}")
    public R<LedgerExportService.Job> job(@PathVariable long jobId) {
        return R.ok(exports.job(jobId, actors.current()));
    }

    @GetMapping("/export/{jobId}/download")
    public void download(@PathVariable long jobId, HttpServletResponse response) throws IOException {
        ActorContext actor = actors.current();
        LedgerExportService.Job job = exports.job(jobId, actor);
        try (InputStream in = exports.download(jobId, actor)) {
            attachment(response, crud.model(job.concept()).concept().displayName() + "-" + jobId + ".xlsx");
            in.transferTo(response.getOutputStream());
        }
    }

    private static void attachment(HttpServletResponse response, String name) {
        response.setContentType(XLSX);
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
    }
}
