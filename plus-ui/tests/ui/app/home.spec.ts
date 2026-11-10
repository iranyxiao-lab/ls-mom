/**
 * modernize-ui-ux 3.9：角色化首页（部署环境，无头）。
 * 班组长登录：首屏显示本班组今日工作、待我审核、未关闭异常，每张卡片都能一键进入对应列表；
 * 隐藏一张卡片并调整顺序 → 换浏览器登录后保持；「恢复默认」回到角色布局。
 */
import { expect, test, type Page } from '@playwright/test';
import { api, freshLogin, loggedIn } from '../support/auth';
import { expectAccessible } from '../support/a11y';

test.describe.configure({ timeout: 120_000 });
const card = (page: Page, code: string) => page.getByTestId(`home-card-${code}`);
const order = (page: Page) => page.locator('.home-slot').evaluateAll((els) => els.map((e) => e.getAttribute('data-card')));

test('班组长首页：首屏三张卡片，各自一键进入列表', async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'demo_team', { viewport: { width: 1440, height: 900 } });
  await api(page)('DELETE', '/api/ui/home/mine');
  // 准备：甲班一条今天进行中的工作（管理员建）
  const admin = await loggedIn(browser, 'admin');
  await admin.page.goto('/index');
  const teamA = (await api(admin.page)('GET', '/api/asset/orgs')).data.find((o: any) => o.name === '一车间甲班');
  const title = `首页卡片测试${Date.now().toString(36)}`;
  const day = new Date().toISOString().slice(0, 10);
  const w = (
    await api(admin.page)('POST', '/api/op/ls.core.Work.create', {
      input: { workType: 'PRODUCTION', title, orgUnitId: teamA.id, plannedStart: `${day}T08:00:00`, plannedEnd: `${day}T18:00:00` }
    })
  ).data;
  const ready = await api(admin.page)('POST', '/api/op/ls.core.Work.ready', { targetId: w.targetId, version: w.version, input: {} });
  expect(ready.code, ready.msg).toBe(200);
  await admin.context.close();

  await page.goto('/index');
  for (const code of ['ls.home.teamWork', 'ls.home.myReviews', 'ls.home.openIssues']) {
    await expect(card(page, code)).toBeVisible();
    await expect(card(page, code).getByTestId('home-card-link')).toBeVisible();
    // 首屏：卡片顶部在视口内
    const box = await card(page, code).boundingBox();
    expect(box && box.y < 900, `${code} 在首屏`).toBeTruthy();
  }
  expect((await order(page)).slice(0, 3), '班组长角色默认顺序').toEqual(['ls.home.teamWork', 'ls.home.myReviews', 'ls.home.openIssues']);
  await expectAccessible(page, '班组长首页');

  // 本班组今日工作：卡片数量与命名 Query 一致，今天计划的这条工作在结果里
  const q = (await api(page)('POST', '/api/q/ls.ui.homeTeamWork', { limit: 20 })).data.rows;
  const total = Number(q[0]?.total ?? 0);
  await expect(card(page, 'ls.home.teamWork').getByTestId('home-card-count')).toHaveText(String(total));
  expect(q.some((r: any) => r.title === title) || total > 20, '今天计划的工作在本班组今日工作里').toBe(true);
  // 其他班组的工作不出现
  expect(
    q.every((r: any) => r.orgName === '一车间甲班'),
    '只含本班组'
  ).toBe(true);
  // 一键进入对应列表
  const targets: [string, RegExp][] = [
    ['ls.home.teamWork', /^\/production\/(board|dispatch)$|^\/lsmom\/workbench$/],
    ['ls.home.myReviews', /^\/lsmom\/workbench$/],
    ['ls.home.openIssues', /^\/(quality\/nc|asset\/defects)$/]
  ];
  for (const [code, path] of targets) {
    await page.goto('/index');
    await card(page, code).getByTestId('home-card-link').click();
    await page.waitForURL((u) => path.test(u.pathname));
  }
  await context.close();
});

test('个人调整：隐藏与排序在换浏览器后保持，恢复默认回到角色布局', async ({ browser }) => {
  const a = await freshLogin(browser, 'demo_team', { viewport: { width: 1440, height: 900 } });
  await api(a.page)('DELETE', '/api/ui/home/mine');
  await a.page.goto('/index');
  await expect(card(a.page, 'ls.home.teamWork')).toBeVisible();
  const roleOrder = await order(a.page);
  await a.page.getByTestId('home-edit').click();
  // 隐藏「待我审核」，把「未关闭异常」上移到第一位
  // 隐藏是低风险可逆操作：不弹确认，5 秒内可撤销，回到原位置
  const before = await order(a.page);
  await a.page.locator('.home-slot[data-card="ls.home.myReviews"]').getByTestId('card-hide').click();
  await expect(a.page.locator('.el-message-box')).toHaveCount(0);
  await expect(a.page.getByTestId('undo-text')).toContainText('已隐藏「待我审核」');
  await a.page.getByTestId('undo-btn').click();
  await expect(a.page.locator('.home-slot[data-card="ls.home.myReviews"]')).toBeVisible();
  expect(await order(a.page)).toEqual(before);
  await a.page.locator('.home-slot[data-card="ls.home.myReviews"]').getByTestId('card-hide').click();
  await a.page.locator('.home-slot[data-card="ls.home.openIssues"]').getByTestId('card-up').click();
  await expect(a.page.getByTestId('card-add-ls.home.myReviews')).toBeVisible();
  await a.page.getByTestId('home-done').click();
  await expect(a.page.locator('.el-message--success', { hasText: '已保存' })).toBeVisible();
  const mine = await order(a.page);
  expect(mine[0]).toBe('ls.home.openIssues');
  expect(mine).not.toContain('ls.home.myReviews');
  await a.context.close();

  const b = await freshLogin(browser, 'demo_team', { viewport: { width: 1440, height: 900 } });
  await b.page.goto('/index');
  await expect(card(b.page, 'ls.home.openIssues')).toBeVisible();
  expect(await order(b.page), '换浏览器登录后保持').toEqual(mine);
  await b.page.getByTestId('home-edit').click();
  await b.page.getByTestId('home-reset').click();
  await expect(card(b.page, 'ls.home.myReviews')).toBeVisible();
  expect(await order(b.page), '恢复为角色默认布局').toEqual(roleOrder);
  await b.context.close();
});
