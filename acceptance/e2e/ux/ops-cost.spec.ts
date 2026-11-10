/**
 * 操作成本实测（acceptance-walkthrough 6.4，spec quality/ui-walkthrough「操作成本实测」、ui/ux-quality「关键任务操作预算」）：
 * 报工、审核、报缺陷、配置一张台账四项关键任务的点击数与完成耗时，写入 runs/<run>/budgets.json 并与预算对比
 * （点击预算：移动报工 ≤5、待办审核 ≤3、从设备报缺陷 ≤4；耗时与台账配置的点击数 spec 未规定预算，只记录）。
 */
import { record } from '../support/budget.ts';
import { countClicks } from '../support/clicks.ts';
import { expect, test, type Session } from '../support/walk.ts';

const day = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};
const SHIFT = (() => {
  const h = new Date().getHours();
  return h < 8 ? 'NIGHT' : h < 16 ? 'DAY' : 'EVENING';
})();
const ok = (r: { body: any }, what: string) => {
  expect(r.body.code, `${what}：${r.body.msg}`).toBe(200);
  return r.body.data;
};
const exec = (s: Session, code: string, id: string | number, version: number, input: unknown = {}) => s.api('POST', `/api/op/${code}`, { targetId: String(id), version, input });

function judge(id: string, name: string, budget: number | null, r: { clicks: number; seconds: number; log: string[] }, caseId: string) {
  record({ id: `UI-CLICK-${id}`, name: `${name}（点击）`, kind: 'click', budget, actual: r.clicks, unit: '次', steps: r.log, caseId });
  record({ id: `UI-TIME-${id}`, name: `${name}（耗时）`, kind: 'time', budget: null, actual: r.seconds, unit: 's', caseId });
  if (budget !== null) expect(r.clicks, `${name}点击 ${r.clicks} 次，预算 ${budget}（${r.log.join(' → ')}）`).toBeLessThanOrEqual(budget);
}

test('[AC-UI-MF-002] 操作成本：移动常规报工的点击数（≤5）与耗时', async ({ walk }) => {
  const admin = await walk.as('acc_leader');
  const op = await walk.as('acc_operator', { mobile: true });
  const workId = await walk.step('准备：给操作工排一条本班次已分派的工作', async () => {
    const me = ok(await op.api('GET', '/api/flow/me'), '操作工身份');
    const team = (ok(await admin.api('GET', '/api/production/teams'), '班组') as any[]).find((t) => t.id === 2011 || t.name.includes('甲班'));
    const REQ = 'ls.production.ProductionRequest';
    const title = `操作成本 报工 ${Date.now()}`;
    const req = ok(await admin.api('POST', `/api/crud/${REQ}`, { title, period: 'TEMP' }), '登记需求');
    ok(await exec(admin, `${REQ}.submit`, req.id, req.version), '发布需求');
    const r1 = ok(await admin.api('GET', `/api/crud/${REQ}/${req.id}`), '读需求');
    ok(await exec(admin, `${REQ}.convert`, r1.id, r1.version, { works: [{ title, quantity: 300, unit: '件', teamId: team.id, shiftDate: day(), shift: SHIFT }] }), '转为工作并排班');
    const works = ok(await admin.api('GET', '/api/production/board?' + new URLSearchParams({ requestId: String(req.id) })), '工作板') as any[];
    expect(works.length, '需求转出一条工作').toBe(1);
    const board = async () => (ok(await admin.api('GET', '/api/production/board?' + new URLSearchParams({ requestId: String(req.id) })), '工作板') as any[])[0];
    let cur = await board();
    ok(await exec(admin, 'ls.core.Work.ready', cur.id, cur.version), '就绪');
    cur = await board();
    ok(await exec(admin, 'ls.core.Work.assign', cur.id, cur.version, { targets: [{ resourceType: 'PARTY', resourceRef: String(me.partyId) }] }), '分派');
    return cur.id;
  }, admin);
  await walk.step('手机工作台：开工 → 填 120 件 → 提交', async () => {
    const p = op.page;
    const clicks = await countClicks(p);
    await p.goto('/m');
    await p.getByTestId(`m-task-start-${workId}`).waitFor({ timeout: 20_000 });
    await clicks.reset();
    await p.getByTestId(`m-task-start-${workId}`).click();
    await p.getByTestId('m-report-form').waitFor({ timeout: 15_000 });
    await p.getByTestId('input-outputQty').locator('input').fill('120');
    await p.getByTestId('m-report-submit').click();
    await p.getByTestId(`m-task-report-${workId}`).waitFor({ timeout: 15_000 });
    judge('REPORT', '移动常规报工', 5, await clicks.result(), 'AC-UI-MF-002');
  }, op);
});

