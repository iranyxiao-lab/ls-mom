# Design：LS-MOM 工业底座 MVP

## Context

- 仓库为空，只有 OpenSpec 骨架；不是 Git 仓库，本机无 `gh` CLI。动机见 proposal.md「Why」，行为要求见 `specs/` 下 25 个 capability。
- 以 v2.1 设计为唯一依据；《LS-IDF v0.1》中的 React、K8s、Kafka、湖仓、图库、自研 BPMN 等不进入 MVP。
- 已核实的上游事实（2026-11，Context7 查询 dromara/ruoyi-vue-plus）：
  - RuoYi-Vue-Plus 主线 6.x 已是 **Spring Boot 4.1＋JDK 21**，工作流模块 `ruoyi-workflow` 使用 **Warm-Flow**，不是 Flowable；
  - 框架为 MIT 许可，原生支持 PostgreSQL，模块结构 `ruoyi-admin／ruoyi-common／ruoyi-modules／ruoyi-extend／ruoyi-api`，默认开启多租户插件。
  - 这与 v2.1「Spring Boot 3＋Flowable 7」冲突，需在下文 D1、D2 中决策。

### 参考的 GitHub 开源项目

| 项目 | 许可 | 用途 |
| --- | --- | --- |
| dromara/RuoYi-Vue-Plus（5.X 分支）＋ plus-ui | MIT | 后端基座与前端管理台（用户、部门、角色、菜单、字典、日志、数据权限、OSS、代码生成） |
| flowable/flowable-engine 7.x | Apache-2.0 | 人工审批与协同任务引擎（嵌入式） |
| xaboy/form-create-designer（Vue3＋Element Plus 版） | MIT | 拖拽表单设计器编辑器，经适配层产出 LS FormSchema |
| didi/LogicFlow | Apache-2.0 | 流程只读预览与版本差异画布 |
| google/cel-java | Apache-2.0 | 后端受限 CEL 求值 |
| flyway/flyway（社区版） | Apache-2.0 | 核心与 Pack 迁移 |
| modelcontextprotocol/java-sdk | MIT | MCP 适配器 |
| pgvector/pgvector | PostgreSQL License | 知识库向量检索（同库扩展，不引入独立向量库） |
| frappe/frappe | MIT | 仅作构建期转换样例来源；**不使用 ERPNext（GPL-3）资产** |
| TNG/ArchUnit、testcontainers-java、CycloneDX | Apache-2.0 | 架构测试、集成测试、SBOM |

## Goals / Non-Goals

**Goals:**
- 用最少的自研代码满足 specs：自研范围限定为工业语义、表单／流程契约、台账运行时、Pack 治理、Operation 与 AI 安全边界。
- 上游 RuoYi 代码保持可合并：LS 代码放在独立模块，对上游文件的修改集中且有清单。
- 每个切片（A～F）都能独立演示并有自动化验收测试对应 spec 场景。

**Non-Goals:**
- 不做集群化、K8s 部署、多租户 SaaS；不做原生移动 App（移动填报为响应式 H5）。
- 不自研 UI 组件库、ORM、BPMN 引擎、表达式编译器、认证体系。
- 不追随 RuoYi 6.x／Spring Boot 4 升级（留待 MVP 后单独评估）。

## Decisions

### D1 基座：RuoYi-Vue-Plus 5.X 分支，而非 6.x
- 选 5.X 最新稳定 tag（Spring Boot 3.x、JDK 21），满足 v2.1 的 Spring Boot 3 基线，并与 Flowable 7（要求 Spring Boot 3）兼容。
- 关闭多租户（`tenant.enable=false`），符合"单客户单库"。
- 以"上游快照导入＋`upstream` 远程"方式引入：首个提交为未修改的上游代码，第二个提交为 LS 改造，`docs/upstream-patches.md` 记录所有对上游文件的修改。
- **备选**：6.x＋Spring Boot 4 — Flowable 7 尚不支持 Boot 4，且偏离设计基线，放弃；从零搭 Spring Boot — 重做用户/权限/字典，违背"不自研通用认证"，放弃。

