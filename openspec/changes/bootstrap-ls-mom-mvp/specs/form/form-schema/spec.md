# Spec Delta

## Purpose

定义 LS FormSchema 作为录入、列表、详情、打印、移动端视图的唯一产品契约，并由服务端负责校验与版本管理，使设计器、运行时、流程节点和 LLM 共用同一份表单语义。

## ADDED Requirements

### Requirement: FormSchema 结构
FormSchema SHALL 包含 `formCode`、`version`、`conceptCode`、`layout`、`fields`、`rules`、`actions`；字段 SHALL 绑定 Concept 字段编码并声明类型、必填、长度／范围、字典、引用 Concept 与过滤、默认值和栅格宽度。

#### Scenario: 字段未绑定 Concept
- **WHEN** 发布的表单字段 `key` 不存在于目标 Concept
- **THEN** 发布校验失败

### Requirement: 服务端按 Schema 校验
所有经表单提交的数据 SHALL 在服务端按记录冻结的表单版本重新执行类型、必填、范围、正则、字典、引用过滤和规则校验。

#### Scenario: 引用越过滤
- **WHEN** 提交的设备引用不属于 `$context.orgId` 过滤范围
- **THEN** 后端拒绝并指出字段 `asset`

### Requirement: 动作只绑定已发布 Operation
表单 `actions` 中每个按钮 SHALL 绑定已发布 Operation 编码；绑定不存在或未发布的 Operation 时 SHALL 拒绝发布。

#### Scenario: 绑定草稿 Operation
- **WHEN** 表单提交按钮绑定一个尚未发布的 Operation
- **THEN** 表单发布失败

### Requirement: 多形态视图
系统 SHALL 支持 ListSchema（列、筛选、默认排序、行／批量操作、导出）、DetailSchema（只读分组、关联列表、流程时间轴、附件）、PrintSchema（页眉页脚、签字区、模板版本）、MobileSchema（必要字段、拍照、离线草稿）和看板绑定（只绑定 Metric 与命名 Query）。

#### Scenario: 移动端精简
- **WHEN** 在移动端打开填报表单
- **THEN** 只显示 MobileSchema 中的字段，提交时仍按完整 FormSchema 服务端校验

#### Scenario: 看板写 SQL
- **WHEN** 看板绑定中出现原始 SQL
- **THEN** 发布校验失败

### Requirement: 表单版本不可变
每次发布 SHALL 生成不可变的表单版本；已发布版本只能派生新版本。业务记录 SHALL 保存提交时的表单版本，历史详情默认按该版本只读渲染。

#### Scenario: 发布新版本后打开旧单
- **WHEN** 表单发布 v4 后打开按 v3 提交的记录
- **THEN** 按 v3 布局只读显示

#### Scenario: 编辑旧单
- **WHEN** 用户编辑一条 v3 草稿
- **THEN** 系统按已配置的迁移策略提示切换到 v4 或保持 v3

### Requirement: 升级兼容性校验
表单发布 SHALL 同时校验与 Concept、Flow 节点策略、Operation 输入、规则和打印模板的兼容性；字段改名 SHALL 保留原语义编码。

#### Scenario: 节点策略引用被删字段
- **WHEN** 新表单版本移除了某流程节点 `required` 中的字段
- **THEN** 发布校验失败并列出该流程节点

### Requirement: 节点字段策略合并
流程节点的字段策略 SHALL 按"显式 editable > readonly 通配 > 表单默认"的优先级计算可见、可编辑、必填；前端渲染与服务端校验 SHALL 使用同一算法。

#### Scenario: readonly 通配与显式 editable
- **WHEN** 节点策略为 `readonly:["*"]`、`editable:["confirmComment"]`
- **THEN** 仅 `confirmComment` 可编辑，其他字段只读，服务端拒绝修改其他字段
