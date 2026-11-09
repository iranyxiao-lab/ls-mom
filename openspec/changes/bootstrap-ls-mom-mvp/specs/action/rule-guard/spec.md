# Spec Delta

## Purpose

按 L0～L4 分级表达业务约束，用受限表达式与决策表处理声明式规则，用经评审的代码插件处理跨对象与监管规则，并保证前后端语义一致、后端为最终裁决者。

## ADDED Requirements

### Requirement: 约束分级
系统 SHALL 区分 L0 提示（不阻断）、L1 Schema 校验、L2 声明式守卫、L3 代码守卫插件和 L4 外部权威；每条约束 SHALL 标注级别，且 L2 及以上在服务端执行。

#### Scenario: L0 提示
- **WHEN** 提交工作未填计划结束时间
- **THEN** 提交成功并返回提示信息

#### Scenario: L2 阻断
- **WHEN** 高风险工作分派给资质已过期的人员
- **THEN** 分派被拒绝并说明资质过期

### Requirement: 受限表达式子集
声明式规则 SHALL 使用受限表达式子集，只允许访问 `$model`、`$context` 和白名单函数，支持比较、逻辑、范围、正则、长度、枚举与四则运算；SHALL NOT 支持循环、递归、任意方法调用、网络或数据库访问。

#### Scenario: 非法表达式
- **WHEN** 规则表达式中调用未在白名单的函数
- **THEN** 发布校验失败并指出函数名

#### Scenario: 执行超限
- **WHEN** 表达式求值超过成本或时间上限
- **THEN** 求值中止并按规则失败处理

### Requirement: 前后端语义一致
同一规则 SHALL 在前端与后端得到相同结果；后端 SHALL 对所有提交重新求值，不信任前端结论。

#### Scenario: 绕过前端
- **WHEN** 直接调用 API 提交违反条件必填规则的数据
- **THEN** 后端拒绝

#### Scenario: 一致性测试
- **WHEN** 运行规则一致性测试集
- **THEN** 前端与后端对每条用例的求值结果一致

### Requirement: 决策表
系统 SHALL 支持以决策表表达多条件判定（命中策略：首个命中／全部命中），并可被守卫引用。

#### Scenario: 风险定级
- **WHEN** 输入严重度为 HIGH 且涉及安全设备
- **THEN** 决策表返回风险等级 L3

### Requirement: 代码守卫插件
跨对象、事务一致性或监管规则 SHALL 以注册的代码守卫实现，需代码评审与测试后随 Pack 发布；配置中 SHALL NOT 内嵌任意代码。

#### Scenario: 双签互斥
- **WHEN** 同一人试图同时作为执行人和复核人完成双签
- **THEN** 代码守卫拒绝

### Requirement: 加严不放宽
客户或 Pack 级规则 SHALL 只能在标准规则之上加严，SHALL NOT 关闭或放宽标准 Pack 声明为阻断的约束。

#### Scenario: 试图放宽
- **WHEN** 客户 Pack 将标准阻断规则覆盖为提示
- **THEN** 发布校验失败
