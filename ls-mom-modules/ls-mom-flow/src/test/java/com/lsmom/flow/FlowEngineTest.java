package com.lsmom.flow;

import com.lsmom.core.error.LsBizException;
import com.lsmom.flow.bpmn.BpmnSafetyScanner;
import com.lsmom.flow.engine.RestrictedExpressionManager;
import com.lsmom.flow.model.FlowDefinition;
import com.lsmom.flow.runtime.FlowRegistry;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 9.1 引擎只在 flowable schema；10.1 配置生成 BPMN 可部署且节点一致；10.2 安全扫描。 */
class FlowEngineTest {

    private final FlowFixture f = new FlowFixture();

    @Test
    void 引擎表只出现在flowable_schema() {
        List<Map<String, Object>> rows = f.action.core.jdbc.queryForList("""
            select table_schema, count(*) as n from information_schema.tables where lower(table_name) like 'act\\_%' or lower(table_name) like 'flw\\_%'
            group by table_schema""");
        assertThat(rows).extracting(r -> r.get("table_schema")).containsExactly("flowable");
        assertThat(((Number) rows.get(0).get("n")).intValue()).isGreaterThan(20);
    }

    @Test
    void 配置生成的BPMN可部署且人工节点与配置一致() {
        FlowFixture.Scene s = f.scene();
        FlowDefinition d = f.loanFlow(s, """
            [{"key":"deptAudit","type":"AUDIT","name":"部门审核","assignee":{"strategy":"DEPT_LEADER","of":"INITIATOR"},"operation":"%s.audit","timeout":{"hours":24,"action":"REMIND_ESCALATE"}},
             {"key":"check","type":"CONDITION","name":"是否加急","branches":[
                {"name":"加急","when":"$model.purpose == 'OTHER'","nodes":[{"key":"ccLeader","type":"CC","name":"抄送公司","assignee":{"strategy":"USER","value":[%d]}}]},
                {"name":"普通","nodes":[]}]},
             {"key":"jointAudit","type":"AUDIT","name":"会签","mode":"AND","assignee":{"strategy":"USER","value":[%d,%d,%d]}},
             {"key":"par","type":"PARALLEL","name":"并行","branches":[
                {"name":"甲","nodes":[{"key":"orAudit","type":"AUDIT","name":"或签","mode":"OR","assignee":{"strategy":"USER","value":[%d,%d]}}]},
                {"name":"乙","nodes":[{"key":"svc","type":"SERVICE","name":"自动审核","operation":"%s.audit"}]}]},
             {"key":"confirm","type":"CONFIRM","name":"确认","assignee":{"strategy":"INITIATOR"}}]"""
            .formatted(s.order(), s.leader().id(), s.auditor1().id(), s.auditor2().id(), s.auditor3().id(), s.auditor1().id(), s.auditor2().id(), s.order()));
        FlowRegistry.Version v = f.runtime.publish(d, 1L);
        assertThat(v.status()).isEqualTo("PUBLISHED");
        assertThat(f.registry.scanner().scan(v.bpmnXml())).isEmpty();
        BpmnModel model = f.engine.getRepositoryService().getBpmnModel(v.procDefId());
        List<String> userTasks = model.getMainProcess().findFlowElementsOfType(UserTask.class).stream().map(UserTask::getId).toList();
        assertThat(userTasks).containsExactlyInAnyOrder("initiator", "deptAudit", "jointAudit", "orAudit", "confirm");
        assertThat(model.getMainProcess().getFlowElement("timer_deptAudit")).isNotNull();
        assertThat(((UserTask) model.getMainProcess().getFlowElement("jointAudit")).getLoopCharacteristics().isSequential()).isFalse();
        // 再发布一次得到 v2，旧版本 RETIRED
        FlowRegistry.Version v2 = f.runtime.publish(d, 1L);
        assertThat(v2.version()).isEqualTo(v.version() + 1);
        assertThat(f.registry.get(d.code(), v.version()).status()).isEqualTo("RETIRED");
    }

    @Test
    void 研发流程含脚本任务等危险内容时扫描失败部署被拒() {
        BpmnSafetyScanner scanner = new BpmnSafetyScanner();
        String script = bpmn("<scriptTask id=\"s1\" scriptFormat=\"groovy\"><script>Runtime.getRuntime().exec('rm')</script></scriptTask>", "s1");
        assertThat(scanner.scan(script)).anySatisfy(m -> assertThat(m).contains("ScriptTask"));
        assertThatThrownBy(() -> scanner.require(script)).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.getCode()).isEqualTo("FLOW_UNSAFE"));
        assertThat(scanner.scan(bpmn("<serviceTask id=\"s1\" flowable:class=\"com.evil.Delegate\"/>", "s1")))
            .anySatisfy(m -> assertThat(m).contains("平台服务节点"));
        assertThat(scanner.scan(bpmn("<serviceTask id=\"s1\" flowable:delegateExpression=\"${someBean}\"/>", "s1"))).isNotEmpty();
        assertThat(scanner.scan(bpmn("<serviceTask id=\"s1\" flowable:expression=\"${execution.getEngineServices()}\"/>", "s1"))).isNotEmpty();
        assertThat(scanner.scan(bpmn("<serviceTask id=\"s1\" flowable:type=\"http\"><extensionElements><flowable:field name=\"requestUrl\"><flowable:string>https://evil.example.com</flowable:string></flowable:field></extensionElements></serviceTask>", "s1")))
            .anySatisfy(m -> assertThat(m).contains("URL"));
        assertThat(scanner.scan(bpmn("<userTask id=\"s1\" flowable:assignee=\"${userService.findAdmin()}\"/>", "s1")))
            .anySatisfy(m -> assertThat(m).contains("白名单"));
        assertThat(scanner.scan(bpmn("<userTask id=\"s1\" flowable:assignee=\"${starter}\"/>", "s1"))).isEmpty();
    }

    @Test
    void 引擎表达式只允许白名单形式() {
        RestrictedExpressionManager m = new RestrictedExpressionManager();
        assertThat(m.createExpression("${outcome == 'REJECTED'}")).isNotNull();
        assertThatThrownBy(() -> m.createExpression("${execution.getEngineServices()}")).hasMessageContaining("白名单");
        assertThatThrownBy(() -> m.createExpression("${''.getClass().forName('java.lang.Runtime')}")).hasMessageContaining("白名单");
    }

    private static String bpmn(String element, String id) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="https://lsmom.local/flow">
              <process id="dev" isExecutable="true">
                <startEvent id="start"/>
                %s
                <endEvent id="end"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="%s"/>
                <sequenceFlow id="f2" sourceRef="%s" targetRef="end"/>
              </process>
            </definitions>""".formatted(element, id, id);
    }
}
