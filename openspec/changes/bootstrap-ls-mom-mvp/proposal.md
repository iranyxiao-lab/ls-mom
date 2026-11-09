# Proposal：LS-MOM 工业底座 MVP（v2.1）

## Why

面向手工产线的客户需要的是"工作协同＋台账＋填报审批＋异常闭环＋经营看板"，而不是以 BOM／工艺路线为前提的重型 MES；同时每个客户的临时台账与审批需求变化很快，若直接在项目源码里堆功能，第二个客户、第一次跨版本升级时就会失控。现在仓库是空的，需要一次性确立 Java 主干、稳定核心语义、元数据运行时、表单／流程契约、Pack 治理和 AI 安全边界，并把代码托管到 GitHub，作为后续所有客户交付的唯一产品基线。

本方案以《LS-MOM 工业底座 MVP 设计 v2.1》为准；随附的《LS-IDF 架构总览 v0.1》只作远期参考，二者冲突处一律按 v2.1（Vue3、Docker Compose、Flowable 7、PostgreSQL 16、单体优先）。

## What Changes

- 建立 Git 仓库并推送到 GitHub 私有仓库 `ls-mom`；建立 Maven 多模块工程（参考 dromara/RuoYi-Vue-Plus 5.X 分支，Spring Boot 3＋JDK 21）与 plus-ui 前端（Vue3＋TS＋Element Plus），配套 Docker Compose（app＋PostgreSQL 16＋Redis＋对象存储）、Flyway、CI。
- 新增稳定核心：Party／OrgUnit／WorkItem／WorkAssignment／WorkReport／WorkRelation／Issue／Observation／DomainEvent（含 Outbox）／DocumentLink，核心不含任何 BOM、库存或客户专属字段。
- 新增自研元数据内核：`meta_concept／meta_view／meta_operation／meta_flow／meta_rule／meta_metric／meta_pack` 七类契约及 `meta_change_set`，支持版本冻结与发布链路。
- 新增 Operation Gateway：所有受控写入（提交、审核、退回、关闭、确认、分派）经命名 Operation，带权限、守卫、幂等、审计和 `trace_id`；约束分 L0～L4。
- 新增通用台账引擎：动态 Concept 生成受控物理表 `c_<namespace>_<concept>`，`GenericCrudController` 提供白名单查询、主子表、导入导出、乐观锁、时间轴；正式动作强制分流到 Operation。
- 新增 LS FormSchema 与可拖拽表单设计器（基于 form-create 设计器适配，私有格式只在适配层），服务端校验、节点字段策略、表单版本、受限 CEL 规则，禁止自由脚本。
- 新增 Flowable 7 协同：业务状态只在业务表，`StateMachineExecutor`＋Flowable Adapter＋`biz_task` 读模型＋`biz_flow_instance`，流程配置设计器从 `meta_flow` 固定模板生成 BPMN，支持或签／会签、退回／撤回／转办／催办、超时、版本冻结、幂等回调与对账。
- 新增指标与命名查询（`meta_metric`、`/api/q/{queryCode}`）、协同工作中心、生产运营工作台、协同应用中心。
- 新增领域 Pack：轻生产、设备、质量、DCS（点位语义、快照、报警去重，只读）、培训考试、意见咨询。
- 新增 Pack 生命周期：安装、依赖、迁移、启停、升级、复审、配置漂移检测与回灌 ChangeSet、备份恢复演练。
- 新增 AI 能力：LS-IR 中间表示、构建期需求→LS-IR→Java／元数据生成、Frappe 样例的静态转换（不执行 Python）、运行期 Agent 门面（QUERY／DRAFT／SIMULATE／APPROVAL_REQUIRED／DENY 暴露等级、OBO 双主体、审批绑定输入）、带引用的知识问答。
- 明确不做：ERP／财务、强制 BOM／MRP、APS、PLM、DCS／PLC 写入、任意 SQL／脚本／BPMN XML 粘贴、通用聊天 Agent、图数据库、EAV 大表、Frappe 运行时。

## Capabilities

### New Capabilities

