# Spec Delta

## Purpose

让大部分单表、主子表和简单协同台账只通过配置即可获得受控物理表、增删改查、查询、导入导出、权限、并发与时间轴，不写 Java，同时不让通用更新绕过业务动作。

## ADDED Requirements

### Requirement: 动态 Concept 生成受控物理表
发布动态 Concept SHALL 生成独立主表 `c_<namespace>_<concept>` 及必要子表，包含统一保留字段 `id`、`org_id`、`status`、`created_by`、`created_at`、`updated_by`、`updated_at`、`version`、`del_flag`、`schema_version`；DDL SHALL 进入版本化迁移记录。系统 SHALL NOT 使用统一 EAV 或通用记录大表承载业务。

#### Scenario: 发布工器具借还台账
- **WHEN** 发布包含主表"借还单"与子表"借出明细"的 Concept
- **THEN** 数据库中出现对应主表与子表，迁移历史中有该次 DDL 记录

### Requirement: 新字段默认显式列
新增字段 SHALL 默认生成显式列并按查询需求建索引；只有标记为非关键补充的信息才允许进入 `ext_attrs`。改名、删列、改类型 SHALL 通过迁移脚本完成。

#### Scenario: 改字段类型
- **WHEN** 将文本字段改为数字字段
- **THEN** 系统生成需评审的迁移脚本，而非直接覆盖旧定义

### Requirement: 通用增删改查
系统 SHALL 为每个台账 Concept 提供单表与主子表的新增、编辑、详情、复制和逻辑删除；主子表保存 SHALL 在单一事务中完成。

#### Scenario: 子表校验失败
- **WHEN** 保存借还单时第三行明细缺少必填的工器具
- **THEN** 整单保存失败，返回第三行的字段错误

### Requirement: 白名单查询
列表查询 SHALL 只接受白名单字段、操作符、排序字段、关联对象和聚合字段，所有值参数化绑定；不允许在设计器或请求中提交 SQL 片段。

#### Scenario: 注入尝试
- **WHEN** 排序参数为 `name; drop table x`
- **THEN** 请求被拒绝为非法排序字段

#### Scenario: 非白名单字段过滤
- **WHEN** 以未开放查询的字段作为筛选条件
- **THEN** 请求被拒绝

### Requirement: 服务端权限与校验
通用接口 SHALL 在服务端执行功能权限、组织／行级数据域、字段权限和表单 Schema 校验，绕过前端直接调用时依然生效。

#### Scenario: 越域读取
- **WHEN** A 车间用户通过 ID 直接读取 B 车间台账记录
- **THEN** 返回无权限

### Requirement: 正式动作分流
提交、审核、退回、关闭、确认、分派 SHALL 只能通过绑定的 Operation 执行；通用更新 SHALL 只允许修改草稿态的非受控字段。

#### Scenario: 已提交记录通用编辑
- **WHEN** 对已提交的借还单调用通用更新修改数量
- **THEN** 请求被拒绝

### Requirement: 导入导出
系统 SHALL 支持 Excel 导入预检（逐行校验、错误行报告、确认后入库）和异步导出；导出受权限、条数上限和资源限制约束，并记录审计。

#### Scenario: 导入有错误行
- **WHEN** 导入 100 行其中 3 行字典值非法
- **THEN** 预检报告列出 3 行错误，用户可选择仅导入 97 条正确行

#### Scenario: 超量导出
- **WHEN** 导出条数超过配置上限
- **THEN** 系统拒绝或要求缩小范围，并记录审计

### Requirement: 并发、重复提交与审计
通用写入 SHALL 使用乐观锁、防重复提交，并记录修改人与字段级变更到业务时间轴。

#### Scenario: 双击保存
- **WHEN** 用户快速连续点击两次保存
- **THEN** 只产生一次写入

### Requirement: 不向 Agent 直接暴露
通用 CRUD 接口 SHALL NOT 对 Agent 身份开放；Agent 只能调用包装后的 Operation。

#### Scenario: Agent 调通用接口
- **WHEN** Agent 身份令牌调用通用 CRUD 新增接口
- **THEN** 请求被拒绝并审计
