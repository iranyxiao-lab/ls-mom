# Tasks

> 按 v2.1 §14.1 阶段排列：1～3 为 P0，4～5 为 P1，6～8 为 P2，9～10 为 P3，11～15 为 P4，16～18 为 P5，19 为跨切片集成验收。每组自带测试与文档。

## 1. 仓库初始化与 GitHub 托管（P0）

- [x] 1.1 在项目根执行 `git init`，添加 `.gitignore`（Java/Node/IDE/.env/数据卷）与 `.gitattributes`，提交现有 OpenSpec 规划；验证 `git log` 有首个提交且 `git status` 干净
- [x] 1.2 选定 RuoYi-Vue-Plus 5.X 最新 Spring Boot 3.x 稳定 tag 与对应 plus-ui 版本，记录到 `docs/upstream-patches.md`；以未修改快照单独提交并添加 `upstream` 远程；验证 `mvn -q -DskipTests package` 在快照上成功
- [x] 1.3 编写 README（产品定位、模块图、快速启动、RuoYi MIT 版权与致谢）和 `LICENSE`／`NOTICE`；验证 README 中的快速启动命令在后续 3.x 完成后可照做
- [ ] 1.4 由用户完成 GitHub 登录（安装 `gh` 并 `gh auth login`，或提供 PAT 由用户自行配置凭据助手，令牌不写入仓库），创建私有仓库 `ls-mom` 并推送 `main`；验证 `git ls-remote origin main` 返回与本地相同的提交
- [ ] 1.5 配置 `main` 分支保护（需 CI 通过、禁止强推）；验证对 `main` 强推被拒绝

## 2. 工程改造与模块骨架（P0）

- [x] 2.1 关闭多租户、切换默认数据源为 PostgreSQL 16，移除 `ruoyi-workflow`（Warm-Flow）模块、前端页面与菜单种子并记录补丁；验证应用启动后无 Warm-Flow 表与菜单
- [x] 2.2 创建 `ls-mom-modules` 下 core/meta/crud/form/flow/action/pack/ai/dcs/mcp 与 `ls-mom-biz` 各 Pack 子模块的空骨架，接入 `ruoyi-admin`；验证 `mvn package` 成功
- [x] 2.3 编写 ArchUnit 测试强制 D4 依赖方向（core 不依赖 Pack、ai/mcp 不触达 Mapper、crud/form/flow 不依赖 biz）；验证人为加入违规引用时测试失败
- [x] 2.4 引入 Flyway：核心迁移目录 `db/migration/core`，启动时结构校验（漂移在生产 profile 拒绝启动）；验证手工 `ALTER TABLE` 后 prod profile 启动失败
- [x] 2.5 实现全链路 `trace_id`（请求过滤器生成/透传、MDC、异步与 Outbox 传播）；验证集成测试中请求日志、审计、Outbox 记录的 trace_id 一致

## 3. 部署环境与 CI（P0）

- [x] 3.1 编写 `deploy/docker-compose.yml`（app、postgres:16 含 pgvector、redis、minio，`flowable` 独立 schema，可选 profile `dcs`）与健康检查；验证全新机器 `docker compose up -d` 后所有服务 healthy
- [x] 3.2 编写幂等种子（演示组织、管理员、角色、班次、工作类型），重复执行不产生重复数据；验证连续执行两次种子命令后记录数不变
- [ ] 3.3 编写 GitHub Actions：backend（构建、单测、ArchUnit、Testcontainers）、frontend（pnpm build、`vitest --browser.headless`）、security（gitleaks、许可证扫描拒绝 GPL/AGPL、CycloneDX SBOM）；验证提交含伪造密钥的分支时 CI 失败
- [x] 3.4 编写 `deploy/scripts/backup.sh` 与 `restore.sh`（业务库＋flowable schema＋对象存储＋Pack 清单）；验证在空环境恢复后可登录并读取附件

## 4. 稳定核心：主体、组织与工作（P1）

