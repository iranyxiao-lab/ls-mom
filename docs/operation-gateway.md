# Operation Gateway

所有受控写入（提交、审核、退回、关闭、确认、分派……）和对外能力都通过**命名 Operation** 完成。人、流程回调和 Agent 调用的是同一份实现、同一套约束，每次执行都有权限、守卫、幂等、审计和 `trace_id`。

## 调用

```
POST /api/op/{operationCode}
Idempotency-Key: <可选，24 小时内同键重复请求返回首次结果>

{
  "input":    { ... 按该 Operation 的输入 Schema ... },
  "targetId": "123",     // 修改已有对象时必填
  "version":  4,         // 对象当前版本（乐观并发），迁移类 Operation 必填
  "simulate": false      // true：完整执行后回滚，用于预演（Agent SIMULATE 等级）
}
```

响应沿用 RuoYi 格式（HTTP 200）。`code=200` 时 `data` 为 `OperationResult`：`targetId`、`version`、`status`、`data`、`warnings`（如资源冲突提示）、`events`、`replayed`、`simulated`。失败时 `code` 为语义状态码，`data.errorCode` 为稳定编码，`data.details` 为结构化细节：

| code | errorCode 示例 | 说明 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED`、`TARGET_REQUIRED` | `details.fields` 为 字段 → 原因 |
| 403 | `ACCESS_DENIED` | 缺少功能权限或目标不在数据域内 |
| 404 | `OPERATION_NOT_FOUND`、`NOT_FOUND` | 未注册的 Operation 不可调用 |
| 409 | `VERSION_CONFLICT`、`IDEMPOTENCY_CONFLICT` | `details.currentVersion` 为最新版本 |
| 422 | `INVALID_TRANSITION`、`GUARD_FAILED`、业务规则编码 | 非法迁移时 `details.allowedOperations` 列出当前允许的动作 |

辅助接口：

- `GET /api/op/allowed?concept=ls.core.Work&id=123`：对象当前状态、版本与可执行动作（含是否有权限、输入 Schema），界面据此只显示可用按钮。
- `GET /api/op/catalog`：当前用户可调用的 Operation 列表。
- `GET /api/timeline/{concept}/{id}`：业务时间轴（状态迁移、填报、附件、整改，按时间排序并标注操作人）。
- `GET /v3/api-docs/5.LS-MOM`：OpenAPI 3.1，每个已发布 Operation 一条 `POST /api/op/<code>`，`input` 即注册的输入 Schema。

## 执行管道

一次调用在**一个事务**内依次执行，任一步失败整体回滚（业务数据、事件、Outbox 都不留下），失败审计在独立事务中写入：

1. 查找 Operation（未注册或未发布 → 404）
2. 幂等：同键同输入返回首次结果（`REPLAYED`）；同键不同输入 → 409；`idempotency=REQUIRED` 必须带键
3. 功能权限（Sa-Token 菜单权限，超级管理员放行）
4. 加载目标并校验数据域
5. 输入 JSON Schema（2020-12）校验
6. 字段策略（表单节点的只读/隐藏/必填，由表单模块实现 `FieldPolicyCheck`）
7. 状态检查（迁移类）与版本校验
8. 守卫链：Operation 声明的守卫＋迁移声明的守卫；WARN 只提示，BLOCK 拒绝
9. 写入：迁移类由状态机执行（效果 → 写状态与版本）；处理器类调用 Java 实现
10. 领域事件＋Outbox（同事务）、成功审计、幂等记录

## 契约

| 字段 | 说明 |
| --- | --- |
| `code` | 全局唯一，形如 `<Concept 编码>.<动作>`，发布后不可修改或复用 |
| `concept` | 目标 Concept，必须已注册 |
| `kind` | `HANDLER`（Java 处理器）、`TRANSITION`（状态机迁移，由元数据生成）、`QUERY` |
| `risk` | 写入风险分级，见下 |
| `exposure` | Agent 暴露等级：NONE / QUERY / DRAFT / SIMULATE / APPROVAL_REQUIRED / DENY |
| `permission` | 所需功能权限，为空表示登录即可 |
| `inputSchema` | 输入 JSON Schema |
| `guards` | 守卫编码列表 |
| `idempotency` | NONE / OPTIONAL / REQUIRED |
| `requiresVersion` | 修改已有对象时要求携带版本号 |

### 写入风险分级

| 级别 | 含义 | 约束 |
| --- | --- | --- |
| C0 | 低风险台账 | 可走通用 CRUD |
| C1 | 受控业务 | 必须经 Operation＋状态机＋守卫；状态字段禁止通用更新 |
| C2 | 高风险 | 必须经专用处理器＋人工审批＋完整审计；禁止任何通用写入 |

Concept 的风险取其已发布 Operation 的最高级别。通用写入前调用 `GenericWritePolicy.check(concept, fields)`；核心显式表只能经 Operation 写入。

## 状态机

业务状态只在业务表。状态机定义存于 `meta_flow`（`kind=STATE_MACHINE`），发布时每个迁移自动注册为 `TRANSITION` 类 Operation，无需写 Java：

```json
{ "operation": "ls.core.Work.block", "name": "阻塞", "from": ["IN_PROGRESS"], "to": "BLOCKED",
  "permission": "lsmom:work:execute", "risk": "C1", "guards": [],
  "inputSchema": {"type":"object","required":["reason"],"properties":{"reason":{"type":"string","minLength":1}}} }
