# Spec Delta

## Purpose

以 LS-IR 作为需求、外部低代码资产与产品元数据／代码之间的中间表示，支撑构建期 LLM 生成和 Frappe 资产的静态转换，所有产物进入 ChangeSet、测试与人工评审，永不直达生产。

## ADDED Requirements

### Requirement: LS-IR 格式
LS-IR SHALL 是带 `irVersion` 的版本化文档，包含 `namespace`、`concepts`、`views`、`flows`、`operations`、`rules`、`permissions`、`metrics`、`migrations`、`tests` 和 `provenance`，并有机器可校验的 Schema。

#### Scenario: Schema 校验
- **WHEN** 提交缺少 `provenance` 的 LS-IR
- **THEN** 校验失败

### Requirement: 由 LS-IR 生成产物
系统 SHALL 能由 LS-IR 生成元数据定义、DDL 迁移、表单 Schema、流程配置、权限点、Operation 骨架和测试，并打包为 ChangeSet。

#### Scenario: AI-02 可构建
- **WHEN** 由一份新台账 LS-IR 生成产物
- **THEN** 生成物能编译、在临时数据库安装并通过生成的测试

### Requirement: 构建期需求生成
构建期 Copilot SHALL 能从需求说明／表格／旧表结构生成需求规格与 LS-IR，并先做语义复用检查，优先复用已有 Concept、Operation 与 Pack。

#### Scenario: 复用已有对象
- **WHEN** 需求中提到"设备"
- **THEN** 生成的 LS-IR 引用现有 `ls.asset.Asset` 而非新建设备 Concept

### Requirement: 不直达生产
LLM 及生成流水线 SHALL NOT 拥有生产发布权限，产物只能进入分支、LS-IR 或 ChangeSet，经人工批准后发布。

#### Scenario: AI-01 验收
- **WHEN** 生成流水线尝试直接发布 ChangeSet 到生产
- **THEN** 请求被拒绝

### Requirement: Frappe 静态转换
Frappe 资产 SHALL 只通过静态解析转为 LS-IR，系统 SHALL NOT 执行任何源 Python、Server Script 或 Client Script；DocType、子表、Workflow、DocPerm、Report、Fixture、Patch 按映射规则转换。

#### Scenario: 转换样例
- **WHEN** 转换一个许可清晰的简单 DocType 与 Workflow 样例
- **THEN** 得到 LS-IR 及其生成物，场景测试通过

### Requirement: 危险资产阻断
Server Script、未知或不兼容许可证、无法确认权限语义的资产 SHALL 标记为"禁止自动转换"或"需人工"，SHALL NOT 自动发布。

#### Scenario: AI-04 验收
- **WHEN** 待转换包中含 Server Script
- **THEN** 该部分标记为禁止自动转换，ChangeSet 无法在未人工处理时发布

### Requirement: 来源追溯
每份转换或生成结果 SHALL 记录来源类型、来源应用与版本、许可证、转换器版本、模型版本和人工修改记录。

#### Scenario: AI-03 验收
- **WHEN** 查询某 Pack 中由转换生成的 Concept
- **THEN** 可看到其 Frappe 来源、许可证、转换器版本和人工修改历史

### Requirement: 生成质量指标
流水线 SHALL 统计一次编译与安装成功率、校验通过率、测试一次通过率、人工修改量与原因、复用比例和需求到 UAT 工时。

#### Scenario: 指标报告
- **WHEN** 完成一次生成任务
- **THEN** 指标报告中记录本次的各项数值
