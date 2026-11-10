# Tasks

> 前置：`bootstrap-ls-mom-mvp` 任务 1～3（仓库、Compose、CI）和 `modernize-ui-ux` 任务 2.1～2.3（Playwright、countClicks、axe、视觉回归）完成。第 1～3 组可以立即开始；第 4～6 组随被验收 change 的切片完成逐步推进；第 7～8 组在两个 change 全部完成后执行。所有浏览器测试无头运行。

## 1. 用例库与追溯门禁

- [x] 1.1 建立 `acceptance/` 目录结构，编写 `schema/case.schema.json` 与 ajv 校验脚本 `tools/validate.ts`；验证缺少 `ref.scenario` 或 AUTO 用例缺脚本的示例用例校验失败
- [x] 1.2 编写 `tools/trace.ts`：调用 `openspec show <change> --json --type change` 提取 (spec, requirement, scenario)，输出未覆盖、悬空、P0 缺 AUTO 三类问题；验证对两个 change 运行后列出全部 264 个未覆盖场景（当前用例为空）
- [x] 1.3 编写 `priorities.yaml`，把 v2.1 §13 验收编号（DOM、CRUD、FORM、FLOW、AI）涉及的 Requirement 和 UI 操作预算项标为 P0；验证 trace 报告中 P0 数量与清单一致
- [ ] 1.4 把 `validate` 与 `trace` 接入 CI（先只报告不阻断）；验证 PR 中出现覆盖报告摘要
- [x] 1.5 编写 `acceptance/README.md`（用例写法、命名规则、如何运行、如何补用例）；验证按文档新增一条用例后 trace 覆盖数加 1

## 2. 数据基线与执行框架

- [x] 2.1 编写 `deploy/scripts/acceptance-reset.sh` 与 `acceptance` 种子数据集（固定组织、8 类角色账号、设备、点位、模板），口令只存种子文件；验证连续两次重置后数据一致且报告和日志中不出现口令
- [x] 2.2 编写 Playwright 走查夹具：按角色登录、每步自动截图、记录响应 `trace_id`、失败保留 trace 与录像、以 `[AC-xxx]` 标题绑定用例；验证故意失败的示例用例生成完整证据目录
- [x] 2.3 编写 `tools/collect.ts`，把 Playwright JSON 结果写入 `runs/<run>/results.json`；验证结果中每条记录含用例 ID、结果、耗时与证据路径
- [x] 2.4 编写只读的跨模块一致性断言库（业务状态、待办读模型、流程实例、时间轴、审计、Outbox、看板指标，通过命名 Query 与审计查询接口读取）；验证在一条示例审批上全部断言通过
- [x] 2.5 编写 `tools/manual.ts`：按本轮范围生成人工用例与 UI 走查 Markdown 检查表，并能把填写结果解析回 results.json；验证填写示例表后结果被正确导入

## 3. 缺陷管理与报告

- [ ] 3.1 编写 `.github/ISSUE_TEMPLATE/defect.yml`（必填：严重级别、类型、用例编号、spec/Scenario、复现步骤、预期与实际、证据）并创建 `defect`、`S1`～`S4`、`type/*` 标签；验证缺少用例编号时无法提交 Issue
- [x] 3.2 在 `acceptance/README.md` 中写明 S1～S4 定级规则和示例；验证"非办理人可办理他人任务"示例被定为 S1
- [ ] 3.3 编写 `tools/defects.ts`（`gh issue list --json` 拉取缺陷并关联到矩阵，网络失败时标注"未同步"）与修复 PR 自动回归工作流（`Fixes #n` → 重跑关联用例 → 结果评论回 Issue，失败则重新打开）；验证示例缺陷的回归结果出现在 Issue 评论中
- [x] 3.4 编写 `tools/report.ts`：生成 Markdown＋单文件 HTML 报告（范围与提交号、覆盖、通过率、缺陷、预算实测、可用性结果、延期项），并按准出条件自动判定结论；验证有 1 个 S2 缺陷时结论为"不通过"并给出原因
- [x] 3.5 编写签收记录模板与 CI 检查（`acceptance/signoff/` 已有文件不得修改）；验证修改历史签收文件的提交被 CI 拒绝

## 4. 功能用例：稳定核心与平台（随 bootstrap P0～P2 推进）