### D2 流程引擎：移除 Warm-Flow，嵌入 Flowable 7
- 从构建中移除 `ruoyi-workflow` 模块及其前端页面、菜单种子，避免双流程引擎。
- Flowable 以嵌入式 `flowable-spring-boot-starter-process` 方式运行，表放在独立 schema `flowable`（同一 PostgreSQL 实例），不引入 Flowable UI/REST 应用。
- 禁止 `scriptTask`、`camunda/flowable:class`、`delegateExpression`、`expression` 访问 Spring Bean：部署前由 `BpmnSafetyScanner` 静态扫描，且引擎配置关闭脚本引擎、`expressionManager` 只暴露白名单变量。
- 所有服务节点统一使用一个受控的 `OperationServiceTask`，其唯一参数是已发布 Operation 编码。
- **备选**：保留 Warm-Flow — 设计明确选 Flowable 且需要标准 BPMN，放弃。

### D3 业务状态机：表驱动自研 StateMachineExecutor
- `meta_flow` 中声明每个 Concept 的状态、迁移（from、to、operation、guards）。执行器只做"校验当前状态→执行守卫→更新 `status`＋`version`→写事件"，约 300 行级别。
- 与 Flowable 的衔接：`FlowableTaskListener` 只把"任务完成"翻译成 `onApproved／onRejected` 等命名 Operation 调用（带 `idempotency_key = procInstId:taskId:event`），由 Operation Gateway 推进状态。
- **备选**：Spring Statemachine — 持久化模型重、与 Operation 审计重复，放弃。

### D4 模块布局
```
ls-mom/                         Git 仓库根
├─ ruoyi-*/                     上游 RuoYi 5.X（尽量不改）
├─ ls-mom-modules/
│  ├─ ls-mom-core   ls-mom-meta  ls-mom-crud   ls-mom-form
│  ├─ ls-mom-flow   ls-mom-action ls-mom-pack  ls-mom-ai
│  ├─ ls-mom-dcs    ls-mom-mcp
│  └─ ls-mom-biz/   pack-production pack-asset pack-quality pack-training pack-consultation
├─ ls-mom-cli/                  构建期工具：LS-IR 校验/生成、Frappe 转换、Pack 打包
├─ ls-mom-dcs-simulator/        DCS/Historian 模拟器（MVP 演示与测试）
├─ plus-ui/                     前端（上游 plus-ui＋LS 页面）
├─ packs/                       Pack 源资产（yaml/json/sql/tests）
├─ deploy/                      docker-compose、种子、备份恢复脚本
└─ openspec/
```
- 依赖方向由 ArchUnit 测试强制（spec `platform/project-baseline`）。`ls-mom-mcp`、`ls-mom-ai` 只依赖 `ls-mom-action` 的 API 包。

### D5 Operation Gateway
- `OperationHandler<I,O>` 接口＋`@LsOperation(code=..., risk=C1, exposure=DRAFT)` 注解；启动时与 `meta_operation` 对账，代码注解与元数据不一致则启动失败。
- 通用状态迁移类 Operation（如 `submit`、`approve`）无需写 Java：由 `meta_flow` 迁移声明自动注册为 `TransitionOperationHandler`。
- 管道：认证→功能权限（Sa-Token `perms`）→数据域→输入 JSON Schema→字段策略→守卫链（L1/L2/L3）→状态机→写入→事件＋Outbox，全部在一个 `@Transactional` 内。
- 幂等表 `op_idempotency(key, op_code, result_hash, response, expires_at)` 唯一索引；审计表 `op_audit` 追加写，普通管理员无删除权限。
- 对外：`POST /api/op/{code}`；OpenAPI 由 springdoc＋运行时注册的 Schema 生成。

### D6 通用台账：DDL 生成器＋受限 SQL 构造
- Concept 发布时 `DdlPlanner` 比较新旧定义生成迁移 SQL，写入 ChangeSet；新增列/表自动通过，改类型/删列/改名标记 `BREAKING` 需审批。DDL 只由 Pack 安装器经 Flyway 执行，设计器无 DDL 权限。
- 运行时查询用 MyBatis `@SelectProvider`＋自写 `SafeSqlBuilder`：表名、列名只能来自 `meta_concept` 已发布定义并经标识符白名单校验，所有值以 `#{}` 参数绑定；不引入 jOOQ 等第二 ORM。
- 数据权限复用 RuoYi `@DataPermission` 的 SQL 拼接思路，但在 `SafeSqlBuilder` 中以 `org_id` 条件注入，确保动态表同样生效。
- 导入导出复用 RuoYi Excel 能力；异步导出走 Outbox Worker，产物存 MinIO 并经受控下载。