- [x] 4.1 实现 `core_party`、`core_org_unit`（类型、层级、有效期）及与 RuoYi 用户/部门的映射，用户创建自动生成 Party；验证 spec `core/party-org` 全部场景的集成测试通过
- [x] 4.2 实现负责人解析（部门/车间/上级）并在缺失时返回"无人可办"；验证负责人缺失场景测试
- [x] 4.3 实现 `core_work_item`、`core_work_assignment`、`core_work_report`、`core_work_relation` 表、实体与服务（不含任何 BOM/库存字段）；验证 DOM-01「无制造主数据创建」测试
- [x] 4.4 实现约束 Profile（宽松/严格，冲突提示/阻断、产出单位必填等开关），启用后不影响历史记录；验证 DOM-02 测试
- [x] 4.5 实现工作关系成环检测与父子拆分；验证成环拒绝测试
- [x] 4.6 实现 `core_issue` 外壳、多对象关联、整改工作生成与"整改未完成不得提交验证"规则、来源去重键；验证 spec `core/issue-management` 全部场景
- [x] 4.7 实现 `core_observation`、`core_document_link`（对象存储 受控下载、密级、数据域）；验证越权下载被拒绝并审计
- [x] 4.8 编写 `docs/core-model.md` 描述核心对象与状态；验证文档字段与迁移脚本一致（脚本自动比对）

## 5. 元数据内核与 Operation Gateway（P1）

- [x] 5.1 建立 `meta_concept/meta_view/meta_operation/meta_flow/meta_rule/meta_metric/meta_pack/meta_change_set` 表与 Java 模型，核心对象启动时自注册；验证启动后可查询 `ls.core.Work` 且标记为核心显式表
- [x] 5.2 实现编码不可变/不可复用、字段 DEPRECATED 流程、字段使用方反查、`ls.core` 命名空间污染检测；验证 spec `meta/concept-registry` 全部场景
- [x] 5.3 实现 `@LsOperation`＋`OperationHandler` 注册与 meta 对账（不一致启动失败）、`POST /api/op/{code}` 管道（权限→数据域→Schema→字段策略→守卫→状态机→写入→事件，单事务）；验证守卫失败整体回滚测试
- [x] 5.4 实现表驱动 `StateMachineExecutor` 与 `TransitionOperationHandler`，WorkItem/Issue 状态全部改由 Operation 推进；验证非法迁移返回允许动作列表
- [x] 5.5 实现幂等表、乐观锁版本校验、`op_audit` 追加写（含失败记录、代理主体）；验证重复提交只产生一次事件、并发编辑冲突、失败审计三项测试
- [x] 5.6 实现 C0/C1/C2 风险分级：C1/C2 状态字段禁止通用更新；验证通用接口改 WorkItem 状态被拒绝
- [x] 5.7 实现 DomainEvent＋Outbox 同事务写入、`SKIP LOCKED` 投递 Worker、退避重试、补偿列表与手工重投；验证业务回滚无 Outbox、下游超时进入补偿列表
- [x] 5.8 实现外部写入"待同步"状态与补偿（以模拟 ERP 端点验证）；验证外部失败时填报仍为已提交且出现在补偿列表
- [x] 5.9 由注册表生成 OpenAPI 3.1；验证新 Operation 出现在 `/v3/api-docs` 且 Schema 一致
- [x] 5.10 编写 `docs/operation-gateway.md`（契约格式、风险分级、新增 Operation 步骤）；验证按文档新增一个示例 Operation 可调用

## 6. 规则与守卫（P2）

- [x] 6.1 基于 cel-java 实现受限 CEL 环境（仅 `$model`/`$context`/白名单函数、成本上限）与发布期静态校验；验证非法函数与超限表达式测试
- [x] 6.2 评估并接入前端 TS CEL 实现（许可证、维护状态），建立 `packs/_shared/cel-conformance` 一致性用例集并在前后端 CI 同跑；验证两端结果一致（不满足则启用"前端仅提示"降级并记录）
- [x] 6.3 实现决策表（首个命中/全部命中）与 L0～L4 约束级别标注、L0 提示返回；验证风险定级与 L0 提示测试
- [x] 6.4 实现代码守卫插件 SPI 与示例双签互斥守卫，以及"客户规则只能加严"发布校验；验证双签互斥与放宽拒绝测试

