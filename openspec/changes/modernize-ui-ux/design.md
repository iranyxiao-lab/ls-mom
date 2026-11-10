# Design：前端现代化与低操作成本交互

## Context

- 前端尚未编写。`bootstrap-ls-mom-mvp` 已定：`plus-ui`（Vue 3＋TS＋Element Plus＋Vite）为管理台基座，自研 `SchemaForm／SchemaTable／SchemaDetail` 渲染器，form-create 设计器经适配层接入，移动端为同一应用内的响应式 H5。
- 本 change 只定义体验层，动机见 proposal.md，行为要求见 `specs/ui/*`。后端契约（Operation、FormSchema、节点字段策略、数据域）保持不变，所有前端便利功能最终都经过服务端校验。

### 参考的 GitHub 开源项目

| 项目 | 许可 | 借鉴内容 |
| --- | --- | --- |
| pure-admin/vue-pure-admin | MIT | Element Plus 下的布局（侧栏／顶栏／混合）、多页签保活、主题与暗色切换实现 |
| vbenjs/vue-vben-admin（v5） | MIT | 偏好设置中心、设计令牌分层、全局搜索面板、锁屏与权限路由组织方式 |
| soybeanjs/soybean-admin | MIT | 基于 UnoCSS 的主题令牌与 CSS 变量映射 |
| x-extends/vxe-table | MIT | 可编辑表格：键盘导航、Excel 粘贴、虚拟滚动、列拖拽与自定义 |
| youzan/vant 4 | MIT | 移动端组件：底部操作栏、数字键盘、上传、下拉刷新 |
| vueuse/vueuse | MIT | 快捷键、网络状态、本地存储、元素可见等组合式工具 |
| unocss/unocss＋Iconify（Lucide 图标集） | MIT／ISC | 原子化样式与按需图标 |
| zxing-js/browser | Apache-2.0 | 浏览器摄像头扫码 |
| kamranahmedse/driver.js | MIT | 分步新手引导 |
| zh-lx/pinyin-pro | MIT | 命令面板拼音及首字母匹配 |
| vite-pwa/vite-plugin-pwa（Workbox） | MIT | PWA 与离线缓存 |
| jakearchibald/idb-keyval | Apache-2.0 | IndexedDB 草稿与离线队列 |
| apache/echarts | Apache-2.0 | 看板图表，主题与令牌联动 |
| storybookjs/storybook | MIT | 组件目录与视觉回归基线 |
| microsoft/playwright＋dequelabs/axe-core＋GoogleChrome/lighthouse-ci | Apache-2.0／MPL-2.0 | 端到端、点击预算、无障碍、性能预算（axe-core 仅测试期使用，不打进产品包） |

## Goals / Non-Goals

**Goals:**
- 建立一套设计系统，管理台、Schema 渲染器、设计器、移动端共用，避免各页各写一套样式。
- 把"少操作"做成平台能力（默认值、记忆值、草稿、批量、撤销、抽屉办理），业务页面靠配置开启，不用每页单独实现。
- 体验指标进入 CI，防止体验随迭代退化。

**Non-Goals:**
- 不更换 UI 组件库（不迁移到 Ant Design Vue、Naive UI 或 shadcn-vue）。
- 不做原生 App 和小程序；不做可视化大屏专题设计（另开 change）。
- 不做完整的多语言，MVP 只做中文，但文案全部走 i18n 键，便于以后扩展。

## Decisions

### U1 保留 plus-ui 基座，移植优秀项目的交互模式
- plus-ui 已对接 RuoYi 的登录、动态菜单、字典、权限指令，换掉基座要重做这些对接。做法是在 plus-ui 上重做 `layout/`、主题和通用组件，交互模式参考 vue-pure-admin（布局、页签）和 vben v5（偏好中心、全局搜索）。
- **备选**：直接改用 vue-pure-admin 或 vben 作前端基座：外观更现成，但要重写与 RuoYi 后端的鉴权和菜单对接，还会和上游 plus-ui 的更新长期分叉，因此放弃。

