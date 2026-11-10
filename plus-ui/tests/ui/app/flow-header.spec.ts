/**
 * modernize-ui-ux 6.1/6.2：单据头部"状态与下一步"与流程进度（部署环境，无头）。
 * 依赖测试环境中已发布的工器具借还示例配置包（toolloan-config 端到端用例会发布）。
 * 班组长提交借还单 → 打开详情：顶部"等待 某某（车间主任）审核"、剩余时间、催办；
 * 车间主任打开同一单据：顶部主按钮"审核通过"、意见框直接可见；在头部退回并写明原因 →
 * 班组长再打开：醒目显示退回节点与原因，步骤条标出退回节点，点击查看办理人、时间和意见。
 */
import { expect, test } from '@playwright/test';
import { api, loggedIn } from '../support/auth';

test.describe.configure({ timeout: 180_000 });

test('发起人与审核人看到不同的头部；被退回单据显示退回节点与原因', async ({ browser }) => {
  const { context: tCtx, page: tPage } = await loggedIn(browser, 'demo_team', { viewport: { width: 1440, height: 900 } });
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
  const created = await team('POST', `/api/crud/${encodeURIComponent(concept)}`, {
    loanNo: `TL-FH-${run}`,
    borrower: Number(me.partyId),
    loanDate: '2026-11-10',
    purpose: 'OUTSOURCE',
    outsourceUnit: '头部测试',
    loanLine: [{ tool: '扳手', qty: 1 }]
  });
  expect(created.code, JSON.stringify(created)).toBe(200);
  const id = created.data.id as number;
  const sub = await team('POST', `/api/op/${ns}.LoanOrder.submit`, { targetId: String(id), version: created.data.version, input: {} });
  expect(sub.code, JSON.stringify(sub)).toBe(200);

  // 发起人：等待谁审核、剩余时间、催办
  await tPage.goto(`/lsmom/record/${encodeURIComponent(concept)}/${id}`);
  const head = tPage.getByTestId('flow-header');
  await expect(head).toBeVisible();
  await expect(head.getByTestId('flow-waiting')).toContainText(/等待 .+（车间主任）.*审核/);
  await expect(head).toContainText('当前：');
  await expect(head.getByTestId('flow-urge')).toBeVisible();
  await expect(head.getByTestId('flow-main-action')).toHaveCount(0);
  await expect(head.getByTestId('flow-step-initiator')).toBeVisible();

  // 审核人：唯一主按钮、意见框直接可见
  const { context: lCtx, page: lPage } = await loggedIn(browser, 'demo_leader', { viewport: { width: 1440, height: 900 } });
  await lPage.goto(`/lsmom/record/${encodeURIComponent(concept)}/${id}`);
  const lHead = lPage.getByTestId('flow-header');
  await expect(lHead.getByTestId('flow-my-turn')).toContainText('轮到你审核');
  await expect(lHead.getByTestId('flow-main-action')).toHaveText('审核通过');
  await expect(lHead.locator('[data-testid="flow-act"] .el-button--primary:not(.is-link)')).toHaveCount(1);
  await expect(lHead.getByTestId('flow-comment')).toBeVisible();
  await expect(lHead.getByTestId('flow-urge')).toHaveCount(0);
  // 在头部退回并写明原因
  await lHead.getByTestId('flow-comment').fill('借用数量不清楚，请补充');
  await lHead.getByTestId('flow-return').click();
  await expect(lPage.locator('.el-message', { hasText: '已退回发起人' }).first()).toBeVisible();
  await expect(lHead.getByTestId('flow-my-turn')).toHaveCount(0);

  // 发起人：醒目显示退回节点与原因；步骤条标出退回节点
  await tPage.reload();
  const ret = tPage.getByTestId('flow-returned');
  await expect(ret).toContainText('（车间主任） 退回');
  await expect(ret).toContainText('退回意见：借用数量不清楚，请补充');
  await expect(head.getByTestId('flow-main-action')).toHaveText('再提交');
  const stepKey = (await tPage.locator('[data-testid^="flow-step-btn-"]').nth(1).getAttribute('data-testid'))!.replace('flow-step-btn-', '');
  await expect(tPage.getByTestId(`flow-step-${stepKey}`).locator('.is-error').first()).toBeVisible();
  await tPage.getByTestId(`flow-step-btn-${stepKey}`).click();
  const detail = tPage.getByTestId(`flow-step-detail-${stepKey}`);
  await expect(detail).toContainText('已退回');
  await expect(detail).toContainText('（车间主任） 退回');
  await expect(detail).toContainText('意见：借用数量不清楚，请补充');
  await expect(detail).toContainText(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}/);

  // 无关人员不能看该单据的流程
  const { context: oCtx, page: oPage } = await loggedIn(browser, 'demo_operator');
  await oPage.goto('/index');
  const denied = await api(oPage)('GET', `/api/workbench/biz/${encodeURIComponent(concept)}/${id}/progress`);
  expect(denied.code).not.toBe(200);
  const deniedOld = await api(oPage)('GET', `/api/flow/biz/${encodeURIComponent(concept)}/${id}`);
  expect(deniedOld.code).not.toBe(200);
  await Promise.all([tCtx.close(), lCtx.close(), oCtx.close()]);
});
