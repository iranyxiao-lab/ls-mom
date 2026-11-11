/**
 * 旅程 J-A-PROD：无 BOM 生产闭环（切片 A）：需求 → 工作 → 分派 → 报工 → 确认 → 交接班 → 看板。
 * 角色：车间主任（周主任）登记需求、转工作、分派、确认、接班；操作工（郑师傅）手机报工；甲班班长（吴班长）交班。
 * 负向：二车间主任读取一车间需求、操作工转工作（绕过前端直接调 API）、操作工确认自己的工作、交班人自己接班、重复确认。
 * 一致性：工作状态、时间轴、审计、事件、trace_id、未完成事项进入下一班次、按期完成率钻取包含完成的工作。
 */
import { consistency } from '../support/consistency.ts';
import { auditedDenial, denied, get, ok, op, query } from '../support/ops.ts';
import { expect, test } from '../support/walk.ts';

const REQ = 'ls.production.ProductionRequest';
const HO = 'ls.production.ShiftHandover';
const WORK = 'ls.core.Work';

const pad = (n: number) => String(n).padStart(2, '0');
const today = () => {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};
const SHIFT = (() => {
  const h = new Date().getHours();
  return h < 8 ? 'NIGHT' : h < 16 ? 'DAY' : 'EVENING';
})();