### U2 设计令牌三层，同时映射到 Element Plus 和 Vant
- 三层令牌：`primitive`（色板、尺寸刻度）→ `semantic`（`--ls-color-primary`、`--ls-space-3`、`--ls-radius-md`…）→ `component`（按钮高度、表格行高等）。以 TS 文件为唯一来源，构建时生成 CSS 变量、UnoCSS 主题和 ECharts 主题。
- 用一个适配文件把语义令牌映射到 Element Plus 的 `--el-*` 变量和 Vant 的 `--van-*` 变量，两套组件库外观一致。
- 暗色通过 `html.dark` 切换语义层；密度通过 `data-density` 切换组件层。
- stylelint 规则禁止在 `src/views/**` 中写十六进制颜色和裸 px 间距（spec 要求硬编码检测）。

### U3 表格统一采用 vxe-table
- `SchemaTable` 和子表编辑器统一用 vxe-table：原生支持键盘单元格导航、Excel 粘贴、虚拟滚动、列拖拽与列自定义，Element Plus 的 `el-table` 要大量自写才能达到。外观通过 U2 的令牌映射与 Element Plus 保持一致。
- `bootstrap-ls-mom-mvp` 任务 7.7 中的 SchemaTable 按本决策实现（只换实现库，不改契约）。
- **备选**：`el-table-v2`：有虚拟滚动，但没有可编辑网格和粘贴能力，因此放弃。

### U4 平台级交互能力以组合式函数和渲染器选项提供
- `useSmartDefaults(schema, context)`：按 FormSchema 字段上的 `defaultFrom`（`$user`、`$org`、`$shift`、`$now`、`$source.<field>`）和 `remember: true` 填值；记忆值保存在服务端用户偏好（同一用户换设备也生效）。
- `useDraft(formKey)`：每 3 秒防抖写 IndexedDB，同时调用服务端草稿 Operation；尚未落库的新单据存 `ui_form_draft`，已有草稿态记录直接存业务表的草稿。
- `useUndoable(op)`：前端先乐观更新，5 秒内撤销则调用该 Operation 注册的补偿 Operation（`meta_operation.undoOperation`）；没有补偿 Operation 的动作一律走确认框（spec：分级确认）。
- `useHotkeys`：统一注册 Ctrl/⌘+K、Ctrl/⌘+S、Ctrl/⌘+Enter、Esc，并显示快捷键提示。
- 服务端字段错误统一为 `{field, rowIndex?, code, message, fix?}`，渲染器据此就近定位。`fix` 字段指向修复入口（例如"查看培训记录"），供可操作错误提示使用。

### U5 命令面板与全局搜索
- 前端：自写 `CommandPalette` 组件，菜单与动作在本地用 pinyin-pro 做全拼和首字母匹配，业务对象调用后端命名 Query `ls.ui.globalSearch`。
- 后端：`ui_search_index` 读模型（对象编码、名称、拼音、首字母、org_id、concept），由 Outbox 事件增量更新，用 PostgreSQL `pg_trgm` 做模糊匹配，查询时按数据域过滤。不引入 Elasticsearch。

### U6 抽屉优先的详情与办理
- 列表点击默认打开右侧抽屉（宽度可拖拽，记住宽度），抽屉内是 `SchemaDetail`＋时间轴＋动作区，支持上一条/下一条；"在新页签打开"走同一组件。
- 待办抽屉办理完成后，调用待办读模型取下一条（按当前列表排序），实现连续审批。

### U7 移动端：同一代码库的 `/m` 路由组＋Vant＋PWA
- 移动路由组 `/m/*` 使用 Vant 组件和移动布局，复用同一套 API、权限和渲染器逻辑（渲染器按 `platform` 切换控件实现）。打开时按设备宽度和用户类型自动跳转，也可手动切换。
- 离线：vite-plugin-pwa 缓存应用外壳和今日任务；提交排入 IndexedDB 队列 `outbox-ui`，每条带客户端生成的幂等键，恢复网络后按顺序重放（服务端幂等，见 bootstrap `action/operation-gateway`）。
- 语音输入：优先用浏览器 Web Speech API；不可用时（国产安卓浏览器常见）回退到可配置的服务端语音识别端点，未配置时隐藏语音按钮。

