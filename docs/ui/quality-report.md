# 集成验收：体验质量门禁结果（modernize-ui-ux 9.1）

对 bootstrap 六个切片（生产、设备、质量 / 通用台账、DCS、Agent 与知识问答、协同工作中心与应用中心）的核心页面全量运行
无障碍扫描、视觉回归、点击预算与性能预算。全部无头运行，环境为本机 Compose 部署（`lsmom-verify`）。

核心页面清单：`plus-ui/tests/ui/support/pages.ts`（首页、协同工作中心、协同应用中心、生产需求、班次看板、报工、交接班、
设备中心、设备缺陷、质量记录、DCS 报警、摘要确认、知识问答），移动端另含工作台、点检、报缺陷、我的。

## 结果（2026-11-11）

| 门禁 | 命令 | 结果 |
|---|---|---|
| 无障碍（axe，critical/serious 即失败） | `LS_UI_NO_STORYBOOK=1 pnpm exec playwright test --project=app tests/ui/app/a11y.spec.ts` | 37 项全部通过：登录页与 13 个桌面核心页面 × 浅色 / 深色，4 个移动页面 × 浅色 / 深色，另有缺少按钮标签的自检 |
| 视觉回归（组件 + 核心页面，浅色/深色 × 桌面/移动） | `tests/ui/visual-docker.sh` | 99 项全部通过，含 54 张核心页面截图；"修改全局圆角"自检能报出差异 |
| 点击预算 | `app` 项目 home-todo；`node tests/e2e/mobile.e2e.mjs` | 首页待办完成审核 ≤3 次点击；移动常规报工 2 次（≤5）；从设备登记缺陷 4 次（≤4） |
| 性能预算（Playwright 实测） | `tests/ui/app/perf.spec.ts` | 13 个桌面核心页面 LCP 128–340ms（≤2.5s），INP 72–96ms（≤200ms）；移动工作台在 4G 下（9Mbps、RTT 170ms、CPU 4 倍降速、无缓存）2.2s 显示任务区（≤3s） |
| 性能预算（Lighthouse CI） | `lhci collect/assert`（`lighthouserc.cjs`） | 桌面 LCP 426–968ms、TBT 0–3ms；移动（4G 模拟）/login LCP 320ms、/m LCP 666ms；断言全部通过 |
| 首屏 JS 体积 | `pnpm size` | 388.9 KB gzip（≤400 KB） |
| 其余体验用例 | `LS_UI_NO_STORYBOOK=1 pnpm exec playwright test --project=app`；`--project=storybook` | app 93 项、storybook 25 项全部通过 |

## 本轮发现并修复的问题

- **移动端深色模式对比度不足**：移动页面引用了设计令牌中不存在的 `--ls-color-surface`、`--ls-color-warning-bg`，深色下回退成白底，文字却是浅色。
  已改为 `--ls-color-bg-elevated`、`--ls-color-bg-page`、`--ls-color-*-soft` 等现有令牌；文字类主色改用 `--ls-color-primary-text`。
  无障碍扫描新增 4 个移动页面（浅色 / 深色），这类问题之后会被自动发现。
- **视觉回归只覆盖了登录页与出错页**：新增 13 个核心页面与移动工作台的截图对比。业务数据区域（表格内容、计数、卡片内容、日期、
  已安装应用的菜单）截图时遮住，只比对外壳、页头、筛选栏、表头、按钮等样式；页面接口报错时先重载，不把错误状态记进基线。
- 登录页标题已改为"LS-MOM 协同制造平台"，移动端登录 / 出错页基线仍是旧标题，已更新。

## 待改进（不阻塞当前预算）

- 慢速 4G（Lighthouse 移动默认：RTT 150ms、1.6Mbps）下移动工作台约 4.1s 才显示任务区，主要耗时是 380KB（gzip）的主包。
  后续可给移动端单独入口，不加载桌面组件库，把首包降到 150KB 以内。
