# Spec Delta

## Purpose

提供可独立存在、也可关联多种对象的质量记录、不符合项和质量整改能力，并以受控流程完成登记、审核、整改、验证与关闭。

## ADDED Requirements

### Requirement: 质量记录独立与关联
质量记录 SHALL 可独立存在，也可关联 WorkItem、产出、设备、物料记录或外部对象中的任意多个；任何关联都不是必填。

#### Scenario: DOM-04 验收
- **WHEN** 分别创建一条无关联的质量记录和一条同时关联工作与设备的质量记录
- **THEN** 两者均保存成功并可在相应对象时间轴中查看

### Requirement: 不符合项条件必填
不符合项 SHALL 支持按严重程度的条件必填：严重程度为 HIGH 时相关设备与立即措施必填，前后端一致。

#### Scenario: 高严重度缺字段
- **WHEN** 通过 API 提交 HIGH 严重度且未填立即措施的不符合项
- **THEN** 后端拒绝

### Requirement: 受控质量流程
不符合项 SHALL 走"登记→车间审核→整改 WorkItem→验证→关闭"，车间审核节点只可编辑审核意见与立即措施。

#### Scenario: 审核节点改描述
- **WHEN** 车间审核人试图修改问题描述
- **THEN** 前端只读，直接 API 修改被拒绝

### Requirement: 质量终判仅限真人
质量最终判定与关闭 SHALL 只能由具备权限的真人执行，SHALL NOT 由 Agent 或服务身份执行。

#### Scenario: Agent 试图关闭
- **WHEN** Agent 身份调用不符合项关闭 Operation
- **THEN** 请求被拒绝并审计

### Requirement: 整改证据
质量整改 SHALL 要求附上证据（附件或观察记录）后才能提交验证。

#### Scenario: 无证据提交
- **WHEN** 整改工作无任何证据时提交验证
- **THEN** 系统拒绝并提示补充证据