## 7. 通用台账 CRUD（P2）

- [x] 7.1 实现 `DdlPlanner`（主表/子表/保留字段/索引生成，BREAKING 变更识别）并输出 Pack 迁移脚本；验证发布借还单 Concept 后生成主子表与迁移记录
- [x] 7.2 实现 `SafeSqlBuilder`（标识符白名单、参数绑定、数据域注入）与 QuerySpec 解析；验证 CRUD-03 注入测试集（排序注入、非白名单字段、联表越权）全部拒绝
- [x] 7.3 实现 `GenericCrudController`：主子表单事务保存、详情、复制、逻辑删除、字典翻译、引用展开、草稿态限制；验证子表行错误定位与已提交记录禁止通用编辑
- [x] 7.4 实现服务端字段/行级权限与 Schema 校验在通用接口中强制执行；验证 CRUD-02 绕过前端测试
- [x] 7.5 实现 Excel 导入预检（错误行报告、部分导入）与异步导出（条数上限、对象存储、审计）；验证 CRUD-04 测试
- [x] 7.6 实现防重复提交、字段级变更时间轴、Agent 身份禁止访问通用接口；验证双击保存只写一次、Agent 调用被拒
- [x] 7.7 前端实现 `SchemaTable`（ListSchema 驱动的列、筛选、排序、行/批量操作绑定 Operation、导出）；验证组件单测与无头 vitest 通过

## 8. 表单 Schema、渲染器与设计器（P2）

- [x] 8.1 定义 LS FormSchema/ListSchema/DetailSchema/PrintSchema/MobileSchema 的 JSON Schema 与 Java/TS 类型；验证示例 Schema 通过校验、缺失字段绑定被拒
- [x] 8.2 实现后端按冻结版本的服务端校验（类型、必填、范围、正则、字典、引用过滤、CEL 规则）与动作只绑定已发布 Operation 校验；验证引用越过滤与绑定草稿 Operation 两个场景
- [x] 8.3 实现字段策略合并算法 Java/TS 双实现与共享用例集；验证 `readonly:["*"]`＋显式 editable 用例两端一致
- [x] 8.4 实现前端 `SchemaForm`/`SchemaDetail`/打印/移动视图渲染器及 MVP 全部控件（含用户/部门/班组/设备/工作项/物料/点位/通用引用、子表、附件、计算字段）；验证控件单测与无头 vitest 通过
- [x] 8.5 引入并冻结 form-create-designer（Vue3/Element Plus），实现 `fc-adapter` 双向转换与扩展面板（Concept 绑定、Operation 动作、节点策略），记录单向边界；验证 LS→FC→LS 往返等价测试
- [x] 8.6 实现设计器工作区：Schema 差异区、桌面/移动/详情/打印预览（使用运行时渲染器）、按角色与节点的权限模拟、≥50 步撤销重做；验证 Playwright 无头 E2E 拖拽主子表并发布
- [x] 8.7 实现组件注册表（版本、输入输出、权限、兼容版本）并拒绝任何脚本内容；验证 FORM-08 粘贴脚本无法发布
- [x] 8.8 实现表单版本不可变、历史按旧版只读渲染、编辑旧草稿的迁移策略、发布时与 Concept/Flow/Operation/Rule/打印模板的兼容性校验；验证 FORM-07 与节点策略引用被删字段场景
- [x] 8.9 性能验证：200 字段表单拖拽响应 ≤300ms（无头浏览器性能测试）
- [x] 8.10 编写 `docs/form-schema.md` 与 `docs/form-designer-adapter.md`；验证文档中的示例 Schema 可直接被校验器接受

## 9. Flowable 协同引擎（P3）

