# Spec Delta

## Purpose

让走查发现的每个问题都有统一的记录、明确的严重级别、到用例与 spec 的关联，并在修复后经过回归验证才能关闭。

## ADDED Requirements

### Requirement: 缺陷记录格式
缺陷 SHALL 记录在项目的 GitHub Issues 中，使用统一模板：标题、严重级别、类型（功能／UI 体验／无障碍／性能／安全）、关联用例编号、关联 change／spec／Scenario、复现步骤、预期与实际、环境与版本、证据（截图、trace、trace_id）。

#### Scenario: 缺少复现信息
- **WHEN** 提交的缺陷未填写关联用例或复现步骤
- **THEN** 模板必填校验阻止提交

### Requirement: 严重级别定义
缺陷 SHALL 按 S1～S4 分级：S1 阻断（核心流程走不通、数据错误、越权、安全问题）；S2 严重（功能可用但结果不对，或关键任务超出操作预算）；S3 一般（体验或一致性问题，有替代路径）；S4 轻微（文案、样式细节）。

#### Scenario: 越权定级
- **WHEN** 走查发现非办理人能办理他人任务
- **THEN** 该缺陷定为 S1

### Requirement: 修复后回归
缺陷标记为已修复后 SHALL 重跑其关联用例和同一旅程；只有回归通过才能关闭，回归结果链接到缺陷记录。

#### Scenario: 回归失败
- **WHEN** 已修复缺陷的关联用例回归仍失败
- **THEN** 缺陷重新打开并记录回归失败证据

### Requirement: 缺陷与报告同步
可追溯矩阵与验收报告 SHALL 显示每个 Scenario 关联的未关闭缺陷，按严重级别汇总。

#### Scenario: 报告显示缺陷
- **WHEN** 某 Scenario 有一个未关闭的 S2 缺陷
- **THEN** 报告中该 Scenario 标记为"有未关闭缺陷"并链接到对应 Issue
