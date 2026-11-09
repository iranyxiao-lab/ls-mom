package com.lsmom.pack;

import com.lsmom.crud.CrudFixture;
import com.lsmom.crud.metric.MetricRegistry;
import com.lsmom.flow.FlowFixture;
import com.lsmom.form.FormFixture;
import com.lsmom.action.ActionFixture;
import com.lsmom.meta.pack.PackGate;
import com.lsmom.core.party.Party;
import com.lsmom.test.CoreFixture;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pack 测试装配：在流程装配之上组装安装器、生命周期、测试器、ChangeSet 流水线、漂移与复审（与生产装配同一批类）。 */
public class PackFixture {

    public final FlowFixture flow = new FlowFixture();
    public final FormFixture form = flow.form;
    public final CrudFixture crud = flow.crud;
    public final ActionFixture action = flow.action;
    public final CoreFixture core = action.core;
    public final PackGate gate = new PackGate(core.jdbc);
    public final PackStore store = new PackStore(core.jdbc);
    public final PackDefinitions defs = new PackDefinitions(core.jdbc, action.concepts);
    public final MetricRegistry metrics = new MetricRegistry(core.jdbc, action.concepts, action.txm);
    public final PackInstaller installer = new PackInstaller(core.jdbc, store, defs, action.concepts, crud.publisher, action.machines,
        action.rulePublisher, form.forms, metrics, flow.registry, crud.crud, gate);
    public final PackLifecycle lifecycle = new PackLifecycle(core.jdbc, store, gate);
    public final PackTester tester = new PackTester(core.jdbc, store, crud.crud, action.gateway, action.operations, action.concepts, action.txm);
    public final ChangeSetPipeline pipeline = new ChangeSetPipeline(core.jdbc, store, installer, defs);
    public final DriftDetector drift = new DriftDetector(store, defs, pipeline);
    public final PackReview review = new PackReview(core.jdbc, store, core.clock);
    public final CoreFixture.Plant plant;
    public final Party owner;

    public PackFixture() {
        action.gateway.setPackGate(gate);
        crud.crud.setPackGate(gate);
        plant = core.plant();
        owner = core.employee("pack-owner-" + System.nanoTime(), plant.company());
    }

    /** 唯一的 Pack 编码（共享测试库）。 */
    public static String code(String prefix) {
        return "acme." + prefix + Long.toString(System.nanoTime(), 36);
    }

    /** 由"路径 → 文本"构造 Pack 源；文本中的 {ns} 替换为 Pack 编码。 */
    public static PackSource pack(String ns, Map<String, String> files) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        files.forEach((k, v) -> m.put(k, v.replace("{ns}", ns).getBytes(StandardCharsets.UTF_8)));
        return PackSource.of(m);
    }

    /** 培训考试 Pack：培训记录台账、完成状态机、表单、指标、种子与黄金场景。 */
    public Map<String, String> training(String version, String reviewDue) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("pack.yaml", """
            code: {ns}
            version: %s
            name: 培训考试
            standard: true
            owner: { name: 王培训, party: %s }
            requester: { name: 李车间 }
            reviewDue: %s
            contents:
              concepts: [{ns}.Record]
              operations: [{ns}.Record.complete]
              views: [{ns}.recordForm]
              metrics: [{ns}.recordCount]
            entries:
              - { name: 培训记录, path: /lsmom/ledger/{ns}.Record }
            """.formatted(version, owner.code(), reviewDue));
        f.put("concepts/record.yaml", """
            code: {ns}.Record
            displayName: 培训记录
            fields:
              - { code: title, displayName: 课程, dataType: STRING, required: true, props: { queryable: true } }
              - { code: trainee, displayName: 学员, dataType: STRING }
              - { code: score, displayName: 成绩, dataType: INTEGER }
            """);
        f.put("operations/lifecycle.yaml", """
            code: {ns}.Record.lifecycle
            concept: {ns}.Record
            displayName: 培训记录状态
            states: [DRAFT, DONE]
            initial: DRAFT
            transitions:
              - operation: {ns}.Record.complete
                name: 完成培训
                from: [DRAFT]
                to: DONE
                permission: "{ns}:record:complete"
                risk: C1
                inputSchema: { type: object }
            """);
        f.put("views/record-form.json", """
            {"viewType":"FORM","formCode":"{ns}.recordForm","version":1,"conceptCode":"{ns}.Record","title":"培训记录",
             "layout":[{"type":"field","key":"title"},{"type":"field","key":"trainee"},{"type":"field","key":"score"}],
             "fields":[{"key":"title","label":"课程","widget":"input","required":true},{"key":"trainee","label":"学员","widget":"input"},
                       {"key":"score","label":"成绩","widget":"number"}]}
            """);
        f.put("metrics/record-count.yaml", """
            code: {ns}.recordCount
            name: 培训记录数
            concept: {ns}.Record
            measure: { fn: count }
            timeField: createdAt
            grains: [DAY]
            dimensions: [status]
            """);
        f.put("seed/records.yaml", """
            concept: {ns}.Record
            key: title
            rows:
              - { title: 入职安全培训, trainee: 张三 }
            """);
        f.put("tests/scenarios/complete.yaml", """
            name: 登记并完成培训
            steps:
              - create: { concept: {ns}.Record, as: r1, values: { title: 消防培训, trainee: 李四 } }
              - op: { code: {ns}.Record.complete, target: r1 }
              - expect: { concept: {ns}.Record, target: r1, status: DONE, values: { trainee: 李四 } }
              - expectError: { code: INVALID_TRANSITION, op: { code: {ns}.Record.complete, target: r1 } }
            """);
        return f;
    }
}
