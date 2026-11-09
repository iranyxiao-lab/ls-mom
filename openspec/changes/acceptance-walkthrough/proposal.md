# Proposal：功能流程与 UI 交互走查验收

## Why

`bootstrap-ls-mom-mvp`（25 个 spec、197 个场景）和 `modernize-ui-ux`（8 个 spec、67 个场景）都在各自任务里写了验证，但这些验证分散在单元测试和各组 E2E 里，回答不了三个问题：每个 spec 场景是否真的被验收过；按真实角色从头到尾走一遍业务是否顺畅；页面之间的交互是否一致、好用。没有统一的走查验收，"功能齐全但走不通"或"每页都对、连起来别扭"的问题会一直留到客户现场才暴露。

## What Changes

- 建立**验收用例库**（仓库 `acceptance/` 目录，YAML 格式）：每个 spec 场景至少对应一条验收用例，标注角色、前置数据、步骤、预期、执行方式（自动／人工）和对应的自动化脚本。
- 建立**可追溯矩阵与覆盖门禁**：自动从 OpenSpec change 提取全部 Requirement 与 Scenario，与用例库比对，生成覆盖报告；有场景未被覆盖时门禁失败。
- 建立**功能流程走查**：按 v2.1 六个切片和关键角色（操作工、班组长、车间主任、设备员、质检员、实施顾问、管理员、Agent）编写端到端旅程，在本地 Docker Compose 一键重置数据后执行，留存截图、录像、Playwright trace 和 `trace_id`。
- 建立**UI 交互走查**：页面 × 视口（手机／平板／桌面）× 主题（浅／深）的检查清单，覆盖可用性启发式原则、设计系统一致性、点击预算、无障碍和空态／错误态。
- 建立**缺陷管理**：走查发现的问题记入 GitHub Issues（统一模板、严重级别 S1～S4、关联用例与 spec 场景），修复后必须回归对应用例。
- 建立**准入、准出与签收**：明确开始走查和允许验收通过的条件，生成验收报告并在仓库中留存签收记录。
- 只在本地或 CI 的 Docker Compose 环境执行，不使用公司测试环境和生产环境；用例与结果只存仓库，不接入外部测试管理系统。

## Capabilities

### New Capabilities

- `quality/acceptance-traceability`：验收用例格式、spec 场景到用例的可追溯矩阵、覆盖率报告与门禁。
- `quality/functional-walkthrough`：按切片与角色的端到端业务旅程走查、数据重置、执行证据留存。
- `quality/ui-walkthrough`：按页面、视口、主题的 UI 交互走查清单与一致性检查。
- `quality/defect-management`：缺陷记录格式、严重级别、与用例和 spec 的关联、修复回归。
- `quality/acceptance-signoff`：走查准入／准出条件、验收报告、签收与复验。

### Modified Capabilities

（无。被验收的两个 change 尚未归档，本 change 不改变它们的任何行为要求。）

## Impact

- **仓库**：新增 `acceptance/`（用例、旅程、清单、工具、报告、签收记录）、`.github/ISSUE_TEMPLATE/defect.yml`、CI 中的覆盖门禁与走查任务。
- **工具**：复用 `modernize-ui-ux` 引入的 Playwright（无头）、axe-core、`countClicks()` 和视觉回归；用 `openspec show --json` 提取场景；新增一个用 Node/TS 写的追溯与报告脚本。
- **环境**：`deploy/` Compose 增加 `acceptance` 种子数据集与一键重置命令；不需要任何外部环境。
- **时间关系**：用例库与追溯矩阵可以马上开始编写，并随两个 change 的实施逐切片补充；完整走查在两个 change 的功能都完成后执行。
