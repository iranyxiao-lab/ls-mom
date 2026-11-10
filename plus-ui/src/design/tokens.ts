/**
 * LS-MOM 设计令牌：唯一来源。
 * 三层：primitive（色板、尺寸刻度）→ semantic（--ls-color-*、--ls-space-* …，随明暗主题切换）→ component（随密度切换）。
 * 构建时由 vite/plugins/ls-tokens.ts 生成 CSS 变量（virtual:ls-tokens.css）；UnoCSS 主题与 ECharts 主题也从这里生成。
 * 新增令牌见 docs/ui/design-system.md。
 */

/** 第一层：原始色板与刻度（只被语义层引用，业务代码不要直接用）。 */
export const primitive = {
  color: {
    blue: { 50: '#eef5ff', 100: '#d9e8ff', 200: '#b3d1ff', 300: '#80b3ff', 400: '#4d94ff', 500: '#1f6fe5', 600: '#175bc2', 700: '#12479a', 800: '#0d3573', 900: '#08224d' },
    green: { 50: '#ecfbf2', 100: '#cff3de', 500: '#1f9d55', 600: '#178045', 700: '#116336' },
    orange: { 50: '#fff6e8', 100: '#ffe7c2', 500: '#d97706', 600: '#b45f05', 700: '#8c4904' },
    red: { 50: '#fef0f0', 100: '#fcd9d9', 500: '#d93838', 600: '#b82c2c', 700: '#8f2121' },
    gray: {
      0: '#ffffff', 25: '#fbfcfd', 50: '#f5f7fa', 100: '#eef1f5', 200: '#e1e5eb', 300: '#c9cfd8', 400: '#a3abb8', 500: '#7b8494',
      600: '#5b6474', 700: '#414857', 800: '#2a303b', 850: '#1f242d', 900: '#171b22', 950: '#111419'
    }
  },
  /** 间距刻度（px）：键名即 --ls-space-<键>。 */
  space: { 0: 0, half: 2, 1: 4, 2: 8, 3: 12, 4: 16, 5: 20, 6: 24, 8: 32, 10: 40, 12: 48, 16: 64 },
  radius: { none: 0, sm: 2, md: 4, lg: 8, xl: 12, full: 9999 },
  fontSize: { xs: 12, sm: 13, md: 14, lg: 16, xl: 18, '2xl': 20, '3xl': 24, '4xl': 30 },
  lineHeight: { tight: 1.25, normal: 1.5, relaxed: 1.75 },
  fontWeight: { regular: 400, medium: 500, semibold: 600 },
  duration: { instant: 0, fast: 120, normal: 200, slow: 300 }
} as const;

const c = primitive.color;

export type ThemeMode = 'light' | 'dark';
export type Density = 'comfortable' | 'compact';

/** 第二层：语义颜色（明暗两套）。键名即 --ls-color-<键>。 */
export const semanticColor: Record<ThemeMode, Record<string, string>> = {
  light: {
    // 均满足白底 / 页面底 ≥4.5:1（WCAG AA 正文），白字压在实色上也 ≥4.5:1
    primary: '#1a63d1',
    /** 文字链接等以主色作文字时使用（暗色主题下与按钮底色分开，保证对比度） */
    'primary-text': 'var(--ls-color-primary)',
    success: c.green[600],
    warning: '#9a5503',
    danger: '#c42f2f',
    info: c.gray[600],
    'success-soft': c.green[50],
    'warning-soft': c.orange[50],
    'danger-soft': c.red[50],
    'info-soft': c.gray[100],
    text: c.gray[900],
    'text-regular': c.gray[700],
    'text-secondary': c.gray[600],
    'text-placeholder': c.gray[400],
    'text-disabled': c.gray[400],
    'text-inverse': c.gray[0],
    /** 压在实色（主色、成功、危险等）上的文字 */
    'on-solid': '#ffffff',
    border: c.gray[300],
    'border-light': c.gray[200],
    'border-lighter': c.gray[100],
    'border-strong': c.gray[400],
    bg: c.gray[0],
    'bg-page': c.gray[50],
    'bg-elevated': c.gray[0],
    'bg-sunken': c.gray[100],
    fill: c.gray[100],
    'fill-light': c.gray[50],
    'fill-dark': c.gray[200],
    /** 派生色（hover、浅底）的混合基色：浅色主题向白混合 */
    'mix-base': c.gray[0],
    mask: 'rgba(17, 20, 25, 0.45)',
    'focus-ring': 'color-mix(in srgb, var(--ls-color-primary) 45%, transparent)',
    /** 图表首色跟随品牌主色 */
    'chart-1': 'var(--ls-color-primary)',
    'chart-2': c.green[500],
    'chart-3': c.orange[500],
    'chart-4': c.red[500],
    'chart-5': '#7c5cdb',
    'chart-6': '#0f9fb3'
  },
  dark: {
    // 白字压在主色上 ≥4.5:1；作文字时用 primary-text（深底 ≥4.5:1）
    primary: '#3373dd',
    'primary-text': 'color-mix(in srgb, var(--ls-color-primary) 55%, #ffffff)',
    success: '#3cbf74',
    warning: '#f0a33a',
    danger: '#f06464',
    info: c.gray[400],
    'success-soft': 'color-mix(in srgb, #3cbf74 16%, #171b22)',
    'warning-soft': 'color-mix(in srgb, #f0a33a 16%, #171b22)',
    'danger-soft': 'color-mix(in srgb, #f06464 16%, #171b22)',
    'info-soft': c.gray[800],
    text: '#e8ebf0',
    'text-regular': c.gray[300],
    'text-secondary': c.gray[400],
    'text-placeholder': c.gray[500],
    'text-disabled': c.gray[600],
    'text-inverse': c.gray[950],
    'on-solid': '#ffffff',
    border: c.gray[700],
    'border-light': c.gray[800],
    'border-lighter': c.gray[850],
    'border-strong': c.gray[600],
    bg: c.gray[900],
    'bg-page': c.gray[950],
    'bg-elevated': c.gray[850],
    'bg-sunken': c.gray[950],
    fill: c.gray[800],
    'fill-light': c.gray[850],
    'fill-dark': c.gray[700],
    'mix-base': c.gray[900],
    mask: 'rgba(0, 0, 0, 0.6)',
    'focus-ring': 'color-mix(in srgb, var(--ls-color-primary) 55%, transparent)',
    'chart-1': 'var(--ls-color-primary)',
    'chart-2': '#3cbf74',
    'chart-3': '#f0a33a',
    'chart-4': '#f06464',
    'chart-5': '#9b82f0',
    'chart-6': '#2ec4d6'
  }
};

