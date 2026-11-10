# 表单设计器：智能助手（自然语言生成字段与规则）

表单设计器 →「智能助手」。用一句话描述要加的字段或规则（如"用途为外协时，外协单位必填"），助手生成修改草稿：

- **差异高亮**：每一项标"新增 / 修改"，新增内容绿色，修改显示改前 → 改后；不合法的项标明原因且不能勾选。
- **确认后才应用**：生成时不改当前草稿、不写服务端；勾选后点「应用 N 项到草稿」才进入设计器（可撤销 / 重做），
  保存草稿、校验、发布仍按原流程（发布前服务端完整校验）。

## 能做的修改

| 类型 | 说明 |
|---|---|
| addRule | 规则草稿：条件（when）、断言（assert，CEL）、标注字段、提示语。"某条件下必填"一律生成规则，不把字段改成无条件必填 |
| updateField | 修改字段属性，限 label、required、readonly、hidden、help、placeholder、maxLength、min、max |
| addField | 把 Concept 中已有、尚未放到表单的字段加到表单 |

服务端逐条校验：字段存在、属性在白名单内、CEL 可编译、表达式只引用表单上的字段；规则编码与已有规则重复时自动加序号。

## 模型接入

经 `LlmGateway` 调用 OpenAI 兼容接口（可指向私有化部署）：`LSMOM_LLM_ENDPOINT`、`LSMOM_LLM_MODEL`、`LSMOM_LLM_API_KEY`、
`LSMOM_LLM_TIMEOUT_SECONDS`。单次请求最多 5 次调用、6 万 token；日志只记录摘要与打码片段。

未配置端点时回放录制的响应（`ls-mom-ai/src/main/resources/lsmom-llm-recordings/`，按请求摘要命名），用于演示与确定性测试；
提示词只含字段、选项、已有规则与需求，不含表单编码、版本等每次不同的内容，同样的表单和需求得到同样的摘要。

接口：`POST /api/form/assist`（`lsmom:form:design`）。验证：`FormAssistServiceTest`、`tests/unit/assist-patch.spec.ts`、`tests/e2e/form-assist.e2e.mjs`。
