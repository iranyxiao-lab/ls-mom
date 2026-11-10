/**
 * modernize-ui-ux 5.4：抽屉详情（部署环境，无头）。
 * 车间主任在不符合项台账连续审阅三条待审核记录：抽屉里看时间轴、填写意见驳回一条、"下一条"切换，
 * 期间列表不重新查询；关闭抽屉后才刷新；拖宽抽屉后重新打开仍是该宽度；"在新页签打开"显示同一条记录。
 */
import { expect, test } from '@playwright/test';
import { api, loggedIn } from '../support/auth';

test.describe.configure({ timeout: 180_000 });
const NC = 'ls.quality.NonConformance';
const WORKSHOP = 17; // 一车间

test('连续审阅：抽屉内办理并切换下一条，列表保持不动', async ({ browser }) => {
  const run = Date.now().toString(36).toUpperCase();
  const marker = `RD${run}`;
  const { context: adminCtx, page: adminPage } = await loggedIn(browser, 'admin');
  await adminPage.goto('/index');
  const admin = api(adminPage);
  const ids: number[] = [];
  for (const s of ['1', '2', '3']) {
    const r = await admin('POST', `/api/crud/${NC}`, { title: `${marker}-${s}`, description: '抽屉审阅测试', severity: 'LOW', orgId: WORKSHOP });
    expect(r.code, JSON.stringify(r)).toBe(200);
    const cur = (await admin('GET', `/api/crud/${NC}/${r.data.id}`)).data;
    const sub = await admin('POST', `/api/op/${NC}.submit`, { targetId: String(r.data.id), version: cur.version, input: {} });
    expect(sub.code, JSON.stringify(sub)).toBe(200);
    ids.push(r.data.id);
  }
  await adminCtx.close();

  const { context, page } = await loggedIn(browser, 'demo_leader', { viewport: { width: 1440, height: 900 } });
  await page.goto('/index');
  await page.evaluate(() => localStorage.removeItem('ls:ledger-detail-width'));
  await api(page)('PUT', '/api/ui/saved-views/default', { concept: NC, viewId: null });
  await page.goto(`/lsmom/ledger/${NC}`);
  await page.getByTestId('keyword').fill(marker);
  await page.getByTestId('keyword').press('Enter');
  const rows = page.locator('[data-testid="table"] .vxe-table--main-wrapper .vxe-body--row');
  await expect(rows).toHaveCount(3);
  await expect(rows.first()).toContainText(`${marker}-3`); // 新的在前

  let queries = 0;
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().includes(`/api/crud/${NC}/query`)) queries++;
  });

  await rows.first().click();
  const drawer = page.getByTestId('ledger-detail');
  await expect(drawer.getByTestId('detail-title')).toContainText(`${marker}-3`);
  await expect(drawer.getByTestId('detail-pos')).toHaveText('1 / 3');
  await expect(drawer.getByTestId('detail-status')).toHaveText('已登记提交');
  // 时间轴有登记提交
  await drawer.getByRole('tab', { name: /时间轴/ }).click();
  await expect(drawer.getByTestId('detail-timeline')).toContainText('已登记提交');

  // 驳回需要意见
  await drawer.getByTestId(`detail-op-${NC}.reject`).click();
  await drawer.getByTestId('detail-op-confirm').click();
  await expect(page.locator('.el-message', { hasText: '请填写审核意见' }).first()).toBeVisible();
  await drawer.getByTestId('detail-in-auditOpinion').fill('不构成不符合项');
  await drawer.getByTestId('detail-op-confirm').click();
  // 驳回不可撤销：确认框列出影响对象与后果
  const box = page.locator('.ls-op-confirm-box');
  await expect(box.getByTestId('op-confirm-body')).toContainText(`将驳回 1 个不符合项：${marker}-3`);
  await expect(box.getByTestId('op-confirm-body')).toContainText('状态：已登记提交 → 已审核驳回');
  await expect(box.getByTestId('op-confirm-body')).toContainText('流程结束，不能再编辑或办理');
  await box.getByRole('button', { name: '驳回' }).click();
  await expect(drawer.getByTestId('detail-status')).toHaveText('已审核驳回');
  await expect(drawer.getByTestId(`detail-op-${NC}.reject`)).toHaveCount(0);

  // 下一条：抽屉切换，列表不动
  await drawer.getByTestId('detail-next').click();
  await expect(drawer.getByTestId('detail-title')).toContainText(`${marker}-2`);
  await expect(drawer.getByTestId('detail-pos')).toHaveText('2 / 3');
  await expect(drawer.getByTestId('detail-status')).toHaveText('已登记提交');
  await drawer.getByTestId('detail-next').click();
  await expect(drawer.getByTestId('detail-title')).toContainText(`${marker}-1`);
  await expect(drawer.getByTestId('detail-next')).toBeDisabled();
  expect(queries).toBe(0);
  // 列表里第一行仍显示原状态（未刷新）
  await expect(rows.first()).toContainText(`${marker}-3`);

  // 在新页签打开
  const [tab] = await Promise.all([context.waitForEvent('page'), drawer.getByTestId('detail-newtab').click()]);
  await tab.waitForLoadState();
  await expect(tab).toHaveURL(new RegExp(`/lsmom/record/${encodeURIComponent(NC).replace(/\./g, '\\.')}/${ids[0]}$`));
  await expect(tab.getByTestId('record-view').getByTestId('detail-status')).toHaveText('已登记提交');
  await expect(tab.getByTestId('record-page')).toContainText(`${marker}-1`);
  await tab.close();

  // 拖宽抽屉
  const panel = page.locator('.el-drawer:has([data-testid="detail-title"])');
  const before = (await panel.boundingBox())!.width;
  const dragger = panel.locator('.el-drawer__dragger');
  const d = (await dragger.boundingBox())!;
  await page.mouse.move(d.x + d.width / 2, d.y + 100);
  await page.mouse.down();
  await page.mouse.move(d.x - 150, d.y + 100, { steps: 8 });
  await page.mouse.up();
  const after = (await panel.boundingBox())!.width;
  expect(after).toBeGreaterThan(before + 100);

  // 关闭后列表才刷新
  await page.keyboard.press('Escape');
  await expect(panel).toBeHidden();
  await expect.poll(() => queries).toBe(1);
  const rejected = rows.filter({ hasText: `${marker}-3` });
  await expect(rejected).toContainText('已审核驳回');
  await expect(rejected).not.toContainText('REJECTED');

  // 重新打开：宽度记住
  await page.reload();
  await page.getByTestId('keyword').fill(marker);
  await page.getByTestId('keyword').press('Enter');
  await expect(rows).toHaveCount(3);
  await rows.nth(1).click();
  await expect(panel).toBeVisible();
  expect(Math.abs((await panel.boundingBox())!.width - after)).toBeLessThan(4);
  await context.close();
});
