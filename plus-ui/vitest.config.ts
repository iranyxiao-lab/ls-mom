import path from 'path';
import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import { playwright } from '@vitest/browser-playwright';
import createLsTokens from './vite/plugins/ls-tokens';

// 单元/组件测试在真实浏览器中运行，默认无头；需要看界面时用 pnpm test:browser
export default defineConfig({
  plugins: [vue(), createLsTokens()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
    // 与 vite.config.ts 一致：业务代码中有省略 .vue 扩展名的导入
    extensions: ['.mjs', '.js', '.ts', '.jsx', '.tsx', '.json', '.vue']
  },
  // 一致性用例位于仓库根目录 packs/_shared，测试服务需允许读取
  server: { fs: { allow: [path.resolve(__dirname, '..')] } },
  // 浏览器模式中途发现新依赖会重新预构建并重载页面，CI 上偶发挂起到超时：预先声明（列表来自 CI 日志）
  optimizeDeps: { include: ['nprogress', 'element-plus/es'] },
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
