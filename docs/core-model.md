# LS-MOM 稳定核心模型

稳定核心只承载所有客户、所有行业都成立的语义：**谁（Party）在哪个组织（OrgUnit）做了什么工作（WorkItem），怎么分派、怎么填报、出了什么问题（Issue）、观察到什么（Observation）、留下了哪些附件（DocumentLink）**。BOM、工艺路线、库存、批次、成本等专业字段一律不进核心，由 Pack 的扩展对象承载（`ext_concept` + `ext_id`）。

> 本文档的字段表由测试 `CoreModelDocTest` 与迁移脚本自动比对：新增、删除或改名字段而不更新本文档，CI 会失败。

## 设计约定

- **状态只经 Operation 推进**：`status` 列只能由状态机执行器经命名 Operation 修改（带版本号乐观锁）；通用接口不能直接改状态。
- **乐观锁**：带 `version` 的表每次更新加一，写入时校验调用方持有的版本。
- **有效期**：`valid_to` 当日仍有效；失效的组织不能被新业务引用，历史记录照常可读。
- **与 RuoYi 的映射**：组织单元与 `sys_dept` 一一对应（复用数据权限）；每个登录账号对应一个 EMPLOYEE 主体。映射由数据库触发器维护，经 RuoYi 管理界面、种子脚本或 LS 服务创建都不会遗漏。

## 主体与组织

### `core_org_unit`

组织单元：COMPANY / DEPARTMENT / WORKSHOP / TEAM / SECTION。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `code` | varchar(64) | 稳定编码（由部门生成为 `D<dept_id>`） |
| `name` | varchar(100) | 名称，随部门名称同步 |
| `unit_type` | varchar(16) | 类型；取 `sys_dept.dept_category`，为空时顶层为 COMPANY、其余为 DEPARTMENT |
| `parent_id` | bigint | 上级组织 |
| `dept_id` | bigint | 映射的系统部门（唯一） |
| `leader_party_id` | bigint | 负责人主体；为空回退到 `sys_dept.leader` |
| `valid_from` | date | 生效日期 |
| `valid_to` | date | 失效日期（含当日有效），部门被删除时自动置为前一天 |
| `constraint_profile` | varchar(64) | 本组织及下级默认的约束 Profile，为空继承上级 |
| `created_at` / `updated_at` | timestamp | 创建、更新时间 |
| `version` | int | 乐观锁版本 |

### `core_party`

统一主体：人员、外部协作方、服务身份、Agent 身份。主体本身不承担权限判定。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `code` | varchar(64) | 稳定编码：员工 `U<user_id>`，外部 `X…`、外包 `O…`、服务 `S…`、Agent `A…` |
| `party_type` | varchar(16) | EMPLOYEE / OUTSOURCE / SERVICE / AGENT / EXTERNAL |
| `name` | varchar(100) | 名称 |
| `user_id` | bigint | 关联登录账号；为空表示不能登录（外部协作方、外包人员必须为空） |
| `phone` | varchar(32) | 联系电话 |
| `org_name` | varchar(100) | 外部协作方所属单位 |
| `status` | varchar(16) | ACTIVE / INACTIVE（账号删除时停用，主体保留） |
| `clearance` | varchar(16) | 可访问的最高附件密级 |
| `created_at` / `updated_at` | timestamp | 创建、更新时间 |
| `version` | int | 乐观锁版本 |

### `core_party_org`

主体所属组织。主组织（`is_primary`）随登录账号的部门同步。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `party_id` | bigint | 主体 |
| `org_unit_id` | bigint | 组织 |
| `role` | varchar(16) | MEMBER / LEADER / DEPUTY |
| `is_primary` | boolean | 是否主组织（每个主体最多一个） |
| `valid_from` / `valid_to` | date | 有效期 |

**负责人解析**（`LeaderResolver`）：SELF 本组织、DEPARTMENT 最近的部门、WORKSHOP 最近的车间、SUPERIOR 上级组织。解析不到返回 `NO_ASSIGNEE` 及原因，调用方必须据此阻断发布或生成异常待办。

**数据域**（`DataScopeService`）：SELF 仅本人（自己创建或分派给自己）、TEAM 本班组及下级、WORKSHOP 所在车间及下级、DEPT_AND_CHILD 主组织及下级、ALL 全部。

## 工作协同

### `core_constraint_profile`

