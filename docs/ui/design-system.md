# 设计系统

管理台、Schema 渲染器、设计器和移动端共用一套设计令牌。业务页面只使用令牌，不写死颜色和间距，这样换品牌色、切换明暗主题或密度时不用改页面。

## 令牌从哪里来

源码只有一份：[`plus-ui/src/design/tokens.ts`](../../plus-ui/src/design/tokens.ts)。令牌分三层：

| 层 | 内容 | 业务代码能否直接用 |
| --- | --- | --- |
| primitive | 色板、尺寸刻度 | 不能，只给语义层引用 |
| semantic | `--ls-color-*`、`--ls-space-*`、`--ls-radius-*`、`--ls-font-size-*`、`--ls-shadow-*`、`--ls-duration-*` | 能，页面和组件都用这一层 |
| component | `--ls-control-height`、`--ls-table-row-height` 等，随密度切换 | 能，组件尺寸用这一层 |

[`plus-ui/src/design/build.ts`](../../plus-ui/src/design/build.ts) 从令牌生成以下产物：

- **CSS 变量**：Vite 插件 `vite/plugins/ls-tokens.ts` 在构建时生成虚拟模块 `virtual:ls-tokens.css`，`main.ts` 和 Storybook 都引入它。
- **Element Plus 映射**：`--el-color-primary`、`--el-text-color-*`、`--el-border-radius-*`、`--el-component-size` 等全部指向语义令牌，hover 色和浅底色用 `color-mix()` 从主色派生。
- **Vant 映射**：移动端组件读取的 `--van-*` 同样指向语义令牌。
- **UnoCSS 主题**：可以使用 `text-primary`、`bg-bg-page`、`rounded-md`、`shadow-md`、`p-ls-4` 等类。
- **ECharts 主题**：图表画在 canvas 上，不能直接使用 CSS 变量。`src/design/echarts.ts` 在运行时读取变量的计算值来注册主题，明暗主题或品牌色变化后会重新注册，图表随之重绘。

## 主题与密度

| 能力 | 用法 | 存储键 |
| --- | --- | --- |
| 明暗主题 | 跟随系统（默认）、浅色、深色。`useThemeMode().pref` 或设置抽屉「明暗主题」 | `ls-theme` |
| 密度 | 自动（默认：桌面列表紧凑，表单与移动端舒适）、舒适、紧凑。`useDensity()` | `ls-density` |
| 品牌主色 | `setBrand('#0f8a6c')`；设置抽屉的主题色也走这里 | `ls-brand` |
| 减少动态效果 | 跟随系统 `prefers-reduced-motion`，或设置 `html[data-motion="reduced"]` | `ls-motion` |

- `index.html` 内联了一段首屏脚本（与 `src/design/theme.ts` 的 `BOOT_SCRIPT` 一致，单测会比对），在页面渲染前就设置好 `html.dark`、`data-density` 和品牌色，不会闪白。
- 切换明暗主题时会临时关闭过渡动画，整页在一帧内完成切换。
- 局部区域可以单独指定密度：在容器上写 `data-density="compact"` 或 `data-density="comfortable"`。列表和表单组件通过 `useDensity().attrFor('list' | 'form' | 'mobile')` 取得"自动"档下应使用的值。

## 规则

- **只用令牌**：`plus-ui/src/views/**` 中禁止十六进制颜色，也禁止在 margin、padding、gap 上写裸 px。由 stylelint 检查（`node tests/lint/style-gate.mjs`，已加入 CI）。RuoYi 上游页面（system、monitor、tool、demo、登录、注册）不在检查范围内，以便合并上游更新。边框粗细（如 `1px`）、固定宽高不受限制。
- **对比度**：正文与背景对比度至少 4.5:1（WCAG 2.1 AA）。浅色主题的主色、成功、警告、危险、信息色在白底和页面底上都满足这一要求，白字压在实色上也满足。深色主题中，主色用作文字时请用 `--ls-color-primary-text`。
- **状态不能只靠颜色区分**：用 `LsStatusTag`，它同时显示颜色、图标和文字。
- **焦点可见**：不要去掉 `outline`。键盘聚焦时，焦点环颜色由主色派生（`--ls-color-focus-ring`）。
- **图标按钮要有可读标签**：写 `aria-label`；纯装饰性的图标写 `aria-hidden="true"`。
- **动效**：只用来表达状态变化和空间关系，单次不超过 `--ls-duration-slow`（300ms）。开启"减少动态效果"后，所有过渡和动画的时长归零。
- **响应式断点**：手机 `<768px`、平板 `768～1279px`、桌面 `≥1280px`（见 `breakpoints`），三档下都不能出现整页横向滚动。
- **图标**：使用 Lucide 图标集，经 UnoCSS 按需生成，写法为 `<span class="i-lucide-bell" aria-hidden="true" />`。

