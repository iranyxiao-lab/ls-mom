# 录制的 LLM 响应

未配置 LLM 端点（`LSMOM_LLM_ENDPOINT`）时，设计助手按请求摘要（模型＋消息的 sha256）回放这里的响应，用于演示与确定性测试。
文件名为请求摘要；内容为 OpenAI 兼容 Chat Completions 响应。

| 摘要 | 场景 |
|---|---|
| `10f0cf89…b683` | 设备缺陷新表单（按 ls.asset.Defect 字段生成的初稿）：“严重度为高或严重时，必须填写现象” → 条件必填规则草稿 |

新增录制：配置真实端点并设 `lsmom.llm.recordings=<目录>`、`lsmom.llm.record=true`，执行一次后把生成的文件复制到这里。