test('[AC-UI-UX-001] 操作成本：从首页待办完成一次审核的点击数（≤3）与耗时', async ({ walk }) => {
  const impl = await walk.as('acc_implementer');
  const leader = await walk.as('acc_leader');
  await walk.step('准备：实施顾问提交一门课程', async () => {
    const orgs = ok(await impl.api('GET', '/api/asset/orgs'), '组织') as any[];
    const ws = orgs.find((o) => o.unit_type === 'WORKSHOP' && o.name.includes('一车间'));
    const c = ok(await impl.api('POST', '/api/crud/ls.training.Course', { courseCode: `ACC-OC-${Date.now().toString(36)}`, title: '操作成本 审核', positions: '操作工', passScore: 60, validMonths: 12, origin: 'MANUAL', orgId: ws.id }), '新建课程');
    ok(await impl.api('POST', '/api/op/ls.training.Course.submit', { targetId: String(c.id), version: c.version, input: {} }), '提交');
  }, impl);
  await walk.step('首页：点待办 → 填意见 → 通过', async () => {
    const p = leader.page;
    await p.setViewportSize({ width: 1440, height: 900 });
    const clicks = await countClicks(p);
    await p.goto('/index');
    const rows = p.getByTestId('home-card-ls.home.myReviews').getByTestId('home-row');
    await expect(rows.first()).toBeVisible({ timeout: 15_000 });
    await clicks.reset();
    await rows.first().click();
    const panel = p.getByTestId('task-panel');
    await expect(panel.getByTestId('task-form')).toBeVisible();
    const note = panel.locator('textarea').first();
    await note.click();
    await p.keyboard.type('同意（操作成本实测）');
    await panel.getByTestId('btn-approve').click();
    await expect(p.locator('.el-message', { hasText: /已通过/ }).first()).toBeVisible();
    judge('AUDIT', '首页待办审核', 3, await clicks.result(), 'AC-UI-UX-001');
  }, leader);
});

test('[AC-UI-DE-001] 操作成本：从设备登记缺陷的点击数（≤4）与耗时', async ({ walk }) => {
  const asset = await walk.as('acc_asset');
  const op = await walk.as('acc_operator', { mobile: true });
  await walk.step('准备：设备员生成一号空压机今日点检（操作工负责）', async () => {
    const plans = ok(await asset.api('POST', '/api/crud/ls.asset.InspectionPlan/query', { filters: [{ field: 'name', op: 'eq', value: '一号空压机日点检（验收）' }], page: 1, size: 1 }), '点检计划').rows as any[];
    expect(plans.length, '验收种子中的点检计划').toBe(1);
    ok(await exec(asset, 'ls.asset.InspectionPlan.generate', plans[0].id, plans[0].version), '生成点检');
  }, asset);
  await walk.step('扫码打开一号空压机 → 报缺陷 → 填描述 → 提交', async () => {
    const p = op.page;
    const clicks = await countClicks(p);
    await p.goto('/m/inspect?code=ACC-EQ-001');
    await expect(p.getByTestId('m-inspect-defect').or(p.getByTestId('m-inspect-hint'))).toBeVisible({ timeout: 20_000 });
    if (await p.getByTestId('m-inspect-hint').isVisible()) throw new Error('扫码未打开点检：' + (await p.getByTestId('m-inspect-hint').textContent()));
    await clicks.reset();
    await p.getByTestId('m-inspect-defect').click();
    await p.getByTestId('m-defect').waitFor();
    await p.getByTestId('input-title').locator('textarea, input').first().fill('空压机排气温度偏高（操作成本实测）');
    await p.getByTestId('m-defect-submit').click();
    await p.waitForURL(/\/m$/, { timeout: 30_000 });
    judge('DEFECT', '从设备登记缺陷', 4, await clicks.result(), 'AC-UI-DE-001');
  }, op);
});

test('[AC-UI-DU-001] 操作成本：从模板配置一张借还台账的点击数与耗时（只记录）', async ({ walk }) => {
  const impl = await walk.as('acc_admin');
  await walk.step('协同应用中心：从模板新建 → 借还登记 → 改名 → 生成草稿', async () => {
    const p = impl.page;
    await p.setViewportSize({ width: 1440, height: 900 });
    const clicks = await countClicks(p);
    const run = Date.now().toString(36);
    await p.goto('/lsmom/apps');
    await p.locator('#tab-changes').waitFor({ timeout: 20_000 });
    await clicks.reset();
    await p.locator('#tab-changes').click();
    await p.getByTestId('btn-from-template').click();
    const wz = p.getByTestId('tpl-wizard');
    await wz.getByTestId('tpl-loan').click();
    await wz.getByTestId('tpl-title').fill(`借还登记 成本${run}`);
    await wz.getByTestId('tpl-ns').fill(`acc.cost${run}`);
    await wz.getByTestId('tpl-node-confirm').click();
    await p.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '设备员' }).first().click();
    await wz.getByTestId('tpl-next').click();
    await wz.getByTestId('tpl-preview').waitFor();
    await wz.getByTestId('tpl-create').click();
    await p.locator('.el-message--success', { hasText: '已生成草稿' }).first().waitFor({ timeout: 60_000 });
    const r = await clicks.result();
    judge('LEDGER', '从模板配置借还台账', null, r, 'AC-UI-DU-001');
    expect(r.clicks).toBeGreaterThan(0);
  }, impl);
});
