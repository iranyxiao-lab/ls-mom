# Spec Delta

## Purpose

为实施人员提供面向配置的流程设计器，以有限节点类型与命名办理人策略编排审批流程，由固定模板生成标准流程定义，杜绝脚本节点与任意流程定义粘贴。

## ADDED Requirements

### Requirement: 节点类型范围
设计器 SHALL 提供开始、结束、填报、审核、确认、抄送、条件分支、并行、或签、会签、服务节点；服务节点 SHALL 只能选择已发布 Operation。加签、委托和子流程不在 MVP 开放。

#### Scenario: 服务节点选择
- **WHEN** 配置服务节点
- **THEN** 只能从已发布 Operation 列表中选择，不能输入类名、URL 或脚本

### Requirement: 从配置生成标准流程定义
流程发布 SHALL 将流程配置经固定模板转为标准 BPMN 2.0 定义并部署；业务用户 SHALL NOT 上传或粘贴原始 BPMN XML。

#### Scenario: 粘贴 XML
- **WHEN** 用户尝试在设计器导入 BPMN XML
- **THEN** 操作不可用；只有代码库中经评审的研发流程可部署原始定义

### Requirement: 危险节点禁止
生成或部署的流程定义 SHALL NOT 包含脚本任务、任意代码委托类、表达式访问系统对象、外部 URL 调用；部署前 SHALL 进行静态扫描。

#### Scenario: 研发流程含脚本任务
- **WHEN** 代码库流程定义中包含脚本任务并提交部署
- **THEN** 静态扫描失败，部署被拒绝

### Requirement: 节点绑定表单策略
每个人工节点 SHALL 引用一份已发布表单版本并只保存字段策略差异（visible／editable／readonly／required）；节点 SHALL NOT 复制整张表单。

#### Scenario: 引用表单不存在字段
- **WHEN** 节点 `required` 包含表单中不存在的字段
- **THEN** 发布校验失败

### Requirement: 发布前办理人校验
发布 SHALL 校验每个人工节点的办理人策略在当前组织中可解析出至少一名有权限的候选人。

#### Scenario: 角色无成员
- **WHEN** 节点办理人策略为某角色而该角色在业务部门下无成员
- **THEN** 发布被阻断并提示节点名

### Requirement: 预览与版本差异
设计器 SHALL 提供生成后流程图的只读预览，以及任意两个流程版本之间的节点、连线、策略差异对比。

#### Scenario: 版本对比
- **WHEN** 对比 v1 与 v2
- **THEN** 显示新增的会签节点与变化的超时设置

### Requirement: 业务结果绑定
流程配置 SHALL 声明 `onApproved`、`onRejected` 等结果对应的 Operation；未声明结果绑定的流程 SHALL NOT 发布。

#### Scenario: 缺少回调
- **WHEN** 流程未配置 `onApproved`
- **THEN** 发布失败
