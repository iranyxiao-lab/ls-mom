/**
 * 由设计令牌生成各端产物（构建期与运行期共用，不依赖浏览器 API）：
 * - cssVariables()：--ls-* 变量（明暗主题、密度档位、减少动态效果）＋ Element Plus（--el-*）与 Vant（--van-*）映射层；
 * - unoTheme()：UnoCSS 主题（颜色、圆角、字号、阴影都指向 CSS 变量）；
 * - echartsTheme()：ECharts 主题（图表绘制在 canvas 上，需传入解析后的颜色值）。
 */
import { componentTokens, easing, primitive, semanticColor, semanticShadow } from './tokens';

const px = (n: number) => (n === 0 ? '0' : `${n}px`);
const block = (selector: string, vars: Record<string, string>) =>
  `${selector} {\n${Object.entries(vars)
    .map(([k, v]) => `  ${k}: ${v};`)
    .join('\n')}\n}\n`;

export const FONT_FAMILY =
  "'Inter', 'PingFang SC', 'HarmonyOS Sans SC', 'Microsoft YaHei', 'Noto Sans SC', system-ui, -apple-system, 'Segoe UI', sans-serif";

function baseVars(): Record<string, string> {
  const v: Record<string, string> = {};
  for (const [k, n] of Object.entries(primitive.space)) v[`--ls-space-${k}`] = px(n);
  for (const [k, n] of Object.entries(primitive.radius)) v[`--ls-radius-${k}`] = px(n);
  for (const [k, n] of Object.entries(primitive.fontSize)) v[`--ls-font-size-${k}`] = px(n);
  for (const [k, n] of Object.entries(primitive.lineHeight)) v[`--ls-line-height-${k}`] = String(n);
  for (const [k, n] of Object.entries(primitive.fontWeight)) v[`--ls-font-weight-${k}`] = String(n);
  for (const [k, n] of Object.entries(primitive.duration)) v[`--ls-duration-${k}`] = `${n}ms`;
  for (const [k, e] of Object.entries(easing)) v[`--ls-easing-${k}`] = e;
  v['--ls-font-family'] = FONT_FAMILY;
  return v;
}

const colorVars = (mode: 'light' | 'dark') => {
  const v: Record<string, string> = {};
  for (const [k, col] of Object.entries(semanticColor[mode])) v[`--ls-color-${k}`] = col;
  for (const [k, s] of Object.entries(semanticShadow[mode])) v[`--ls-shadow-${k}`] = s;
  v['color-scheme'] = mode;
  return v;
};

const componentVars = (density: 'comfortable' | 'compact') =>
  Object.fromEntries(Object.entries(componentTokens[density]).map(([k, n]) => [`--ls-${k}`, px(n)]));

/** 派生色：与混合基色（浅色主题为白、深色主题为深底）按比例混合，品牌色改变时自动跟随。 */
const mix = (token: string, percent: number) => `color-mix(in srgb, var(--ls-color-${token}) ${percent}%, var(--ls-color-mix-base))`;
const darken = (token: string, percent: number) => `color-mix(in srgb, var(--ls-color-${token}) ${percent}%, #000000)`;