- [x] 4.1 为 `platform/project-baseline`、`core/*`、`meta/*`、`action/*` 的全部场景编写用例；验证 trace 显示这些 spec 覆盖率 100%
- [x] 4.2 为 `crud/generic-ledger`、`form/*` 的全部场景编写用例，CRUD-01～06、FORM-01～09 标为 P0 且有 AUTO 用例；验证 trace 无 P0 缺 AUTO
- [x] 4.3 运行上述自动用例并修复脚本问题，失败项按模板登记缺陷；验证本组结果写入 results.json

## 5. 功能用例：流程、领域与智能（随 bootstrap P3～P5 推进）

- [x] 5.1 为 `flow/*`、`metric/*`、`workbench/*` 的全部场景编写用例（FLOW-01～08 为 P0）；验证覆盖率 100%
- [x] 5.2 为 `domain/*`、`dcs/*`、`packs/*` 的全部场景编写用例（DOM-01～06 为 P0）；验证覆盖率 100%
- [x] 5.3 为 `ai/*`、`security/*` 的全部场景编写用例（AI-01～10 为 P0），包含 Agent 越权与禁止能力的负向用例；验证覆盖率 100%
- [x] 5.4 编写六个切片的角色旅程（含多角色切换、每条旅程的负向步骤、跨模块一致性断言）以及"带在途流程的升级"和"备份恢复"两条旅程；验证每条旅程可在重置后的环境中单独运行

## 6. UI 用例与走查清单（随 modernize-ui-ux 推进）

- [x] 6.1 为 `ui/*` 8 个 spec 的全部场景编写用例，点击预算、性能预算、无障碍项为 P0；验证覆盖率 100%
- [x] 6.2 编写 `pages.yaml` 页面清单与路由表比对脚本；验证新增未登记路由时报告"未走查页面"
- [x] 6.3 编写 `checklists/ui-heuristics.yaml`（10 项可用性启发式、设计系统一致性、关键交互专项、键盘与读屏抽查），每项附判定说明与示例；验证 `tools/manual.ts` 能据此生成页面 × 视口 × 主题检查表
- [x] 6.4 编写操作成本实测脚本（报工、审核、报缺陷、配置一张台账的点击数与耗时）；验证结果写入 results.json 并与预算对比

## 7. 正式走查执行

- [ ] 7.1 执行准入检查（实施任务完成、CI 全绿、可一键重置、覆盖门禁切换为阻断且通过）；验证准入检查脚本输出"通过"
- [ ] 7.2 第一轮全量执行：全部 AUTO 用例、六个切片旅程、升级与恢复旅程；验证 run 目录证据完整、失败项均已登记缺陷
- [ ] 7.3 第一轮人工走查：人工用例与 UI 走查（全部页面 × 3 视口 × 2 主题、启发式清单、键盘与读屏抽查），S1/S2 问题由第二人复核；验证检查表全部填写并导入结果
- [ ] 7.4 组织可用性测试（操作工、班组长、审核人、实施顾问各 ≥3 人，任务为报工、审核、报缺陷、配置台账），记录完成率与耗时并导入报告；验证每项任务完成率 ≥80%，否则登记缺陷待复测
- [ ] 7.5 跟踪缺陷修复与回归，直至 S1、S2 清零、S3 均有处理决定；验证缺陷统计满足准出条件

## 8. 验收报告与签收

- [ ] 8.1 第二轮回归：重跑全部 P0 用例和受缺陷修复影响的 spec 用例与旅程；验证 P0 全部通过、总体通过率 ≥95%
- [ ] 8.2 生成最终验收报告（带提交号）并确认自动判定为"通过"；验证报告中各项准出指标均达标
- [ ] 8.3 由产品负责人签收，新增签收记录（报告版本、签收人、时间、遗留问题与跟进计划）并推送到 GitHub；验证 `acceptance/signoff/` 新增文件且 CI 通过

## Workflow follow-up

- 签收后与 `bootstrap-ls-mom-mvp`、`modernize-ui-ux` 一并执行 `/opsx:archive` 归档，并把 `tools/trace.ts` 的数据来源切换为 `openspec/specs/` 主 spec。
- 后续每个新 change 都按本流程补用例，并在签收前复验受影响的 spec。
