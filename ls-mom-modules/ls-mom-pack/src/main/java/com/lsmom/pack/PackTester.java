package com.lsmom.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScope;
import com.lsmom.crud.GenericCrudService;
import com.lsmom.meta.concept.ConceptRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pack 测试：tests/scenarios 中的黄金场景（回归）与自动越权检查（无权限身份调用每个台账写入与 Operation 必须被拒）。
 * 每个场景在单独事务中执行并回滚，不留下业务数据。
 *
 * <pre>
 * name: 登记并完成培训
 * steps:
 *   - create: { concept: acme.training.Record, as: r1, values: { title: 安全培训 } }
 *   - op: { code: acme.training.Record.complete, target: r1, input: { score: 90 } }
 *   - expect: { concept: acme.training.Record, target: r1, status: DONE, values: { score: 90 } }
 *   - expectError: { code: STATE_TRANSITION_INVALID, op: { code: acme.training.Record.complete, target: r1 } }
 * </pre>
 * 场景默认以系统身份执行；{@code op} 加 {@code as: HUMAN} 时以真人测试身份执行（验证"须由真人执行"类规则）。
 */
public class PackTester {

    /** 场景中 as: HUMAN 的执行身份：真人、全部权限、不限数据域。 */
    static final ActorContext HUMAN_TESTER = new ActorContext(ActorContext.ActorType.HUMAN, -2L, null, null, true, Set.of(),
        DataScope.unrestricted(), "场景测试（真人）");

    public record Case(String name, boolean passed, String message) {
    }

    public record Report(String pack, String version, boolean passed, List<Case> regression, List<Case> privilege) {
    }

    private final JdbcTemplate jdbc;
    private final PackStore store;
    private final GenericCrudService crud;
    private final OperationGateway gateway;
    private final OperationRegistry operations;
    private final ConceptRegistry concepts;
    private final TransactionTemplate tx;

    public PackTester(JdbcTemplate jdbc, PackStore store, GenericCrudService crud, OperationGateway gateway, OperationRegistry operations,
                      ConceptRegistry concepts, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.store = store;
        this.crud = crud;
        this.gateway = gateway;
        this.operations = operations;
        this.concepts = concepts;
        this.tx = new TransactionTemplate(txm);
    }

    public Report run(String code) {
        PackStore.Pack p = store.get(code);
        if (!"ENABLED".equals(p.status())) throw new LsBizException("PACK_STATE_INVALID", code + " 未启用，不能运行测试");
        PackContents c = PackContents.parse(store.artifact(p.artifactSha()));
        List<Case> regression = new ArrayList<>();
        for (PackContents.Item s : c.scenarios()) regression.add(rollback(s.code(), () -> scenario(code, s.body())));
        List<Case> privilege = new ArrayList<>();
        ActorContext nobody = new ActorContext(ActorContext.ActorType.HUMAN, -1L, null, null, false, Set.of(), DataScope.unrestricted(), "越权测试");
        for (PackContents.Item i : c.concepts()) {
            if (i.body().hasNonNull("extends")) continue;
            String concept = i.code();
            privilege.add(rollback("无权限新增 " + concept, () -> denied(() -> crud.create(concept, PackJson.JSON.createObjectNode(), nobody, null))));
        }
        for (PackContents.Item sm : c.machines()) {
            for (JsonNode t : sm.body().path("transitions")) {
                String op = t.path("operation").asText();
                privilege.add(rollback("无权限执行 " + op, () -> denied(() -> gateway.execute(op,
                    new OperationRequest(PackJson.JSON.createObjectNode(), "0", null, null, false), nobody))));
            }
        }
        boolean ok = regression.stream().allMatch(Case::passed) && privilege.stream().allMatch(Case::passed);
        return new Report(code, p.version(), ok, regression, privilege);
    }

    private interface Body {
        void run();
    }

    private Case rollback(String name, Body body) {
        try {
            tx.executeWithoutResult(s -> {
                s.setRollbackOnly();
                body.run();
            });
            return new Case(name, true, null);
        } catch (AssertionError | RuntimeException e) {
            return new Case(name, false, e.getMessage());
        }
    }

    private static void denied(Runnable call) {
        try {
            call.run();
        } catch (LsBizException e) {
            if ("ACCESS_DENIED".equals(e.getCode())) return;
            throw new AssertionError("应因无权限被拒，实际为 " + e.getCode() + "：" + e.getMessage());
        }
        throw new AssertionError("无权限身份调用成功，存在越权");
    }

    // —— 场景 ——

