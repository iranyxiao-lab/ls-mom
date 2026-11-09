# Spec Delta

## Purpose

定义 LS-MOM 产品基线的工程形态：代码托管、模块边界与依赖方向、可重跑的部署环境、数据库迁移和持续集成门禁，确保所有客户交付都基于同一可升级的产品主干。

## ADDED Requirements

### Requirement: 代码托管于 GitHub 私有仓库
项目 SHALL 以 Git 管理，并托管在 GitHub 私有仓库 `ls-mom`；默认分支为 `main`，仓库中 SHALL NOT 提交任何凭据、令牌或客户数据。

#### Scenario: 首次推送
- **WHEN** 实施者完成初始化并推送
- **THEN** GitHub 上存在私有仓库 `ls-mom`，`main` 分支包含 README、LICENSE 声明、`.gitignore` 和 OpenSpec 目录

#### Scenario: 凭据扫描
- **WHEN** 任一提交包含疑似密钥、令牌或密码明文
- **THEN** CI 的密钥扫描步骤失败并阻止合并

### Requirement: 模块依赖方向受控
后端 SHALL 划分为 core、meta、crud、form、flow、action、pack、ai、dcs、biz 等模块，并满足：biz 只依赖 core／action／flow／meta；crud／form／flow 不依赖任何客户业务；ai 与 MCP 适配只依赖 action；core 不依赖任何 Pack。

#### Scenario: 违规依赖被拦截
- **WHEN** 有代码让 core 模块引用 biz 或任意 Pack 的类型
- **THEN** 构建期架构测试失败并指出违规的依赖边

#### Scenario: AI 模块绕过 Operation
- **WHEN** ai 或 MCP 模块直接引用数据访问层（Mapper／Repository）
- **THEN** 架构测试失败

### Requirement: 一键可重跑的部署环境
项目 SHALL 提供容器编排定义，一条命令即可在全新机器上启动应用、关系数据库、缓存和对象存储，并自动执行迁移与种子数据；流程引擎表 SHALL 位于独立 schema。

#### Scenario: 全新环境启动
- **WHEN** 在没有任何数据卷的机器上执行启动命令
- **THEN** 所有服务健康，迁移执行成功，可用种子管理员登录并看到演示组织与 Pack

#### Scenario: 重复执行
- **WHEN** 在已初始化的环境上再次执行启动与种子命令
- **THEN** 不产生重复种子数据，迁移不报错

### Requirement: 数据库结构只经版本化迁移变更
所有表结构变更 SHALL 通过版本化迁移脚本进入数据库，迁移历史可查询；任何运行时设计器 SHALL NOT 直接对数据库执行任意 DDL。

#### Scenario: 手工 DDL 检测
- **WHEN** 数据库结构与迁移历史推导出的结构不一致
- **THEN** 启动时的结构校验报告漂移并在生产配置下拒绝启动

### Requirement: 持续集成质量门禁
每次提交与合并请求 SHALL 执行编译、单元测试、架构测试、依赖许可证扫描（拒绝 GPL／AGPL 链接依赖）、SBOM 生成和前端构建；任一失败即阻止合并。

#### Scenario: 引入不兼容许可证
- **WHEN** 新增依赖的许可证为 AGPL
- **THEN** 许可证扫描失败并列出该依赖

### Requirement: 全链路追踪标识
每个外部请求、Operation 执行、流程回调、Outbox 投递和 Agent 调用 SHALL 携带并记录同一 `trace_id`。

#### Scenario: 跨组件追踪
- **WHEN** 用户提交一张单据并触发流程启动与 Outbox 事件
- **THEN** 请求日志、审计记录、流程绑定记录和 Outbox 记录中可查到同一 `trace_id`

### Requirement: 非功能基线
常用列表在约定数据量（单表 100 万行）和 50 并发下 P95 SHALL ≤800ms；普通保存在 30 并发下 P95 SHALL ≤500ms（不含外部等待）。

#### Scenario: 性能基准
- **WHEN** 在基准环境运行性能测试套件
- **THEN** 报告中列表与保存的 P95 均满足阈值，否则发布门禁失败