- [x] 9.1 集成 Flowable 7 嵌入式引擎（`flowable` schema、关闭脚本引擎、受限表达式上下文）与 `OperationServiceTask`；验证引擎表仅出现在 `flowable` schema
- [x] 9.2 实现 `biz_flow_instance`、`biz_task`、`biz_flow_callback_log` 与全局事件监听器同步待办；验证任务完成后读模型状态更新
- [x] 9.3 实现回调 → 命名 Operation（幂等键 `procInstId:taskId:event`）与启动/完成失败的 `PENDING_RETRY` 重试；验证重复回调只迁移一次（FLOW-07）
- [x] 9.4 实现 `AssigneeResolver` 全部命名策略与"无人可办"异常待办；验证离职候选人场景
- [x] 9.5 实现提交、通过、退回（发起人/指定节点）、再提交、确认、归档、撤回（经业务 Operation 判定）、转办、催办（频率限制）、抄送；验证 FLOW-01、FLOW-03 测试
- [x] 9.6 实现或签/会签/条件分支/并行模板；验证 FLOW-02 或签与会签测试
- [x] 9.7 实现节点字段策略服务端强制与任务办理人双重校验；验证 FLOW-05 越权改字段与非办理人办理被拒
- [x] 9.8 实现超时提醒与升级（Flowable 定时边界事件→Operation）；验证超时升级测试（时钟可注入）
- [x] 9.9 实现流程/表单版本冻结与在途实例保持旧版本；验证 FLOW-06
- [x] 9.10 实现受控审批模式的批准证据（批准人、时间、输入摘要、数据版本、实例）与失效判定守卫；验证批准后数据被改须重审
- [x] 9.11 实现 `FlowReconcileJob` 对账与读模型重建；验证人为删除 `biz_task` 行后对账可重建
- [x] 9.12 编写 `docs/flow-integration.md`（单一业务状态原则、回调约定、对账）；验证文档与实现的事件名一致

## 10. 流程设计器（P3）

- [x] 10.1 实现 `meta_flow` 流程配置模型与 `BpmnTemplateGenerator`（固定模板，生成 BPMN 2.0）；验证生成 XML 可部署且节点与配置一致
- [x] 10.2 实现 `BpmnSafetyScanner`（拒绝 scriptTask、class/delegateExpression、任意 expression、外部 URL），覆盖设计器产物与代码库流程；验证 FLOW-08 含脚本任务部署被拒
- [x] 10.3 实现发布校验：节点表单引用、策略字段存在、办理人可解析、`onApproved/onRejected` 必填；验证缺少回调与角色无成员两个场景
- [x] 10.4 前端实现审批流树形配置器（节点卡片、条件、并行、或签/会签、服务节点仅选已发布 Operation、节点表单策略编辑），不提供 XML 导入；验证 Playwright 无头 E2E 配置并发布一条流程
- [x] 10.5 实现 LogicFlow 只读预览与版本差异高亮；验证 v1/v2 差异显示新增会签节点

## 11. 协同工作中心与指标（P4）

- [x] 11.1 实现 `meta_metric` 注册、口径版本、重复口径检测与命名 Query（参数 Schema、超时、数据域、测试）及 `/api/q/{queryCode}`；验证 spec `metric/metrics-query` 全部场景
- [x] 11.2 实现看板组件只绑定 Metric/命名 Query 并支持钻取；验证看板含 SQL 发布被拒、钻取返回明细
- [x] 11.3 前端实现协同工作中心（待办/已办/发起/抄送/委托、超时排序、在待办中办理、时间轴、版本快照）与消息中心分离；验证"已读不等于完成"E2E
- [x] 11.4 前端实现协同应用中心（Pack 版本、依赖、负责人、复审、使用量、失败任务、漂移与下线状态）；验证页面数据与 `meta_pack` 一致

## 12. Pack 生命周期（P4）

- [x] 12.1 定义 `pack.yaml` 与 Pack 目录格式 JSON Schema，实现 zip 打包（sha256 清单）；验证缺少负责人的 Pack 被拒
- [x] 12.2 实现安装器：依赖拓扑、循环检测、独立 Flyway 历史表、种子、启用/停用（数据保留、被依赖不可停用）；验证循环依赖与停用培训 Pack 场景
- [x] 12.3 实现 ChangeSet 流水线：校验与依赖分析→沙箱安装→回归/越权/升级测试→人工批准→发布；验证未批准发布被拒
- [x] 12.4 实现配置漂移检测（规范化哈希）与差异回灌为 ChangeSet；验证手工改表单标签后漂移报告与回灌
- [x] 12.5 实现使用量统计、复审到期与建议下线通知；验证超期未用 Pack 被标记
- [x] 12.6 实现升级演练命令（从上一版本备份恢复到临时环境并升级），接入发布门禁；验证人为制造失败迁移时门禁阻止发布
- [x] 12.7 编写 `docs/pack-authoring.md`；验证按文档从零创建一个最小客户 Pack 并安装成功

