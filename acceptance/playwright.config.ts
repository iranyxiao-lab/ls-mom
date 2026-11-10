/**
 * 走查执行（acceptance-walkthrough 2.2）：一律无头。每轮结果写入 runs/<yyyymmdd-hhmm>-<提交>/：
 *   playwright.json（结果）、artifacts/（每步截图、失败录像与 trace）、traces.json（各步响应的 X-Trace-Id）。
 * 运行：ACC_BASE=http://127.0.0.1:18101 pnpm walk [过滤]；ACC_RUN 可指定结果目录名。
 */
import { execSync } from 'node:child_process';
import path from 'node:path';
import { defineConfig, devices } from '@playwright/test';

// 工厂时区（与服务端容器 TZ 一致）：用例里按"今天""当前班次""开考时间"生成的时间与服务端同一口径，与执行机所在时区无关
const TZ = process.env.ACC_TZ ?? 'Asia/Shanghai';
process.env.TZ = TZ;

if (!process.env.ACC_RUN) {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  let sha = 'nogit';
  try {
    sha = execSync('git rev-parse --short HEAD', { encoding: 'utf8' }).trim();
  } catch {}
  process.env.ACC_RUN = `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}-${sha}`;
}
const RUN = path.join(import.meta.dirname, 'runs', process.env.ACC_RUN);

export default defineConfig({
  testDir: 'e2e',
  timeout: 180_000,
  fullyParallel: false,
  workers: 1,
  outputDir: path.join(RUN, 'artifacts'),
  reporter: [['list'], ['json', { outputFile: path.join(RUN, 'playwright.json') }]],
  use: {
    ...devices['Desktop Chrome'],
    headless: true,
    timezoneId: TZ,
    baseURL: process.env.ACC_BASE ?? 'http://127.0.0.1:18101',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  metadata: { run: process.env.ACC_RUN }
});
