# Spec Delta

## Purpose

以统一注册的指标口径和命名查询服务看板、报表与 LLM 问数，保证同一指标只定义一次、受数据域约束，并可钻取到原始记录。

## ADDED Requirements

### Requirement: 指标口径注册
每个指标 SHALL 注册编码、显示名、计算口径、过滤条件、时间粒度、维度、数据域和权限；同一业务含义 SHALL NOT 存在两个指标定义。

#### Scenario: 重复口径
- **WHEN** 新建指标的口径与现有指标 `ls.prod.onTimeRate` 完全相同
- **THEN** 发布校验提示重复并要求复用

### Requirement: 命名查询
复杂查询 SHALL 由研发以命名 Query 发布，包含参数 Schema、数据域、超时和测试，通过 `/api/q/{queryCode}` 调用；客户配置 SHALL NOT 提交 SQL。

#### Scenario: 参数越界
- **WHEN** 调用命名 Query 时参数不符合参数 Schema
- **THEN** 返回参数校验错误

#### Scenario: 超时
- **WHEN** 查询执行超过声明的超时
- **THEN** 查询被取消并返回超时错误

### Requirement: 数据域生效
指标与命名 Query 的结果 SHALL 按调用者数据域过滤，人和 Agent 使用同一过滤逻辑。

#### Scenario: 班组长看指标
- **WHEN** 班组长查询按期完成率
- **THEN** 只基于本班组工作计算

### Requirement: 可钻取与可追溯
看板上的每个指标值 SHALL 可钻取到参与计算的记录列表，并能显示所用指标编码与口径版本。

#### Scenario: 钻取
- **WHEN** 点击看板上"本周异常数 12"
- **THEN** 打开包含这 12 条 Issue 的列表

### Requirement: 口径版本
指标口径变更 SHALL 生成新版本，历史看板截图或报告引用的版本可追溯。

#### Scenario: 口径升级
- **WHEN** 按期完成率口径从"计划结束日"改为"班次结束"
- **THEN** 新版本生效，旧版本定义仍可查询