/** Element Plus 映射层：Element Plus 组件只读取 --el-*，这里全部指向语义令牌。 */
export function elementPlusVars(): Record<string, string> {
  const v: Record<string, string> = {};
  for (const t of ['primary', 'success', 'warning', 'danger', 'info']) {
    v[`--el-color-${t}`] = `var(--ls-color-${t})`;
    for (const n of [1, 2, 3, 4, 5, 6, 7, 8, 9]) v[`--el-color-${t}-light-${n}`] = mix(t, 100 - n * 10);
    v[`--el-color-${t}-dark-2`] = darken(t, 80);
  }
  v['--el-color-error'] = 'var(--ls-color-danger)';
  for (const n of [3, 5, 7, 8, 9]) v[`--el-color-error-light-${n}`] = mix('danger', 100 - n * 10);
  v['--el-color-error-dark-2'] = darken('danger', 80);
  Object.assign(v, {
    '--el-text-color-primary': 'var(--ls-color-text)',
    '--el-text-color-regular': 'var(--ls-color-text-regular)',
    '--el-text-color-secondary': 'var(--ls-color-text-secondary)',
    '--el-text-color-placeholder': 'var(--ls-color-text-placeholder)',
    '--el-text-color-disabled': 'var(--ls-color-text-disabled)',
    '--el-border-color': 'var(--ls-color-border)',
    '--el-border-color-light': 'var(--ls-color-border-light)',
    '--el-border-color-lighter': 'var(--ls-color-border-lighter)',
    '--el-border-color-extra-light': 'var(--ls-color-border-lighter)',
    '--el-border-color-dark': 'var(--ls-color-border-strong)',
    '--el-border-color-darker': 'var(--ls-color-border-strong)',
    '--el-fill-color': 'var(--ls-color-fill)',
    '--el-fill-color-light': 'var(--ls-color-fill-light)',
    '--el-fill-color-lighter': 'var(--ls-color-fill-light)',
    '--el-fill-color-extra-light': 'var(--ls-color-fill-light)',
    '--el-fill-color-dark': 'var(--ls-color-fill-dark)',
    '--el-fill-color-darker': 'var(--ls-color-fill-dark)',
    '--el-fill-color-blank': 'var(--ls-color-bg)',
    '--el-bg-color': 'var(--ls-color-bg)',
    '--el-bg-color-page': 'var(--ls-color-bg-page)',
    '--el-bg-color-overlay': 'var(--ls-color-bg-elevated)',
    '--el-mask-color': 'var(--ls-color-mask)',
    '--el-border-radius-small': 'var(--ls-radius-sm)',
    '--el-border-radius-base': 'var(--ls-radius-md)',
    '--el-border-radius-round': '20px',
    '--el-font-size-extra-small': 'var(--ls-font-size-xs)',
    '--el-font-size-small': 'var(--ls-font-size-sm)',
    '--el-font-size-base': 'var(--ls-font-size-md)',
    '--el-font-size-medium': 'var(--ls-font-size-lg)',
    '--el-font-size-large': 'var(--ls-font-size-xl)',
    '--el-font-size-extra-large': 'var(--ls-font-size-2xl)',
    '--el-font-family': 'var(--ls-font-family)',
    '--el-box-shadow': 'var(--ls-shadow-md)',
    '--el-box-shadow-light': 'var(--ls-shadow-sm)',
    '--el-box-shadow-lighter': 'var(--ls-shadow-sm)',
    '--el-box-shadow-dark': 'var(--ls-shadow-lg)',
    '--el-transition-duration': 'var(--ls-duration-slow)',
    '--el-transition-duration-fast': 'var(--ls-duration-normal)',
    '--el-component-size': 'var(--ls-control-height)',
    '--el-component-size-small': 'var(--ls-control-height-sm)',
    '--el-component-size-large': 'var(--ls-control-height-lg)'
  });
  return v;
}

/** Vant 映射层：移动端组件读取 --van-*。 */
export function vantVars(): Record<string, string> {
  return {
    '--van-primary-color': 'var(--ls-color-primary)',
    '--van-success-color': 'var(--ls-color-success)',
    '--van-warning-color': 'var(--ls-color-warning)',
    '--van-danger-color': 'var(--ls-color-danger)',
    '--van-text-color': 'var(--ls-color-text)',
    '--van-text-color-2': 'var(--ls-color-text-secondary)',
    '--van-text-color-3': 'var(--ls-color-text-placeholder)',
    '--van-active-color': 'var(--ls-color-fill)',
    '--van-background': 'var(--ls-color-bg-page)',
    '--van-background-2': 'var(--ls-color-bg)',
    '--van-background-3': 'var(--ls-color-bg)',
    '--van-border-color': 'var(--ls-color-border-light)',
    '--van-radius-sm': 'var(--ls-radius-sm)',
    '--van-radius-md': 'var(--ls-radius-md)',
    '--van-radius-lg': 'var(--ls-radius-lg)',
    '--van-font-size-xs': 'var(--ls-font-size-xs)',
    '--van-font-size-sm': 'var(--ls-font-size-sm)',
    '--van-font-size-md': 'var(--ls-font-size-md)',
    '--van-font-size-lg': 'var(--ls-font-size-lg)',
    '--van-base-font': 'var(--ls-font-family)',
    '--van-duration-base': 'var(--ls-duration-normal)',
    '--van-duration-fast': 'var(--ls-duration-fast)',
    '--van-button-primary-background': 'var(--ls-color-primary)',
    '--van-button-primary-border-color': 'var(--ls-color-primary)',
    '--van-button-default-height': 'var(--ls-touch-target)',
    '--van-button-normal-font-size': 'var(--ls-font-size-md)',
    '--van-button-radius': 'var(--ls-radius-md)',
    '--van-cell-font-size': 'var(--ls-font-size-md)',
    '--van-cell-vertical-padding': 'var(--ls-space-3)',
    '--van-cell-horizontal-padding': 'var(--ls-space-4)'
  };
}

/** 上游外壳（RuoYi 侧栏、顶栏）读取的变量，统一由令牌驱动。 */
export function shellVars(): Record<string, string> {
  return {
    '--menuBg': 'var(--ls-color-sidebar-bg)',
    '--menuColor': 'var(--ls-color-sidebar-text)',
    '--menuActiveText': 'var(--ls-color-sidebar-text-active)',
    '--menuHover': 'var(--ls-color-sidebar-hover)',
    '--subMenuBg': 'var(--ls-color-sidebar-bg)',
    '--subMenuActiveText': 'var(--ls-color-sidebar-text-active)',
    '--subMenuHover': 'var(--ls-color-sidebar-hover)',
    '--subMenuTitleHover': 'var(--ls-color-sidebar-hover)',
    '--fixedHeaderBg': 'var(--ls-color-header-bg)'
  };
}

