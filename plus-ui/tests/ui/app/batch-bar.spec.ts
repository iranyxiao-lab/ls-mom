/**
 * modernize-ui-ux 5.6：批量操作栏（部署环境，无头）。
 * 不符合项台账 20 条草稿：
 * 每页 10 条时勾选本页 → "选择全部 20 条筛选结果"需二次确认 → 导出所选走异步导出任务；
 * 每页 20 条全选后，其中 2 条被他人改过（版本变了）→ 批量登记提交逐条结果 18 成功 2 失败并写明原因 →
 * "重试失败项"取最新版本后只发 2 条请求，全部成功。
 */
import { expect, test } from '@playwright/test';
import { api, loggedIn } from '../support/auth';

test.describe.configure({ timeout: 240_000 });
const NC = 'ls.quality.NonConformance';

test('批量登记提交：跨页全选二次确认、逐条结果、只重试失败项', async ({ browser }) => {
  const run = Date.now().toString(36).toUpperCase();
  const marker = `BB${run}`;
  const { context, page } = await loggedIn(browser, 'admin', { viewport: { width: 1600, height: 1000 } });
  await page.goto('/index');
  const call = api(page);
  const ids: number[] = [];
  for (let i = 1; i <= 20; i++) {
    const r = await call('POST', `/api/crud/${NC}`, {
      title: `${marker}-${String(i).padStart(2, '0')}`,
      description: '批量测试',
      severity: 'LOW',
      orgId: 17
    });
    expect(r.code, JSON.stringify(r)).toBe(200);
    ids.push(r.data.id);
  }
  const bad = [ids[4], ids[12]]; // 第 05、13 条
  await call('PUT', '/api/ui/saved-views/default', { concept: NC, viewId: null });

  await page.goto(`/lsmom/ledger/${NC}`);
  await page.getByTestId('keyword').fill(marker);
  await page.getByTestId('keyword').press('Enter');
  const rows = page.locator('[data-testid="table"] .vxe-table--main-wrapper .vxe-body--row');
  await expect(rows).toHaveCount(20);
  await expect(page.getByTestId('batch-bar')).toHaveCount(0);

  // 每页 10 条
  await page.locator('.schema-table__pager .el-pagination__sizes .el-select').click();
  await page.locator('.el-select-dropdown__item:visible', { hasText: '10条/页' }).click();
  await expect(rows).toHaveCount(10);

  const checkAll = page.locator('.vxe-header--column.col--checkbox:not(.fixed--hidden) .vxe-cell--checkbox');
  await checkAll.click();
  await expect(page.getByTestId('batch-count')).toHaveText('已选 10 条');
  await page.getByTestId('batch-select-all').click();
  const box = page.locator('.el-message-box');
  await expect(box).toContainText('全部 20 条筛选结果');
  await box.getByRole('button', { name: '选择 20 条' }).click();
  await expect(page.getByTestId('batch-count')).toContainText('已选 20 条（全部筛选结果）');

  // 导出全部筛选结果：确认后异步导出
  await page.getByTestId('batch-export').click();
  await expect(box).toContainText('导出全部 20 条');
  await box.getByRole('button', { name: '确定' }).click();
  await expect(page.getByTestId('export-job')).toContainText('（20 行）', { timeout: 30_000 });

  // 回到每页 20 条，全选本页；随后其中 2 条被他人修改
  await page.getByTestId('batch-clear').click();
  await page.locator('.schema-table__pager .el-pagination__sizes .el-select').click();
  await page.locator('.el-select-dropdown__item:visible', { hasText: '20条/页' }).click();
  await expect(rows).toHaveCount(20);
  await checkAll.click();
  await expect(page.getByTestId('batch-count')).toHaveText('已选 20 条');
  for (const id of bad) {
    const cur = (await call('GET', `/api/crud/${NC}/${id}`)).data;
    const u = await call('PUT', `/api/crud/${NC}/${id}?version=${cur.version}`, { description: '他人补充了描述' });
    expect(u.code, JSON.stringify(u)).toBe(200);
  }

  // 批量登记提交
  let submits = 0;
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().includes(`/api/op/${NC}.submit`)) submits++;
  });
  await page.getByTestId(`batch-${NC}.submit`).click();
  const dlg = page.getByTestId('batch-dialog');
  await expect(dlg.getByTestId('batch-summary')).toContainText('共 20 条：成功 18 条，失败 2 条', { timeout: 60_000 });
  expect(submits).toBe(20);
  const failed = dlg.getByTestId('batch-failed');
  await expect(failed).toContainText(`${marker}-05`);
  await expect(failed).toContainText(`${marker}-13`);
  await expect(failed).toContainText(/已被|版本|修改/);

  // 只重试失败项（取最新版本）
  submits = 0;
  await dlg.getByTestId('batch-retry').click();
  await expect(dlg.getByTestId('batch-summary')).toContainText('共 20 条：成功 20 条，失败 0 条', { timeout: 30_000 });
  expect(submits).toBe(2);
  await expect(dlg.getByTestId('batch-retry')).toHaveCount(0);
  for (const id of ids) expect((await call('GET', `/api/crud/${NC}/${id}`)).data.status).toBe('PENDING_AUDIT');
  await context.close();
});
