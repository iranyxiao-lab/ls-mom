# Spec Delta

## Purpose

以命名 Operation 作为所有受控写入与对外能力的唯一入口，统一输入输出契约、权限、守卫、幂等、审计和 Agent 暴露，保证人和 Agent 调用同一份实现与同一套约束。

## ADDED Requirements

### Requirement: Operation 契约
每个 Operation SHALL 注册编码、目标 Concept、输入／输出 Schema、所需权限、守卫引用、风险级别（C0／C1／C2）、幂等策略、审计级别和 Agent 暴露等级；未注册的动作 SHALL NOT 可被调用。

#### Scenario: 调用未注册 Operation
- **WHEN** 客户端请求 `/api/op/ls.core.Work.unknown`
- **THEN** 返回"Operation 不存在"错误

### Requirement: 统一执行入口
Operation SHALL 通过 `/api/op/{operationCode}` 执行，并依次进行认证、功能权限、数据域、输入 Schema 校验、字段策略校验、守卫检查、状态机迁移、业务写入与事件写入；任一步失败 SHALL 整体回滚。

#### Scenario: 守卫失败回滚
- **WHEN** 提交 Issue 时守卫判定缺少必需证据
- **THEN** 返回守卫错误，业务数据、事件与 Outbox 均无变化

### Requirement: 写入风险分级
写入 SHALL 分为 C0（低风险台账，可走通用 CRUD）、C1（受控业务，必须经 Operation＋状态机＋守卫）、C2（高风险，必须经专用处理器＋人工审批＋完整审计）；C1／C2 对象的状态字段 SHALL NOT 通过通用更新修改。

#### Scenario: 通用更新改状态
- **WHEN** 通过通用 CRUD 更新接口把 WorkItem 的 `status` 改为 DONE
- **THEN** 请求被拒绝，提示应使用对应 Operation

### Requirement: 幂等
带幂等键的 Operation 重复调用 SHALL 返回首次结果而不重复执行副作用；幂等键在约定窗口内有效。

#### Scenario: 重复提交
- **WHEN** 网络重试导致同一幂等键的"提交"请求到达两次
- **THEN** 只产生一次状态迁移与一个事件，第二次返回相同结果

### Requirement: 乐观并发
修改型 Operation SHALL 携带对象版本号；版本不一致时 SHALL 拒绝并返回最新版本。

#### Scenario: 并发编辑
- **WHEN** 两人基于同一版本先后提交修改
- **THEN** 后到的请求返回版本冲突

### Requirement: 审计
每次 Operation 执行（成功或失败）SHALL 记录操作人、代理主体、Operation 编码、输入摘要、结果、目标对象、数据版本、耗时和 `trace_id`。

#### Scenario: 失败也审计
- **WHEN** 用户因权限不足调用"关闭 Issue"失败
- **THEN** 审计中存在一条失败记录及拒绝原因

### Requirement: 外部写入经 Operation
对外部系统（ERP／OA 等）的过账或通知 SHALL 只通过命名 Operation 发起；外部成功前业务 SHALL 只记录"待同步"，失败进入重试或人工补偿。

#### Scenario: 外部过账失败
- **WHEN** 外部 ERP 返回失败
- **THEN** 业务记录同步状态为失败，出现在补偿列表，填报本身保持已提交

### Requirement: 开放接口描述
系统 SHALL 为已发布 Operation 与命名 Query 自动生成 OpenAPI 3.1 描述，并与运行时契约一致。

#### Scenario: 文档一致性
- **WHEN** 发布新 Operation
- **THEN** OpenAPI 文档中出现该 Operation，其输入 Schema 与注册定义一致