### D7 表单：自有 LS FormSchema＋自有渲染器＋form-create 设计器适配
- **渲染器自研**（`SchemaForm`／`SchemaTable`／`SchemaDetail`，基于 Element Plus）：运行时、移动端、打印、流程节点、设计器预览都用同一渲染器，保证"预览即运行"。
- **设计器借用** form-create-designer：通过 `fc-adapter`（`toFcRule(lsSchema)`／`fromFcRule(rule)`）双向转换；无法表达的属性（节点策略、Operation 绑定、Concept 引用）放在适配器扩展面板中，存储只保存 LS FormSchema。冻结 form-create 版本，fork 到 `plus-ui/src/vendor/` 下不改核心。
- 字段策略合并算法 `resolveFieldPolicy(form, nodePolicy)` 以同一份 JSON 测试用例集在 Java 与 TS 两侧实现并交叉测试。
- **备选**：直接用 form-create 运行时渲染 — 产品契约会被其私有 rule 格式绑定，放弃；从零写设计器 — 工作量过大，放弃。

### D8 表达式：CEL 子集双端实现
- 后端 cel-java，自定义 `Env` 只声明 `$model`、`$context` 与白名单函数，开启成本上限；前端选用一个维护中的 TS CEL 实现（任务 1 中做许可证与一致性评估），失败时降级为"前端只做提示、后端裁决"。
- 共享 `packs/_shared/cel-conformance/*.json` 一致性用例集，CI 两端同跑。

### D9 流程设计器：审批流树形配置器＋LogicFlow 预览
- 采用国内常见的"审批流树"交互（节点卡片＋条件分支＋并行），比通用 BPMN 画布更适合实施人员，且天然限制节点类型。
- `meta_flow` JSON → `BpmnTemplateGenerator`（Java，固定模板）→ BPMN XML → 扫描 → 部署；LogicFlow 只用于只读预览与差异高亮。
- 办理人策略编码格式 `user:<id>`／`role:<code>@<scope>`／`dept:<id>`／`team:<id>`／`field:<key>`／`leader:<level>`，由 `AssigneeResolver` 解析；不支持 SpEL。

### D10 待办读模型、版本与对账
- 表：`biz_flow_instance`（biz_type、biz_id、proc_inst_id、flow_version、form_version、sync_status）、`biz_task`（字段见 spec）、`biz_flow_callback_log`。
- Flowable 全局事件监听器在同事务内写 `biz_task`；回调失败时 `sync_status=PENDING_RETRY` 由 Worker 重试。
- `FlowReconcileJob` 每 10 分钟比对 `ACT_RU_TASK` 与 `biz_task`、业务状态与流程状态，输出差异表并支持重建读模型。
- 版本：流程以 `flowKey` 部署，Flowable 自带版本号映射到 `meta_flow.version`；表单版本由 `meta_view` 维护；业务记录保存三元版本。

### D11 Pack 格式与安装
```
packs/<pack-code>/
  pack.yaml                 编码、版本、依赖、负责人、复审日期
  concepts/*.yaml  views/*.json  flows/*.json  operations/*.yaml
  rules/*.yaml  metrics/*.yaml  migrations/V<n>__*.sql  seed/*.yaml
  tests/scenarios/*.yaml    黄金场景数据集
```
- 打包为 zip（含 sha256 清单）；安装器按依赖拓扑执行，每个 Pack 使用独立 Flyway 历史表 `flyway_history_<pack>`，避免版本号冲突。
- 漂移检测：比较运行库中 meta_* 定义的规范化哈希与安装清单哈希；差异可导出为 ChangeSet。
- 含 Java 代码的标准 Pack（守卫插件、专用 Handler）随应用一起编译发布；纯元数据的客户 Pack 可运行时安装。不支持运行时热部署 Java。

### D12 DCS：独立时序存储＋模拟器
- Compose 可选 profile `dcs` 启动 TimescaleDB 实例（独立容器，非业务库）＋`ls-mom-dcs-simulator`（生成点位值、报警、断网、乱序、时钟偏差场景）。
- `ls-mom-dcs` 只读访问时序库，定时降采样写 `dcs_telemetry_snapshot`；报警以 `dedup_key` 唯一索引 upsert。真实客户接入时替换为现场 Historian 适配器。

