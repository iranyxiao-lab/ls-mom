import {
  defineConfig,
  presetAttributify,
  presetIcons,
  presetTypography,
  presetUno,
  presetWebFonts,
  transformerDirectives,
  transformerVariantGroup
} from 'unocss';
import { unoTheme } from './src/design/build';

export default defineConfig({
  // 图标类也会写在 .ts 中（如状态图标映射 src/components/LsState/status.ts），需要一并提取
  content: {
    pipeline: {
      include: [/\.(vue|svelte|[jt]sx|mdx?|astro|html)($|\?)/, /src\/.*\.ts($|\?)/]
    }
  },
  shortcuts: {
    'panel-title':
      'pb-[5px] font-sans leading-[1.1] font-medium text-base text-primary-text border-b border-b-solid border-[var(--el-border-color-light)] mb-5 mt-0'
  },
  // 设计令牌（src/design/tokens.ts）：text-primary、bg-bg-page、rounded-md、shadow-md、p-ls-4 等都指向 CSS 变量
  theme: {
    ...unoTheme(),
    colors: { ...unoTheme().colors, primary_dark: 'var(--el-color-primary-light-5)' }
  },
  presets: [
    presetUno(),
    presetAttributify(),
    presetIcons(),
    presetTypography(),
    presetWebFonts({
      fonts: {}
    })
  ],
  transformers: [transformerDirectives(), transformerVariantGroup()]
});