## 13. 轻生产 Pack 与生产运营工作台（P4）

- [x] 13.1 实现 ProductionRequest、ShiftPlan、ProductionOutput、MaterialRecord、ShiftHandover、ProductionContext 的 Concept、表单、Operation 与迁移（默认不核销）；验证投料无领用不报错
- [x] 13.2 实现需求转工作、班次安排冲突处理、交接班双方确认与未完成事项流转；验证交接班双方确认与草稿不可见场景
- [x] 13.3 前端实现生产需求板、工作板、分派台、移动填报（拍照证据、草稿）、交接班页面；验证 Playwright 无头移动视口 E2E 填报
- [x] 13.4 实现经营看板指标（工作量、按期完成率、异常数、产出、人工、设备状态）；验证钻取明细与指标数值一致
- [x] 13.5 编写切片 A 黄金场景数据集并自动化 DOM-01/DOM-02/DOM-03；验证场景测试通过

## 14. 设备、质量与通用台账样板（P4）

- [x] 14.1 实现设备 Pack：台账、状态历史、点检计划生成点检工作、点检异常一键生成缺陷草稿、缺陷 Issue＋扩展、维修 MAINTENANCE 工作与验证关闭、设备时间轴；验证 spec `domain/asset-management` 全部场景
- [x] 14.2 实现质量 Pack：质量记录多对象可选关联、不符合项条件必填、受控质量流程、整改证据要求、终判仅限真人；验证 DOM-04 与受控质量闭环 E2E
- [ ] 14.3 仅用配置交付"工器具借还"台账（主子表、设备/人员引用、附件、条件必填、列表筛选导入导出、提交→部门审核→退回→再提交→确认归档流程），发布后修改布局生成新版本；验证 CRUD-01 不写 Java、FORM-07 旧单按旧版渲染
- [ ] 14.4 编写切片 B/C/D（设备、质量部分）黄金场景数据集；验证场景测试通过

## 15. DCS 集成（P4）

- [ ] 15.1 实现 `ls-mom-dcs-simulator`（点位值、报警、断网、重复、乱序、坏质量位、时钟偏差）与 compose `dcs` profile 的 TimescaleDB；验证模拟器按配置输出各类场景
- [ ] 15.2 实现点位语义、点位-设备映射、只读时序访问与降采样快照（默认 1 分钟）；验证 24 小时 1Hz 推送后业务库只有分钟级快照
- [ ] 15.3 实现报警 `dedup_key` upsert、次数与最近发生时间、坏质量位排除、时钟异常标记、断网补传去重；验证 spec `dcs/dcs-integration` 健壮性场景
- [ ] 15.4 实现点位处置策略（自动草稿/仅提醒/忽略）与报警-人工缺陷关联建议；验证"先人工后报警"合并场景
- [ ] 15.5 实现 DCS 写入能力黑名单扫描并接入 CI；验证 Operation 与 MCP 工具清单中无写入类能力

## 16. 协同扩展 Pack（P5 前置，可与 P4 并行）

- [ ] 16.1 实现培训考试 Pack（课程、题库、组卷、限时作答自动交卷、判分、培训记录、资质查询公开 Operation、课程发布审批）；验证 spec `packs/training-exam` 全部场景
- [ ] 16.2 实现意见咨询 Pack（议题、匿名意见、答复、转 Issue、总结引用）；验证 spec `packs/consultation` 全部场景
- [ ] 16.3 验证 DOM-05：扫描核心 schema 与核心代码无培训/咨询概念（自动化脚本接入 CI），两 Pack 独立安装/升级/停用测试通过

## 17. LS-IR 与构建期生成（P5）