## 状态与反馈组件

都在 `plus-ui/src/components/LsState` 中，Storybook 的「设计系统 / 状态与反馈」一节有示例和文档。

| 组件 | 用途 |
| --- | --- |
| `LsStatusTag` | 状态标签（颜色＋图标＋文字），内置工作、异常、单据、流程的常见状态 |
| `LsEmpty` | 空态：插图、说明和一个相关的主操作，不显示空表格 |
| `LsSkeleton` | 骨架屏（表格、表单、卡片三种形状），减少动态效果时不闪烁 |
| `LsError` | 错误态：原因、下一步、重试和追踪编号（trace_id） |
| `LsBadge` | 徽标，超过上限显示 99+，读屏会读出"N 条未读" |
| `lsSuccess()` | 成功提示，文案统一为"已＋动作"，可以带"撤销" |

## 如何新增令牌

1. 在 `tokens.ts` 的对应层加键：
   - 新颜色要在 `semanticColor.light` 和 `semanticColor.dark` 中同时添加。
   - 新的组件尺寸要在 `componentTokens.comfortable` 和 `componentTokens.compact` 中同时添加。
2. 如果 Element Plus 或 Vant 需要读取新令牌，在 `build.ts` 的 `elementPlusVars()` 或 `vantVars()` 中加映射。
3. 在本文「令牌清单」中补一行。`node tests/lint/tokens-doc.mjs` 会比对文档与源码：缺一行、多一行或名字写错都会失败。
4. 运行 `pnpm storybook:build && pnpm test:ui`，确认组件外观符合预期。

## Storybook

- 构建静态站点：`pnpm storybook:build`（输出到 `storybook-static/`）。不启动带界面的浏览器。
- 无头检查：`pnpm test:ui`（Playwright），覆盖品牌换色、组件库一致性、图标渲染和深色可读性。
- 工具栏可以切换主题、密度和品牌色。

## 令牌清单

<!-- tokens:start -->
### 颜色（随明暗主题切换）

| 令牌 | 浅色 | 深色 |
| --- | --- | --- |
| `--ls-color-primary` | `#1a63d1` | `#3373dd` |
| `--ls-color-primary-text` | `var(--ls-color-primary)` | `color-mix(in srgb, var(--ls-color-primary) 55%, #ffffff)` |
| `--ls-color-success` | `#178045` | `#3cbf74` |
| `--ls-color-warning` | `#9a5503` | `#f0a33a` |
| `--ls-color-danger` | `#c42f2f` | `#f06464` |
| `--ls-color-info` | `#5b6474` | `#a3abb8` |
| `--ls-color-success-soft` | `#ecfbf2` | `color-mix(in srgb, #3cbf74 16%, #171b22)` |
| `--ls-color-warning-soft` | `#fff6e8` | `color-mix(in srgb, #f0a33a 16%, #171b22)` |
| `--ls-color-danger-soft` | `#fef0f0` | `color-mix(in srgb, #f06464 16%, #171b22)` |
| `--ls-color-info-soft` | `#eef1f5` | `#2a303b` |
| `--ls-color-text` | `#171b22` | `#e8ebf0` |
| `--ls-color-text-regular` | `#414857` | `#c9cfd8` |
| `--ls-color-text-secondary` | `#5b6474` | `#a3abb8` |
| `--ls-color-text-placeholder` | `#a3abb8` | `#7b8494` |
| `--ls-color-text-disabled` | `#a3abb8` | `#5b6474` |
| `--ls-color-text-inverse` | `#ffffff` | `#111419` |
| `--ls-color-on-solid` | `#ffffff` | `#ffffff` |
| `--ls-color-border` | `#c9cfd8` | `#414857` |
| `--ls-color-border-light` | `#e1e5eb` | `#2a303b` |
| `--ls-color-border-lighter` | `#eef1f5` | `#1f242d` |
| `--ls-color-border-strong` | `#a3abb8` | `#5b6474` |
| `--ls-color-bg` | `#ffffff` | `#171b22` |
| `--ls-color-bg-page` | `#f5f7fa` | `#111419` |
| `--ls-color-bg-elevated` | `#ffffff` | `#1f242d` |
| `--ls-color-bg-sunken` | `#eef1f5` | `#111419` |
| `--ls-color-fill` | `#eef1f5` | `#2a303b` |
| `--ls-color-fill-light` | `#f5f7fa` | `#1f242d` |
| `--ls-color-fill-dark` | `#e1e5eb` | `#414857` |
| `--ls-color-mix-base` | `#ffffff` | `#171b22` |
| `--ls-color-mask` | `rgba(17, 20, 25, 0.45)` | `rgba(0, 0, 0, 0.6)` |
| `--ls-color-focus-ring` | `color-mix(in srgb, var(--ls-color-primary) 45%, transparent)` | `color-mix(in srgb, var(--ls-color-primary) 55%, transparent)` |
| `--ls-color-chart-1` | `var(--ls-color-primary)` | `var(--ls-color-primary)` |
| `--ls-color-chart-2` | `#1f9d55` | `#3cbf74` |
| `--ls-color-chart-3` | `#d97706` | `#f0a33a` |
| `--ls-color-chart-4` | `#d93838` | `#f06464` |
| `--ls-color-chart-5` | `#7c5cdb` | `#9b82f0` |
| `--ls-color-chart-6` | `#0f9fb3` | `#2ec4d6` |