    private void scenario(String pack, JsonNode s) {
        ActorContext actor = ActorContext.system("pack-test:" + pack);
        Map<String, Long> vars = new HashMap<>();
        int n = 0;
        for (JsonNode step : s.path("steps")) {
            n++;
            try {
                step(step, actor, vars);
            } catch (AssertionError e) {
                throw new AssertionError("第 " + n + " 步：" + e.getMessage());
            } catch (LsBizException e) {
                throw new AssertionError("第 " + n + " 步失败：" + e.getCode() + " " + e.getMessage());
            }
        }
    }

    private void step(JsonNode step, ActorContext actor, Map<String, Long> vars) {
        if (step.has("create")) {
            create(step.get("create"), actor, vars);
        } else if (step.has("op")) {
            op(step.get("op"), actor, vars);
        } else if (step.has("expect")) {
            expect(step.get("expect"), actor, vars);
        } else if (step.has("expectError")) {
            JsonNode e = step.get("expectError");
            String want = e.path("code").asText();
            try {
                if (e.has("op")) op(e.get("op"), actor, vars);
                else if (e.has("create")) create(e.get("create"), actor, vars);
                else throw new AssertionError("expectError 须包含 op 或 create");
            } catch (LsBizException ex) {
                if (want.equals(ex.getCode())) return;
                throw new AssertionError("期望错误 " + want + "，实际 " + ex.getCode() + "：" + ex.getMessage());
            }
            throw new AssertionError("期望错误 " + want + "，实际执行成功");
        } else {
            throw new AssertionError("未知步骤：" + step);
        }
    }

    private void create(JsonNode c, ActorContext actor, Map<String, Long> vars) {
        ObjectNode values = c.path("values").isObject() ? ((ObjectNode) c.get("values")).deepCopy() : PackJson.JSON.createObjectNode();
        resolveVars(values, vars);
        if (!values.has("orgId")) {
            jdbc.queryForList("select id from core_org_unit where parent_id is null order by id limit 1", Long.class).stream().findFirst()
                .ifPresent(o -> values.put("orgId", o));
        }
        Map<String, Object> r = crud.create(c.path("concept").asText(), values, actor, null);
        if (c.hasNonNull("as")) vars.put(c.get("as").asText(), ((Number) r.get("id")).longValue());
    }

    /** 值写成 "$变量名" 时替换为前面步骤创建的记录编号（如引用字段 asset: $a）。 */
    private static void resolveVars(ObjectNode values, Map<String, Long> vars) {
        for (Iterator<Map.Entry<String, JsonNode>> it = values.fields(); it.hasNext(); ) {
            var f = it.next();
            String t = f.getValue().isTextual() ? f.getValue().asText() : null;
            if (t != null && t.startsWith("$") && vars.containsKey(t.substring(1))) f.setValue(PackJson.JSON.getNodeFactory().numberNode(vars.get(t.substring(1))));
        }
    }

    private void op(JsonNode o, ActorContext actor, Map<String, Long> vars) {
        String code = o.path("code").asText();
        String target = null;
        Integer version = null;
        if (o.hasNonNull("target")) {
            Long id = Objects.requireNonNull(vars.get(o.get("target").asText()), "未定义的记录变量：" + o.get("target").asText());
            target = String.valueOf(id);
            String table = concepts.get(operations.require(code).concept()).tableName();
            version = jdbc.queryForObject("select version from " + table + " where id = ?", Integer.class, id);
        }
        ObjectNode input = o.path("input").isObject() ? ((ObjectNode) o.get("input")).deepCopy() : PackJson.JSON.createObjectNode();
        resolveVars(input, vars);
        ActorContext who = "HUMAN".equals(o.path("as").asText()) ? HUMAN_TESTER : actor;
        gateway.execute(code, new OperationRequest(input, target, version,
            null, false), who);
    }

    private void expect(JsonNode e, ActorContext actor, Map<String, Long> vars) {
        Long id = vars.get(e.path("target").asText());
        if (id == null) throw new AssertionError("未定义的记录变量：" + e.path("target").asText());
        Map<String, Object> row = crud.get(e.path("concept").asText(), id, actor);
        JsonNode actual = PackJson.JSON.valueToTree(row);
        if (e.hasNonNull("status") && !e.get("status").asText().equals(actual.path("status").asText())) {
            throw new AssertionError("状态应为 " + e.get("status").asText() + "，实际 " + actual.path("status").asText());
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = e.path("values").fields(); it.hasNext(); ) {
            var f = it.next();
            JsonNode a = actual.path(f.getKey());
            if (!f.getValue().asText().equals(a.asText())) {
                throw new AssertionError(f.getKey() + " 应为 " + f.getValue().asText() + "，实际 " + (a.isMissingNode() ? "（无）" : a.asText()));
            }
        }
    }
}
