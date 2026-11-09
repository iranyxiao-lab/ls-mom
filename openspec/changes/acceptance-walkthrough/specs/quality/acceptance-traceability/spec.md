# Spec Delta

## Purpose

保证每一条已规划的行为要求都有对应的验收用例并留有执行结果，通过可追溯矩阵和覆盖门禁，让"还有哪些没验收"始终可查。

## ADDED Requirements

### Requirement: 验收用例格式
每条验收用例 SHALL 包含唯一编号、标题、关联的 change／spec 路径／Requirement 名称／Scenario 名称、角色、前置数据集、步骤、预期结果、执行方式（AUTO／MANUAL）、优先级，自动用例还要写明对应的自动化脚本位置；格式 SHALL 有机器可校验的 Schema。

#### Scenario: 缺少关联场景
- **WHEN** 新增一条未填写关联 Scenario 的用例
- **THEN** 用例校验失败并指出该用例编号

#### Scenario: 自动用例缺脚本
- **WHEN** 执行方式为 AUTO 的用例未填写自动化脚本，或脚本文件不存在
- **THEN** 用例校验失败

### Requirement: 场景全覆盖
被验收的每个 change 中的每个 Scenario SHALL 至少被一条验收用例覆盖；被标注为 P0 的 Requirement SHALL 至少有一条 AUTO 用例。

#### Scenario: 遗漏场景
- **WHEN** 某 spec 新增了一个 Scenario 而用例库中没有对应用例
- **THEN** 覆盖门禁失败，报告列出该 Scenario 所在的 change、spec 与 Requirement

### Requirement: 关联失效检测
当 spec 中的 Requirement 或 Scenario 被改名、删除或移动后，引用它的用例 SHALL 被识别为"悬空"并导致门禁失败。

#### Scenario: 场景改名
- **WHEN** 某 Scenario 改名后仍有用例引用旧名称
- **THEN** 门禁报告该用例悬空

### Requirement: 可追溯矩阵与覆盖报告
系统 SHALL 生成可追溯矩阵（Scenario → 用例 → 最近一次执行结果 → 关联缺陷），并按 change、spec、切片统计覆盖率和通过率，输出 HTML 与 Markdown 报告。

#### Scenario: 查看某 spec 的验收情况
- **WHEN** 打开报告中的 `flow/workflow-collaboration`
- **THEN** 能看到每个 Scenario 的用例、最近执行时间、结果、证据链接和未关闭缺陷

### Requirement: 验收编号对齐
v2.1 §13 中的验收编号（DOM、CRUD、FORM、FLOW、AI 等）与 UI 预算项 SHALL 在用例中标注，报告 SHALL 能按这些编号汇总结果。

#### Scenario: 按编号汇总
- **WHEN** 报告按"FLOW-01～FLOW-08"筛选
- **THEN** 列出每个编号对应的用例与通过情况
