# Design：功能流程与 UI 交互走查验收

## Context

- 被验收对象：`bootstrap-ls-mom-mvp`（25 个 spec、173 个 Requirement、197 个 Scenario）和 `modernize-ui-ux`（8 个 spec、67 个 Scenario），两者都还没有实施。动机见 proposal.md，要求见 `specs/quality/*`。
- 已确定的约束：用例、结果、报告、签收全部存在仓库内，缺陷记在 GitHub Issues（私有仓库 `ls-mom`）；只在本地或 CI 的 Docker Compose 环境执行，不接入公司测试环境和 qa-center。
- 可复用的现成能力：`openspec show <change> --json --type change` 能输出每个 delta 的 Requirement 与 Scenario（已实测）；`modernize-ui-ux` 会提供 Playwright 无头框架、`countClicks()`、axe-core 扫描和视觉回归；bootstrap 会提供 Compose、种子数据、`trace_id` 和黄金场景数据集。

## Goals / Non-Goals

**Goals:**
- 让"每个场景都验收过"可以用机器证明，而不是靠人工核对。
- 走查可在任意干净机器上一键复现，失败时证据齐全。
- 走查框架尽早可用，随两个 change 的实施逐切片补充用例，而不是等全部做完才开始。

**Non-Goals:**
- 不替代各任务里的单元测试和集成测试；走查只做端到端和体验层面的验收。
- 不做压测、渗透测试；性能只复核已有的预算指标。
- 不引入外部测试管理平台。

## Decisions

### A1 目录结构与用例格式
```
acceptance/
  schema/case.schema.json      用例 JSON Schema
  priorities.yaml              Requirement 优先级（P0/P1/P2），未列出的默认 P1
  cases/<change>/<spec-path>/*.yaml
  journeys/<slice>/*.yaml      角色旅程（引用多条用例）
  checklists/ui-heuristics.yaml  UI 启发式检查清单
  pages.yaml                   页面清单（与路由表比对）
  e2e/                         Playwright 走查脚本（无头）
  tools/                       追溯、校验、报告脚本（Node/TS）
  runs/<yyyymmdd-hhmm>-<sha>/  每轮结果：results.json、截图、trace、报告
  signoff/<yyyymmdd>-<sha>.md  签收记录（只增不改）
```
- 用例示例字段：`id: AC-FLOW-WC-012`、`ref: {change, spec, requirement, scenario}`、`codes: [FLOW-05]`、`roles`、`dataset`、`steps`、`expected`、`mode: AUTO|MANUAL`、`script: e2e/flow/review.spec.ts#越权改字段`、`priority`。
- 用 YAML 而不是表格，方便代码评审和 diff；JSON Schema 校验用 ajv。

### A2 追溯与覆盖门禁
- `acceptance/tools/trace.ts`：调用 `openspec show <change> --json --type change` 提取所有 (spec, requirement, scenario)，与 `cases/**` 中的 `ref` 做集合比对，输出：未覆盖、悬空引用、P0 无 AUTO 用例三类问题，任一非空则退出码非 0。
- 场景以 `spec + requirement 名 + scenario 名` 作为键，改名会立刻暴露为"悬空＋未覆盖"一对问题，满足"关联失效检测"。
- 覆盖门禁加入 CI（每次提交），运行时间很短；全量走查不在每次提交时运行。
- 被验收 change 归档后，改为从 `openspec/specs/` 主 spec 读取，`ref.change` 字段保留供历史追溯。

### A3 执行与结果收集
- 自动用例：Playwright 无头运行，`reporter` 输出 JSON，用例 ID 写在测试标题里（`[AC-xxx]`），由 `tools/collect.ts` 回写到 `runs/<run>/results.json`。失败时保留 trace、录像和截图；每一步截图通过测试夹具统一完成。
- 人工用例与 UI 走查：`tools/manual.ts` 生成本轮的 Markdown 检查表（每个页面 × 视口 × 主题一节），走查员填写结果后，再由脚本解析回 `results.json`。人工走查时用浏览器查看本地 Compose 环境，截图放入同一 run 目录。
- 每一步记录响应头中的 `trace_id`，跨模块一致性检查通过命名 Query 和审计查询接口核对（只读）。

### A4 数据重置与数据集
- `deploy/scripts/acceptance-reset.sh`：`docker compose down -v` → `up -d` → 迁移 → 安装标准 Pack → 导入 `acceptance` 种子数据集（固定组织、8 类角色账号、设备、点位、模板）。旅程之间通过各自创建数据并以唯一前缀命名来隔离，保证可以单独重跑。
- DCS 旅程使用 bootstrap 的 `ls-mom-dcs-simulator` 场景脚本（报警、重复、乱序、断网）。
- 测试账号与口令只写在 `acceptance` 种子文件中，仅供本地环境使用，不在报告与日志中输出。

### A5 缺陷管理
- `.github/ISSUE_TEMPLATE/defect.yml`（GitHub Issue Forms），用必填字段强制关联用例和复现信息；标签 `defect`、`S1`～`S4`、`type/*`、`spec/<path>`。
- `tools/defects.ts` 通过 `gh issue list --json` 拉取缺陷，按 `spec/*` 标签和用例 ID 关联到矩阵；修复 PR 中写 `Fixes #n` 后，CI 自动重跑缺陷关联的用例，结果评论回 Issue。

### A6 报告与签收
- `tools/report.ts` 汇总 results.json＋缺陷＋预算实测＋可用性测试记录，生成 `runs/<run>/report.md` 与 `report.html`（单文件，可直接打开），并按准出条件自动给出"通过／不通过"及原因。
- 签收记录用 Markdown 模板，CI 检查 `acceptance/signoff/` 下已有文件在后续提交中未被修改（只增不改）。

### A7 与被验收 change 的衔接节奏
- 每完成 bootstrap 的一个切片（或 UI change 的一个任务组），同步补齐该部分用例并跑通自动用例；全量正式走查在两个 change 都完成后进行。

## Risks / Trade-offs

- [用例数量大（≥264 条），编写成本高] → 每个场景至少一条，允许一条用例覆盖同一 Requirement 下的多个场景；旅程复用用例而不复制步骤。
- [spec 在实施中频繁调整导致大量悬空] → 悬空检测本身就是信号；先用 `openspec-update-change` 更新 spec，再批量修正用例引用。
- [人工走查结果主观] → 启发式清单每项都有判定说明和示例；S1/S2 必须附证据，由第二人复核。
- [GitHub Issues 依赖网络与 `gh` 登录] → 缺陷同步失败不影响走查执行，报告中标注"缺陷数据未同步"。
- [端到端走查时间长] → 旅程可并行运行（独立数据前缀）；全量走查只在准入后和发布前运行。

## Migration Plan

全新增加的目录与 CI 任务，不改变产品代码。启用顺序：先上线用例 Schema 与覆盖门禁（只报告、不阻断），用例补齐到 100% 后切换为阻断模式。

## Open Questions

- 签收人名单（产品负责人、业务代表）：执行签收时填写，不影响框架。