### D13 AI：OpenAI 兼容端点＋本地 LS-IR 工具链
- LLM 通过 OpenAI 兼容 HTTP 接口调用（可配置私有化 vLLM/Qwen 等），封装在 `ls-mom-ai` 的 `LlmGateway`（超时、配额、日志脱敏、模型版本记录）。
- LS-IR 以 JSON Schema 定义（`ls-mom-cli/schema/ls-ir-1.0.json`），`ls-mom-cli generate` 生成 Pack 目录与 JUnit 测试；`ls-mom-cli frappe-import` 只读 DocType/Workflow JSON，遇 `.py`／Server Script 标记 `MANUAL_REQUIRED`。
- 知识库：PostgreSQL＋pgvector，文档分块带 `doc_id／version／org_scope／classification`，检索 SQL 中先做权限过滤再做向量相似度，返回前再按权限复核。
- MCP：`ls-mom-mcp` 用 MCP Java SDK 暴露 Streamable HTTP，工具清单由 `meta_operation.exposure ∈ {QUERY,DRAFT,SIMULATE,APPROVAL_REQUIRED}` 生成，SIMULATE 通过事务强制回滚实现。

### D14 GitHub 托管与 CI
- `git init`，提交顺序：OpenSpec 规划 → 上游 RuoYi 快照 → LS 改造；推送到用户 GitHub 账号下私有仓库 `ls-mom`（实施时由用户执行登录或提供 PAT，不写入仓库）。
- GitHub Actions：`backend.yml`（Maven 构建、单测、ArchUnit、Testcontainers 集成测试）、`frontend.yml`（pnpm build、vitest 无头）、`security.yml`（gitleaks、许可证扫描、CycloneDX SBOM）。分支保护：`main` 需 CI 通过。
- README 中保留 RuoYi-Vue-Plus MIT 版权声明与致谢。

## Risks / Trade-offs

- [RuoYi 5.X 维护期缩短，社区重心转 6.x] → 记录上游补丁清单，LS 代码与上游解耦；MVP 后单独评估升级到 Boot 4＋Flowable 新版。
- [移除 Warm-Flow 后上游合并冲突] → 移除动作集中在 pom 与菜单种子，记录在 `docs/upstream-patches.md`。
- [form-create 设计器停更或格式变化] → 适配层隔离＋冻结版本＋往返一致性测试；最坏情况只需替换编辑器，不影响存储与运行时。
- [前端 CEL 实现与 cel-java 语义不一致] → 一致性用例集双端跑；不一致时前端降级为提示，后端始终裁决。
- [动态 SQL 构造引入注入面] → 标识符只来自元数据白名单、值全部参数化；专门的注入测试集（spec CRUD-03）进入 CI。
- [Flowable 表达式/脚本能力被滥用] → 引擎侧关闭脚本、限制表达式上下文＋部署前静态扫描双保险。
- [范围大（12～16 周）导致切片相互阻塞] → tasks 按 P0～P5 阶段排列，每阶段末有可演示验收；P2 与 P3 可部分并行。
- [LLM 生成伪等价代码] → 产物只进 ChangeSet，必须通过场景、越权与升级测试和人工评审。
- [时序库增加运维组件] → 仅在 `dcs` profile 启用；客户已有 Historian 时直接对接，不部署 TimescaleDB。

## Migration Plan

全新系统，无存量数据迁移。部署与回滚：
1. `deploy/` 下 `docker compose up -d` 启动；应用启动时执行核心 Flyway 迁移，再由 Pack 安装器按依赖安装标准 Pack 与演示种子。
2. 每次发布前执行 `deploy/scripts/backup.sh`（pg_dump 业务库与 `flowable` schema＋MinIO 镜像＋Pack 清单），并在临时环境用 `restore.sh` 演练升级。
3. 回滚：非破坏性 ChangeSet 可停用 Pack 或回退到上一 Pack 版本的定义；破坏性迁移只支持"备份恢复＋前向修复"，不提供自动 down 迁移。

## Open Questions

- RuoYi-Vue-Plus 5.X 具体 tag 与 Flowable 7 小版本：实施第一步按"最新稳定且 Boot 3.x"选定并记录，不影响方案。
- 运行期默认 LLM 模型与私有化端点地址：由部署配置提供，演示环境可用任意 OpenAI 兼容端点。
- 首个真实客户的 DCS 协议（OPC UA／厂商 Historian API）：MVP 以模拟器验收，真实适配器在客户项目中补充。
