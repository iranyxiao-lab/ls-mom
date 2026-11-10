package com.lsmom.ai.assist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.action.rule.CelRuleEngine;
import com.lsmom.ai.llm.LlmGateway;
import com.lsmom.core.error.LsBizException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自然语言生成字段与规则：把表单现有字段、可添加的 Concept 字段、已有规则与需求描述发给 LLM，
 * 要求输出 FormSchema 补丁（addField / updateField / addRule）。服务端逐条校验（字段存在、属性白名单、CEL 可编译、
 * 只引用表单字段），返回补丁与问题；不修改任何表单——由设计器展示差异，确认后才应用到草稿，发布仍走原校验。
 */
public class FormAssistService {

    /** 可添加到表单的 Concept 字段 */
    public record ConceptField(String code, String label, String type) {
    }

    public record Proposal(String summary, ArrayNode ops, List<String> issues, String model, String modelVersion, String requestDigest) {
    }

    static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELD_SET = Set.of("label", "required", "readonly", "hidden", "help", "placeholder", "maxLength", "min", "max");
    private static final Set<String> WIDGETS = Set.of("input", "textarea", "number", "money", "date", "datetime", "switch", "select", "radio",
        "checkbox", "dict", "userRef", "deptRef", "teamRef", "assetRef", "conceptRef", "attachment", "image", "file");
    private static final Pattern MODEL_REF = Pattern.compile("\\$model\\.([A-Za-z][A-Za-z0-9_]*)");
    private static final Pattern RULE_CODE = Pattern.compile("^[a-z][A-Za-z0-9]{0,47}$");

    static final String SYSTEM = """
        你是工业台账表单设计助手。根据"需求"修改表单，只输出一个 JSON 对象，不要其他文字：
        {"summary": "一句话说明改了什么",
         "ops": [
           {"op": "addRule", "rule": {"code": "小驼峰编码", "level": "L1", "when": "CEL 条件（可省略）", "assert": "CEL 断言", "field": "出错时标在哪个字段", "message": "给填写人看的提示"}},
           {"op": "updateField", "key": "字段键", "set": {"required": true, "label": "...", "help": "...", "readonly": false, "hidden": false}},
           {"op": "addField", "field": {"key": "可添加字段的编码", "label": "...", "widget": "input", "required": false}}
         ]}
        规则：
        - CEL 里用 $model.<字段键> 取值；判断文本已填写用 has($model.x) && $model.x != null && size($model.x) > 0；选项比较用编码（如 $model.severity == 'HIGH'）。
        - "某条件下必填"用 addRule（when 写条件，assert 写已填写），不要把字段改成无条件必填。
        - 只能引用"已有字段"和"可添加字段"中的键；不要改编码、不要删除字段。
        - 每条规则的 message 用中文，说明什么情况下必须填什么。""";

    private final LlmClients clients;
    private final CelRuleEngine cel;

    public FormAssistService(LlmClients clients, CelRuleEngine cel) {
        this.clients = clients;
        this.cel = cel;
    }

    /** 提示词（确定性：只含字段、选项、规则与需求，不含编码、版本等每次不同的内容，便于录制回放）。 */
    static String prompt(JsonNode schema, List<ConceptField> conceptFields, String instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("表单：").append(schema.path("title").asText("")).append('\n');
        sb.append("已有字段：\n");
        Set<String> onForm = new LinkedHashSet<>();
        for (JsonNode f : schema.path("fields")) {
            onForm.add(f.path("key").asText());
            sb.append("- ").append(f.path("key").asText()).append("｜").append(f.path("label").asText(f.path("key").asText()))
                .append("｜").append(f.path("widget").asText("input")).append("｜").append(f.path("required").asBoolean(false) ? "必填" : "选填");
            if (f.path("options").isArray() && !f.path("options").isEmpty()) {
                List<String> o = new ArrayList<>();
                f.path("options").forEach(x -> o.add(x.path("value").asText() + "=" + x.path("label").asText(x.path("value").asText())));
                sb.append("｜选项 ").append(String.join(", ", o));
            }
            sb.append('\n');
        }
        List<ConceptField> addable = conceptFields == null ? List.of() : conceptFields.stream().filter(c -> !onForm.contains(c.code())).toList();
        if (!addable.isEmpty()) {
            sb.append("可添加字段：\n");
            addable.forEach(c -> sb.append("- ").append(c.code()).append("｜").append(c.label()).append("｜").append(c.type()).append('\n'));
        }
        if (schema.path("rules").isArray() && !schema.path("rules").isEmpty()) {
            sb.append("已有规则：\n");
            schema.path("rules").forEach(r -> sb.append("- ").append(r.path("code").asText("")).append("：").append(r.path("message").asText("")).append('\n'));
        }
        sb.append("需求：").append(instruction.trim());
        return sb.toString();
    }