/** 语义阴影（明暗两套）：--ls-shadow-<键>。 */
export const semanticShadow: Record<ThemeMode, Record<string, string>> = {
  light: {
    sm: '0 1px 2px rgba(17, 20, 25, 0.06)',
    md: '0 4px 12px rgba(17, 20, 25, 0.08)',
    lg: '0 12px 32px rgba(17, 20, 25, 0.12)'
  },
  dark: {
    sm: '0 1px 2px rgba(0, 0, 0, 0.4)',
    md: '0 4px 12px rgba(0, 0, 0, 0.45)',
    lg: '0 12px 32px rgba(0, 0, 0, 0.55)'
  }
};

/** 动效缓动：--ls-easing-<键>。 */
export const easing = {
  standard: 'cubic-bezier(0.2, 0, 0, 1)',
  emphasized: 'cubic-bezier(0.3, 0, 0, 1.2)',
  exit: 'cubic-bezier(0.4, 0, 1, 1)'
} as const;

/** 第三层：组件令牌（随密度切换，单位 px）：--ls-<键>。 */
export const componentTokens: Record<Density, Record<string, number>> = {
  comfortable: {
    'control-height': 36,
    'control-height-sm': 30,
    'control-height-lg': 44,
    'table-row-height': 48,
    'table-cell-padding-y': 12,
    'table-cell-padding-x': 12,
    'form-item-gap': 18,
    'card-padding': 20,
    'touch-target': 44
  },
  compact: {
    'control-height': 28,
    'control-height-sm': 24,
    'control-height-lg': 36,
    'table-row-height': 32,
    'table-cell-padding-y': 4,
    'table-cell-padding-x': 8,
    'form-item-gap': 12,
    'card-padding': 12,
    'touch-target': 44
  }
};

/** 响应式断点（px）：手机 < tablet ≤ 平板 < desktop ≤ 桌面。 */
export const breakpoints = { tablet: 768, desktop: 1280 } as const;

/** 全部 CSS 变量名（供文档比对与类型提示）。 */
export function tokenNames(): string[] {
  const names = new Set<string>();
  for (const k of Object.keys(semanticColor.light)) names.add(`--ls-color-${k}`);
  for (const k of Object.keys(semanticShadow.light)) names.add(`--ls-shadow-${k}`);
  for (const k of Object.keys(primitive.space)) names.add(`--ls-space-${k}`);
  for (const k of Object.keys(primitive.radius)) names.add(`--ls-radius-${k}`);
  for (const k of Object.keys(primitive.fontSize)) names.add(`--ls-font-size-${k}`);
  for (const k of Object.keys(primitive.lineHeight)) names.add(`--ls-line-height-${k}`);
  for (const k of Object.keys(primitive.fontWeight)) names.add(`--ls-font-weight-${k}`);
  for (const k of Object.keys(primitive.duration)) names.add(`--ls-duration-${k}`);
  for (const k of Object.keys(easing)) names.add(`--ls-easing-${k}`);
  for (const k of Object.keys(componentTokens.comfortable)) names.add(`--ls-${k}`);
  names.add('--ls-font-family');
  return [...names].sort();
}
