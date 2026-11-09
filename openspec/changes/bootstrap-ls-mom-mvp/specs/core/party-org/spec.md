# Spec Delta

## Purpose

为人、外部协作方、系统主体及公司／部门／车间／班组／工段提供统一、带有效期的引用，使工作、权限和数据域都能指向稳定的主体与组织。

## ADDED Requirements

### Requirement: 统一主体引用
系统 SHALL 以 Party 统一表示人员、外部协作方、服务身份和 Agent 身份，每个 Party 具有稳定编码和类型（EMPLOYEE／OUTSOURCE／SERVICE／AGENT／EXTERNAL）；登录用户 SHALL 映射到唯一 Party，Party 本身不承担权限判定。

#### Scenario: 用户创建时生成 Party
- **WHEN** 管理员新建一个员工账号
- **THEN** 系统自动创建类型为 EMPLOYEE 的 Party 并与该账号关联

#### Scenario: 外部协作方无登录
- **WHEN** 登记一个外部维修单位联系人
- **THEN** 该 Party 可被工作分派引用，但不具备登录能力

### Requirement: 组织单元层级与有效期
系统 SHALL 维护 OrgUnit 树，类型包括 COMPANY／DEPARTMENT／WORKSHOP／TEAM／SECTION，每个节点具有生效与失效日期；失效节点 SHALL NOT 被新业务引用，但历史记录保持可读。

#### Scenario: 班组撤销
- **WHEN** 某班组设置失效日期为昨天
- **THEN** 新建工作时无法选择该班组，历史工作详情仍显示该班组名称

### Requirement: 组织与系统部门映射
每个 OrgUnit SHALL 映射到系统部门以复用数据权限；车间与班组 SHALL 可作为数据域维度参与行级过滤。

#### Scenario: 班组数据域
- **WHEN** 数据域为"本班组"的用户查询工作列表
- **THEN** 只返回所属班组的工作

### Requirement: 负责人解析
系统 SHALL 能按组织解析部门负责人、车间负责人和上级负责人，供流程办理人策略和通知使用；无法解析时 SHALL 返回明确的"无人可办"结果而非空成功。

#### Scenario: 负责人缺失
- **WHEN** 流程需要车间负责人而该车间未设置负责人
- **THEN** 解析返回"无人可办"，调用方据此阻断发布或生成异常待办
