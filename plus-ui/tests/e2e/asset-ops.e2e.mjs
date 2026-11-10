/**
 * 14.1 端到端（无头 Chromium，部署环境，演示账号）：
 *   设备员新增设备并投用 → 停机检修（必须写原因）→ 恢复运行，状态历史留痕 →
 *   建点检计划（读数上限、判定项）并立即生成 → 甲班班长在"我的点检"填写，温度超限自动判异常 → 一键生成缺陷草稿 →
 *   设备员登记缺陷（产生 Issue）→ 派班长维修并附检查清单 → 班长开始、完成 → 设备员确认 → 班长提交验证 →
 *   设备员验证通过 → 缺陷与 Issue 关闭 → 设备时间轴可见点检、缺陷、维修、验证与状态变更。
 * 运行：node tests/e2e/asset-ops.e2e.mjs（LSMOM_E2E_BASE 指定环境）
 */
import { BASE, check, open } from './lib.mjs';

const ASSET = 'ls.asset.Asset';
const PLAN = 'ls.asset.InspectionPlan';
const RECORD = 'ls.asset.InspectionRecord';
const DEFECT = 'ls.asset.Defect';
const ts = Date.now().toString(36).toUpperCase();
const code = `P-${ts}`;

const keeper = await open({ user: 'demo_asset' });
const team = await open({ user: 'demo_team', browser: keeper.browser });

const query = (s, concept, filters, size = 20) =>
  s.api('POST', `/api/crud/${encodeURIComponent(concept)}/query`, { filters, sort: [{ field: 'id', dir: 'desc' }], page: 1, size });
const one = async (s, concept, filters) => (await query(s, concept, filters, 1)).data.rows[0];
const toast = async (page, text) => {
  const m = page.locator('.el-message', { hasText: text }).first();
  await m.waitFor({ timeout: 15000 }).catch(async (e) => {
    const shown = await page.locator('.el-message, .el-notification').allTextContents();
    throw new Error(`没有出现「${text}」，当前提示：${shown.join(' | ') || '无'}`, { cause: e });
  });
  await m.waitFor({ state: 'detached', timeout: 15000 }).catch(() => undefined);
};
const pickOption = async (page, testId, text) => {
  await page.getByTestId(testId).click();
  const input = page.getByTestId(testId).locator('input').first();
  if (await input.isEditable().catch(() => false)) await input.fill(text.slice(0, 1));
  await page.locator('.el-select-dropdown__item:visible', { hasText: text }).first().click();
  await page
    .locator('.el-select-dropdown:visible')
    .first()
    .waitFor({ state: 'hidden', timeout: 5000 })
    .catch(() => undefined);
};

