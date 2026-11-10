# 验收用例库与走查

功能流程与 UI 交互走查验收（OpenSpec change `acceptance-walkthrough`）。用例、走查结果、报告与签收记录都存在本目录；
缺陷记在 GitHub Issues。只在本地或 CI 的 Docker Compose 环境执行，所有浏览器测试无头运行。

```
acceptance/
  schema/case.schema.json        用例 JSON Schema（ajv 校验）
  config.yaml                    被验收的 change
  priorities.yaml                Requirement 优先级（P0 必须有 AUTO 用例）与验收编号
  cases/<change>/<spec>/*.yaml   验收用例
  journeys/<切片>/*.yaml          角色旅程（引用用例）
  checklists/ui-heuristics.yaml  UI 启发式检查清单
  pages.yaml                     页面清单（与路由表比对）
  e2e/                           Playwright 走查脚本与夹具
  tools/                         校验、追溯、收集、人工检查表、报告脚本（Node，直接运行 .ts）
  runs/<时间>-<提交>/             每轮结果：results.json、截图、trace、报告
  signoff/                       签收记录（只增不改）
```

## 运行

需要 Node ≥22.18（直接运行 TypeScript）。首次在本目录执行 `pnpm install`。

| 命令 | 作用 |
|---|---|
| `pnpm validate` | 校验全部用例：Schema、编号唯一、AUTO 用例的脚本存在且包含 `#` 后的测试片段 |
| `pnpm trace` | 覆盖门禁：列出未覆盖场景、悬空引用、P0 缺 AUTO；有问题时退出码 1（`--report-only` 只报告） |
| `pnpm test` | 工具自测 |

`trace` 优先调用 `openspec show <change> --json --type change` 提取场景；机器上没有 openspec CLI（如 CI）时直接解析
`openspec/changes/<change>/specs/**/spec.md`，两者结果一致（由自测保证）。

## 写一条用例

用例放在 `cases/<change>/<spec 路径>/` 下的 YAML 文件里，一个文件可以写多条（`cases:` 列表）：

```yaml
cases:
  - id: AC-FLOW-WC-012                 # AC-<领域>-<spec 缩写>-<三位序号>，全库唯一
    title: 审核人越权修改只读字段被拒绝
    ref:
      change: bootstrap-ls-mom-mvp
      spec: flow/workflow-collaboration
      requirement: 节点字段权限服务端强制   # 与 spec 中 "### Requirement:" 后的名称完全一致
      scenario: 审核人越权改字段           # 与 "#### Scenario:" 后的名称完全一致；覆盖同一 Requirement 的多个场景时写成列表
    codes: [FLOW-05]                     # v2.1 §13 验收编号或 UI 预算项（可选）
    slice: B                             # 切片 A～F、PLATFORM、UI（可选）
    roles: [车间主任]
    dataset: acceptance                  # 前置数据集
    steps:
      - 车间主任打开待审核不符合项
      - 绕过前端，直接调用办理接口并修改"现象"字段
    expected:
      - 返回 FIELD_READONLY，记录未被修改
      - 审计中有拒绝记录
    mode: AUTO                           # AUTO：必须写 script；MANUAL：人工执行，由检查表回填结果
    script: ls-mom-modules/ls-mom-flow/src/test/java/com/lsmom/flow/FlowGovernanceTest.java#审核人越权
    priority: P0                         # 可省略：默认取 priorities.yaml（未列出为 P1）
```

命名规则：

- 编号前缀按 spec 领域：`AC-PLAT`（platform）、`AC-CORE`、`AC-META`、`AC-ACT`（action）、`AC-CRUD`、`AC-FORM`、`AC-FLOW`、`AC-MET`（metric）、
  `AC-WB`（workbench）、`AC-DOM`（domain）、`AC-DCS`、`AC-PACK`、`AC-AI`、`AC-SEC`、`AC-UI`；第二段为 spec 名缩写，如 `AC-FLOW-WC`（workflow-collaboration）。
- `script` 写仓库相对路径；`#` 后写测试标题或方法名中的一段文字，校验时会检查它确实出现在文件里。
  Playwright 走查脚本的测试标题以 `[AC-xxx]` 开头，结果收集时据此关联用例。
- 一个场景至少一条用例；P0 Requirement 至少一条 AUTO 用例。

补完用例后运行 `pnpm validate && pnpm trace --report-only`，报告里"已覆盖"会相应增加。

## spec 改名或删除时

场景以 `spec ▸ Requirement ▸ Scenario` 为键。spec 里的名称一改，引用旧名称的用例会被报告为"悬空"，新名称同时报告为"未覆盖"。
先用 `openspec-update-change` 更新 spec，再批量修正用例的 `ref`。

## 缺陷定级（S1～S4）

走查发现的问题按 `.github/ISSUE_TEMPLATE/defect.yml` 登记为 GitHub Issue，必须关联用例编号与 spec 场景。定级规则：

| 级别 | 含义 | 判定要点 | 示例 |
|---|---|---|---|
| **S1 阻断** | 核心流程走不通、数据错误、越权、安全问题 | 只要涉及越权、数据被错误写入或丢失、安全边界失效，一律 S1，不看影响面 | **非办理人可以办理他人的待办**；B 车间用户能打开 A 车间单据；Agent 调用了禁止的能力；提交后业务状态与流程状态不一致 |
| **S2 严重** | 功能可用但结果不对，或关键任务超出操作预算 | 有错误结果但无越权；或点击 / 性能预算超标 | 看板指标与列表条数不符；移动报工需要 7 次点击（预算 5）；导出缺列 |
| **S3 一般** | 体验或一致性问题，有替代路径 | 能完成任务，但别扭、不一致、要多想 | 列表无数据时显示空白表格而非统一空态；删除确认没有说明后果；关闭抽屉后焦点没回到按钮 |
| **S4 轻微** | 文案、样式细节 | 不影响理解和操作 | 错别字；按钮间距差 2px；提示语句号中英文混用 |

拿不准时取更高一级；S1、S2 必须附证据（截图、trace、`trace_id`），由第二人复核。

## 准出与签收

见 `signoff/README.md`。准出条件：S1、S2 为零；P0 用例全部通过；总体通过率 ≥95%；操作与性能预算达标；无障碍无严重问题；
每个 S3 都有处理决定。签收记录只能新增（CI 的 `signoff-guard` 检查）。
