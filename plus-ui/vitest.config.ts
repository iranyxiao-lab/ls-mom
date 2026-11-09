import path from 'path';
import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import { playwright } from '@vitest/browser-playwright';

// 单元/组件测试在真实浏览器中运行，默认无头；需要看界面时用 pnpm test:browser
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') }
  },
  // 一致性用例位于仓库根目录 packs/_shared，测试服务需允许读取
  server: { fs: { allow: [path.resolve(__dirname, '..')] } },
  test: {
    include: ['src/**/*.{test,spec}.ts', 'tests/unit/**/*.{test,spec}.ts'],
    browser: {
      enabled: true,
      headless: true,
      provider: playwright(),
      instances: [{ browser: 'chromium' }]
    }
  }
});