- `platform/project-baseline`：仓库与 GitHub 托管、模块结构与依赖方向、Docker Compose 可重跑部署、Flyway、CI 与质量门禁。
- `core/party-org`：Party 与 OrgUnit 统一引用、组织层级与有效期、与 RuoYi 用户／部门映射。
- `core/work-collaboration`：WorkItem、WorkAssignment、WorkReport、WorkRelation 的工作闭环与状态机、约束 Profile、版本冻结。
- `core/issue-management`：Issue 异常／缺陷／整改闭环外壳及与工作、设备、质量对象的关联。
- `core/domain-events`：Observation、DomainEvent、Outbox 投递、DocumentLink 附件关联与业务时间轴。
- `meta/concept-registry`：Concept／Relation／Mapping 语义注册、编码稳定、字段弃用与版本。
- `meta/pack-lifecycle`：Pack Manifest、ChangeSet、迁移、安装／启停／升级／回滚、复审与配置漂移。
- `action/operation-gateway`：命名 Operation 契约、C0／C1／C2 写入分级、权限、幂等、审计与 trace。
- `action/rule-guard`：L0～L4 约束分级、受限 CEL 子集、决策表与 Java Guard 插件。
- `crud/generic-ledger`：动态物理表、通用 CRUD、白名单 QuerySpec、导入导出、并发控制与时间轴。
- `form/form-schema`：LS FormSchema、List／Detail／Print／Mobile Schema、服务端校验、表单版本与迁移。
- `form/form-designer`：可拖拽设计器工作区、MVP 控件集、组件注册表、预览与权限模拟、开源设计器适配。
- `flow/workflow-collaboration`：Flowable 7 适配、单一业务状态、办理人策略、节点字段策略、待办读模型、版本、幂等与对账。
- `flow/flow-designer`：流程配置设计器、固定模板生成 BPMN、危险节点禁止、版本差异预览。
- `metric/metrics-query`：Metric 口径、命名 Query、看板绑定与钻取。
- `workbench/collaboration-center`：我的待办／已办／发起／抄送、业务时间轴、消息与待办分离、协同应用中心。
- `domain/light-production`：生产需求、班次安排、产出／物料记录、交接班、生产运营工作台与经营看板。
- `domain/asset-management`：设备台账、点检、缺陷与维修 WorkItem。
- `domain/quality-management`：质量记录、不符合项、整改与受控质量流程。
- `dcs/dcs-integration`：点位语义、点位-设备映射、降采样快照、报警幂等去重与草稿生成，只读。
- `packs/training-exam`：培训考试扩展 Pack。
- `packs/consultation`：意见咨询扩展 Pack。
- `ai/ls-ir-conversion`：LS-IR、构建期生成流水线、Frappe 静态转换与来源追溯。
- `ai/agent-gateway`：运行期 Agent 暴露等级、有效权限计算、OBO、审批绑定、草稿默认与知识问答引用。
- `security/access-audit`：身份类型、功能／数据／字段／附件权限、API Key、审计覆盖、备份与联合恢复。

### Modified Capabilities

（无，仓库当前没有任何既有 spec。）

## Impact

- **代码**：全新仓库。后端 Maven 多模块（RuoYi 基础模块＋`ls-mom-*` 10 个模块），前端 plus-ui 改造＋设计器与工作台页面，`deploy/` Compose 与种子数据，`packs/` 标准 Pack 资产。
- **依赖**：RuoYi-Vue-Plus 5.X（MIT）、Spring Boot 3、Sa-Token、MyBatis-Plus、Flowable 7（Apache-2.0）、Flyway、cel-java（Apache-2.0）、PostgreSQL 16、Redis、对象存储、form-create 设计器（MIT）、LogicFlow（Apache-2.0，流程画布）。移除 RuoYi 自带 Warm-Flow 工作流模块，避免双流程引擎。
- **API**：`/api/op/{operationCode}`、`/api/q/{queryCode}`、`/api/crud/{conceptCode}`、OpenAPI 3.1、独立 MCP Adapter。
- **外部系统**：GitHub（私有仓库 `ls-mom`，实施时需用户提供登录或令牌）；DCS／Historian 只读接入（MVP 用模拟器）；LLM 服务可配置私有化端点。
- **周期**：按 v2.1 §14.1 约 12～16 周，分 P0～P5 阶段推进。
