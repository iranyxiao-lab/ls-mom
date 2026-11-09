# Spec Delta

## Purpose

记录对象的事实观察、业务事件与附件关联，并以 Outbox 可靠投递到待办、通知、外部系统和读模型，同时为每个业务对象提供可审阅的时间轴。

## ADDED Requirements

### Requirement: 事实观察
Observation SHALL 记录"某时刻对某对象的观察"，包含对象引用、观察项、值、单位、观察时间、来源（人工／DCS／外部）和质量标记；SHALL NOT 存储高频原始波形。

#### Scenario: 人工读数
- **WHEN** 点检人员录入一次压力读数
- **THEN** 生成一条来源为人工的 Observation，并出现在设备时间轴

### Requirement: 领域事件结构
每个关键 Operation 成功后 SHALL 产生 DomainEvent，包含 `event_id`、`type`、`version`、`occurred_at`、`actor`、`subject`、`org_scope`、`trace_id`、`idempotency_key`。

#### Scenario: 事件完整性
- **WHEN** 一条 WorkItem 被分派
- **THEN** 产生类型为分派的事件，字段齐全且 `trace_id` 与请求一致

### Requirement: Outbox 同事务写入
业务数据与对应 Outbox 记录 SHALL 在同一事务提交；业务回滚时 Outbox SHALL NOT 留存记录。

#### Scenario: 业务失败
- **WHEN** Operation 在写入后因守卫失败回滚
- **THEN** 不存在对应的 Outbox 记录，也不发送任何通知

### Requirement: 至少一次投递与消费幂等
Outbox 投递 SHALL 至少一次并支持失败重试与退避，超过重试上限进入人工补偿列表；消费方 SHALL 以 `idempotency_key` 去重。

#### Scenario: 下游超时
- **WHEN** 外部通知服务连续超时
- **THEN** 记录按退避重试，达到上限后出现在失败任务列表并可手工重投

### Requirement: 附件关联
DocumentLink SHALL 将对象存储中的文件与任意业务对象关联，记录用途、密级和版本；附件下载 SHALL 经受控下载并校验数据域。

#### Scenario: 越权下载
- **WHEN** 无该对象数据域的用户请求附件下载链接
- **THEN** 系统拒绝并记录审计

### Requirement: 业务时间轴
系统 SHALL 为每个业务对象聚合展示填报、附件、评论、流程办理、状态迁移和 Operation 记录，按时间排序。

#### Scenario: 查看时间轴
- **WHEN** 打开一条质量不符合项详情
- **THEN** 时间轴依次展示登记、审核、整改工作、验证和关闭，并标注操作人

### Requirement: 不采用全量事件溯源
业务表 SHALL 保持当前事实；事件用于集成、审计和读模型，系统 SHALL NOT 依赖事件重放重建业务状态。

#### Scenario: 事件清理
- **WHEN** 已投递的历史 Outbox 记录按保留策略归档
- **THEN** 业务查询结果不受影响
