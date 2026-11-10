/**
 * modernize-ui-ux 3.5：命令面板（部署环境，无头）。
 * Ctrl+K 打开；"sbtz" 命中设备台账；输入工作单编号首项即该工作、回车打开详情；设备编号直达台账详情；
 * 动作「登记不符合项」直接打开登记对话框；空输入显示收藏与最近访问；其他班组的工作搜不到。
 */
import { expect, test, type Page } from '@playwright/test';
import { API, CLIENT_ID, loggedIn } from '../support/auth';
import { expectAccessible } from '../support/a11y';

const api = (page: Page, method: string, url: string, body?: unknown) =>
  page.evaluate(
    async ([method, url, body, clientId, prefix]) => {
      const r = await fetch(prefix + url, {
        method,
        headers: { 'Content-Type': 'application/json', clientid: clientId, Authorization: 'Bearer ' + localStorage.getItem('Admin-Token') },
        body: body === undefined ? undefined : JSON.stringify(body)
      });
      return r.json();
    },
    [method, url, body, CLIENT_ID, API] as const
  );

const palette = (page: Page) => page.getByTestId('command-palette');
const input = (page: Page) => page.getByTestId('palette-input');
async function openPalette(page: Page) {
  // 布局（顶栏）加载完成后快捷键才生效
  await page.getByTestId('open-palette').waitFor();
  await page.keyboard.press('Control+k');
  await expect(input(page)).toBeFocused();
}
/** 等搜索结果稳定（对象结果来自后端，索引经 Outbox 更新可能稍有延迟）。 */
async function searchUntil(page: Page, q: string, ok: () => Promise<boolean>) {
  for (let i = 0; i < 20; i++) {
    await input(page).fill(q);
    await page.waitForTimeout(400);
    await page.waitForLoadState('networkidle');
    if (await ok()) return;
    await input(page).fill('');
    await page.waitForTimeout(500);
  }
}
const first = (page: Page) => palette(page).locator('[role="option"]').first();

test.describe.configure({ timeout: 120_000 });