    public Proposal propose(JsonNode schema, List<ConceptField> conceptFields, String instruction) {
        if (instruction == null || instruction.isBlank()) throw LsBizException.validation(Map.of("instruction", "请描述要加的字段或规则"));
        if (instruction.length() > 500) throw LsBizException.validation(Map.of("instruction", "描述请控制在 500 字以内"));
        LlmGateway llm = clients.create();
        LlmGateway.Completion c;
        try {
            c = llm.complete(List.of(LlmGateway.Message.system(SYSTEM), LlmGateway.Message.user(prompt(schema, conceptFields, instruction))), true);
        } catch (LlmGateway.LlmException e) {
            throw new LsBizException(e.code(), e.getMessage());
        }
        JsonNode out;
        try {
            out = JSON.readTree(c.content());
        } catch (Exception e) {
            throw new LsBizException("ASSIST_BAD_OUTPUT", "模型输出不是合法的 JSON，请换个说法再试");
        }
        List<String> issues = new ArrayList<>();
        ArrayNode ops = check(schema, conceptFields, out.path("ops"), issues);
        return new Proposal(out.path("summary").asText(""), ops, issues, c.model(), c.modelVersion(), c.requestDigest());
    }

    /** 逐条校验补丁：合法的标 valid=true，不合法的带 issue 说明（设计器里不能应用）。 */
    ArrayNode check(JsonNode schema, List<ConceptField> conceptFields, JsonNode raw, List<String> issues) {
        Set<String> keys = new LinkedHashSet<>();
        schema.path("fields").forEach(f -> keys.add(f.path("key").asText()));
        Set<String> addable = new LinkedHashSet<>();
        if (conceptFields != null) conceptFields.stream().map(ConceptField::code).filter(k -> !keys.contains(k)).forEach(addable::add);
        Set<String> ruleCodes = new LinkedHashSet<>();
        schema.path("rules").forEach(r -> ruleCodes.add(r.path("code").asText()));
        Set<String> all = new LinkedHashSet<>(keys);
        // 本次补丁里新增的字段也可被规则引用
        if (raw.isArray()) raw.forEach(o -> {
            if ("addField".equals(o.path("op").asText()) && addable.contains(o.path("field").path("key").asText())) all.add(o.path("field").path("key").asText());
        });
        ArrayNode out = JSON.createArrayNode();
        if (!raw.isArray()) {
            issues.add("模型没有给出修改项");
            return out;
        }
        for (JsonNode o : raw) {
            ObjectNode op = o.isObject() ? ((ObjectNode) o).deepCopy() : JSON.createObjectNode();
            String problem = switch (op.path("op").asText()) {
                case "addRule" -> checkRule(op, all, ruleCodes);
                case "updateField" -> checkUpdate(op, keys);
                case "addField" -> checkAdd(op, addable);
                default -> "不支持的修改：" + op.path("op").asText();
            };
            op.put("valid", problem == null);
            if (problem != null) {
                op.put("issue", problem);
                issues.add(problem);
            }
            out.add(op);
        }
        return out;
    }

    private String checkRule(ObjectNode op, Set<String> fields, Set<String> ruleCodes) {
        JsonNode r = op.path("rule");
        if (!r.isObject()) return "规则内容缺失";
        ObjectNode rule = (ObjectNode) r;
        String code = rule.path("code").asText("");
        if (!RULE_CODE.matcher(code).matches()) return "规则编码须为英文小驼峰：" + code;
        if (ruleCodes.contains(code)) {
            String c = code;
            for (int i = 2; ruleCodes.contains(c); i++) c = code + i;
            rule.put("code", c);
        }
        ruleCodes.add(rule.path("code").asText());
        if (!rule.hasNonNull("level")) rule.put("level", "L1");
        if (!Set.of("L0", "L1", "L2").contains(rule.path("level").asText())) return "规则级别只能是 L0/L1/L2";
        if (rule.path("message").asText("").isBlank()) return "规则缺少提示语";
        for (String part : new String[]{"when", "assert"}) {
            if (!rule.hasNonNull(part) || rule.path(part).asText().isBlank()) {
                if (part.equals("assert")) return "规则缺少断言";
                rule.remove(part);
                continue;
            }
            String src = rule.path(part).asText();
            List<Map<String, Object>> bad = cel.validate(src, true);
            if (!bad.isEmpty()) return "表达式不合法（" + part + "）：" + bad.get(0).get("message");
            Matcher m = MODEL_REF.matcher(src);
            while (m.find()) if (!fields.contains(m.group(1))) return "表达式引用了表单上没有的字段：" + m.group(1);
        }
        if (rule.hasNonNull("field") && !fields.contains(rule.path("field").asText())) return "规则标注的字段不存在：" + rule.path("field").asText();
        return null;
    }

    private static String checkUpdate(ObjectNode op, Set<String> keys) {
        String key = op.path("key").asText("");
        if (!keys.contains(key)) return "字段不在表单上：" + key;
        if (!op.path("set").isObject() || op.path("set").isEmpty()) return "没有要修改的属性";
        var it = op.path("set").fieldNames();
        while (it.hasNext()) {
            String p = it.next();
            if (!FIELD_SET.contains(p)) return "不能通过助手修改属性 " + p;
        }
        return null;
    }

    private static String checkAdd(ObjectNode op, Set<String> addable) {
        JsonNode f = op.path("field");
        String key = f.path("key").asText("");
        if (!addable.contains(key)) return "只能添加 Concept 中已有、尚未放到表单的字段：" + key;
        if (f.hasNonNull("widget") && !WIDGETS.contains(f.path("widget").asText())) return "不支持的控件：" + f.path("widget").asText();
        if (!f.hasNonNull("widget")) ((ObjectNode) f).put("widget", "input");
        return null;
    }
}