```

迁移需要附带写入（如分派写分派记录、阻塞写原因）时，实现一个同编码的 `TransitionEffect` Bean。核心状态机见 `CoreStateMachines`。

## 新增一个 Operation

以「调整工作优先级」（`ls.core.Work.reprioritize`，代码见 `ReprioritizeWork`）为例：

1. **确认 Concept 已注册**：`ls.core.Work` 由核心自注册；Pack 对象由 Pack 安装注册。
2. **写处理器**：实现 `OperationHandler`，加 `@LsOperation` 声明编码、Concept、名称、风险、权限、输入 Schema 等；注册为 Spring Bean（`@Component`）。修改已有对象时设 `requiresVersion = true`，用 `ctx.targetVersion()` 做乐观锁写入，写完调用 `ctx.target(...)` 登记结果版本。
3. **事件内容**：需要进时间轴的信息放进 `ctx.eventPayload()`；需要通知其他对象时调用 `ctx.emit(...)`。主事件（类型即 Operation 编码）由网关自动写入。
4. **权限**：在菜单中为所需权限（如 `lsmom:work:edit`）建按钮权限并授予角色。
5. **测试**：经 `OperationGateway.execute` 调用，覆盖成功、Schema 校验失败、版本冲突（参考 `ExampleOperationTest`）。
6. **启动对账**：首次启动时契约写入 `meta_operation`。之后修改注解中的契约（风险、权限、Schema……）必须同时提高 `version`，否则启动失败并列出差异；删除处理器前先把元数据置为 DEPRECATED。

## 守卫

实现 `Guard`（`code()` + `check(ctx)`）并注册为 Bean，返回 `GuardResult.pass()`、`warn(...)` 或 `block(code, message, details)`。CEL 规则、决策表等由规则模块以 `GuardProvider` 按编码前缀解析。核心守卫见 `CoreGuards`：

- `ls.core.Issue.evidenceRequired`：高、严重级别的 Issue 提交前必须有证据附件
- `ls.core.Issue.rectificationDone`：整改工作全部完成才能提交验证
- `ls.core.Issue.verifierSeparation`：Profile 要求分离时验证人不得是唯一整改执行人

## 事件、Outbox 与外部写入

- 每个成功的 Operation 产生 `DomainEvent`（`event_id`、`type`、`version`、`occurred_at`、actor、subject、`org_unit_id`、`trace_id`、`idempotency_key`），与业务同事务写入 `core_domain_event` 和 `core_outbox`。
- `OutboxWorker` 以 `FOR UPDATE SKIP LOCKED` 领取，按指数退避重试，超过订阅方上限进入补偿列表（`GET /api/compensation/outbox`），可手工重投。消费方以幂等键去重（`ConsumerReceipts`）。
- 对 ERP/OA 的过账只由 Operation 产生的事件发起：入队即登记"待同步"（`op_external_sync`），外部 2xx 才标记已同步，失败重试、最终进入补偿列表（`GET /api/compensation/external`）；业务记录本身保持已提交。配置 `lsmom.external.erp.url` 启用 ERP 过账。

## 审计

`op_audit` 追加写（数据库触发器禁止修改和删除），记录操作人、代理主体（Agent 代表谁执行）、Operation、输入摘要（敏感字段打码）与摘要哈希、结果（SUCCESS / FAILED / REPLAYED / SIMULATED）、错误编码与原因、目标对象、数据版本、耗时和 `trace_id`。

## 迁移版本号分段

各模块的 Flyway 脚本共用 `db/migration/core` 位置与历史表，按版本段区分：V1–V19 core、V20–V39 meta、V40–V59 action、V60–V79 crud、V80–V99 form、V100–V119 flow、V120 起其他模块。
