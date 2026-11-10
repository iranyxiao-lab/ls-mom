/**
 * 性能预算（spec ui/ux-quality「性能预算」），对部署环境无头运行：
 * - 桌面核心页面（六个切片）：LCP ≤2.5s；页面内交互（切换侧栏）的 INP ≤200ms；
 * - 移动端工作台：模拟 4G（WebPageTest「4G」：RTT 170ms、上下行 9Mbps；CPU 4 倍降速；无缓存、不用 Service Worker）
 *   从开始导航到本班次任务区可见 ≤3s。另记录慢速 4G（Lighthouse 移动默认：RTT 150ms、1.6Mbps/750kbps）的数值供参考。
 * 首屏 JS 体积由 pnpm size（≤400KB gzip）单独检查。
 */
import { expect, test, type Page } from '@playwright/test';
import { loggedIn } from '../support/auth';
import { CORE_PAGES } from '../support/pages';

const LCP_BUDGET = 2500;
const INP_BUDGET = 200;
const MOBILE_BUDGET = 3000;

/** 在页面加载前注册观察器：记录最后一次 LCP 与最长一次交互（event timing，按 interactionId 合并） */
const OBSERVE = () => {
  const w = window as any;
  w.__perf = { lcp: 0, inp: 0, fcp: 0 };
  new PerformanceObserver((l) => {
    for (const e of l.getEntries()) w.__perf.lcp = Math.max(w.__perf.lcp, (e as any).renderTime || e.startTime);
  }).observe({ type: 'largest-contentful-paint', buffered: true });
  new PerformanceObserver((l) => {
    for (const e of l.getEntries() as any[]) if (e.interactionId) w.__perf.inp = Math.max(w.__perf.inp, e.duration);
  }).observe({ type: 'event', buffered: true, durationThreshold: 16 } as any);
  new PerformanceObserver((l) => {
    for (const e of l.getEntries()) if (e.name === 'first-contentful-paint') w.__perf.fcp = e.startTime;
  }).observe({ type: 'paint', buffered: true });
};

const perf = (page: Page) => page.evaluate(() => (window as any).__perf as { lcp: number; inp: number; fcp: number });

test.describe('桌面核心页面', () => {
  for (const p of CORE_PAGES) {
    test(`${p.name}（${p.path}）LCP ≤2.5s、INP ≤200ms`, async ({ browser }) => {
      const { context, page } = await loggedIn(browser, 'admin', { viewport: { width: 1440, height: 900 } });
      await context.addInitScript(OBSERVE);
      // 先打开一次让路由块进入 HTTP 缓存（与用户日常使用一致），再测量整页加载
      await page.goto(p.path);
      await page.waitForLoadState('networkidle');
      await page.goto(p.path);
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(300);
      // 交互：切换侧栏两次（触发整页重新布局），按键交互也计入 INP
      for (let i = 0; i < 2; i++) {
        await page.locator('#hamburger-container').click();
        await page.waitForTimeout(400);
      }
      // 让 event timing 条目送达（交互后下一帧之后才分发）
      await page.waitForTimeout(300);
      const m = await perf(page);
      test.info().annotations.push({ type: 'perf', description: `${p.name}: LCP ${Math.round(m.lcp)}ms, INP ${Math.round(m.inp)}ms` });
      console.log(`${p.name}: LCP ${Math.round(m.lcp)}ms, INP ${Math.round(m.inp)}ms`);
      expect(m.lcp, `${p.name} LCP`).toBeGreaterThan(0);
      expect(m.lcp, `${p.name} LCP`).toBeLessThanOrEqual(LCP_BUDGET);
      expect(m.inp, `${p.name} INP`).toBeLessThanOrEqual(INP_BUDGET);
      await context.close();
    });
  }
});

const NETWORKS = {
  '4G': { latency: 170, down: 9 * 1024 * 1024, up: 9 * 1024 * 1024, budget: MOBILE_BUDGET },
  慢速4G: { latency: 150, down: 1.6 * 1024 * 1024, up: 750 * 1024, budget: undefined }
} as const;

for (const [net, cfg] of Object.entries(NETWORKS)) test(`移动工作台：模拟${net}首屏${cfg.budget ? ' ≤3s' : '（记录）'}`, async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'demo_operator', {
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
    serviceWorkers: 'block'
  });
  await context.addInitScript(OBSERVE);
  const cdp = await context.newCDPSession(page);
  await cdp.send('Network.enable');
  await cdp.send('Network.setCacheDisabled', { cacheDisabled: true });
  await cdp.send('Network.emulateNetworkConditions', {
    offline: false,
    latency: cfg.latency,
    downloadThroughput: cfg.down / 8,
    uploadThroughput: cfg.up / 8
  });
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
  const t0 = Date.now();
  await page.goto('/m');
  // 首屏：本班次任务区出现（有任务时为卡片，没有时为空状态）
  await page.locator('[data-testid="m-home"], .m-home, [data-testid^="m-section"]').first().waitFor({ timeout: 15000 });
  const wall = Date.now() - t0;
  await page.waitForTimeout(500);
  const m = await perf(page);
  console.log(`移动工作台（模拟${net}）：任务区可见 ${wall}ms，FCP ${Math.round(m.fcp)}ms，LCP ${Math.round(m.lcp)}ms`);
  test.info().annotations.push({ type: 'perf', description: `移动 ${net}: 首屏 ${wall}ms` });
  if (cfg.budget) expect(wall, `移动首屏（${net}）`).toBeLessThanOrEqual(cfg.budget);
  await context.close();
});
