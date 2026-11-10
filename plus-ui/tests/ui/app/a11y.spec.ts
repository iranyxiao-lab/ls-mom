/** 核心页面无障碍扫描（无头）。对部署环境运行：LSMOM_E2E_BASE=… pnpm exec playwright test --project=app */
import { test } from '@playwright/test';
import { loggedIn } from '../support/auth';
import { expectAccessible, scan } from '../support/a11y';
import { CORE_PAGES } from '../support/pages';

export { CORE_PAGES };


for (const theme of ['light', 'dark'] as const) {
  test(`登录页（${theme}）无严重无障碍问题`, async ({ browser }) => {
    const ctx = await browser.newContext({ baseURL: process.env.LSMOM_E2E_BASE ?? 'http://127.0.0.1:18081' });
    await ctx.addInitScript((t) => localStorage.setItem('ls-theme', t), theme);
    const page = await ctx.newPage();
    await page.goto('/login');
    await page.waitForSelector('input[type="password"]');
    await page.waitForLoadState('networkidle');
    await expectAccessible(page, `登录页（${theme}）`);
    await ctx.close();
  });

  for (const p of CORE_PAGES) {
    test(`${p.name}（${p.path}，${theme}）无严重无障碍问题`, async ({ browser }) => {
      const { context, page } = await loggedIn(browser, 'admin');
      await context.addInitScript((t) => localStorage.setItem('ls-theme', t), theme);
      await page.goto(p.path);
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(500);
      await expectAccessible(page, `${p.name}（${theme}）`);
      await context.close();
    });
  }
}

// 移动端（操作工）：工作台、点检、报缺陷、我的
for (const theme of ['light', 'dark'] as const) {
  for (const p of [
    { path: '/m', name: '移动工作台' },
    { path: '/m/inspect', name: '移动点检' },
    { path: '/m/defect', name: '移动报缺陷' },
    { path: '/m/me', name: '移动我的' }
  ]) {
    test(`${p.name}（${p.path}，${theme}）无严重无障碍问题`, async ({ browser }) => {
      const { context, page } = await loggedIn(browser, 'demo_operator', { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
      await context.addInitScript((t) => localStorage.setItem('ls-theme', t), theme);
      await page.goto(p.path);
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(500);
      await expectAccessible(page, `${p.name}（${theme}）`);
      await context.close();
    });
  }
}

test('自检：图标按钮缺少可读标签时扫描失败', async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'admin');
  await page.goto('/index');
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => {
    const b = document.createElement('button');
    b.id = 'a11y-probe';
    b.innerHTML = '<span class="i-lucide-bell"></span>';
    document.body.appendChild(b);
  });
  const vs = await scan(page, { include: '#a11y-probe' });
  test.expect(vs.map((v) => v.id)).toContain('button-name');
  await context.close();
});
