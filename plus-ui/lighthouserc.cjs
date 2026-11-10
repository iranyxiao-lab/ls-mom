/**
 * 性能预算（spec ui/ux-quality「性能预算」）：Lighthouse CI，只在合并到 main 时运行（ux-gate 工作流）。
 * LHCI_FORM=desktop（默认）：桌面主要页面 LCP ≤2.5s、总阻塞时间 ≤200ms（实验室环境下 INP 的替代指标）；
 * LHCI_FORM=mobile：模拟 4G（9Mbps、往返 70ms、CPU 4 倍降速）首屏 LCP ≤3s。
 * 运行：LSMOM_E2E_BASE=… CHROME_PATH=… pnpm exec lhci autorun --config=lighthouserc.cjs
 */
/** Playwright 安装的无头 Chromium（CI 与本机都只装 headless shell，不弹窗口）。 */
function chromePath() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH;
  const full = require('playwright').chromium.executablePath();
  const shell = full.replace(/chromium-(\d+)/, 'chromium_headless_shell-$1').replace(/chrome-linux64\/chrome$/, 'chrome-headless-shell-linux64/chrome-headless-shell');
  return require('node:fs').existsSync(shell) ? shell : full;
}

const base = process.env.LSMOM_E2E_BASE ?? 'http://127.0.0.1:18081';
const mobile = process.env.LHCI_FORM === 'mobile';
const runs = Number(process.env.LHCI_RUNS ?? 3);
// 预算可用环境变量覆盖（门禁自检时调低以确认超标会失败）
const lcp = Number(process.env.LHCI_LCP_MAX ?? (mobile ? 3000 : 2500));
const tbt = Number(process.env.LHCI_TBT_MAX ?? 200);

const desktopUrls = ['/index', '/lsmom/workbench', '/production/board', '/production/requests'];
const mobileUrls = ['/login', '/index'];

module.exports = {
  ci: {
    collect: {
      url: (mobile ? mobileUrls : desktopUrls).map((p) => base + p),
      numberOfRuns: runs,
      // 默认使用 Playwright 自带的 Chromium（无头）
      chromePath: chromePath(),
      puppeteerScript: './tests/perf/lhci-login.cjs',
      puppeteerLaunchOptions: { headless: true, executablePath: chromePath(), args: ['--no-sandbox'] },
      settings: {
        onlyCategories: ['performance'],
        chromeFlags: '--headless=new --no-sandbox',
        disableStorageReset: true,
        ...(mobile
          ? {
              formFactor: 'mobile',
              screenEmulation: { mobile: true, width: 390, height: 844, deviceScaleFactor: 3, disabled: false },
              throttlingMethod: 'simulate',
              throttling: { rttMs: 70, throughputKbps: 9000, cpuSlowdownMultiplier: 4 }
            }
          : { preset: 'desktop' })
      }
    },
    assert: {
      assertions: mobile
        ? { 'largest-contentful-paint': ['error', { maxNumericValue: lcp, aggregationMethod: 'median' }] }
        : {
            'largest-contentful-paint': ['error', { maxNumericValue: lcp, aggregationMethod: 'median' }],
            'total-blocking-time': ['error', { maxNumericValue: tbt, aggregationMethod: 'median' }]
          }
    },
    upload: { target: 'filesystem', outputDir: mobile ? '.lighthouseci/mobile' : '.lighthouseci/desktop' }
  }
};