约束 Profile：可选增强约束。校验只在新的提交、分派时执行，切换 Profile 不会让历史已完成记录变为不合法。内置 LOOSE（默认）与 STRICT。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | varchar(64) | 主键 |
| `name` | varchar(100) | 名称 |
| `conflict_mode` | varchar(8) | 资源时段冲突：WARN 提示 / BLOCK 阻断 |
| `output_unit_required` | boolean | 产出填报必须带单位 |
| `material_required` | boolean | 生产填报必须记录物料 |
| `verify_separation` | boolean | Issue 验证人不得是唯一整改执行人 |
| `builtin` | boolean | 内置 Profile 不可修改 |
| `created_at` / `updated_at` | timestamp | 创建、更新时间 |

### `core_work_item`

工作项：生产、维修、点检、改善与临时工作的统一闭环对象。创建只要求类型、标题、说明、组织和约束 Profile（未指定时取组织生效的 Profile）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `code` | varchar(32) | 编号 `WI<日期><序号>` |
| `work_type` | varchar(16) | PRODUCTION / MAINTENANCE / INSPECTION / IMPROVEMENT / OTHER |
| `title` | varchar(200) | 标题（必填） |
| `description` | text | 说明 |
| `org_unit_id` | bigint | 所属组织（数据域） |
| `constraint_profile` | varchar(64) | 创建时的约束 Profile |
| `status` | varchar(20) | 状态，见下方状态机 |
| `priority` | smallint | 1～5，默认 3 |
| `planned_start` / `planned_end` | timestamp | 计划时间 |
| `actual_start` / `actual_end` | timestamp | 实际开始、完成时间 |
| `progress` | numeric(5,2) | 进度（取已提交填报的最大值） |
| `block_reason` | varchar(500) | 阻塞原因 |
| `form_code` | varchar(100) | 使用的表单 |
| `form_version` / `flow_version` / `schema_version` | int | 提交时冻结的定义版本 |
| `ext_concept` / `ext_id` | varchar / bigint | Pack 扩展对象 |
| `source_type` / `source_id` | varchar / bigint | 来源（如 ISSUE 整改） |
| `created_by_party` | bigint | 创建人主体 |
| `created_at` / `updated_at` | timestamp | 创建、更新时间 |
| `version` | int | 乐观锁版本 |

状态机：

```
DRAFT → READY → ASSIGNED → IN_PROGRESS → PENDING_CONFIRM → DONE
                               ↕ BLOCKED（阻塞 / 解除阻塞）
任一未结束状态 → CANCELLED
```

### `core_work_assignment`

分派：人员（PARTY）、班组（TEAM）、场地（SITE）、设备（EQUIPMENT）任意组合。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `work_item_id` | bigint | 工作 |
| `resource_type` | varchar(16) | 资源类型 |
| `resource_ref` | varchar(64) | 资源编号（主体 id、组织 id、场地或设备编码） |
| `role` | varchar(16) | RESPONSIBLE / ASSIST / REVIEW / CC |
| `planned_start` / `planned_end` | timestamp | 分派时段，为空取工作计划时间 |
| `status` | varchar(16) | ACTIVE / RELEASED |
| `assigned_by` | bigint | 分派人 |
| `created_at` | timestamp | 分派时间 |

### `core_work_report`

填报：进度、人工、产出、消耗、说明。草稿不参与统计；提交按约束 Profile 校验。填报不产生库存或会计过账。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `work_item_id` | bigint | 工作 |
| `reporter_party` | bigint | 填报人 |
| `status` | varchar(16) | DRAFT / SUBMITTED |
| `progress` | numeric(5,2) | 进度 0～100 |
| `labor_minutes` | int | 人工时长（分钟） |
| `output_qty` / `output_unit` | numeric / varchar | 产出数量与单位 |
| `consumption` | jsonb | 物料消耗事实 `[{name, qty, unit}]` |
| `remark` | varchar(2000) | 说明 |
| `submitted_at` | timestamp | 提交时间 |
| `created_at` / `updated_at` | timestamp | 创建、更新时间 |
| `version` | int | 乐观锁版本 |

### `core_work_relation`

工作关系。PARENT 表示 `from_id` 是 `to_id` 的父工作，每个工作最多一个父；PARENT 与 DEPENDS 不允许成环。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `from_id` / `to_id` | bigint | 关系两端 |
| `relation_type` | varchar(16) | PARENT / DEPENDS / RELATED / BLOCKS |
| `created_at` | timestamp | 创建时间 |

## 异常与整改

### `core_issue`