test('[AC-J-A-PROD-001] 旅程：无 BOM 生产闭环（需求→工作→分派→报工→确认→交接班→看板）', async ({ walk }) => {
  const leader = await walk.as('acc_leader');
  const team = await walk.as('acc_team');
  const operator = await walk.as('acc_operator', { mobile: true });
  const leaderB = await walk.as('acc_leader_b');
  const admin = await walk.as('acc_admin');
  const ts = Date.now().toString(36);
  const board = async (params: Record<string, string>) => ok<any[]>(await leader.api('GET', '/api/production/board?' + new URLSearchParams(params)), '工作板');

  const req = await walk.step('车间主任：在需求板登记并发布一条文字需求', async () => {
    const title = `A 线清洗与 200 件组装（验收 ${ts}）`;
    await leader.page.goto('/production/requests');
    await leader.page.getByTestId('btn-new-request').click();
    await leader.page.getByTestId('req-title').fill(title);
    await leader.page.getByTestId('btn-save-request').click();
    await expect(leader.page.locator('.el-message', { hasText: '需求已发布' }).first()).toBeVisible({ timeout: 15_000 });
    const r = (await query(leader, REQ, [{ field: 'title', op: 'eq', value: title }]))[0];
    expect(r?.status).toBe('OPEN');
    return r;
  }, leader);

  await walk.step('负向：二车间主任读取一车间需求被拒；操作工绕过前端直接转工作被拒并留审计', async () => {
    await denied(leaderB.api('GET', `/api/crud/${REQ}/${req.id}`), '跨车间读取需求');
    const cur = await get(leader, REQ, req.id);
    await denied(operator.api('POST', `/api/op/${REQ}.convert`, { targetId: String(req.id), version: cur.version, input: { works: [{ title: '越权' }] } }), '操作工转工作');
    await auditedDenial(admin, REQ, req.id, `${REQ}.convert`);
  }, operator);

  const teams = ok<any[]>(await leader.api('GET', '/api/production/teams'), '班组');
  const teamA = teams.find((t) => t.name === '一车间甲班');
  const teamB = teams.find((t) => t.name === '一车间乙班');
  expect(teamA && teamB, '一车间甲班、乙班').toBeTruthy();

  const [w1, w2] = await walk.step('车间主任：转为两条工作，排到甲班本班次', async () => {
    await op(leader, `${REQ}.convert`, req.id, {
      works: [
        { title: `A 线清洗（${ts}）`, teamId: teamA.id, shiftDate: today(), shift: SHIFT },
        { title: `200 件组装（${ts}）`, quantity: 200, unit: '件', teamId: teamA.id, shiftDate: today(), shift: SHIFT }
      ]
    });
    const works = (await board({ requestId: String(req.id) })).sort((a, b) => a.id - b.id);
    expect(works).toHaveLength(2);
    expect(Number(works[1].plannedQty)).toBe(200);
    await leader.page.goto('/production/board');
    return works;
  }, leader);

  await walk.step('车间主任：两条工作就绪，A 线清洗分派给郑师傅', async () => {
    const partyOf = async (s: typeof leader) => ok<any>(await s.api('GET', '/api/flow/me'), '身份').partyId;
    for (const w of [w1, w2]) await op(leader, `${WORK}.ready`, w.id, {}, (await board({ requestId: String(req.id) })).find((x) => x.id === w.id).version);
    const cur = (await board({ requestId: String(req.id) })).find((x) => x.id === w1.id);
    await op(leader, `${WORK}.assign`, w1.id, { targets: [{ resourceType: 'PARTY', resourceRef: String(await partyOf(operator)) }] }, cur.version);
  }, leader);

  await walk.step('操作工（手机）：开工并报工，提交确认', async () => {
    const p = operator.page;
    await p.goto('/m');
    await p.getByTestId(`m-task-start-${w1.id}`).click({ timeout: 20_000 });
    await p.getByTestId('m-report-form').waitFor({ timeout: 15_000 });
    await p.getByTestId('input-outputQty').locator('input').fill('1');
    await p.getByTestId('m-report-submit').click();
    await p.getByTestId(`m-task-report-${w1.id}`).waitFor({ timeout: 15_000 });
    const cur = (await board({ requestId: String(req.id) })).find((x) => x.id === w1.id);
    await op(operator, `${WORK}.submitConfirm`, w1.id, {}, cur.version);
    expect((await board({ requestId: String(req.id) })).find((x) => x.id === w1.id).status).toBe('PENDING_CONFIRM');
  }, operator);

  const confirmed = await walk.step('负向：操作工确认自己的工作被拒；车间主任确认完成；重复确认被拒', async () => {
    const cur = (await board({ requestId: String(req.id) })).find((x) => x.id === w1.id);
    await denied(operator.api('POST', `/api/op/${WORK}.confirm`, { targetId: String(w1.id), version: cur.version, input: {} }), '操作工确认自己的工作');
    const r = await op(leader, `${WORK}.confirm`, w1.id, {}, cur.version);
    await denied(leader.api('POST', `/api/op/${WORK}.confirm`, { targetId: String(w1.id), version: cur.version, input: {} }), '重复确认');
    expect((await board({ requestId: String(req.id) })).find((x) => x.id === w1.id).status).toBe('DONE');
    return r;
  }, leader);

  await walk.step('跨模块一致性：工作状态、时间轴、审计、事件与 trace_id', async () => {
    const r = await consistency(admin, WORK, w1.id, { status: 'DONE', record: async () => (await board({ requestId: String(req.id) })).find((x) => x.id === w1.id), timeline: /确认|完成|DONE/, auditOp: `${WORK}.confirm`, eventType: /confirm|DONE|done/i, traceId: confirmed.traceId });
    await test.info().attach('consistency.json', { body: JSON.stringify(r.checks, null, 2), contentType: 'application/json' });
    expect(r.failed, r.failed.map((c) => `${c.name}：${c.detail}`).join('；')).toEqual([]);
  }, admin);

  const ho = await walk.step('甲班班长：生成交接班（摘要含未完成事项）并确认交班；负向：自己接班被拒', async () => {
    const t = team.page;
    await t.goto('/production/handover');
    await t.getByTestId('btn-new-handover').click();
    await t.getByTestId('ho-to').click();
    await t.locator('.el-select-dropdown__item:visible', { hasText: '一车间乙班' }).first().click();
    await t.getByTestId('btn-create-handover').click();
    await t.getByTestId('ho-summary').waitFor({ timeout: 15_000 });
    expect(await t.getByTestId('ho-summary').inputValue()).toContain(w2.title);
    const h = (await query(team, HO, [{ field: 'status', op: 'eq', value: 'DRAFT' }]))[0];
    await denied(leader.api('GET', `/api/crud/${HO}/${h.id}`), '草稿未确认：接班方打不开');
    await t.getByTestId('btn-publish').click();
    await expect(t.locator('.el-message', { hasText: '已确认交班' }).first()).toBeVisible({ timeout: 15_000 });
    const pub = await get(team, HO, h.id);
    await denied(team.api('POST', `/api/op/${HO}.accept`, { targetId: String(h.id), version: pub.version, input: {} }), '交班人自己接班');
    return h;
  }, team);

  await walk.step('车间主任：确认接班；未完成的组装进入乙班下一班次', async () => {
    await op(leader, `${HO}.accept`, ho.id);
    expect((await get(leader, HO, ho.id)).status).toBe('COMPLETED');
    const moved = (await board({ teamId: String(teamB.id) })).find((x) => x.id === w2.id);
    expect(moved, '组装工作出现在乙班工作板').toBeTruthy();
    await leader.page.goto('/production/handover');
  }, leader);

  await walk.step('经营看板：按期完成率钻取明细包含刚完成的工作', async () => {
    // 计划结束时间可能是今天任意时刻，中班则为明天 00:00；指标区间左闭右开，统计窗口取今天到后天
    const from = today();
    const t = new Date();
    t.setDate(t.getDate() + 2);
    const to = `${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())}`;
    const q = ok<any>(await admin.api('POST', '/api/metrics/ls.production.onTimeRate/query', { from, to }), '按期完成率').rows[0];
    expect(q, '有指标值').toBeTruthy();
    const drill = ok<any>(await admin.api('POST', '/api/metrics/ls.production.onTimeRate/drill', { from, to, page: 1, size: 500 }), '钻取');
    expect(JSON.stringify(drill.rows ?? drill)).toContain(String(w1.code ?? w1.id));
    await admin.page.goto('/production/dashboard');
    await admin.page.getByTestId('widget-value-0').waitFor({ timeout: 20_000 });
  }, admin);
});
