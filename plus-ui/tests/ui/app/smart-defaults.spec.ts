/**
 * modernize-ui-ux 4.2：从设备报缺陷自动带出（部署环境，无头）。
 * 设备员在设备详情点「登记缺陷」→ 设备、位置、登记人、登记时间已填好并标为"自动带入"，只需填描述和严重度 →
 * 登记后服务端记录带出的值；再次登记时严重度为"上次填写"，换一个全新的浏览器登录仍然保持。
 */
import { expect, test, type Page } from '@playwright/test';
import { api, freshLogin } from '../support/auth';
import { expectAccessible } from '../support/a11y';

const USER = 'demo_asset';
const DEFECT = 'ls.asset.Defect';
test.describe.configure({ timeout: 150_000 });

async function openDefect(page: Page, assetId: number) {
  await page.goto('/asset/center');
  await page.getByTestId(`asset-row-${assetId}`).click();
  await page.getByTestId('btn-asset-defect').click();
  await expect(page.getByTestId('defect-dialog').getByTestId('schema-form')).toBeVisible();
}

test('从设备报缺陷：带出设备、位置、登记人、时间；严重度记住上次选择', async ({ browser }) => {
  const a = await freshLogin(browser, USER, { viewport: { width: 1440, height: 900 } });
  await a.page.goto('/index');
  const call = api(a.page);
  const assets = (
    await call('POST', '/api/crud/ls.asset.Asset/query', { filters: [{ field: 'status', op: 'eq', value: 'RUNNING' }], page: 1, size: 50 })
  ).data.rows as any[];
  const asset = assets.find((x) => x.location) ?? assets[0];
  expect(asset, '有可登记缺陷的在用设备').toBeTruthy();
  const me = (await call('GET', '/api/flow/me')).data;

  // 先清掉本人对缺陷表单的记忆值：选一次"中"再登记
  await openDefect(a.page, asset.id);
  const dlg = a.page.getByTestId('defect-dialog');
  await expect(dlg.getByTestId('field-asset')).toContainText(asset.name);
  if (asset.location) await expect(dlg.getByTestId('input-location')).toHaveValue(asset.location);
  await expect(dlg.getByTestId('origin-asset')).toHaveText('自动带入');
  await expect(dlg.getByTestId('origin-reporter')).toHaveText('自动带入');
  await expect(dlg.getByTestId('origin-reportedAt')).toHaveText('自动带入');
  await expect(dlg.getByTestId('field-reporter')).not.toContainText('请选择');
  await expectAccessible(a.page, '缺陷登记表单', { include: '[data-testid="defect-dialog"]' });

  // 登记时间按服务器时钟（与系统其他时间一致），与浏览器所在时区无关
  const serverNow = (await call('GET', '/api/ui/form-context')).data.now as string;
  await dlg.getByTestId('input-title').fill('自动带入测试：泵体异响');
  await dlg.getByTestId('input-severity').click();
  await a.page.locator('.el-select-dropdown__item:visible').filter({ hasText: /^高$/ }).click();
  await dlg.getByTestId('input-symptom').fill('运行时有周期性异响');
  await a.page.getByTestId('btn-defect-report').click();
  await expect(a.page.locator('.el-message').first()).toContainText(/缺陷已登记|保存为草稿/);

  const rows = (
    await call('POST', `/api/crud/${DEFECT}/query`, {
      filters: [{ field: 'asset', op: 'eq', value: asset.id }],
      sort: [{ field: 'id', dir: 'desc' }],
      page: 1,
      size: 1
    })
  ).data.rows as any[];
  const d = rows[0];
  expect(d.title).toBe('自动带入测试：泵体异响');
  expect(d.severity).toBe('HIGH');
  expect(d.reporter, '登记人为当前人员').toBe(me.partyId);
  if (asset.location) expect(d.location).toBe(asset.location);
  expect(Math.abs(new Date(d.reportedAt).getTime() - new Date(serverNow).getTime()), '登记时间为当前时间').toBeLessThan(10 * 60_000);

  // 再次登记：严重度为上次填写（与系统带入的标记不同）
  await openDefect(a.page, asset.id);
  await expect(dlg.getByTestId('origin-severity')).toHaveText('上次填写');
  await expect(dlg.getByTestId('field-severity')).toContainText('高');
  await a.context.close();

  // 全新浏览器：记忆值保存在服务端
  const b = await freshLogin(browser, USER, { viewport: { width: 1440, height: 900 } });
  await openDefect(b.page, asset.id);
  const dlg2 = b.page.getByTestId('defect-dialog');
  await expect(dlg2.getByTestId('origin-severity')).toHaveText('上次填写');
  await expect(dlg2.getByTestId('field-severity')).toContainText('高');
  // 改成别的值后标记消失
  await dlg2.getByTestId('input-severity').click();
  await b.page.locator('.el-select-dropdown__item:visible').filter({ hasText: /^低$/ }).click();
  await expect(dlg2.getByTestId('origin-severity')).toHaveCount(0);
  // 恢复为"中"，避免影响其他用例（严重度高的缺陷登记前须上传证据）
  await api(b.page)('PUT', '/api/ui/form-context/remembered/ls.asset.defectForm', { severity: 'MEDIUM' });
  await b.context.close();
});