### U8 首页卡片与偏好中心
- 首页卡片是注册式组件（`registerHomeCard({code, roles, query})`），每张卡片的数据来自 Metric 或命名 Query。Pack 可以注册自己的卡片，角色默认布局存在 `meta_view` 的 `HOME` 类型中。
- 偏好中心（主题、密度、布局模式、首页卡片、默认视图、快捷键提示开关）存服务端 `ui_user_pref`，本地只做缓存。

### U9 设计器辅助
- Excel 生成台账：前端用 SheetJS 社区版（Apache-2.0）解析表头和前 50 行样例，后端规则引擎推断字段类型和字典，可选再交给 LLM 优化命名；输出的是 LS-IR 片段，复用 bootstrap 的 `ls-mom-cli generate` 逻辑生成草稿。
- 自然语言规则：调用 `LlmGateway` 生成 FormSchema 规则补丁，补丁在设计器中以差异高亮显示，确认后才应用。
- 沙箱试运行：设计器调用 `ChangeSet` 沙箱环境（bootstrap D11/任务 12.3）执行，不写正式库。实施时的取舍：ChangeSet 演练环境（恢复备份→安装→测试）单次约 4 分钟，不适合设计时反复试填；改为在应用内的沙箱事务中执行（`Sandbox` 标记 + 只回滚事务：独立事务并入当前事务、提交后回调立即执行），业务表、流程实例、待办、回调、事件、审计全部回滚，只把轨迹存入 `flow_trial_run` 供一键清除。草稿在沙箱内存为临时版本并改绑到试运行实例；各节点以候选办理人账号的角色、权限与数据范围模拟办理。上线前的完整安装验证仍走 ChangeSet 演练。

### U10 体验质量门禁
- Playwright 端到端测试封装 `countClicks()`，为关键任务断言点击预算；`@axe-core/playwright` 扫描核心页面；Playwright 截图对比 Storybook 中的组件和核心页面（浅色/深色 × 桌面/移动）。
- Lighthouse CI 负责 LCP 和 INP 预算，size-limit 负责首屏 JS 体积预算。所有测试无头运行。
- 埋点用自研轻量 `track(event, {task, step, durationMs})`，数据进业务库 `ui_usage_event`（只存事件名和耗时，不存表单值），管理员可以关闭。

## Risks / Trade-offs

- [Element Plus＋vxe-table＋Vant 三套组件外观不统一] → 统一由令牌映射层驱动，Storybook 中并排对比，视觉回归覆盖。
- [vxe-table 版本升级有破坏性变更] → 冻结主版本，SchemaTable 内部封装，业务页面不直接使用 vxe API。
- [离线队列与服务端状态冲突] → 每条带幂等键和对象版本号，冲突时让用户处理，不自动覆盖。
- [自动保存草稿带来服务端写压力] → 3 秒防抖＋内容哈希去重，服务端草稿只保留每个用户每张表单最新一份。
- [撤销与补偿 Operation 语义不完整] → 没有补偿 Operation 的动作不提供撤销，改走确认框。
- [Web Speech API 在国产浏览器上不可用] → 回退到服务端语音识别或隐藏入口，不影响主流程。
- [埋点涉及隐私] → 只记录事件名与耗时，管理员可关闭，并在隐私说明中披露。
- [体验门禁使 CI 变慢] → 点击预算与无障碍扫描随端到端测试一起跑；Lighthouse 只在合并到 main 时执行。

## Migration Plan

- 本 change 应先于或同步于 `bootstrap-ls-mom-mvp` 的前端任务（7.7、8.4、8.6、10.4、11.3、13.3）实施：先落地设计系统和外壳（本 change 任务 1～3），之后的业务页面直接按新规范开发，不留旧样式。
- 新增的后端表 `ui_user_pref`、`ui_form_draft`、`ui_search_index`、`ui_usage_event` 通过核心 Flyway 迁移创建，全部是新增表，不影响已有表。
- 回滚：设计系统与布局按版本回退前端构建即可；后端新增表不被核心业务依赖，可以保留。
