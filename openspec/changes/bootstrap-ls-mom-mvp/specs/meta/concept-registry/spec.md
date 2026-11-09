# Spec Delta

## Purpose

提供可版本化的工业语义注册：Concept、Relation、Mapping 及其字段，使表单、流程、权限、指标、LLM 和看板共用同一份稳定编码与语义。

## ADDED Requirements

### Requirement: Concept 注册
每个业务对象（核心、标准 Pack、客户扩展）SHALL 注册为 Concept，具有全局唯一编码（如 `ls.core.Work`）、命名空间、显示名、字段、关系、存储方式（核心显式表／受控物理表）和版本。

#### Scenario: 核心对象注册
- **WHEN** 系统启动
- **THEN** 可查询到 `ls.core.Work`、`ls.core.Issue` 等核心 Concept，其存储方式为核心显式表且不可经设计器修改结构

### Requirement: 编码稳定与不可复用
已发布的 Concept、字段、Operation、Metric 编码 SHALL NOT 被修改或复用；显示名称可调整且不影响历史语义。

#### Scenario: 修改显示名
- **WHEN** 将字段 `severity` 的显示名从"严重程度"改为"等级"
- **THEN** 编码不变，历史记录、表单、指标继续正常工作

#### Scenario: 复用已删除编码
- **WHEN** 尝试新建一个与已弃用字段同编码的字段
- **THEN** 发布校验失败

### Requirement: 字段弃用先于删除
删除字段 SHALL 先进入 DEPRECATED 状态；只有确认无已发布表单引用、无在途流程依赖后才允许经评审的迁移移除。

#### Scenario: 删除被引用字段
- **WHEN** 申请移除仍被在途流程表单引用的字段
- **THEN** 发布校验失败并列出引用方

### Requirement: 关系与映射
系统 SHALL 维护 Concept 间的命名关系（如 `assignedTo`、`observedOn`、`causedBy`）以及语义到物理表、字段、表单、流程、外部编码和 DCS 点位的映射，并支持从字段反查使用方。

#### Scenario: 影响分析
- **WHEN** 查询字段 `asset` 的使用方
- **THEN** 返回引用它的表单、流程节点策略、指标和 Operation 列表

### Requirement: 核心语义不含客户概念
核心命名空间 `ls.core` 下 SHALL NOT 出现培训、咨询、客户专属概念或字段。

#### Scenario: 核心污染检测
- **WHEN** 有变更向 `ls.core` 添加名为 `examScore` 的字段
- **THEN** 发布校验与架构测试均失败