- [ ] 17.1 定义 `ls-ir-1.0.json` Schema 与 `ls-mom-cli validate`；验证缺少 provenance 的 LS-IR 被拒
- [ ] 17.2 实现 `ls-mom-cli generate`：LS-IR → Pack 目录（Concept、Form、Flow、Operation 骨架、迁移、权限、JUnit 测试）并打包 ChangeSet；验证 AI-02 生成物编译、安装、测试通过
- [ ] 17.3 实现构建期 Copilot（需求文本/表格 → Requirement Spec → LS-IR，先做语义复用检查），经 `LlmGateway` 调用 OpenAI 兼容端点；验证"设备"复用 `ls.asset.Asset` 用例（LLM 用录制响应做确定性测试）
- [ ] 17.4 实现 `ls-mom-cli frappe-import`：静态解析 DocType/Child/Workflow/DocPerm/Report/Fixture，Python 与 Server Script 标记 `MANUAL_REQUIRED`，记录来源/许可证/转换器与模型版本；以 frappe/frappe（MIT）中的简单 DocType＋自编 Workflow 样例完成转换与测试；验证 AI-03/AI-04
- [ ] 17.5 确保生成流水线无生产发布权限并输出生成质量指标报告；验证 AI-01 直接发布被拒
- [ ] 17.6 编写 `docs/ls-ir.md` 与 `docs/frappe-conversion.md`；验证文档示例 LS-IR 可通过校验

## 18. 运行期 Agent、MCP 与知识问答（P5）

- [ ] 18.1 实现 Agent 服务身份、API Key（哈希、过期、轮换、限流、吊销）与 OBO 双主体审计；验证吊销与限流场景
- [ ] 18.2 实现有效权限计算（真人 ∩ 服务身份 ∩ 白名单 ∩ 数据域 − 黑名单）与 QUERY/DRAFT/SIMULATE（事务回滚无副作用）/APPROVAL_REQUIRED（输入摘要、版本、批准人、有效期、一次性 ID）；验证 AI-05～AI-09
- [ ] 18.3 实现 `ls-mom-mcp`（Streamable HTTP，工具清单由暴露等级自动生成）；验证暴露等级改为 DENY 后工具消失
- [ ] 18.4 实现交接班、异常、咨询、DCS 报警上下文摘要（草稿＋引用，人工确认后发布）；验证摘要待确认且带引用
- [ ] 18.5 实现 pgvector 知识库（已发布版本、检索前后双重权限过滤、引用）与问数走 Metric；验证 AI-10 与未发布修订稿不被引用
- [ ] 18.6 编写 `docs/agent-boundary.md`（暴露等级、黑名单、审批绑定）；验证文档中的 DENY 清单与 CI 扫描清单一致

## 19. 跨切片集成验收与硬化

- [ ] 19.1 运行全部黄金场景（无 BOM 生产、维修、质量整改、借还台账、培训、咨询、交接班、DCS 报警）端到端测试；验证全部通过
- [ ] 19.2 运行越权测试矩阵（菜单、Operation、组织、字段、附件、流程任务、Agent）；验证全部被拒并有审计
- [ ] 19.3 运行升级测试：上一版本备份恢复→Core/Pack/客户 Pack 升级→在途流程继续办理→客户字段与数据保留；验证 §13.5 各项
- [ ] 19.4 运行联合备份恢复演练（数据库＋对象存储＋Pack＋Flowable 定义）；验证恢复后在途流程可办理
- [ ] 19.5 运行性能基准（100 万行列表 50 并发 P95≤800ms、保存 30 并发 P95≤500ms）；验证报告达标
- [ ] 19.6 全新环境 `docker compose up` ＋种子重跑演示全部六个切片，推送最终代码到 GitHub `ls-mom` 并确认 CI 全绿

## Workflow follow-up

- 实施完成并评审通过后执行 `/opsx:archive` 归档本 change，并确认 `openspec/specs/` 下生成 25 个主 spec。
- MVP 后单独评估 RuoYi 6.x／Spring Boot 4 升级与 Flowable 新版本兼容性（另开 change）。
