import { defineConfig, devices } from '@playwright/test';

/**
 * 体验质量门禁（modernize-ui-ux 第 2 组）：一律无头运行。
 * - storybook 项目：设计系统组件（需先 pnpm storybook:build）；
 * - app 项目：对已部署环境（LSMOM_E2E_BASE）跑点击预算、无障碍等检查。
 */
const SB_PORT = Number(process.env.LS_STORYBOOK_PORT ?? 6106);

export default defineConfig({
  testDir: 'tests/ui',
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  use: { headless: true, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  expect: { toHaveScreenshot: { maxDiffPixelRatio: 0.002, animations: 'disabled' } },
  snapshotPathTemplate: '{testDir}/__screenshots__/{projectName}/{testFilePath}/{arg}{ext}',
  projects: [
    {
      name: 'storybook',
      testMatch: /storybook\/.*\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], baseURL: `http://127.0.0.1:${SB_PORT}` }
    },
    {
      name: 'app',
      testMatch: /app\/.*\.spec\.ts/,
      use: { ...devices['Desktop Chrome'], baseURL: process.env.LSMOM_E2E_BASE ?? 'http://127.0.0.1:18081' }
    }
  ],
  webServer: process.env.LS_UI_NO_STORYBOOK
    ? undefined
    : { command: `node tests/ui/serve-static.mjs storybook-static ${SB_PORT}`, url: `http://127.0.0.1:${SB_PORT}/index.json`, reuseExistingServer: true, timeout: 30_000 }
});
