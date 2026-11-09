# Spec Delta

## Purpose

以独立扩展 Pack 提供课程、题库、考试、答卷和培训记录，完全复用底座的台账、表单、流程、权限、指标与文件能力，且不在核心中引入任何培训概念。

## ADDED Requirements

### Requirement: 课程与题库
Pack SHALL 提供课程（资料附件、适用岗位）与题库（单选、多选、判断、简答，含答案与分值）的维护。

#### Scenario: 新增题目
- **WHEN** 培训管理员新增一道多选题并设置正确选项
- **THEN** 题目保存在 Pack 的独立表中

### Requirement: 考试与答卷
Pack SHALL 支持从题库组卷、指定参考人员与时间窗口；考生在窗口内作答并提交，客观题自动判分，简答题人工评分。

#### Scenario: 超时交卷
- **WHEN** 考试时间到达而考生未提交
- **THEN** 系统自动提交已作答部分并判分

### Requirement: 培训记录
考试通过 SHALL 生成培训记录（人员、课程、成绩、有效期），可被其他 Pack 的资质守卫查询。

#### Scenario: 资质查询
- **WHEN** 高风险工作分派守卫查询人员资质
- **THEN** 通过 Pack 公开的 Operation 获得有效培训记录，而非直接读表

### Requirement: 课程发布审批
课程与试卷发布 SHALL 走审批流程；LLM 生成的题目与课程只能为草稿。

#### Scenario: LLM 草拟题目
- **WHEN** LLM 根据 SOP 生成 10 道题
- **THEN** 题目状态为草稿，需人工审核后发布

### Requirement: 核心无培训概念
安装、升级、停用本 Pack SHALL NOT 修改核心表或核心代码；核心中 SHALL NOT 出现 Exam、Question 等概念。

#### Scenario: DOM-05 验收
- **WHEN** 扫描核心 schema 与核心代码
- **THEN** 不存在培训考试相关的表、字段或类型
