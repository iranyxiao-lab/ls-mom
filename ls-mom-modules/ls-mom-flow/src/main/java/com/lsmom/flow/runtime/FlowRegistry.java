package com.lsmom.flow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.registry.OperationDefinition;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.action.rule.CelRuleEngine;
import com.lsmom.core.error.LsBizException;
import com.lsmom.flow.bpmn.BpmnSafetyScanner;
import com.lsmom.flow.bpmn.BpmnTemplateGenerator;
import com.lsmom.flow.model.FlowDefinition;
import com.lsmom.flow.model.FlowDefinition.Node;
import com.lsmom.form.FormRegistry;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.FieldUsageIndex;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 审批流定义：校验 → 固定模板生成 BPMN → 安全扫描 → 部署 → 写入 meta_flow 新版本（旧版本 RETIRED，在途实例不受影响）。
 * 同时登记字段使用方（FLOW_NODE），表单删除被流程节点引用的字段时能被兼容性检查拦下。
 */
public class FlowRegistry {

    public record Version(String code, int version, String concept, String name, String status, FlowDefinition definition,
                          String procDefId, String bpmnXml, LocalDateTime publishedAt) {
    }

    public record Issue(String path, String code, String message) {
    }

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9]*(\\.[A-Za-z][A-Za-z0-9]*)+$");
    private static final Pattern KEY = Pattern.compile("^[a-z][A-Za-z0-9]{0,40}$");
    private static final Set<String> RESERVED = Set.of("initiator", "start", "end", "flow");
    private static final Set<String> STRATEGIES = Set.of("USER", "ROLE", "DEPT", "TEAM", "FIELD", "INITIATOR", "ORG_LEADER", "DEPT_LEADER",
        "WORKSHOP_LEADER", "SUPERIOR_LEADER", "DATA_SCOPE");

    private final JdbcTemplate jdbc;
    private final ProcessEngine engine;
    private final ConceptRegistry concepts;
    private final OperationRegistry operations;
    private final FormRegistry forms;
    private final FieldUsageIndex usages;
    private final AssigneeResolver assignees;
    private final CelRuleEngine cel;
    private final BpmnTemplateGenerator generator = new BpmnTemplateGenerator();
    private final BpmnSafetyScanner scanner = new BpmnSafetyScanner();
    private final TransactionTemplate tx;

    public FlowRegistry(JdbcTemplate jdbc, ProcessEngine engine, ConceptRegistry concepts, OperationRegistry operations, FormRegistry forms,
                        FieldUsageIndex usages, AssigneeResolver assignees, CelRuleEngine cel, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.concepts = concepts;
        this.operations = operations;
        this.forms = forms;
        this.usages = usages;
        this.assignees = assignees;
        this.cel = cel;
        this.tx = new TransactionTemplate(txm);
    }

    public BpmnTemplateGenerator generator() {
        return generator;
    }

    public BpmnSafetyScanner scanner() {
        return scanner;
    }

    // ---------------- 校验 ----------------

    public List<Issue> validate(FlowDefinition d) {
        List<Issue> issues = new ArrayList<>();
        if (d.code() == null || !CODE.matcher(d.code()).matches()) issues.add(new Issue("/code", "FLOW_CODE_INVALID", "流程编码格式不正确"));
        if (d.name() == null || d.name().isBlank()) issues.add(new Issue("/name", "REQUIRED", "流程名称必填"));
        if (d.concept() == null || concepts.find(d.concept()).isEmpty()) {
            issues.add(new Issue("/concept", "CONCEPT_NOT_FOUND", "业务 Concept 不存在：" + d.concept()));
            return issues;
        }
        op(d, d.startOn(), "/startOn", true, issues);
        if (d.startOn() != null && d.code() != null) {
            // 同一业务动作只能发起一条流程，否则提交时无法确定走哪条
            jdbc.queryForList("""
                select code from meta_flow where kind = 'APPROVAL' and status = 'PUBLISHED' and definition ->> 'startOn' = ? and code <> ?
                order by code limit 1""", String.class, d.startOn(), d.code()).stream().findFirst()
                .ifPresent(other -> issues.add(new Issue("/startOn", "FLOW_START_CONFLICT",
                    "发起动作 " + d.startOn() + " 已被流程 " + other + " 使用；请先停用该流程或改用其他发起动作")));
        }
        if (d.callbacks() == null || d.callbacks().approved() == null) {
            issues.add(new Issue("/callbacks/approved", "CALLBACK_REQUIRED", "必须声明审批通过（onApproved）对应的 Operation"));
        }
        if (d.callbacks() == null || d.callbacks().rejected() == null) {
            issues.add(new Issue("/callbacks/rejected", "CALLBACK_REQUIRED", "必须声明驳回（onRejected）对应的 Operation"));
        }
        d.callbackMap().forEach((k, v) -> op(d, v, "/callbacks/" + k, true, issues));

        JsonNode form = null;
        if (d.form() != null) {
            try {
                FormRegistry.View v = forms.get(d.form().code(), d.form().version() == null ? 0 : d.form().version());
                if (!"PUBLISHED".equals(v.status()) && !"RETIRED".equals(v.status())) {
                    issues.add(new Issue("/form", "FORM_NOT_PUBLISHED", "节点引用的表单版本未发布"));
                } else if (!d.concept().equals(v.schema().path("conceptCode").asText())) {
                    issues.add(new Issue("/form", "FORM_CONCEPT_MISMATCH", "表单不属于流程的业务 Concept"));
                } else form = v.schema();
            } catch (LsBizException e) {
                issues.add(new Issue("/form", "FORM_NOT_FOUND", "表单不存在：" + d.form().code() + " v" + d.form().version()));
            }
        }
        if (d.nodes() == null || d.nodes().isEmpty()) issues.add(new Issue("/nodes", "REQUIRED", "流程至少需要一个节点"));
        else {
            Set<String> keys = new HashSet<>();
            boolean[] human = {false};
            nodes(d, d.nodes(), "/nodes", form, keys, human, issues);
            if (!human[0]) issues.add(new Issue("/nodes", "NO_HUMAN_NODE", "流程至少需要一个人工节点"));
        }
        if (issues.isEmpty()) {
            List<String> unsafe = scanner.scan(generator.generate(d.withVersion(d.version() == null ? 1 : d.version())));
            unsafe.forEach(m -> issues.add(new Issue("/", "FLOW_UNSAFE", m)));
        }
        return issues;
    }

    private void nodes(FlowDefinition d, List<Node> list, String path, JsonNode form, Set<String> keys, boolean[] human, List<Issue> issues) {
        for (int i = 0; i < list.size(); i++) {
            Node n = list.get(i);
            String p = path + "/" + i;
            if (n.key() == null || !KEY.matcher(n.key()).matches() || RESERVED.contains(n.key())) {
                issues.add(new Issue(p + "/key", "NODE_KEY_INVALID", "节点键格式不正确或为保留字：" + n.key()));
                continue;
            }
            if (!keys.add(n.key())) issues.add(new Issue(p + "/key", "NODE_KEY_DUPLICATE", "节点键重复：" + n.key()));
            if (n.type() == null) {
                issues.add(new Issue(p + "/type", "REQUIRED", "节点类型必填"));
                continue;
            }
            String label = "节点「" + (n.name() == null ? n.key() : n.name()) + "」";
            if (n.name() == null || n.name().isBlank()) issues.add(new Issue(p + "/name", "REQUIRED", "节点名称必填"));
            if (n.mode() != null && n.mode() != FlowDefinition.Mode.SINGLE && !n.human()) {
                issues.add(new Issue(p + "/mode", "MODE_INVALID", label + "只有人工节点可以设置或签/会签"));
            }
            if (n.human()) {
                human[0] = true;
                assignee(d, n, p, label, issues);
                if (n.operation() != null) op(d, n.operation(), p + "/operation", false, issues);
                policy(n, p, label, form, issues);
                if (n.timeout() != null) {
                    if (n.timeout().hours() == null || n.timeout().hours() < 1 || n.timeout().hours() > 720) {
                        issues.add(new Issue(p + "/timeout/hours", "TIMEOUT_INVALID", label + "超时时长须在 1～720 小时"));
                    }
                    if (n.timeout().action() == null) issues.add(new Issue(p + "/timeout/action", "REQUIRED", label + "超时动作必填"));
                }
            } else if (n.type() == FlowDefinition.NodeType.SERVICE) {
                if (n.operation() == null) issues.add(new Issue(p + "/operation", "REQUIRED", label + "服务节点必须选择已发布 Operation"));
                else op(d, n.operation(), p + "/operation", false, issues);
            } else if (n.type() == FlowDefinition.NodeType.CC) {
                assignee(d, n, p, label, issues);
            } else {
                List<FlowDefinition.Branch> bs = n.branches();
                if (bs == null || bs.size() < 2) {
                    issues.add(new Issue(p + "/branches", "BRANCHES_INVALID", label + "至少需要两个分支"));
                    continue;
                }
                int defaults = 0;
                for (int b = 0; b < bs.size(); b++) {
                    FlowDefinition.Branch br = bs.get(b);
                    String bp = p + "/branches/" + b;
                    if (n.type() == FlowDefinition.NodeType.CONDITION) {
                        if (br.when() == null || br.when().isBlank()) defaults++;
                        else cel.validate(br.when(), true).forEach(e -> issues.add(new Issue(bp + "/when", "CONDITION_INVALID",
                            label + "分支条件不合法：" + e.get("message"))));
                    } else if (br.nodes() == null || br.nodes().isEmpty()) {
                        issues.add(new Issue(bp + "/nodes", "REQUIRED", label + "并行分支不能为空"));
                    }
                    if (br.nodes() != null) nodes(d, br.nodes(), bp + "/nodes", form, keys, human, issues);
                }
                if (defaults > 1) issues.add(new Issue(p + "/branches", "BRANCHES_INVALID", label + "最多一个默认分支（无条件）"));
            }
        }
    }

    private void assignee(FlowDefinition d, Node n, String p, String label, List<Issue> issues) {
        FlowDefinition.Assignee a = n.assignee();
        if (a == null || a.strategy() == null || !STRATEGIES.contains(a.strategy().toUpperCase())) {
            issues.add(new Issue(p + "/assignee", "ASSIGNEE_INVALID", label + "必须选择命名办理人策略"));
            return;
        }
        String s = a.strategy().toUpperCase();
        boolean runtimeOnly = s.equals("FIELD") || s.equals("INITIATOR")
            || (s.endsWith("_LEADER") && !"BIZ_ORG".equalsIgnoreCase(a.of()))
            || ((s.equals("DEPT") || s.equals("TEAM")) && (a.value() == null || a.value().isNull()) && d.orgUnitId() == null)
            || (s.equals("DATA_SCOPE") && d.orgUnitId() == null)
            || (s.endsWith("_LEADER") && d.orgUnitId() == null);
        if (s.equals("FIELD")) {
            String f = a.value() == null ? null : a.value().asText();
            if (f == null || concepts.get(d.concept()).fields().stream().noneMatch(x -> x.code().equals(f))) {
                issues.add(new Issue(p + "/assignee/value", "ASSIGNEE_FIELD_INVALID", label + "办理人字段不存在：" + f));
            }
        }
        if (runtimeOnly) return;
        AssigneeResolver.Result r = assignees.resolve(a, n.permission(), new AssigneeResolver.Context(null, d.orgUnitId(), Map.of()));
        if (r.empty()) issues.add(new Issue(p + "/assignee", "ASSIGNEE_UNRESOLVED", label + "办理人无法解析：" + r.reason()));
    }

    private void policy(Node n, String p, String label, JsonNode form, List<Issue> issues) {
        JsonNode effective = n.fieldPolicy() != null ? n.fieldPolicy() : form == null ? null : form.path("nodePolicies").get(n.policyKey());
        if (effective != null && n.operation() == null
            && (effective.path("editable").size() > 0 || effective.path("required").size() > 0)) {
            issues.add(new Issue(p + "/operation", "NODE_OPERATION_REQUIRED", label + "允许填写字段，必须选择提交字段的 Operation"));
        }
        if (n.fieldPolicy() == null && n.policy() == null) return;
        if (form == null) {
            issues.add(new Issue(p + "/fieldPolicy", "FORM_REQUIRED", label + "设置字段策略前须先为流程选择已发布表单"));
            return;
        }
        if (n.policy() != null && n.fieldPolicy() == null && !form.path("nodePolicies").has(n.policy())) {
            issues.add(new Issue(p + "/policy", "POLICY_NOT_FOUND", label + "引用的表单节点策略不存在：" + n.policy()));
        }
        if (n.fieldPolicy() == null) return;
        Set<String> fields = formFields(form);
        for (String kind : List.of("readonly", "hidden", "editable", "required", "visible")) {
            JsonNode arr = n.fieldPolicy().get(kind);
            if (arr == null) continue;
            if (!arr.isArray()) {
                issues.add(new Issue(p + "/fieldPolicy/" + kind, "POLICY_INVALID", label + "字段策略须为字段数组"));
                continue;
            }
            for (int i = 0; i < arr.size(); i++) {
                String f = arr.get(i).asText();
                if (!f.equals("*") && !fields.contains(f)) {
                    issues.add(new Issue(p + "/fieldPolicy/" + kind + "/" + i, "POLICY_FIELD_INVALID", label + "字段策略引用了表单中不存在的字段：" + f));
                }
            }
        }
        n.fieldPolicy().fieldNames().forEachRemaining(k -> {
            if (!Set.of("readonly", "hidden", "editable", "required", "visible").contains(k)) {
                issues.add(new Issue(p + "/fieldPolicy/" + k, "POLICY_INVALID", label + "未知的字段策略：" + k));
            }
        });
    }

    static Set<String> formFields(JsonNode form) {
        Set<String> s = new LinkedHashSet<>();
        form.path("fields").forEach(f -> s.add(f.path("key").asText()));
        form.path("subTables").forEach(t -> {
            s.add(t.path("key").asText());
            t.path("fields").forEach(f -> s.add(t.path("key").asText() + "." + f.path("key").asText()));
        });
        return s;
    }

    private void op(FlowDefinition d, String code, String path, boolean required, List<Issue> issues) {
        if (code == null) {
            if (required) issues.add(new Issue(path, "REQUIRED", "必须选择 Operation"));
            return;
        }
        Optional<OperationDefinition> o = operations.find(code);
        if (o.isEmpty() || !"PUBLISHED".equals(o.get().status())) {
            issues.add(new Issue(path, "OPERATION_NOT_PUBLISHED", "只能选择已发布的 Operation：" + code));
        } else if (!d.concept().equals(o.get().concept())) {
            issues.add(new Issue(path, "OPERATION_CONCEPT_MISMATCH", "Operation 不属于流程的业务 Concept：" + code));
        }
    }

    public void require(FlowDefinition d) {
        List<Issue> issues = validate(d);
        if (!issues.isEmpty()) {
            throw new LsBizException("FLOW_INVALID", "流程配置校验失败：" + issues.get(0).message(), Map.of("issues", issues));
        }
    }

    // ---------------- 发布 ----------------

    /** 校验并发布为新版本；返回发布的版本号。 */
    public Version publish(FlowDefinition input, Long by) {
        FlowDefinition probe = input.withVersion(nextVersion(input.code()));
        require(probe);
        String xml = generator.generate(probe);
        scanner.require(xml);
        return tx.execute(st -> {
            int v = nextVersion(input.code());
            FlowDefinition d = input.withVersion(v);
            String bpmn = generator.generate(d);
            Deployment dep = engine.getRepositoryService().createDeployment().name(d.code() + " v" + v).key(d.processKey())
                .category(d.concept()).addString(d.processKey() + ".bpmn20.xml", bpmn).deploy();
            ProcessDefinition pd = engine.getRepositoryService().createProcessDefinitionQuery().deploymentId(dep.getId()).singleResult();
            List<Integer> old = jdbc.queryForList("select version from meta_flow where code = ? and kind = 'APPROVAL' and status = 'PUBLISHED'",
                Integer.class, d.code());
            jdbc.update("update meta_flow set status = 'RETIRED' where code = ? and kind = 'APPROVAL' and status = 'PUBLISHED'", d.code());
            jdbc.update("delete from meta_flow where code = ? and kind = 'APPROVAL' and status = 'DRAFT'", d.code());
            jdbc.update("""
                insert into meta_flow (code, version, concept_code, kind, display_name, status, definition, published_at, deployment_id,
                                       proc_def_id, bpmn_xml, created_by)
                values (?, ?, ?, 'APPROVAL', ?, 'PUBLISHED', ?::jsonb, now(), ?, ?, ?, ?)""",
                d.code(), v, d.concept(), d.name(), d.toJson().toString(), dep.getId(), pd.getId(), bpmn, by);
            for (Integer o : old) usages.retire(FieldUsageIndex.UserType.FLOW_NODE, d.code(), o);
            usages.replace(FieldUsageIndex.UserType.FLOW_NODE, d.code(), v, fieldRefs(d));
            return get(d.code(), v);
        });
    }

    /** 保存草稿（覆盖已有草稿），不部署。 */
    public Version saveDraft(FlowDefinition input, Long by) {
        return tx.execute(st -> {
            Integer draft = jdbc.query("select version from meta_flow where code = ? and kind = 'APPROVAL' and status = 'DRAFT'",
                (rs, i) -> rs.getInt(1), input.code()).stream().findFirst().orElse(null);
            int v = draft != null ? draft : nextVersion(input.code());
            if (concepts.find(input.concept()).isEmpty()) throw new LsBizException("CONCEPT_NOT_FOUND", "业务 Concept 不存在：" + input.concept());
            FlowDefinition d = input.withVersion(v);
            if (draft != null) {
                jdbc.update("update meta_flow set definition = ?::jsonb, display_name = ?, created_by = ? where code = ? and version = ?",
                    d.toJson().toString(), d.name(), by, d.code(), v);
            } else {
                jdbc.update("""
                    insert into meta_flow (code, version, concept_code, kind, display_name, status, definition, created_by)
                    values (?, ?, ?, 'APPROVAL', ?, 'DRAFT', ?::jsonb, ?)""", d.code(), v, d.concept(), d.name(), d.toJson().toString(), by);
            }
            return get(d.code(), v);
        });
    }

    private int nextVersion(String code) {
        Integer max = jdbc.queryForObject("select coalesce(max(version), 0) from meta_flow where code = ? and status <> 'DRAFT'",
            Integer.class, code);
        return (max == null ? 0 : max) + 1;
    }

    private List<FieldUsageIndex.FieldRef> fieldRefs(FlowDefinition d) {
        Map<String, FieldUsageIndex.FieldRef> refs = new LinkedHashMap<>();
        Set<String> conceptFields = new HashSet<>();
        concepts.get(d.concept()).fields().forEach(f -> conceptFields.add(f.code()));
        d.walk(n -> {
            if (n.fieldPolicy() != null) {
                n.fieldPolicy().fields().forEachRemaining(e -> e.getValue().forEach(v -> {
                    String f = v.asText();
                    if (conceptFields.contains(f)) refs.putIfAbsent(n.key() + ":" + f, new FieldUsageIndex.FieldRef(d.concept(), f, n.key()));
                }));
            }
            if (n.assignee() != null && "FIELD".equalsIgnoreCase(n.assignee().strategy()) && n.assignee().value() != null) {
                String f = n.assignee().value().asText();
                refs.putIfAbsent(n.key() + ":" + f, new FieldUsageIndex.FieldRef(d.concept(), f, n.key()));
            }
        });
        return List.copyOf(refs.values());
    }

    // ---------------- 查询 ----------------

    public Version get(String code, int version) {
        return jdbc.query("select * from meta_flow where code = ? and version = ? and kind = 'APPROVAL'", (rs, i) -> map(rs), code, version)
            .stream().findFirst().orElseThrow(() -> LsBizException.notFound("流程", code + " v" + version));
    }

    public Optional<Version> published(String code) {
        return jdbc.query("select * from meta_flow where code = ? and kind = 'APPROVAL' and status = 'PUBLISHED'", (rs, i) -> map(rs), code)
            .stream().findFirst();
    }

    /** 停用流程：已发布版本置为 RETIRED，之后业务提交不再发起；在途实例按启动时的版本继续。 */
    public int retire(String code) {
        return jdbc.update("update meta_flow set status = 'RETIRED' where code = ? and kind = 'APPROVAL' and status = 'PUBLISHED'", code);
    }

    /** 由某个业务 Operation 发起的已发布流程。 */
    public Optional<Version> startedBy(String operation) {
        return jdbc.query("""
            select * from meta_flow where kind = 'APPROVAL' and status = 'PUBLISHED' and definition ->> 'startOn' = ?
            order by code limit 1""", (rs, i) -> map(rs), operation).stream().findFirst();
    }

    public List<Version> history(String code) {
        return jdbc.query("select * from meta_flow where code = ? and kind = 'APPROVAL' order by version desc", (rs, i) -> map(rs), code);
    }

    public List<Version> list() {
        return jdbc.query("""
            select distinct on (code) * from meta_flow where kind = 'APPROVAL'
            order by code, case status when 'PUBLISHED' then 0 when 'DRAFT' then 1 else 2 end, version desc""", (rs, i) -> map(rs));
    }

    public List<Version> allPublished() {
        return jdbc.query("select * from meta_flow where kind = 'APPROVAL' and status = 'PUBLISHED'", (rs, i) -> map(rs));
    }

    private static Version map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Version(rs.getString("code"), rs.getInt("version"), rs.getString("concept_code"), rs.getString("display_name"),
            rs.getString("status"), FlowDefinition.parse(rs.getString("definition")), rs.getString("proc_def_id"), rs.getString("bpmn_xml"),
            rs.getObject("published_at", LocalDateTime.class));
    }
}