/** 完整样式表。 */
export function cssVariables(): string {
  const reduced: Record<string, string> = {
    '--ls-duration-fast': '0ms',
    '--ls-duration-normal': '0ms',
    '--ls-duration-slow': '0ms'
  };
  return [
    '/* 由 src/design/tokens.ts 生成，请勿手改 */\n',
    block('html:root', { ...baseVars(), ...colorVars('light'), ...componentVars('comfortable') }),
    // 组件库映射层：Element Plus 暗色变量写在 html.dark 上（优先级 0,1,1），这里用 0,2,1 确保不受样式加载顺序影响
    block('html:root:root', { ...elementPlusVars(), ...vantVars(), ...shellVars() }),
    block('html.dark:root', colorVars('dark')),
    block('html:root[data-density="compact"], [data-density="compact"]', componentVars('compact')),
    // 局部区域可单独指定密度（例如紧凑列表页里的舒适表单）
    block('[data-density="comfortable"]', componentVars('comfortable')),
    // 减少动态效果：系统设置或用户偏好（data-motion="reduced"）
    `@media (prefers-reduced-motion: reduce) {\n${block('html:root', reduced)}${block(
      '*, *::before, *::after',
      { 'animation-duration': '0.01ms !important', 'animation-iteration-count': '1 !important', 'transition-duration': '0.01ms !important', 'scroll-behavior': 'auto !important' }
    )}}\n`,
    block('html:root[data-motion="reduced"]', reduced),
    block('html[data-motion="reduced"] *, html[data-motion="reduced"] *::before, html[data-motion="reduced"] *::after', {
      'animation-duration': '0.01ms !important',
      'transition-duration': '0.01ms !important'
    }),
    // 切换明暗时临时关闭过渡，整页在一帧内完成切换
    block('html.ls-theme-switching *, html.ls-theme-switching *::before, html.ls-theme-switching *::after', { transition: 'none !important' }),
    // 可见焦点环（键盘可达）
    block(':focus-visible', { outline: '2px solid var(--ls-color-focus-ring)', 'outline-offset': '2px' }),
    block('body', {
      'font-family': 'var(--ls-font-family)',
      color: 'var(--ls-color-text)',
      'background-color': 'var(--ls-color-bg-page)'
    })
  ].join('\n');
}

/** UnoCSS 主题：类名 text-primary、bg-bg-page、rounded-md、shadow-md 等都指向令牌。 */
export function unoTheme() {
  const colors: Record<string, string> = {};
  for (const k of Object.keys(semanticColor.light)) colors[k] = `var(--ls-color-${k})`;
  return {
    colors,
    borderRadius: Object.fromEntries(Object.keys(primitive.radius).map((k) => [k, `var(--ls-radius-${k})`])),
    fontSize: Object.fromEntries(Object.keys(primitive.fontSize).map((k) => [k, `var(--ls-font-size-${k})`])),
    boxShadow: Object.fromEntries(Object.keys(semanticShadow.light).map((k) => [k, `var(--ls-shadow-${k})`])),
    spacing: Object.fromEntries(Object.keys(primitive.space).map((k) => [`ls-${k}`, `var(--ls-space-${k})`]))
  };
}

/** ECharts 主题。`resolve(name)` 返回 CSS 变量的计算值（运行期从 getComputedStyle 取，构建期可直接取令牌）。 */
export function echartsTheme(resolve: (cssVar: string) => string) {
  const r = (k: string) => resolve(`--ls-color-${k}`);
  const axis = {
    axisLine: { lineStyle: { color: r('border') } },
    axisTick: { lineStyle: { color: r('border') } },
    axisLabel: { color: r('text-secondary') },
    splitLine: { lineStyle: { color: r('border-light') } },
    nameTextStyle: { color: r('text-secondary') }
  };
  return {
    color: ['chart-1', 'chart-2', 'chart-3', 'chart-4', 'chart-5', 'chart-6'].map(r),
    backgroundColor: 'transparent',
    textStyle: { color: r('text-regular'), fontFamily: FONT_FAMILY },
    title: { textStyle: { color: r('text') }, subtextStyle: { color: r('text-secondary') } },
    legend: { textStyle: { color: r('text-regular') } },
    tooltip: { backgroundColor: r('bg-elevated'), borderColor: r('border-light'), textStyle: { color: r('text') } },
    categoryAxis: axis,
    valueAxis: axis,
    timeAxis: axis,
    logAxis: axis,
    line: { symbol: 'circle', symbolSize: 6 },
    bar: { itemStyle: { borderRadius: [2, 2, 0, 0] } }
  };
}

/** 构建期解析：直接取令牌值（主色等受运行期品牌色覆盖时以运行期为准）。 */
export function staticResolver(mode: 'light' | 'dark') {
  return (cssVar: string) => {
    const k = cssVar.replace(/^--ls-color-/, '');
    const v = semanticColor[mode][k];
    if (v && v.startsWith('var(--ls-color-')) return staticResolver(mode)(v.slice(4, -1));
    return v ?? '';
  };
}