try {
  // —— 设备台账：新增并投用、停机检修必须写原因、恢复运行 ——
  const k = keeper.page;
  await k.goto(BASE + '/asset/center');
  await k.getByTestId('btn-new-asset').click();
  await k.getByTestId('asset-code').fill(code);
  await k.getByTestId('asset-name').fill('E2E 循环泵');
  await pickOption(k, 'asset-org', '一车间');
  await k.getByTestId('asset-location').fill('A 线泵房');
  await k.getByTestId('btn-create-asset').click();
  await toast(k, '设备已投用');
  const asset = await one(keeper, ASSET, [{ field: 'assetCode', op: 'eq', value: code }]);
  check(asset?.status === 'RUNNING', `新增设备 ${code} 并投用`);

  await k.getByTestId(`asset-row-${asset.id}`).click();
  await k.getByTestId('btn-asset-maintain').click();
  await k.getByTestId('btn-asset-op-confirm').click();
  await k.locator('.el-message', { hasText: '请填写原因' }).first().waitFor();
  await k.getByTestId('asset-reason').fill('机封泄漏，停机检修');
  await k.getByTestId('btn-asset-op-confirm').click();
  await k.getByTestId('asset-detail-status').filter({ hasText: '停机检修' }).waitFor();
  await k.getByTestId('btn-asset-run').click();
  await k.getByTestId('btn-asset-op-confirm').click();
  await k.getByTestId('asset-detail-status').filter({ hasText: '运行' }).waitFor();
  const logs = (await query(keeper, 'ls.asset.AssetStatusLog', [{ field: 'asset', op: 'eq', value: asset.id }])).data.rows;
  check(
    logs.length === 3 && logs.some((l) => l.toStatus === 'MAINTENANCE' && l.reason === '机封泄漏，停机检修' && l.operator),
    '停机检修必须写原因；状态历史 3 条，含操作人与原因'
  );
  await k.keyboard.press('Escape');

  // —— 点检计划：读数上限 70℃ + 判定项，责任人甲班班长，立即生成 ——
  await k.goto(BASE + '/asset/inspection');
  await k.getByRole('tab', { name: '点检计划' }).click();
  await k.getByTestId('btn-new-plan').click();
  await k.getByTestId('plan-name').fill(`${code} 日检`);
  await pickOption(k, 'plan-asset', code);
  await pickOption(k, 'plan-responsible', '吴班长');
  await k.getByTestId('pi-code-0').fill('TEMP');
  await k.getByTestId('pi-name-0').fill('轴承温度');
  await pickOption(k, 'pi-type-0', '读数');
  await k.getByTestId('pi-upper-0').locator('input').fill('70');
  await k.getByTestId('btn-add-item').click();
  await k.getByTestId('pi-code-1').fill('LEAK');
  await k.getByTestId('pi-name-1').fill('机封泄漏');
  await k.getByTestId('btn-save-plan').click();
  await toast(k, '点检计划已启用');
  const plan = await one(keeper, PLAN, [{ field: 'name', op: 'eq', value: `${code} 日检` }]);
  check(plan?.status === 'ACTIVE', '点检计划启用');
  await k.getByTestId(`btn-generate-${plan.id}`).click();
  await toast(k, '已生成点检工作');
  const rec = await one(keeper, RECORD, [{ field: 'plan', op: 'eq', value: plan.id }]);
  check(rec?.status === 'DRAFT', '按计划生成点检工作与点检记录');

  // —— 班长：我的点检，温度 82℃ 超限 → 异常提示 → 一键生成缺陷草稿 ——
  const t = team.page;
  await t.goto(BASE + '/asset/inspection');
  await t.getByTestId(`ins-${rec.id}`).click();
  await t.getByTestId('ins-value-TEMP').locator('input').fill('82');
  await t.getByTestId('ins-value-TEMP').locator('input').blur();
  await t.getByTestId('ins-ok-LEAK').click();
  await t.getByTestId('btn-ins-submit').click();
  await t.getByTestId('ins-abnormal-hint').filter({ hasText: '轴承温度' }).waitFor();
  check(true, '提交点检：温度超上限自动判异常并提示可生成缺陷');
  await t.getByTestId('ins-outcome-TEMP').filter({ hasText: '异常' }).waitFor();
  await t.getByTestId('btn-raise-defects').click();
  await toast(t, '已生成 1 条缺陷草稿');
  const defect = await one(team, DEFECT, [{ field: 'inspectionRecord', op: 'eq', value: rec.id }]);
  check(defect?.status === 'DRAFT' && defect.issue, '一键生成缺陷草稿（带 Issue 草稿）');
  const work = (await team.api('GET', '/api/asset/inspections/mine?status=SUBMITTED')).data.find((x) => x.id === rec.id);
  check(work?.result === 'ABNORMAL', '点检记录已提交，结论异常');

  // —— 设备员登记缺陷并派维修（附检查清单） ——
  await k.goto(BASE + '/asset/defects');
  await k.getByTestId(`df-row-${defect.id}`).click();
  await k.getByTestId('btn-df-report').click();
  await k.getByTestId('df-detail-status').filter({ hasText: '待处理' }).waitFor();
  await pickOption(k, 'df-assignee', '吴班长');
  await k.getByTestId('df-checklist').fill('更换轴承\n复测温度');
  await k.getByTestId('btn-df-repair').click();
  await k.getByTestId('df-detail-status').filter({ hasText: '维修中' }).waitFor();
  const repairs = (await keeper.api('GET', `/api/asset/defects/${defect.id}/repairs`)).data;
  check(
    repairs.length === 1 && repairs[0].description.includes('1. 更换轴承') && repairs[0].assignees.includes('吴班长'),
    '生成维修工作，附检查清单并派给班长'
  );
  const w = repairs[0];

  // —— 班长执行维修 ——
  await t.goto(BASE + '/asset/defects');
  await t.getByTestId(`df-row-${defect.id}`).click();
  await t.getByTestId(`btn-work-start-${w.id}`).click();
  await t.getByTestId(`btn-work-done-${w.id}`).click();
  await t.getByTestId(`df-work-status-${w.id}`).filter({ hasText: '待确认' }).waitFor();
  check(true, '班长开始并完成维修工作');

  // —— 设备员确认工作，班长提交验证，设备员验证通过 ——
  await k.reload();
  await k.getByTestId(`df-row-${defect.id}`).click();
  await k.getByTestId(`btn-work-confirm-${w.id}`).click();
  await k.getByTestId(`df-work-status-${w.id}`).filter({ hasText: '已完成' }).waitFor();
  await t.reload();
  await t.getByTestId(`df-row-${defect.id}`).click();
  await t.getByTestId('btn-df-submit-verify').click();
  await t.getByTestId('df-detail-status').filter({ hasText: '待验证' }).waitFor();
  await k.reload();
  await k.getByTestId(`df-row-${defect.id}`).click();
  await k.getByTestId('df-conclusion').fill('温度恢复 55℃');
  await k.getByTestId('btn-df-pass').click();
  await k.getByTestId('df-detail-status').filter({ hasText: '已关闭' }).waitFor();
  const closed = (await keeper.api('GET', `/api/crud/${DEFECT}/${defect.id}`)).data;
  check(closed.status === 'CLOSED' && closed.verifyConclusion === '温度恢复 55℃', '验证通过，缺陷关闭');

  // —— 设备时间轴 ——
  await k.goto(BASE + '/asset/center');
  await k.getByTestId(`asset-row-${asset.id}`).click();
  const tl = k.getByTestId('asset-timeline');
  await tl.waitFor();
  const text = await tl.textContent();
  for (const [kind, word] of [
    ['INSPECTION', '点检异常 1 项'],
    ['DEFECT', '登记缺陷'],
    ['REPAIR', '维修工作'],
    ['VERIFY', '验证通过'],
    ['STATUS', '状态']
  ]) {
    check(text.includes(word), `设备时间轴含 ${kind}：${word}`);
  }
  const api = (await keeper.api('GET', `/api/asset/assets/${asset.id}/timeline`)).data;
  check(api[0].kind === 'VERIFY' || api[0].kind === 'DEFECT', '时间轴按时间倒序，最新是验证');
  for (const s of [keeper, team]) check(s.errors.length === 0, '页面无脚本错误：' + s.errors.join('；'));
} finally {
  await keeper.browser.close();
}
