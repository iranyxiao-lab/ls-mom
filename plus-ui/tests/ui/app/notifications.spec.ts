/**
 * modernize-ui-ux 3.7：通知抽屉（部署环境，无头）。
 * 甲班班长确认交班 → 车间主任顶栏出现未读数 → 抽屉「待办提醒」里有"交接班待确认" → 点通知打开该交接班 →
 * 在抽屉内直接「确认接班」→ 交接班完成，通知显示已处理，未读数减少。
 */
import { expect, test, type Page } from '@playwright/test';
import { api, loggedIn } from '../support/auth';
import { expectAccessible } from '../support/a11y';

const HO = 'ls.production.ShiftHandover';
test.describe.configure({ timeout: 120_000 });

/** 甲班班长发起并确认一张交接班（甲班 → 乙班），返回 id。 */
async function publishHandover(page: Page): Promise<number> {
  const call = api(page);
  const orgs = (await call('GET', '/api/production/teams')).data;
  const teamA = orgs.find((o: any) => o.name === '一车间甲班');
  const teamB = orgs.find((o: any) => o.name === '一车间乙班');
  const day = new Date().toISOString().slice(0, 10);
  const shift = ['DAY', 'EVENING', 'NIGHT'][Math.floor(Math.random() * 3)];
  const created = await call('POST', `/api/crud/${HO}`, { fromTeam: teamA.id, toTeam: teamB.id, shiftDate: day, shift, orgId: teamA.id });
  expect(created.code, created.msg).toBe(200);
  const id = created.data.id as number;
  const op = async (name: string) => {
    const cur = (await call('GET', `/api/crud/${HO}/${id}`)).data;
    const r = await call('POST', `/api/op/${HO}.${name}`, { targetId: String(id), version: cur.version, input: {} });
    expect(r.code, `${name}：${r.msg}`).toBe(200);
  };
  await op('generate');
  await op('publish');
  return id;
}

const unreadCount = async (page: Page) =>
  Number(
    (await page
      .getByTestId('notice-badge')
      .locator('sup')
      .textContent({ timeout: 2000 })
      .catch(() => '0')) || 0
  );

test('从通知确认交接班：未读数、分组、点通知直达、抽屉内确认', async ({ browser }) => {
  const team = await loggedIn(browser, 'demo_team');
  await team.page.goto('/index');
  const id = await publishHandover(team.page);
  await team.context.close();

  const { context, page } = await loggedIn(browser, 'demo_leader', { viewport: { width: 1440, height: 900 } });
  await page.goto('/index');
  await expect.poll(() => unreadCount(page), { message: '顶栏显示未读数' }).toBeGreaterThan(0);
  const before = await unreadCount(page);
  // 登录欢迎提示盖在顶栏右上角，先关掉
  for (const close of await page.locator('.el-notification .el-notification__closeBtn').all()) await close.click().catch(() => undefined);
  await expect(page.locator('.el-notification')).toHaveCount(0);

  // 打开抽屉：待办提醒分组里有该交接班
  await page.getByTestId('open-notices').click();
  const drawer = page.getByTestId('notice-drawer');
  await expect(drawer.getByTestId('notice-tab-todo')).toBeVisible();
  await expect(drawer.getByTestId('notice-tab-system')).toBeVisible();
  await expect(drawer.getByTestId('notice-tab-mention')).toBeVisible();
  const item = drawer.locator(`[data-testid="notice-item-todo"]`).filter({ hasText: '交接班待确认' }).first();
  await expect(item).toBeVisible();
  await expectAccessible(page, '通知抽屉', { include: '.ls-notice-drawer' });

  // 点通知：跳到交接班页并打开这张交接班；通知变为已读
  const noticeId = await item.getAttribute('data-notice-id');
  await item.locator('.notice-main').click();
  await page.waitForURL((u) => u.pathname === '/production/handover');
  await expect(page.getByTestId('ho-detail-status')).toContainText('待接班');
  await expect.poll(() => unreadCount(page), { message: '点开后未读数减少' }).toBeLessThan(before);
  await page.keyboard.press('Escape');

  // 抽屉内直接确认接班
  await page.getByTestId('open-notices').click();
  const same = drawer.locator(`[data-notice-id="${noticeId}"]`);
  await same.getByTestId('notice-action').click();
  await expect(page.locator('.el-message--success')).toContainText('已确认接班');
  await expect(same).toContainText('已处理');
  await expect(same.getByTestId('notice-action')).toHaveCount(0);
  const done = (await api(page)('GET', `/api/crud/${HO}/${id}`)).data;
  expect(done.status, '交接班完成').toBe('COMPLETED');
  await context.close();
});
