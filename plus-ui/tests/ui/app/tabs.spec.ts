/**
 * modernize-ui-ux 3.2：多页签状态保留（部署环境，无头）。
 * 切回列表：筛选条件、筛选结果与滚动位置不变；未保存表单：切换页签后内容还在，关闭该页签时提示，可选择继续编辑或仍然关闭。
 */
import { expect, test, type Page } from '@playwright/test';
import { api, loggedIn, orgIds } from '../support/auth';

const tag = (page: Page, title: string) => page.locator('.tags-view-item', { hasText: title });
/** 应用内跳转（与点菜单相同）；page.goto 会整页刷新，页签缓存随之清空。 */
const spaGo = async (page: Page, path: string) => {
  await page.evaluate((p) => (document.querySelector('#app') as any).__vue_app__.config.globalProperties.$router.push(p), path);
  await page.waitForLoadState('networkidle');
};

test('从筛选后的列表切到其他页签再切回：筛选条件、结果与滚动位置不变', async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'admin', { viewport: { width: 1440, height: 700 } });
  await page.goto('/asset/center');
  // 准备数据：本次专用前缀的 30 台设备，筛选后足够滚动（不依赖环境里已有的数据）
  const mark = `TB${Date.now().toString(36).toUpperCase()}-`;
  const call = api(page);
  const { team } = await orgIds(page);
  for (let i = 0; i < 30; i++) {
    const r = await call('POST', '/api/crud/ls.asset.Asset', { assetCode: `${mark}${i}`, name: `页签测试 ${i}`, assetType: 'STATION', orgId: team });
    expect(r.code, JSON.stringify(r)).toBe(200);
  }
  const keyword = page.getByTestId('asset-keyword');
  await keyword.fill(mark);
  await keyword.press('Enter');
  const rows = page.locator('[data-testid="asset-table"] .el-table__body tr.el-table__row');
  await expect.poll(async () => (await rows.allInnerTexts()).filter((t) => !t.includes(mark)).length, { timeout: 15000 }).toBe(0);
  const filtered = await rows.allInnerTexts();
  expect(filtered.length, '筛选后有足够多的行可以滚动').toBeGreaterThan(10);
  expect(filtered.every((t) => t.includes(mark))).toBe(true);
  await page.evaluate(() => window.scrollTo(0, 250));
  await expect.poll(() => page.evaluate(() => Math.round(window.scrollY))).toBeGreaterThan(200);
  const y = await page.evaluate(() => Math.round(window.scrollY));

  // 打开另一个页面（新页签），再点回设备台账页签
  await spaGo(page, '/production/requests');
  await expect(tag(page, '生产需求板')).toHaveClass(/active/);
  await tag(page, '设备台账').click();
  await expect(page.getByTestId('asset-center')).toBeVisible();

  await expect(keyword).toHaveValue(mark);
  expect(await rows.allInnerTexts(), '筛选结果不变（没有重新加载成全部数据）').toEqual(filtered);
  await expect.poll(() => page.evaluate(() => Math.round(window.scrollY)), { message: '滚动位置恢复' }).toBeGreaterThan(y - 5);
  expect(await page.evaluate(() => Math.round(window.scrollY))).toBeLessThan(y + 5);
  await context.close();
});

test('未保存的登记内容：切换页签后还在；在其他页签关闭它时提示，可继续编辑或仍然关闭', async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'admin', { viewport: { width: 1440, height: 900 } });
  await page.goto('/quality/nc');
  await page.getByTestId('btn-new-nc').click();
  await page.getByTestId('nc-title').fill('页签状态保留测试');
  // 从命令面板、通知等跳到其他页面：登记对话框随页签隐藏，不挡住新页面
  await spaGo(page, '/asset/center');
  await expect(page.getByTestId('nc-title')).toBeHidden();
  await page.getByTestId('asset-keyword').click();

  await tag(page, '不符合项').click();
  await expect(page.getByTestId('nc-title'), '切回后对话框与填写内容仍在').toHaveValue('页签状态保留测试');

  // 在设备台账页签上关闭含未保存内容的「不符合项」页签 → 提示
  await spaGo(page, '/asset/center');
  const closeNc = () => tag(page, '不符合项').locator('span').last().click();
  await closeNc();
  const box = page.locator('.el-message-box');
  await expect(box).toContainText('有未保存的内容');
  await box.getByRole('button', { name: '继续编辑' }).click();
  await expect(tag(page, '不符合项'), '选择继续编辑，页签保留').toHaveCount(1);
  await tag(page, '不符合项').click();
  await expect(page.getByTestId('nc-title')).toHaveValue('页签状态保留测试');

  await spaGo(page, '/asset/center');
  await closeNc();
  await page.locator('.el-message-box').getByRole('button', { name: '仍然关闭' }).click();
  await expect(tag(page, '不符合项'), '选择仍然关闭，页签关闭').toHaveCount(0);
  await expect(page.locator('body > .el-overlay:visible'), '关闭后没有残留的对话框').toHaveCount(0);

  // 没有未保存内容的页签直接关闭，不提示
  await spaGo(page, '/production/requests');
  await tag(page, '设备台账').locator('span').last().click();
  await expect(page.locator('.el-message-box:visible')).toHaveCount(0);
  await expect(tag(page, '设备台账')).toHaveCount(0);
  await context.close();
});
