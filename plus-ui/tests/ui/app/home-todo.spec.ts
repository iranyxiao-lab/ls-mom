/**
 * modernize-ui-ux 6.3：待办原地办理与自动加载下一条（部署环境，无头）。
 * 依赖测试环境中已发布的工器具借还示例配置包（toolloan-config 端到端用例会发布）。
 * 班组长提交两张借还单 → 车间主任在首页"待我审核"点一条 → 不离开首页在抽屉里填写审核意见并通过（≤3 次点击）→
 * 提示"已通过，还剩 N 条"，抽屉自动显示下一条待办。
 */
import { expect, test } from '@playwright/test';
import { api, loggedIn } from '../support/auth';
import { countClicks } from '../support/clicks';

test.describe.configure({ timeout: 180_000 });

test('首页待办原地审核一次不超过 3 次点击，办完自动打开下一条', async ({ browser }) => {
  const { context: tCtx, page: tPage } = await loggedIn(browser, 'demo_team');
  await tPage.goto('/index');
  const team = api(tPage);
  const views = (await team('GET', '/api/form/views?type=FORM')).data as any[];
  const candidates = views
    .filter((v) => /^acme\.tl[a-z0-9]+\.loanForm$/.test(v.code) && v.publishedVersion)
    .map((v) => v.code.replace(/\.loanForm$/, '.LoanOrder'))
    .sort()
    .reverse();
  let concept = '';
  for (const c of candidates) {
    if ((await team('GET', `/api/crud/${encodeURIComponent(c)}/schema`)).code === 200) {
      concept = c;
      break;
    }
  }
  expect(concept, '班组长能使用已发布的工器具借还台账').toBeTruthy();
  const ns = concept.replace(/\.LoanOrder$/, '');
  const me = (await team('GET', '/api/flow/me')).data;
  const run = Date.now().toString(36);
  for (const s of ['A', 'B']) {
    const c = await team('POST', `/api/crud/${encodeURIComponent(concept)}`, {
      loanNo: `TL-HT-${run}-${s}`,
      borrower: Number(me.partyId),
      loanDate: '2026-11-10',
      purpose: 'MAINTENANCE',
      loanLine: [{ tool: '扳手', qty: 1 }]
    });
    expect(c.code, JSON.stringify(c)).toBe(200);
    const sub = await team('POST', `/api/op/${ns}.LoanOrder.submit`, { targetId: String(c.data.id), version: c.data.version, input: {} });
    expect(sub.code, JSON.stringify(sub)).toBe(200);
  }
  await tCtx.close();

  const { context, page } = await loggedIn(browser, 'demo_leader', { viewport: { width: 1440, height: 900 } });
  const clicks = await countClicks(page);
  await page.goto('/index');
  // 只办借还单的部门审核：账号里可能还有别的待办（如课程审批）
  const rows = page.getByTestId('home-card-ls.home.myReviews').getByTestId('home-row').filter({ hasText: '部门审核' });
  await expect(rows.first()).toBeVisible();
  await clicks.reset();

  // 1. 点首页的一条待办：在首页原地打开办理抽屉
  await rows.first().click();
  const panel = page.getByTestId('task-panel');
  await expect(panel.getByTestId('task-form')).toBeVisible();
  await expect(page).toHaveURL(/\/index/);
  await expect(panel.getByTestId('task-remaining')).toContainText(/本条之后还剩 \d+ 条/);
  // 2. 点审核意见填写（文字输入不计点击）
  const note = panel.locator('[data-field-key="auditNote"]').locator('textarea, input').first();
  await note.click();
  await page.keyboard.type('同意借用');
  // 3. 通过
  await panel.getByTestId('btn-approve').click();
  await expect(page.locator('.el-message', { hasText: /已通过，还剩 \d+ 条/ }).first()).toBeVisible();
  await clicks.expectWithin(3, '首页待办完成一次审核');

  // 抽屉自动显示下一条待办
  await expect(panel.getByTestId('task-form')).toBeVisible();
  await expect(page).toHaveURL(/\/index/);
  // 下一条若也是借还单，审核意见是空的（不沿用上一条的输入）
  const nextNote = panel.locator('[data-field-key="auditNote"]').locator('textarea, input').first();
  if (await nextNote.count()) await expect.poll(async () => nextNote.inputValue()).toBe('');
  await context.close();
});