test('命令面板：拼音匹配菜单、编号直达工作与设备、动作、最近访问与收藏', async ({ browser }) => {
  const ts = Date.now().toString(36);
  const { context, page } = await loggedIn(browser, 'admin', { viewport: { width: 1440, height: 900 } });
  await page.goto('/index');
  // 收藏与最近访问保存在服务端（3.6）：从空白开始，不受上次运行影响
  await api(page, 'PUT', '/api/ui/prefs/nav', { recent: [], favorites: [] });
  await page.reload();
  const orgs = (await api(page, 'GET', '/api/asset/orgs')).data;
  const teamA = orgs.find((o: any) => o.name === '一车间甲班');
  const work = (
    await api(page, 'POST', '/api/op/ls.core.Work.create', { input: { workType: 'INSPECTION', title: `面板直达巡检${ts}`, orgUnitId: teamA.id } })
  ).data;
  const code = (await api(page, 'GET', `/api/ui/objects/work/${work.targetId}`)).data.code as string;
  const assetCode = `PL-${ts}`.toUpperCase();
  const asset = (await api(page, 'POST', '/api/crud/ls.asset.Asset', { assetCode, name: `面板测试泵${ts}`, assetType: 'STATION', orgId: teamA.id }))
    .data;

  // 1. 拼音首字母命中菜单
  await openPalette(page);
  await input(page).fill('sbtz');
  await expect(first(page)).toContainText('设备台账');
  await expect(first(page)).toHaveAttribute('data-testid', 'palette-item-menu');
  await expectAccessible(page, '命令面板', { include: '.ls-palette' });
  await page.keyboard.press('Escape');
  await expect(palette(page)).toBeHidden();

  // 2. 工作单编号：首项为该工作，回车打开详情
  await openPalette(page);
  await searchUntil(page, code, async () => (await first(page).textContent())?.includes(code) ?? false);
  await expect(first(page)).toHaveAttribute('data-testid', 'palette-item-object');
  await expect(first(page)).toContainText(`面板直达巡检${ts}`);
  await page.keyboard.press('Enter');
  await page.waitForURL(`**/lsmom/object/work/${work.targetId}`);
  await expect(page.getByTestId('object-title')).toContainText(code);
  await expect(page.getByTestId('object-title')).toContainText(`面板直达巡检${ts}`);
  await page.getByTestId('object-star').click();
  await expect(page.getByTestId('object-star')).toContainText('已收藏');

  // 3. 设备编号：回车打开台账详情抽屉
  await openPalette(page);
  await searchUntil(page, assetCode, async () => (await first(page).textContent())?.includes(assetCode) ?? false);
  await page.keyboard.press('Enter');
  await page.waitForURL((u) => u.pathname === '/lsmom/ledger/ls.asset.Asset');
  await expect(page.getByTestId('ledger-detail')).toBeVisible();
  await expect(page.getByTestId('ledger-detail')).toContainText(assetCode);
  await page.keyboard.press('Escape');

  // 4. 动作：登记不符合项 → 直接打开登记对话框
  await openPalette(page);
  await input(page).fill('djbfhx');
  await expect(first(page)).toContainText('登记不符合项');
  await expect(first(page)).toHaveAttribute('data-testid', 'palette-item-action');
  await page.keyboard.press('Enter');
  await page.waitForURL((u) => u.pathname === '/quality/nc');
  await expect(page.getByTestId('nc-title')).toBeVisible();
  expect(new URL(page.url()).searchParams.get('action'), '执行后地址里的动作参数被去掉').toBeNull();
  await page.keyboard.press('Escape');
  await expect(page.getByTestId('nc-title')).toBeHidden();

  // 5. 空输入：收藏与最近访问
  await openPalette(page);
  const sections = palette(page).locator('.ls-palette__section');
  await expect(sections.first()).toHaveText('收藏');
  await expect(palette(page).getByTestId('palette-item-favorite').first()).toContainText(code);
  await expect(sections.nth(1)).toHaveText('最近访问');
  await expect(palette(page).getByTestId('palette-item-recent').filter({ hasText: '不符合项' })).toHaveCount(1);
  // 键盘上下选择
  await page.keyboard.press('ArrowDown');
  const selected = palette(page).locator('[role="option"][aria-selected="true"]');
  await expect(selected).toHaveCount(1);
  // Ctrl+D 收藏当前选中的最近访问项 → 移到收藏分组
  const title = ((await selected.locator('.ls-palette__title').textContent()) ?? '').trim();
  await page.keyboard.press('Control+d');
  await expect(palette(page).getByTestId('palette-item-favorite').filter({ hasText: title })).toHaveCount(1);
  await page.keyboard.press('Control+d');
  await expect(palette(page).getByTestId('palette-item-favorite').filter({ hasText: title })).toHaveCount(0);
  await page.keyboard.press('Control+k');
  await expect(palette(page)).toBeHidden();

  await api(page, 'DELETE', `/api/crud/ls.asset.Asset/${asset.id}?version=${asset.version ?? 0}`);
  await context.close();
});

test('命令面板只显示数据域内的对象：班组长搜不到其他班组的工作', async ({ browser }) => {
  const ts = Date.now().toString(36);
  const admin = await loggedIn(browser, 'admin');
  await admin.page.goto('/index');
  const orgs = (await api(admin.page, 'GET', '/api/asset/orgs')).data;
  const teamB = orgs.find((o: any) => o.name === '一车间乙班');
  const work = (
    await api(admin.page, 'POST', '/api/op/ls.core.Work.create', { input: { workType: 'INSPECTION', title: `乙班面板${ts}`, orgUnitId: teamB.id } })
  ).data;
  const code = (await api(admin.page, 'GET', `/api/ui/objects/work/${work.targetId}`)).data.code as string;
  // 管理员能搜到（确认索引已更新）
  await openPalette(admin.page);
  await searchUntil(admin.page, code, async () => (await first(admin.page).textContent())?.includes(code) ?? false);
  await expect(first(admin.page)).toContainText(code);
  await admin.context.close();

  const { context, page } = await loggedIn(browser, 'demo_team');
  await page.goto('/index');
  await openPalette(page);
  await input(page).fill(code);
  await page.waitForTimeout(600);
  await page.waitForLoadState('networkidle');
  // 编号相近的本班组工作可能作为模糊结果出现，但乙班这张工作单不会出现
  await expect(palette(page).getByTestId('palette-item-object').filter({ hasText: code })).toHaveCount(0);
  await expect(
    palette(page)
      .getByTestId('palette-item-object')
      .filter({ hasText: `乙班面板${ts}` })
  ).toHaveCount(0);
  // 直接打开详情地址也打不开
  await page.keyboard.press('Escape');
  await page.goto(`/lsmom/object/work/${work.targetId}`);
  await expect(page.getByTestId('object-detail')).toContainText('打不开这个对象');
  await context.close();
});