异常／问题／缺陷／整改闭环外壳，只含通用字段；专业字段在 Pack 扩展对象中且一一关联。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `code` | varchar(32) | 编号 `IS<日期><序号>` |
| `issue_type` | varchar(32) | ABNORMAL、DEFECT、NONCONFORMITY、DCS_ALARM 等 |
| `title` / `description` | varchar / text | 标题、描述 |
| `severity` | varchar(8) | LOW / MEDIUM / HIGH / CRITICAL |
| `status` | varchar(20) | 状态，见下方状态机 |
| `org_unit_id` | bigint | 所属组织 |
| `constraint_profile` | varchar(64) | 登记时生效的约束 Profile |
| `source_type` | varchar(32) | MANUAL、DCS 等 |
| `source_key` | varchar(200) | 来源去重键（唯一）；同一键再次到达只累计次数 |
| `occurrence_count` / `last_occurred_at` | int / timestamp | 发生次数与最近发生时间 |
| `reporter_party` / `owner_party` | bigint | 登记人、整改负责人 |
| `due_at` | timestamp | 期限 |
| `verified_by` / `verify_conclusion` / `verified_at` | bigint / varchar / timestamp | 验证人、结论与时间 |
| `ext_concept` / `ext_id` | varchar / bigint | Pack 扩展对象（唯一） |
| `created_at` / `updated_at` | timestamp | 创建、更新时间 |
| `version` | int | 乐观锁版本 |

状态机：`DRAFT → OPEN → IN_RECTIFICATION → PENDING_VERIFY → CLOSED`，验证不通过回到 IN_RECTIFICATION；另有 REJECTED、CANCELLED。所有整改工作完成（或取消）前不能进入 PENDING_VERIFY。

### `core_issue_link`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `issue_id` | bigint | Issue |
| `object_type` / `object_id` | varchar | 关联对象（WORK、ASSET、QUALITY_RECORD、OBSERVATION、外部对象…） |
| `link_role` | varchar(16) | RELATED / SOURCE / RECTIFICATION（由该 Issue 生成的整改工作）/ EVIDENCE |
| `created_at` | timestamp | 关联时间 |

## 观察、附件与审计

### `core_observation`

某时刻对某对象的事实观察（人工读数、DCS 降采样快照、外部数据）。不存高频原始波形。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `object_type` / `object_id` | varchar | 被观察对象 |
| `item_code` | varchar(64) | 观察项 |
| `value_num` / `value_text` | numeric / varchar(500) | 数值或文本值（至少一个） |
| `unit` | varchar(32) | 单位 |
| `observed_at` | timestamp | 观察时间 |
| `source` | varchar(16) | MANUAL / DCS / EXTERNAL |
| `quality` | varchar(8) | GOOD / SUSPECT / BAD |
| `org_unit_id` | bigint | 所属组织 |
| `recorded_by` | bigint | 记录人 |
| `created_at` | timestamp | 记录时间 |

### `core_document_link`

对象存储文件与业务对象的关联。下载必须经受控下载：校验数据域与密级，放行和拒绝都写审计。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `oss_id` | bigint | `sys_oss.oss_id` |
| `object_type` / `object_id` | varchar | 业务对象 |
| `purpose` | varchar(32) | 用途（ATTACHMENT、EVIDENCE、SPEC…） |
| `classification` | varchar(16) | PUBLIC / INTERNAL / CONFIDENTIAL / SECRET |
| `doc_version` | int | 同一对象再次关联同一文件时加一 |
| `file_name` | varchar(255) | 原始文件名 |
| `org_unit_id` | bigint | 数据域 |
| `created_by` | bigint | 上传人 |
| `created_at` / `removed_at` | timestamp | 关联、移除时间 |

### `core_summary`

带引用的摘要（ls.core.Summary）。由 Agent 或人员按上下文草拟，每个要点至少引用一条上下文中的记录；有确认权限的真人发布或作废（发布永不暴露给 Agent）。摘要不回写被摘要的对象。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `kind` | varchar(32) | 摘要类型（ISSUE、SHIFT_HANDOVER、DCS_ALARM…，由上下文提供者注册） |
| `subject_concept` / `subject_id` | varchar | 被摘要的对象 |
| `title` | varchar(200) | 标题 |
| `content` | text | 正文 |
| `points` | jsonb | 要点：`[{text, refs:[{type, id, label}]}]` |
| `citations` | jsonb | 全部引用（去重） |
| `status` | varchar(16) | DRAFT / PUBLISHED / DISCARDED |
| `org_unit_id` | bigint | 数据域（取被摘要对象的组织） |
| `source` | varchar(16) | HUMAN / AGENT |
| `model` | varchar(120) | 草拟所用模型 |
| `created_by_party` / `on_behalf_of` | bigint | 草拟主体；Agent 草拟时为 Agent 主体与被代理的人 |
| `published_by` / `published_at` | bigint / timestamp | 发布人与时间 |
| `discard_reason` | varchar(500) | 作废原因 |
| `created_at` / `updated_at` | timestamp | 创建、修改时间 |
| `version` | int | 乐观锁 |

### `core_knowledge_document`

