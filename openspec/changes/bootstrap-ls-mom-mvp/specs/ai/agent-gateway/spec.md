# Spec Delta

## Purpose

为运行期 Agent 提供受控的能力门面：只能调用白名单 Operation，按暴露等级与有效权限执行，默认产出草稿，高风险动作永不暴露，并提供带引用的摘要与知识问答。

## ADDED Requirements

### Requirement: 仅经 Operation 白名单
Agent SHALL 只能调用暴露等级为 QUERY、DRAFT、SIMULATE 或 APPROVAL_REQUIRED 的 Operation；SHALL NOT 访问通用 CRUD、数据访问层、流程引擎接口或数据库。

#### Scenario: 调用 DENY 能力
- **WHEN** Agent 请求调用暴露等级为 DENY 的审批 Operation
- **THEN** 请求被拒绝并审计

### Requirement: 有效权限不放大
Agent 有效能力 SHALL 等于真人调用者权限 ∩ Agent 服务身份权限 ∩ Operation 白名单 ∩ 当前数据域，再减去审批／控制／监管黑名单。

#### Scenario: AI-05 越权读取
- **WHEN** 无 B 车间权限的用户经 Agent 查询 B 车间工作
- **THEN** 返回无权限并记录

### Requirement: OBO 双主体
Agent 代用户行动 SHALL 同时记录真人主体与 Agent 主体、`trace_id`，批量草稿记录 `batch_id`。

#### Scenario: 审计主体
- **WHEN** Agent 为用户创建缺陷草稿
- **THEN** 审计记录同时包含用户与 Agent 身份

### Requirement: 默认草稿
Agent 创建的工作、缺陷、填报和整改 SHALL 为可识别的草稿状态，由真人提交。

#### Scenario: AI-06 验收
- **WHEN** Agent 根据交接班内容创建两条整改草稿
- **THEN** 两条记录状态为 DRAFT 且标注来源为 Agent

### Requirement: 模拟不产生副作用
SIMULATE 级调用 SHALL 执行完整校验与守卫并返回结果，但 SHALL NOT 写入业务数据、事件或 Outbox。

#### Scenario: 关闭前检查
- **WHEN** Agent 模拟关闭一条 Issue
- **THEN** 返回"缺少验证证据"，数据库无任何变化

### Requirement: 审批绑定输入
APPROVAL_REQUIRED 级调用 SHALL 绑定 Operation 编码、输入摘要、业务版本、批准人、有效期和一次性 ID；输入或业务版本变化、过期或重复使用时 SHALL 拒绝。

#### Scenario: AI-07 输入变化
- **WHEN** 批准后 Agent 以不同参数执行
- **THEN** 执行被拒绝，需要重新批准

#### Scenario: AI-08 重复执行
- **WHEN** 同一一次性 ID 被第二次使用
- **THEN** 不产生第二条正式记录

### Requirement: 永不暴露的高风险行动
代审批、双签、质量终判、通用删除、任意方法调用和 DCS 写入 SHALL 永不暴露给 Agent。

#### Scenario: AI-09 验收
- **WHEN** 列出 Agent 可用工具
- **THEN** 不包含上述任何一类能力

### Requirement: MCP 适配
系统 SHALL 通过独立 MCP 适配将白名单 Operation 发布为工具，工具清单与 Operation 注册自动一致。

#### Scenario: 工具同步
- **WHEN** 一个 Operation 的暴露等级由 DRAFT 改为 DENY 并发布
- **THEN** MCP 工具清单中该工具消失

### Requirement: 摘要人工确认
交接班、异常、咨询摘要 SHALL 由 Agent 生成草稿，经人工确认后才发布；摘要 SHALL NOT 回写为原始事实。

#### Scenario: 交接班摘要
- **WHEN** Agent 生成交接班摘要
- **THEN** 摘要处于待确认状态，并带指向工作与异常的引用

### Requirement: 带引用的知识问答
知识问答 SHALL 只检索调用者有权查看的、已发布版本的文档，权限过滤在检索前与返回前都执行；回答 SHALL 带引用；业务数字 SHALL 来自指标或命名查询并可追溯。

#### Scenario: AI-10 验收
- **WHEN** 用户询问"本周 A 线异常数"
- **THEN** 回答给出数值、所用指标编码和可钻取的记录引用

#### Scenario: 未发布文档
- **WHEN** 知识库中某 SOP 存在未发布的修订稿
- **THEN** 问答只引用已发布版本
