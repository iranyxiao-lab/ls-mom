# Spec Delta

## Purpose

以 WorkItem 为工作闭环核心，统一承载生产、维修、点检、改善和临时工作的分派、填报、阻塞、确认，不以 BOM、工艺路线或库存为前提。

## ADDED Requirements

### Requirement: WorkItem 最小必填集
创建 WorkItem SHALL 只要求 `work_type`（PRODUCTION／MAINTENANCE／INSPECTION／IMPROVEMENT／OTHER）、`title`、`description`、数据域所需组织和 `constraint_profile`；BOM、Routing、Recipe、WorkCenter、Warehouse、Batch、Serial、CostCenter SHALL NOT 成为必填项。

#### Scenario: 无制造主数据创建生产工作
- **WHEN** 系统中没有任何 BOM、Routing、Warehouse 数据时创建一条生产工作
- **THEN** 创建成功，状态为 DRAFT

#### Scenario: 缺少标题
- **WHEN** 通过 API 创建 WorkItem 未提供 `title`
- **THEN** 返回校验错误并指出 `title` 必填

### Requirement: WorkItem 生命周期
WorkItem 状态 SHALL 仅在 DRAFT→READY→ASSIGNED→IN_PROGRESS→PENDING_CONFIRM→DONE 主路径及 BLOCKED、CANCELLED 分支间按状态机迁移；每次迁移 SHALL 通过命名 Operation 完成并记录事件。

#### Scenario: 非法迁移
- **WHEN** 对 DRAFT 状态的工作直接调用"确认完成"
- **THEN** 系统拒绝并返回当前状态与允许的动作列表

#### Scenario: 阻塞与恢复
- **WHEN** 执行中的工作被标记阻塞并填写原因，之后解除阻塞
- **THEN** 状态依次为 BLOCKED、IN_PROGRESS，时间轴记录两次迁移及原因

### Requirement: 柔性分派
系统 SHALL 支持将工作分派给人员、班组、场地或设备中的任意组合，并记录责任角色与计划时间；同一资源时间冲突默认提示，是否阻断由约束 Profile 决定。

#### Scenario: 冲突提示
- **WHEN** 分派的人员在同一时段已有另一项工作，且 Profile 为宽松
- **THEN** 分派成功并返回冲突警告

#### Scenario: 冲突阻断
- **WHEN** 同样冲突但 Profile 设置冲突为阻断
- **THEN** 分派被拒绝并说明冲突的工作

### Requirement: 工作填报
WorkReport SHALL 记录进度、人工、产出、消耗、说明和证据附件；填报支持草稿，正式提交由服务端校验；填报 SHALL NOT 默认产生库存或会计过账。

#### Scenario: 草稿到提交
- **WHEN** 移动端保存填报草稿后再提交
- **THEN** 草稿不参与统计，提交后计入工作进度与看板

### Requirement: 工作关系
系统 SHALL 支持工作之间的父子、依赖、关联和阻塞关系；父子关系 SHALL NOT 形成环。

#### Scenario: 成环拒绝
- **WHEN** 将 A 设为 B 的父工作，而 B 已是 A 的祖先
- **THEN** 系统拒绝并提示形成循环

### Requirement: 约束 Profile 可选增强
`constraint_profile` SHALL 决定额外约束（如产出单位必填、物料记录必填）；启用或切换 Profile SHALL NOT 使历史已完成记录变为不合法。

#### Scenario: 启用严格 Profile
- **WHEN** 管理员为某车间启用要求产出单位的 Profile
- **THEN** 之后提交的产出填报必须带单位，启用前已完成的工作仍可正常查看与统计

### Requirement: 运行时定义版本冻结
需要表单或流程的 WorkItem SHALL 在提交时保存 `form_version`、`flow_version` 和 `schema_version`，后续定义升级不改变已有记录的解释。

#### Scenario: 表单升级后查看历史
- **WHEN** 工作表单发布 v2 后打开按 v1 提交的工作
- **THEN** 详情按 v1 渲染

### Requirement: 生产与维修复用同一闭环
维修、点检等工作 SHALL 复用分派、填报、异常和确认能力，专业字段由各 Pack 的扩展对象承载，SHALL NOT 加入核心工作对象。

#### Scenario: 维修工作
- **WHEN** 设备 Pack 由缺陷生成维修工作
- **THEN** 该工作类型为 MAINTENANCE，使用同一分派与确认动作，设备专属字段存于设备 Pack 的扩展对象
