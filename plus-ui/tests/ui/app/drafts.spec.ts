/**
 * modernize-ui-ux 4.3：草稿自动保存与会话过期原地重新登录（部署环境，无头）。
 * 1. 填了一半关闭浏览器 → 换一个全新的浏览器登录后打开同一表单，提示"发现未提交的草稿（时间）"，恢复后内容回来；丢弃后不再提示。
 * 2. 提交时会话已过期 → 弹出原地登录框 → 登录后自动继续提交，填写内容不丢，记录已保存。
 */
import { expect, test, type Page } from '@playwright/test';
import { API, CLIENT_ID, api, freshLogin, seedPassword } from '../support/auth';
import { expectAccessible } from '../support/a11y';

const USER = 'demo_asset';
const DEFECT = 'ls.asset.Defect';
test.describe.configure({ timeout: 150_000, mode: 'serial' });

async function pickAsset(page: Page) {
  await page.goto('/index');
  const rows = (
    await api(page)('POST', '/api/crud/ls.asset.Asset/query', { filters: [{ field: 'status', op: 'eq', value: 'RUNNING' }], page: 1, size: 20 })
  ).data.rows as any[];
  expect(rows.length, '有在用设备').toBeGreaterThan(0);
  return rows[rows.length - 1];
}

async function openDefect(page: Page, assetId: number) {
  await page.goto('/asset/center');
  await page.getByTestId(`asset-row-${assetId}`).click();
  await page.getByTestId('btn-asset-defect').click();
  await expect(page.getByTestId('defect-dialog').getByTestId('schema-form')).toBeVisible();
  return page.getByTestId('defect-dialog');
}

test('关闭浏览器后重新打开：提示恢复草稿，恢复后内容回来，丢弃后不再提示', async ({ browser }) => {
  const a = await freshLogin(browser, USER, { viewport: { width: 1440, height: 900 } });
  const asset = await pickAsset(a.page);
  // 清掉之前遗留的草稿
  await api(a.page)('DELETE', `/api/ui/drafts/${DEFECT}:new:asset-${asset.id}`);
  let dlg = await openDefect(a.page, asset.id);
  await expect(dlg.getByTestId('draft-found')).toHaveCount(0);
  await dlg.getByTestId('input-title').fill('草稿测试：轴承温度偏高');
  await dlg.getByTestId('input-symptom').fill('巡检时手感发烫，未来得及登记');
  await expect(dlg.getByTestId('draft-saved'), '3 秒内自动保存').toContainText('草稿已自动保存', { timeout: 6000 });
  await a.context.close();

  const b = await freshLogin(browser, USER, { viewport: { width: 1440, height: 900 } });
  dlg = await openDefect(b.page, asset.id);
  await expect(dlg.getByTestId('draft-found')).toContainText(/发现未提交的草稿（(今天 )?\d{1,2}:\d{2}|\d+月\d+日/);
  await expect(dlg.getByTestId('input-title')).toHaveValue('');
  await expectAccessible(b.page, '草稿恢复提示', { include: '[data-testid="defect-dialog"]' });
  await dlg.getByTestId('draft-restore').click();
  await expect(dlg.getByTestId('input-title')).toHaveValue('草稿测试：轴承温度偏高');
  await expect(dlg.getByTestId('input-symptom')).toHaveValue('巡检时手感发烫，未来得及登记');
  await b.page.keyboard.press('Escape');

  // 再打开：内容与草稿相同时不提示（已恢复）；丢弃后不再提示
  dlg = await openDefect(b.page, asset.id);
  await dlg.getByTestId('draft-discard').click();
  await expect(dlg.getByTestId('draft-found')).toHaveCount(0);
  await b.page.keyboard.press('Escape');
  dlg = await openDefect(b.page, asset.id);
  await expect(dlg.getByTestId('draft-found')).toHaveCount(0);
  expect((await api(b.page)('GET', `/api/ui/drafts/${DEFECT}:new:asset-${asset.id}`)).data, '服务端草稿已删除').toBeNull();
  await b.context.close();
});

test('提交时会话已过期：原地重新登录后自动继续提交，内容不丢', async ({ browser, playwright }) => {
  const { context, page } = await freshLogin(browser, USER, { viewport: { width: 1440, height: 900 } });
  const asset = await pickAsset(page);
  const dlg = await openDefect(page, asset.id);
  const title = '会话过期测试：' + Date.now().toString(36);
  await dlg.getByTestId('input-title').fill(title);
  await dlg.getByTestId('input-symptom').fill('填写中会话过期');

  // 让当前会话失效（相当于超时）
  const token = await page.evaluate(() => localStorage.getItem('Admin-Token'));
  const base = process.env.LSMOM_E2E_BASE ?? 'http://127.0.0.1:18081';
  const req = await playwright.request.newContext({ baseURL: base });
  const out = await req.post(`${API}/auth/logout`, { headers: { Authorization: 'Bearer ' + token, clientid: CLIENT_ID } });
  expect(out.ok()).toBe(true);
  await req.dispose();

  await page.getByTestId('btn-defect-report').click();
  const relogin = page.getByTestId('relogin-dialog');
  await expect(relogin).toBeVisible();
  await expect(relogin.getByTestId('relogin-user')).toHaveValue(USER);
  await expect(dlg.getByTestId('input-title'), '登录框后面的内容还在').toHaveValue(title);
  await expectAccessible(page, '重新登录框', { include: '.ls-relogin' });
  await relogin.getByTestId('relogin-password').fill(seedPassword(USER));
  await relogin.getByTestId('relogin-submit').click();

  await expect(page.locator('.el-message').first()).toContainText(/缺陷已登记|保存为草稿/);
  await expect(page.getByTestId('defect-dialog')).toBeHidden();
  const rows = (await api(page)('POST', `/api/crud/${DEFECT}/query`, { filters: [{ field: 'title', op: 'eq', value: title }], page: 1, size: 5 }))
    .data.rows as any[];
  expect(rows, '自动继续提交，只保存了一条').toHaveLength(1);
  expect(rows[0].symptom).toBe('填写中会话过期');
  await context.close();
});