知识库文档（ls.core.KnowledgeDocument）。可见性：组织为空表示全公司；否则本组织、下级与上级组织成员可见；密级不得高于调用者的密级许可。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `code` | varchar(64) | 编码（唯一） |
| `title` | varchar(200) | 标题 |
| `category` | varchar(32) | 分类（SOP…） |
| `org_unit_id` | bigint | 所属组织，空为全公司 |
| `classification` | varchar(16) | PUBLIC / INTERNAL / CONFIDENTIAL / SECRET |
| `published_version` | int | 当前发布版本（问答只引用该版本） |
| `created_by` | bigint | 创建人 |
| `created_at` / `updated_at` | timestamp | 创建、修改时间 |

### `core_knowledge_version`

文档版本。每个文档最多一个修订稿（DRAFT）；发布时旧发布版本改为 SUPERSEDED。修订稿不入索引、不可检索。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `document_id` | bigint | 文档 |
| `version` | int | 版本号 |
| `content` | text | 正文（# 开头为小节标题） |
| `change_note` | varchar(500) | 修订说明 |
| `status` | varchar(16) | DRAFT / PUBLISHED / SUPERSEDED |
| `created_by` / `created_at` | bigint / timestamp | 编写人与时间 |
| `published_by` / `published_at` | bigint / timestamp | 发布人（真人）与时间 |

### `core_knowledge_chunk`

已发布版本的分块与向量（pgvector）。冗余文档的组织与密级，检索 SQL 先按权限与"当前发布版本"过滤再按相似度排序；返回前再按文档当前状态复核。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `document_id` / `version` / `chunk_no` | bigint / int / int | 来源文档、版本与块序号 |
| `org_unit_id` / `classification` | bigint / varchar | 权限过滤字段（随文档同步） |
| `heading` | varchar(200) | 所在小节标题 |
| `text` | text | 分块正文 |
| `embed_model` | varchar(64) | 向量模型（检索只比较同一模型的向量） |
| `embedding` | vector | 向量 |
| `created_at` | timestamp | 入库时间 |

### `core_access_audit`

受控访问审计，只允许追加（数据库触发器禁止修改与删除）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `occurred_at` | timestamp | 发生时间 |
| `actor_party` | bigint | 访问人 |
| `action` | varchar(64) | 动作（DOCUMENT_DOWNLOAD…） |
| `object_type` / `object_id` | varchar | 访问对象 |
| `result` | varchar(8) | ALLOWED / DENIED |
| `reason` | varchar(500) | 拒绝原因 |
| `trace_id` | varchar(64) | 请求链路编号 |

## 领域事件与 Outbox

业务表保持当前事实；事件用于集成、审计和读模型，不用于重放重建状态。事件与 Outbox 与业务数据同事务写入，业务回滚时一并回滚。

### `core_domain_event`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `event_id` | uuid | 主键 |
| `type` | varchar(160) | 事件类型（通常为 Operation 编码） |
| `version` | int | 事件结构版本 |
| `occurred_at` | timestamp | 发生时间 |
| `actor_party` / `on_behalf_of` | bigint | 执行主体与被代理主体 |
| `subject_concept` / `subject_id` | varchar | 事件主体对象 |
| `org_unit_id` | bigint | 组织范围 |
| `trace_id` | varchar(64) | 请求链路编号 |
| `idempotency_key` | varchar(200) | 幂等键，消费方据此去重 |
| `payload` | jsonb | 事件内容（迁移的 from/to、原因、输入摘要等） |

### `core_outbox`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigserial | 主键 |
| `event_id` | uuid | 事件 |
| `destination` | varchar(64) | 订阅方（待办、通知、`external:erp`…） |
| `status` | varchar(12) | PENDING / DELIVERED / DEAD（进入人工补偿列表） |
| `attempts` | int | 已尝试次数 |
| `next_attempt_at` | timestamp | 下次投递时间（指数退避） |
| `last_error` | varchar(1000) | 最近一次失败原因 |
| `created_at` / `delivered_at` | timestamp | 入队、投递成功时间 |

### `core_outbox_archive`

已投递 Outbox 按保留策略归档的副本，字段同 `core_outbox`：`id`、`event_id`、`destination`、`status`、`attempts`、`next_attempt_at`、`last_error`、`created_at`、`delivered_at`。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` / `event_id` / `destination` / `status` / `attempts` | — | 同 `core_outbox` |
| `next_attempt_at` / `last_error` / `created_at` / `delivered_at` | — | 同 `core_outbox` |

### `core_consumer_receipt`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `consumer` | varchar(64) | 消费方 |
| `idempotency_key` | varchar(255) | 已处理的幂等键（与消费方联合主键） |
| `processed_at` | timestamp | 处理时间 |
