# Spec Delta

## Purpose

为异常、问题、缺陷和整改提供统一的闭环外壳，使生产、设备、质量和 DCS 报警产生的问题都能分派、整改、验证并关闭。

## ADDED Requirements

### Requirement: Issue 闭环状态
Issue SHALL 经历 DRAFT→OPEN→IN_RECTIFICATION→PENDING_VERIFY→CLOSED，并支持 REJECTED 与 CANCELLED；关闭 SHALL 需要验证人确认且验证人不得是唯一整改执行人（当 Profile 要求分离时）。

#### Scenario: 正常关闭
- **WHEN** 整改工作完成后验证人确认通过
- **THEN** Issue 状态变为 CLOSED，时间轴记录验证人与验证结论

#### Scenario: 验证不通过
- **WHEN** 验证人判定整改无效
- **THEN** Issue 回到 IN_RECTIFICATION 并通知整改负责人

### Requirement: 多对象关联
Issue SHALL 可关联零个或多个 WorkItem、设备、质量记录、产出、物料记录、观察或外部对象，任一关联都不是必须的。

#### Scenario: 独立异常
- **WHEN** 现场人员登记一条不关联任何对象的异常
- **THEN** 登记成功并可正常分派处理

### Requirement: 整改生成工作
系统 SHALL 支持从 Issue 生成一个或多个整改 WorkItem，并保持双向可追溯；所有整改工作完成前 Issue SHALL NOT 进入 PENDING_VERIFY。

#### Scenario: 整改未完成
- **WHEN** Issue 有两条整改工作，仅一条完成时请求提交验证
- **THEN** 系统拒绝并列出未完成的整改工作

### Requirement: 专业细节外置
Issue 核心结构 SHALL 只包含通用字段（类型、严重度、来源、描述、责任人、期限、证据）；缺陷、不符合项等专业字段 SHALL 存于对应 Pack 的扩展对象。

#### Scenario: 不符合项
- **WHEN** 质量 Pack 创建不符合项
- **THEN** 同时生成一条 Issue 外壳与一条质量扩展记录，两者一一关联

### Requirement: 来源去重
来源为自动事件（如 DCS 报警）的 Issue SHALL 以来源去重键保证同一来源事件只产生一条 Issue。

#### Scenario: 重复报警
- **WHEN** 同一去重键的报警再次到达
- **THEN** 不新建 Issue，原 Issue 的发生次数与最近发生时间更新
