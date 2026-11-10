/**
 * modernize-ui-ux 6.6：新手引导（部署环境，无头）。
 * 首次进入协同工作中心自动出现分步引导 → 第一步点「跳过」→ 关闭，刷新后不再自动出现（记在服务端偏好，换浏览器也保持）；
 * 移动报工按 Esc 关闭也记为跳过；
 * 帮助菜单「重看本页引导」可重新打开并走完；「操作手册」打开知识中心并带上本页主题。
 */
import { expect, test } from '@playwright/test';
import { api, loggedIn } from '../support/auth';

test.describe.configure({ timeout: 120_000 });

test('首次自动引导，跳过后不再自动出现，可从帮助菜单重看', async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'demo_leader', { viewport: { width: 1440, height: 900 } });
  await api(page)('PUT', '/api/ui/prefs', { tourWorkbench: 'pending' });
  await page.goto('/lsmom/workbench');
  const pop = page.getByTestId('tour-popover');
  await expect(pop).toContainText('欢迎使用协同工作中心');
  await expect(pop).toContainText('1 / 4');
  await page.getByTestId('tour-skip').click();
  await expect(pop).toHaveCount(0);
  await expect.poll(async () => (await api(page)('GET', '/api/ui/prefs')).data.prefs.tourWorkbench).toBe('skipped');

  await page.reload();
  await expect(page.getByTestId('work-center')).toBeVisible();
  await page.waitForTimeout(1500);
  await expect(pop).toHaveCount(0);

  // 帮助菜单：重看并走完
  await page.getByTestId('help-open').click();
  await page.getByTestId('help-tour').click();
  await expect(pop).toContainText('欢迎使用协同工作中心');
  for (const t of ['按类别查看', '筛选', '原地办理']) {
    await page.getByTestId('tour-next').click();
    await expect(pop).toContainText(t);
  }
  await expect(page.locator('[data-tour="wc-list"].driver-active-element')).toHaveCount(1);
  await page.getByTestId('tour-next').click();
  await expect(pop).toHaveCount(0);
  await expect.poll(async () => (await api(page)('GET', '/api/ui/prefs')).data.prefs.tourWorkbench).toBe('done');

  // 操作手册：知识中心带上本页主题
  await page.getByTestId('help-open').click();
  await page.getByTestId('help-sop').click();
  await expect(page).toHaveURL(/\/agent\/knowledge\?q=/);
  await expect(page.getByTestId('in-question')).toHaveValue(/协同工作中心/);
  await context.close();
});

test('移动报工首次引导：按 Esc 关闭也记为跳过', async ({ browser }) => {
  const a = await loggedIn(browser, 'demo_operator', { viewport: { width: 1440, height: 900 } });
  await api(a.page)('PUT', '/api/ui/prefs', { tourMobileReport: 'pending' });
  await a.page.goto('/production/report');
  await expect(a.page.getByTestId('tour-popover')).toContainText('欢迎使用移动报工');
  await a.page.keyboard.press('Escape');
  await expect(a.page.getByTestId('tour-popover')).toHaveCount(0);
  await expect.poll(async () => (await api(a.page)('GET', '/api/ui/prefs')).data.prefs.tourMobileReport).toBe('skipped');
  await a.page.reload();
  await expect(a.page.getByTestId('mobile-report')).toBeVisible();
  await a.page.waitForTimeout(1500);
  await expect(a.page.getByTestId('tour-popover')).toHaveCount(0);
  await a.context.close();
});