### 阴影（随明暗主题切换）

| 令牌 | 浅色 | 深色 |
| --- | --- | --- |
| `--ls-shadow-sm` | `0 1px 2px rgba(17, 20, 25, 0.06)` | `0 1px 2px rgba(0, 0, 0, 0.4)` |
| `--ls-shadow-md` | `0 4px 12px rgba(17, 20, 25, 0.08)` | `0 4px 12px rgba(0, 0, 0, 0.45)` |
| `--ls-shadow-lg` | `0 12px 32px rgba(17, 20, 25, 0.12)` | `0 12px 32px rgba(0, 0, 0, 0.55)` |

### 间距

| 令牌 | 值 |
| --- | --- |
| `--ls-space-0` | `0` |
| `--ls-space-1` | `4px` |
| `--ls-space-2` | `8px` |
| `--ls-space-3` | `12px` |
| `--ls-space-4` | `16px` |
| `--ls-space-5` | `20px` |
| `--ls-space-6` | `24px` |
| `--ls-space-8` | `32px` |
| `--ls-space-10` | `40px` |
| `--ls-space-12` | `48px` |
| `--ls-space-16` | `64px` |
| `--ls-space-half` | `2px` |

### 圆角

| 令牌 | 值 |
| --- | --- |
| `--ls-radius-none` | `0` |
| `--ls-radius-sm` | `2px` |
| `--ls-radius-md` | `4px` |
| `--ls-radius-lg` | `8px` |
| `--ls-radius-xl` | `12px` |
| `--ls-radius-full` | `9999px` |

### 字号

| 令牌 | 值 |
| --- | --- |
| `--ls-font-size-xs` | `12px` |
| `--ls-font-size-sm` | `13px` |
| `--ls-font-size-md` | `14px` |
| `--ls-font-size-lg` | `16px` |
| `--ls-font-size-xl` | `18px` |
| `--ls-font-size-2xl` | `20px` |
| `--ls-font-size-3xl` | `24px` |
| `--ls-font-size-4xl` | `30px` |

### 行高

| 令牌 | 值 |
| --- | --- |
| `--ls-line-height-tight` | `1.25` |
| `--ls-line-height-normal` | `1.5` |
| `--ls-line-height-relaxed` | `1.75` |

### 字重

| 令牌 | 值 |
| --- | --- |
| `--ls-font-weight-regular` | `400` |
| `--ls-font-weight-medium` | `500` |
| `--ls-font-weight-semibold` | `600` |

### 动效时长

| 令牌 | 值 |
| --- | --- |
| `--ls-duration-instant` | `0` |
| `--ls-duration-fast` | `120ms` |
| `--ls-duration-normal` | `200ms` |
| `--ls-duration-slow` | `300ms` |

### 缓动

| 令牌 | 值 |
| --- | --- |
| `--ls-easing-standard` | `cubic-bezier(0.2, 0, 0, 1)` |
| `--ls-easing-emphasized` | `cubic-bezier(0.3, 0, 0, 1.2)` |
| `--ls-easing-exit` | `cubic-bezier(0.4, 0, 1, 1)` |

### 组件（随密度切换）

| 令牌 | 舒适 | 紧凑 |
| --- | --- | --- |
| `--ls-control-height` | `36px` | `28px` |
| `--ls-control-height-sm` | `30px` | `24px` |
| `--ls-control-height-lg` | `44px` | `36px` |
| `--ls-table-row-height` | `48px` | `32px` |
| `--ls-table-cell-padding-y` | `12px` | `4px` |
| `--ls-table-cell-padding-x` | `12px` | `8px` |
| `--ls-form-item-gap` | `18px` | `12px` |
| `--ls-card-padding` | `20px` | `12px` |
| `--ls-touch-target` | `44px` | `44px` |

### 其他

| 令牌 | 说明 |
| --- | --- |
| `--ls-font-family` | 中文优先的系统字体栈 |
<!-- tokens:end -->
