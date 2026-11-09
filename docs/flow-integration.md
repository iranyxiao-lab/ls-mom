# 流程集成

LS-MOM 用嵌入式 Flowable 7 跑审批流，但**流程只负责"谁在什么时候办"，业务状态只存在业务表里**。本文说明业务如何接入流程、回调约定、待办读模型与对账。

## 单一业务状态原则

- 业务单据的状态（`DRAFT`、`SUBMITTED`、`APPROVED`……）只由业务 Operation 经状态机修改，流程引擎从不直接写业务表。
- 流程变量只保留最小集合：`flowInstanceId`、`bizType`、`bizId`、`initiator`、`outcome`、`lastTaskId`，以及引擎内部的 `cand_*`（候选人）、`route_*`（条件路由）、`exc_*`（无人可办）。不要把业务字段放进流程变量。
- 条件分支的条件是受限 CEL，运行时从业务表读取 `$model` 计算，不读流程变量里的业务数据。
- 流程结束、退回、撤回等节点通过**命名 Operation 回调**驱动业务状态迁移，与人在界面上点按钮走同一个网关（权限、守卫、字段策略、审计全部生效）。

## 引擎隔离与安全

| 项 | 做法 |
| --- | --- |
| Schema | 引擎表全部在 `flowable` schema；启动时用独立连接建表，运行时用 `flowable.` 表前缀，与业务共用数据源和事务 |
| 脚本 | 不允许 scriptTask、`class`/`delegateExpression`、类型化服务任务、外部 URL、`skipExpression` |
| 表达式 | `RestrictedExpressionManager` 只接受模板生成的白名单表达式，表达式上下文不暴露任何 Spring Bean |
| BPMN 来源 | 只由 `BpmnTemplateGenerator` 从流程配置生成，接口不接受 BPMN XML；部署前 `BpmnSafetyScanner` 再扫描一次（`FLOW_UNSAFE`） |
| 关闭的引擎 | IDM、EventRegistry |

## 流程配置

流程配置是 JSON（`meta_flow`），发布时生成 BPMN 并部署。示例：

