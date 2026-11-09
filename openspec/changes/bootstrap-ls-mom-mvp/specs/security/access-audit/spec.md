# Spec Delta

## Purpose

统一身份类型、功能／数据／字段／流程／附件权限、机器身份与 API Key、审计覆盖以及数据库＋文件＋定义的联合备份恢复，保证所有访问可控、可追溯、可恢复。

## ADDED Requirements

### Requirement: 多层权限
系统 SHALL 依次在功能（菜单与 Operation 权限）、数据域（组织／车间／班组等）、字段（表单字段策略）、流程任务（办理人／候选人）和附件（密级与数据域）层面校验访问。

#### Scenario: 有菜单无数据域
- **WHEN** 用户有台账菜单权限但无目标车间数据域
- **THEN** 列表为空，直接访问记录被拒绝

### Requirement: 机器身份与 API Key
Agent 与外部系统 SHALL 使用独立服务身份和最小 Scope；API Key SHALL 只存哈希，支持过期、轮换、限流和吊销。

#### Scenario: 吊销 Key
- **WHEN** 管理员吊销某外部系统的 API Key
- **THEN** 该 Key 的后续请求立即被拒绝

#### Scenario: 限流
- **WHEN** 某 Key 的请求超过配置速率
- **THEN** 超出部分返回限流错误

### Requirement: 真人专属动作
审批、双签和最终确认 SHALL 只能由真人身份执行。

#### Scenario: 服务身份审批
- **WHEN** 服务身份调用任务审批
- **THEN** 请求被拒绝

### Requirement: 审计覆盖
审计 SHALL 覆盖登录、权限与配置变化、Pack／Form／Flow／Operation／Rule 发布、业务 Operation 与流程办理、状态变化、数据导出与附件访问、Agent 调用（模型、输入摘要、工具、结果、批准）、DCS 原始引用与人工确认、数据迁移与修复；审计记录 SHALL 不可被普通管理员修改或删除。

#### Scenario: 导出审计
- **WHEN** 用户导出 5000 条质量记录
- **THEN** 审计记录导出人、条件、条数与时间

### Requirement: 单客户单库隔离
每个客户部署 SHALL 使用独立数据库；平台 SHALL NOT 依赖共享库多租户来隔离客户数据。

#### Scenario: 新客户部署
- **WHEN** 为新客户部署实例
- **THEN** 使用独立数据库与对象存储桶

### Requirement: 联合备份与恢复
备份 SHALL 同时覆盖数据库（含流程引擎 schema）、对象存储文件和 Pack／流程定义版本，并能联合恢复到一致时间点。

#### Scenario: 恢复演练
- **WHEN** 将最近备份恢复到全新环境
- **THEN** 业务记录、附件、在途流程与 Pack 版本一致可用，在途流程可继续办理
