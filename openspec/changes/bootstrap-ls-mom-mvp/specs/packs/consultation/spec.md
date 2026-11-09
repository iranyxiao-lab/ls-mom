# Spec Delta

## Purpose

以独立扩展 Pack 提供意见征集与咨询：议题发布、意见提交、答复、归类与总结，复用底座的表单、流程、Issue、文档与指标能力。

## ADDED Requirements

### Requirement: 议题与意见
Pack SHALL 支持发布议题（范围、截止时间、是否匿名）并接收意见；匿名议题中意见提交人 SHALL 对答复人不可见，但审计可追溯。

#### Scenario: 匿名意见
- **WHEN** 员工在匿名议题下提交意见
- **THEN** 答复人看不到提交人，审计记录中保留提交主体

### Requirement: 答复与转问题
意见 SHALL 可被答复、归类，必要时转为 Issue 进入整改闭环。

#### Scenario: 意见转整改
- **WHEN** 管理员将一条安全意见转为 Issue
- **THEN** 生成关联该意见的 Issue，意见状态显示已转整改

### Requirement: 咨询总结
议题截止后 SHALL 可生成咨询总结；LLM 可草拟归类与总结，经负责人确认后发布，总结 SHALL 引用来源意见。

#### Scenario: 总结引用
- **WHEN** 负责人发布 LLM 草拟的总结
- **THEN** 总结中每个观点都能链接到对应意见

### Requirement: 独立安装与停用
本 Pack SHALL 可独立安装、升级、停用和复审，不依赖培训考试 Pack。

#### Scenario: 仅安装咨询 Pack
- **WHEN** 在未安装培训 Pack 的环境安装咨询 Pack
- **THEN** 安装成功且功能完整