```json lsflow
{
  "code": "acme.tool.loanApproval",
  "name": "借还单审批",
  "concept": "acme.tool.LoanOrder",
  "startOn": "acme.tool.LoanOrder.submit",
  "form": { "code": "acme.tool.loanOrderForm", "version": 1 },
  "callbacks": {
    "approved": "acme.tool.LoanOrder.archive",
    "rejected": "acme.tool.LoanOrder.reject",
    "returned": "acme.tool.LoanOrder.return",
    "resubmitted": "acme.tool.LoanOrder.resubmit",
    "withdrawn": "acme.tool.LoanOrder.withdraw"
  },
  "nodes": [
    { "key": "deptAudit", "type": "AUDIT", "name": "部门审核",
      "assignee": { "strategy": "DEPT_LEADER", "of": "INITIATOR" },
      "operation": "acme.tool.LoanOrder.audit", "timeout": { "hours": 24, "action": "REMIND_ESCALATE" } },
    { "key": "amount", "type": "CONDITION", "name": "金额判断", "branches": [
      { "name": "大额", "when": "$model.amount > 10000", "nodes": [
        { "key": "finance", "type": "AUDIT", "name": "财务会签", "mode": "AND",
          "assignee": { "strategy": "ROLE", "value": "finance" }, "operation": "acme.tool.LoanOrder.audit" } ] },
      { "name": "其他", "nodes": [] } ] },
    { "key": "confirm", "type": "CONFIRM", "name": "确认归档", "assignee": { "strategy": "INITIATOR" } }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `startOn` | 哪个 Operation 成功后自动发起流程（在同一事务里登记实例，提交后启动） |
| `callbacks.approved` / `rejected` | 必填；流程通过/驳回时执行 |
| `callbacks.returned` / `resubmitted` / `withdrawn` | 退回到发起人、发起人再提交、撤回时执行 |
| 节点 `type` | `FILL` 填报、`AUDIT` 审核、`CONFIRM` 确认、`CC` 抄送、`SERVICE` 服务（只能选已发布 Operation）、`CONDITION` 条件（分支 `when` 为受限 CEL，没有 `when` 的分支为默认分支）、`PARALLEL` 并行 |
| 节点 `mode` | `SINGLE` 单人/候选人抢办、`OR` 或签（一人通过即可）、`AND` 会签（全部通过，一人驳回即驳回） |
| 节点 `operation` | 审核/填报节点提交时执行的 Operation；节点字段策略（`fieldPolicy` 或表单的 `nodePolicies`）在服务端强制 |
| 节点 `timeout` | 小时数与动作 `REMIND`、`ESCALATE`、`REMIND_ESCALATE` |

办理人策略：`USER`、`ROLE`（可 `of: BIZ_ORG` 限定业务组织）、`DEPT`、`TEAM`、`FIELD`（取单据字段）、`INITIATOR`、`ORG_LEADER`、`DEPT_LEADER`、`WORKSHOP_LEADER`、`SUPERIOR_LEADER`、`DATA_SCOPE`。解析时过滤停用、离职与缺少节点权限的人；解析为空时生成**异常待办**（`kind = EXCEPTION`）给流程管理员角色，由管理员转办。

发布校验（`/api/flow/definitions/validate`）的问题码：`CALLBACK_REQUIRED`、`ASSIGNEE_UNRESOLVED`、`POLICY_FIELD_INVALID`、`OPERATION_NOT_PUBLISHED`、`NODE_OPERATION_REQUIRED`、`FLOW_UNSAFE` 等。

## 回调约定

1. 回调幂等键为 `procInstId:taskId:event`（`event` ∈ `approved`、`rejected`、`returned`、`resubmitted`、`withdrawn`），写入 `biz_flow_callback_log`；重复回调只迁移一次（FLOW-07）。
2. 回调与引擎推进在同一事务里**登记**，事务提交后在新事务中**执行**。失败置 `PENDING_RETRY`，指数退避重试，8 次后置 `FAILED`，可在 `/api/flow/callbacks` 查看并 `requeue`。
3. 回调以 `SYSTEM` 身份执行，`onBehalfOf` 记录最后办理人；当 Operation 的输入声明了 `conclusion`、`reason` 或 `comment` 时，自动填入最后一次办理意见。
4. 发起同理：`startOn` 成功后实例状态 `PENDING_START`，提交后启动，失败置 `PENDING_RETRY`，由定时作业每分钟重试。
5. 撤回由业务 Operation（`callbacks.withdrawn`）先判定能否撤回，成功后再终止流程。

受控审批模式下，流程通过时写入**批准证据**（`biz_approval_evidence`：批准人、时间、意见摘要、数据版本、实例）。守卫 `ls.flow.approvalValid` 在后续 Operation 上校验证据仍有效——批准后数据被修改需重新审批。守卫 `ls.flow.notHandled` 要求单据当前没有在途流程。

## 待办读模型

`TaskSync` 是 Flowable 全局事件监听器，与引擎在同一事务里同步 `biz_task`（`kind`：`TASK`、`EXCEPTION`、`CC`、`ESCALATION`）。列表、待办数、催办都只查 `biz_task`，不查引擎表。

办理接口（`/api/flow/tasks/{id}/complete|return|transfer|urge`）再次校验调用者是该待办的办理人或候选人；节点 Operation 另经网关校验功能权限与字段策略（FLOW-05）。催办同一待办 30 分钟内只能一次。

## 时间线事件

流程把以下事件写入业务时间线（`EventPublisher`，按 `bizType` + `bizId` 归档）：

| 事件 | 时机 |
| --- | --- |
| `ls.flow.started` | 流程实例启动 |
| `ls.flow.task.approved` | 节点通过 |
| `ls.flow.task.rejected` | 节点驳回 |
| `ls.flow.task.resubmitted` | 发起人被退回后再提交 |
| `ls.flow.task.returned` | 退回到发起人或指定节点 |
| `ls.flow.task.transferred` | 转办 |
| `ls.flow.task.urged` | 催办 |
| `ls.flow.task.timeout` | 节点超时提醒或升级 |
| `ls.flow.cc` | 抄送 |
| `ls.flow.withdrawn` | 发起人撤回 |
| `ls.flow.approved` | 流程通过结束 |
| `ls.flow.rejected` | 流程驳回结束 |

## 版本冻结

发布生成新版本并部署新的流程定义；在途实例保持启动时的流程版本与表单版本（FLOW-06）。`biz_task.form_version` 记录办理时使用的表单版本。

## 对账

`FlowJobs` 每 10 分钟执行 `FlowReconciler.check()`（只读，差异写日志）；管理员经接口修复：

| 差异 | 含义 | 修复 |
| --- | --- | --- |
| `TASK_MISSING` | 引擎有活动任务，`biz_task` 缺行 | 按引擎任务重建 |
| `TASK_STALE` | `biz_task` 为待办，引擎任务已不存在 | 关闭读模型行 |
| `INSTANCE_ORPHAN` | 实例记录为运行中，引擎实例已不存在 | 按历史结果收尾 |
| `START_PENDING` | 实例未成功启动 | 重新启动 |
| `CALLBACK_PENDING` | 回调待重试或失败 | 重新执行回调 |

## 接口

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/api/flow/definitions`、`/definitions/{code}/history`、`/definitions/{code}/{version}` | `lsmom:flow:design` |
| POST | `/api/flow/definitions/validate`、`/preview`、`/drafts` | `lsmom:flow:design` |
| POST | `/api/flow/definitions/publish` | `lsmom:flow:publish` |
| GET | `/api/flow/todo`、`/notices`、`/tasks/{taskId}`、`/biz/{concept}/{id}` | 登录 |
| POST | `/api/flow/tasks/{taskId}/complete`（`action`：`APPROVE`、`REJECT`、`RESUBMIT`）、`/return`、`/transfer`、`/urge` | 办理人 |
| POST | `/api/flow/instances/{id}/withdraw`、`/cc`，`/api/flow/cc/{id}/read` | 发起人/抄送人 |
| GET/POST | `/api/flow/reconcile`、`/reconcile/repair`、`/callbacks`、`/callbacks/{id}/requeue` | `lsmom:flow:admin` |

配置项：`lsmom.flow.async-executor`（默认 `true`，定时器需要）、`lsmom.flow.jobs`（默认 `true`）、`lsmom.flow.retry-interval-ms`、`lsmom.flow.reconcile-interval-ms`。

## 测试

| 测试 | 内容 |
| --- | --- |
| `FlowEngineTest` | 引擎表只在 `flowable` schema、模板生成与部署、安全扫描（FLOW-08） |
| `FlowLifecycleTest` | 提交→审核→确认归档闭环与时间线（FLOW-01）、最小变量、退回再提交 |
| `FlowCollaborationTest` | 撤回、转办、催办频率、抄送、或签/会签（FLOW-02）、条件与并行 |
| `FlowGovernanceTest` | 字段策略越权与非办理人（FLOW-05）、回调幂等与重试（FLOW-07）、离职异常待办、超时升级、版本冻结（FLOW-06）、批准证据、对账重建 |
| `QualityLoopFlowTest` | 质量问题登记→车间审核→整改→验证→关闭 |
| `FlowDocTest` | 本文的事件表与 `FlowRuntime.TIMELINE_EVENTS` 一致，示例配置可解析 |
