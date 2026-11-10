/**
 * modernize-ui-ux 5.7："有 N 条更新"（部署环境，无头）。
 * 车间主任看着筛选后的不符合项列表并勾选了一条；质检员新增一条同类记录 →
 * 列表顶部出现"有 1 条更新"，列表不自动刷新、勾选保持；点"刷新查看"后出现新记录，原来的勾选仍在。
 */
import { expect, test } from '@playwright/test';
import { api, loggedIn } from '../support/auth';

test.describe.configure({ timeout: 180_000 });
const NC = 'ls.quality.NonConformance';

test('他人新增时提示有更新，勾选保持', async ({ browser }) => {
  const run = Date.now().toString(36).toUpperCase();
  const marker = `LU${run}`;
  const { context: aCtx, page: aPage } = await loggedIn(browser, 'admin');
  await aPage.goto('/index');
  const admin = api(aPage);
  const make = async (s: string) => {
    const r = await admin('POST', `/api/crud/${NC}`, { title: `${marker}-${s}`, description: '更新提示测试', severity: 'LOW', orgId: 17 });
    expect(r.code, JSON.stringify(r)).toBe(200);
    return r.data.id as number;
  };
  await make('1');
  await make('2');

  const { context, page } = await loggedIn(browser, 'demo_leader', { viewport: { width: 1440, height: 900 } });
  await page.goto('/index');
  await api(page)('PUT', '/api/ui/saved-views/default', { concept: NC, viewId: null });
  await page.goto(`/lsmom/ledger/${NC}`);
  await page.getByTestId('keyword').fill(marker);
  await page.getByTestId('keyword').press('Enter');
  const rows = page.locator('[data-testid="table"] .vxe-table--main-wrapper .vxe-body--row');
  await expect(rows).toHaveCount(2);
  const boxes = page.locator('.vxe-body--column.col--checkbox:not(.fixed--hidden) .vxe-cell--checkbox');
  await boxes.nth(1).click(); // 勾选 -1
  await expect(page.getByTestId('batch-count')).toHaveText('已选 1 条');

  let queries = 0;
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().includes(`/api/crud/${NC}/query`)) queries++;
  });
  await make('3');
  await expect(page.getByTestId('updates-banner')).toContainText('有 1 条更新', { timeout: 45_000 });
  expect(queries).toBe(0);
  await expect(rows).toHaveCount(2);
  await expect(page.getByTestId('batch-count')).toHaveText('已选 1 条');

  await page.getByTestId('updates-refresh').click();
  await expect(rows).toHaveCount(3);
  await expect(rows.first()).toContainText(`${marker}-3`);
  await expect(page.getByTestId('updates-banner')).toHaveCount(0);
  await expect(page.getByTestId('batch-count')).toHaveText('已选 1 条');
  await expect(page.locator('.vxe-table--main-wrapper .vxe-body--row.row--checked')).toContainText(`${marker}-1`);
  await context.close();
  await aCtx.close();
});
