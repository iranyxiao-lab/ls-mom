# Spec Delta

## Purpose

以 Pack 和 ChangeSet 管理所有标准扩展与客户扩展的安装、依赖、迁移、启停、升级、回滚与复审，使客户定制不修改核心、跨版本升级不重做数据与在途流程。

## ADDED Requirements

### Requirement: Pack Manifest
每个 Pack SHALL 声明编码、版本、依赖（含版本范围）、包含的 Concept／View／Operation／Flow／Rule／Metric、迁移、种子数据、测试、业务负责人、需求提出人和复审日期。

#### Scenario: 缺少负责人
- **WHEN** 导入一个未填写业务负责人的 Pack
- **THEN** 校验失败

### Requirement: 依赖顺序安装与无环
安装与升级 SHALL 按依赖拓扑顺序执行；存在循环依赖或依赖缺失时 SHALL 拒绝安装。

#### Scenario: 循环依赖
- **WHEN** Pack A 依赖 B，B 又依赖 A
- **THEN** 安装被拒绝并给出循环路径

### Requirement: 发布链路
任何定义变更 SHALL 打包为 ChangeSet（定义差异、DDL 迁移、权限点、流程定义、种子数据、测试、回滚说明），经校验与依赖分析、沙箱安装、回归／越权／升级测试、人工批准后才发布到正式环境。

#### Scenario: 未批准发布
- **WHEN** 测试通过但未经批准的 ChangeSet 请求发布到生产
- **THEN** 发布被拒绝

### Requirement: 启用、停用与数据保留
Pack SHALL 可独立启用与停用；停用后其菜单、Operation 与 Agent 工具不可用，但数据保留并可只读导出；被其他已启用 Pack 依赖时 SHALL NOT 停用。

#### Scenario: 停用培训 Pack
- **WHEN** 停用培训考试 Pack
- **THEN** 其页面与 Operation 不可访问，历史培训记录仍保存在数据库

### Requirement: 升级保留客户扩展
标准 Pack 升级后，客户 Pack 中的字段、视图、数据和验收测试 SHALL 保持有效；破坏性迁移 SHALL 需要独立审批、升级前备份和前向修复脚本。

#### Scenario: 标准 Pack 升级
- **WHEN** 设备 Pack 从 1.0 升级到 1.1，而客户 Pack 为设备增加了两个字段
- **THEN** 升级后客户字段与数据仍在，客户 Pack 验收测试通过

### Requirement: 配置漂移检测与回灌
系统 SHALL 比较运行环境中的定义与 Pack／ChangeSet 记录，发现漂移时列出差异，并支持将差异回灌为新的 ChangeSet。

#### Scenario: 环境被手工修改
- **WHEN** 有人在测试环境直接修改了某表单标签
- **THEN** 漂移报告列出该差异，并可一键生成包含该差异的 ChangeSet

### Requirement: 复审与下线
系统 SHALL 统计每个 Pack 的使用量、最近使用时间和失败任务；到达复审日期或长期未使用的 Pack SHALL 进入待复审／建议下线状态。

#### Scenario: 超期未用
- **WHEN** 某客户 Pack 超过复审日期且 90 天无使用
- **THEN** 协同应用中心将其标记为建议下线并通知负责人

### Requirement: 升级演练
每次发布前 SHALL 能从上一版本备份恢复到临时环境，并成功执行迁移与 Pack 升级。

#### Scenario: 升级演练失败
- **WHEN** 演练环境中某迁移失败
- **THEN** 发布门禁阻止发布并输出失败迁移
