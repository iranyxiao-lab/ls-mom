/**
 * 13.3 / 13.4 端到端（无头 Chromium，部署环境，演示账号）：
 *   车间主任在需求板登记文字需求并拆成两条工作 → 分派 → 工作板拖动调班 →
 *   操作工在手机视口开始工作、填报、拍照（草稿可恢复）、提交确认 → 主任确认 →
 *   甲班班长发起交接班（草稿接班人不可见）→ 交班确认 → 主任接班 → 未完成事项进入乙班工作板 →
 *   经营看板钻取明细与指标数值一致。
 * 运行：node tests/e2e/production-ops.e2e.mjs（LSMOM_E2E_BASE 指定环境）
 */
import { BASE, check, open } from './lib.mjs';

const REQ = 'ls.production.ProductionRequest';
const PLAN = 'ls.production.ShiftPlan';
const HO = 'ls.production.ShiftHandover';
const ts = Date.now().toString(36);
const d = new Date();
const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

const admin = await open();
const leader = await open({ user: 'demo_leader', browser: admin.browser });
const team = await open({ user: 'demo_team', browser: admin.browser });
const worker = await open({ user: 'demo_operator', browser: admin.browser, mobile: true });

const exec = async (s, code, targetId, version, input = {}) => {
  const r = await s.api('POST', `/api/op/${code}`, { targetId: targetId == null ? undefined : String(targetId), version, input });
  if (r.code !== 200) throw new Error(`${code} 失败：${r.msg}`);
  return r.data;
};
const query = (s, concept, filters, size = 20) =>
  s.api('POST', `/api/crud/${encodeURIComponent(concept)}/query`, { filters, sort: [{ field: 'id', dir: 'desc' }], page: 1, size });
const board = async (s, params) => (await s.api('GET', '/api/production/board?' + new URLSearchParams(params))).data;
/** 把卡片拖到另一列：先把卡片滚入视口，在同一高度落到目标列（列等高），避免拖动中滚动打断 HTML5 拖放。 */
async function dragCard(page, card, col) {
  // 滚到视口中部，避免卡片落在固定的顶栏下面
  await card.evaluate((el) => el.scrollIntoView({ block: 'center' }));
  const s = await card.boundingBox();
  const c = await col.boundingBox();
  const y = s.y + s.height / 2;
  await page.mouse.move(s.x + s.width / 2, y);
  await page.mouse.down();
  await page.mouse.move(s.x + s.width / 2 + 10, y, { steps: 3 });
  await page.mouse.move(c.x + c.width / 2, y, { steps: 12 });
  await page.mouse.up();
}

const toast = async (page, text) => {
  try {
    await page.locator('.el-message', { hasText: text }).first().waitFor({ timeout: 15000 });
  } catch (e) {
    const shown = await page.locator('.el-message, .el-notification, .el-message-box').allTextContents();
    throw new Error(`等待提示「${text}」超时，页面提示：${shown.join(' | ') || '无'}`);
  }
};

try {
  // —— 标准 Pack 与菜单 ——
  const packs = (await admin.api('GET', '/api/packs')).data;
  const prod = packs.find((p) => p.code === 'ls.production');
  check(prod && prod.status === 'ENABLED' && prod.standard, '轻生产标准 Pack 已随应用启动安装并启用');
  const routers = JSON.stringify((await leader.api('GET', '/system/menu/getRouters')).data);
  check(routers.includes('生产需求板') && routers.includes('交接班') && routers.includes('经营看板'), '车间主任有轻生产菜单');
  const opRouters = JSON.stringify((await worker.api('GET', '/system/menu/getRouters')).data);
  check(opRouters.includes('移动填报') && !opRouters.includes('生产需求板'), '操作工只有工作板与移动填报');

  // —— 需求板：文字需求 → 两条工作 ——
  const title = `周三前完成 A 线清洗与 200 件组装（E2E ${ts}）`;
  await leader.page.goto(BASE + '/production/requests');
  await leader.page.getByTestId('btn-new-request').click();
  await leader.page.getByTestId('req-title').fill(title);
  await leader.page.getByTestId('btn-save-request').click();
  await toast(leader.page, '需求已发布');
  const req = (await query(leader, REQ, [{ field: 'title', op: 'eq', value: title }])).data.rows[0];
  check(req && req.status === 'OPEN', '需求登记并发布（只需描述）');
  await leader.page.getByTestId(`btn-convert-${req.id}`).click();
  await leader.page.getByTestId('convert-title-1').waitFor();
  check((await leader.page.getByTestId('convert-title-0').inputValue()) === 'A 线清洗', '按描述拆出第一条：A 线清洗');
  check((await leader.page.getByTestId('convert-title-1').inputValue()).startsWith('200 件组装'), '拆出第二条：200 件组装');
  await leader.page.getByTestId('btn-do-convert').click();
  await toast(leader.page, '已生成 2 条工作');
  const works = await board(leader, { requestId: req.id });
  check(works.length === 2, '生成两条 PRODUCTION 工作并关联需求');
  const [w1, w2] = works.sort((a, b) => a.id - b.id);
  check(w2.plannedQty == 200 && w2.unit === '件', '第二条带计划数量 200 件');

  // —— 分派：排到甲班今天早班，w1 分派给操作工 ——
  const teams = (await leader.api('GET', '/api/production/teams')).data;
  const teamA = teams.find((t) => t.name === '一车间甲班');
  const teamB = teams.find((t) => t.name === '一车间乙班');
  check(teamA && teamB && teamB.inScope, '班组列表含甲班、乙班');
  const operator = (await leader.api('GET', '/api/workbench/parties?keyword=' + encodeURIComponent('郑师傅'))).data[0];
  for (const w of [w1, w2]) {
    const c = (await leader.api('POST', `/api/crud/${PLAN}`, { workItem: w.id, team: teamA.id, shiftDate: today, shift: 'DAY' })).data;
    await exec(leader, `${PLAN}.activate`, c.id, c.version);
    const cur = (await board(leader, { requestId: req.id })).find((x) => x.id === w.id);
    await exec(leader, 'ls.core.Work.ready', w.id, cur.version);
  }
  let cur1 = (await board(leader, { requestId: req.id })).find((x) => x.id === w1.id);
  await exec(leader, 'ls.core.Work.assign', w1.id, cur1.version, { targets: [{ resourceType: 'PARTY', resourceRef: String(operator.id) }] });
  check(true, '排班到甲班早班并分派给操作工');

  // —— 工作板：拖动卡片调整班次 ——
  await leader.page.goto(BASE + '/production/board');
  await leader.page.getByTestId('board-team').click();
  await leader.page.locator('.el-select-dropdown__item:visible', { hasText: '一车间甲班' }).first().click();
  const card = leader.page.getByTestId(`card-${w1.id}`);
  await leader.page.getByTestId('col-DAY').getByTestId(`card-${w1.id}`).waitFor();
  await leader.page.waitForTimeout(500);
  await dragCard(leader.page, card, leader.page.getByTestId('col-EVENING'));
  await leader.page.getByTestId('col-EVENING').getByTestId(`card-${w1.id}`).waitFor({ timeout: 15000 });
  cur1 = (await board(leader, { requestId: req.id })).find((x) => x.id === w1.id);
  check(cur1.shift === 'EVENING' && String(cur1.plannedStart).includes('16:00'), '拖到中班：班次与计划时间同步更新');
  // 冲突提示浮在页面顶部，等它消失再拖，免得按到提示上
  await leader.page
    .locator('.el-message')
    .first()
    .waitFor({ state: 'detached', timeout: 15000 })
    .catch(() => undefined);
  await dragCard(leader.page, leader.page.getByTestId('col-EVENING').getByTestId(`card-${w1.id}`), leader.page.getByTestId('col-DAY'));
  await leader.page.getByTestId('col-DAY').getByTestId(`card-${w1.id}`).waitFor({ timeout: 15000 });
  cur1 = (await board(leader, { requestId: req.id })).find((x) => x.id === w1.id);
  check(cur1.shift === 'DAY', '拖回早班');

  // —— 移动填报（手机视口）：开始 → 草稿（拍照）→ 恢复草稿 → 提交 → 提交确认 ——
  const m = worker.page;
  if (process.env.E2E_DEBUG)
    m.on('response', async (r) => {
      if (/\/api\/|resource/.test(r.url())) console.log('  ↳', r.status(), r.url().replace(BASE, ''), (await r.text().catch(() => '')).slice(0, 200));
    });
  await m.goto(BASE + '/production/report');
  await m.getByTestId(`m-work-${w1.id}`).click();
  await m.getByTestId('m-start').click();
  await m.getByTestId('m-labor').waitFor({ timeout: 15000 });
  await m.locator('[data-testid="m-labor"] input').fill('90');
  await m.locator('[data-testid="m-output"] input').fill('48');
  await m.getByTestId('m-remark').fill('现场清洗完成，已拍照');
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
  await m.getByTestId('m-photo').setInputFiles({ name: `site-${ts}.png`, mimeType: 'image/png', buffer: png });
  await m.getByTestId('m-save-draft').click();
  await toast(m, '草稿已保存');
  const draft = await m.evaluate((id) => JSON.parse(localStorage.getItem(`ls-production-report-${id}`) ?? 'null'), w1.id);
  check(draft?.reportId > 0, '填报草稿保存到服务器，本机暂存表单');
  const evidence = (await worker.api('GET', `/api/doc?objectType=ls.core.WorkReport&objectId=${draft.reportId}`)).data;
  check(evidence.length === 1 && evidence[0].purpose === 'EVIDENCE', '照片作为证据附件关联到该填报');
  await m.reload();
  await m.getByTestId(`m-work-${w1.id}`).waitFor();
  check((await m.getByTestId(`m-work-${w1.id}`).textContent()).includes('有草稿'), '重新打开仍显示草稿');
  await m.getByTestId(`m-work-${w1.id}`).click();
  check((await m.locator('[data-testid="m-labor"] input').inputValue()) === '90', '草稿内容恢复');
  await m.locator('.el-slider__button-wrapper').focus();
  await m.keyboard.press('End');
  await m.getByTestId('m-submit').click();
  await toast(m, '填报已提交');
  await m.getByTestId('m-submit-confirm').click();
  await toast(m, '已提交确认');
  cur1 = (await board(leader, { requestId: req.id })).find((x) => x.id === w1.id);
  check(cur1.status === 'PENDING_CONFIRM' && Number(cur1.progress) === 100, '手机端完成填报并提交确认');
  check((await worker.api('GET', `/api/doc?objectType=ls.core.WorkReport&objectId=${draft.reportId}`)).data.length === 1, '提交后证据仍挂在该填报');
  await exec(leader, 'ls.core.Work.confirm', w1.id, cur1.version);
  check((await board(leader, { requestId: req.id })).find((x) => x.id === w1.id).status === 'DONE', '车间主任确认，工作完成');

  // —— 交接班：甲班 → 乙班 ——
  const t = team.page;
  await t.goto(BASE + '/production/handover');
  await t.getByTestId('btn-new-handover').click();
  await t.getByTestId('ho-to').click();
  await t.locator('.el-select-dropdown__item:visible', { hasText: '一车间乙班' }).first().click();
  await t.getByTestId('btn-create-handover').click();
  await t.getByTestId('ho-summary').waitFor({ timeout: 15000 });
  const summaryText = await t.getByTestId('ho-summary').inputValue();
  check(summaryText.includes(w2.title) && summaryText.includes('未完成'), '摘要草稿包含当班未完成事项');
  check((await t.getByTestId('ho-items').textContent()).includes(w2.code), '交接事项列出未完成工作');
  const ho = (await query(team, HO, [{ field: 'status', op: 'eq', value: 'DRAFT' }])).data.rows[0];
  check((await query(leader, HO, [{ field: 'id', op: 'eq', value: ho.id }])).data.total === 0, '草稿未确认：接班方列表中看不到');
  check((await leader.api('GET', `/api/crud/${HO}/${ho.id}`)).code !== 200, '草稿未确认：接班方打不开详情');
  await t.getByTestId('btn-publish').click();
  await toast(t, '已确认交班');
  check((await query(leader, HO, [{ field: 'id', op: 'eq', value: ho.id }])).data.total === 1, '交班确认后接班方可见');
  const pub = (await team.api('GET', `/api/crud/${HO}/${ho.id}`)).data;
  const self = await team.api('POST', `/api/op/${HO}.accept`, { targetId: String(ho.id), version: pub.version, input: {} });
  check(self.code !== 200, '交班人不能自己接班：' + self.msg);

  const l = leader.page;
  await l.goto(BASE + '/production/handover');
  await l.getByTestId(`ho-row-${ho.id}`).click();
  await l.getByTestId('btn-accept').click();
  // 成功时显示"已确认接班"；移入下一班次与其他工作冲突时显示冲突提示，状态同样变为已完成
  await l.getByTestId('ho-detail-status').filter({ hasText: '已完成' }).waitFor({ timeout: 15000 });
  const done = (await leader.api('GET', `/api/crud/${HO}/${ho.id}`)).data;
  check(done.status === 'COMPLETED', '双方确认，交接班完成');
  const bBoard = await board(leader, { teamId: teamB.id });
  const moved = bBoard.find((x) => x.id === w2.id);
  check(moved && moved.shift === 'EVENING', '未完成事项出现在乙班工作板（下一班次：中班）');

  // —— 经营看板：钻取与指标一致 ——
  const a = admin.page;
  await a.goto(BASE + '/production/dashboard');
  await a.getByTestId('widget-value-0').waitFor({ timeout: 20000 });
  const workload = Number((await a.getByTestId('widget-value-0').textContent()).replace(/[^\d.]/g, ''));
  await a.getByTestId('widget-value-0').click();
  const drillCount = async () => {
    await a.getByTestId('drill-total').waitFor();
    await a
      .waitForFunction(
        () => {
          const d = document.querySelector('[data-testid="drill-dialog"]');
          return d && !d.querySelector('.el-loading-mask') && Number(d.querySelector('[data-testid="drill-total"]')?.textContent) > 0;
        },
        null,
        { timeout: 15000 }
      )
      .catch(() => undefined);
    return Number(await a.getByTestId('drill-total').textContent());
  };
  const drillTotal = await drillCount();
  check(workload > 0 && drillTotal === workload, `工作量 ${workload} 与钻取明细 ${drillTotal} 条一致`);
  await a.keyboard.press('Escape');
  await a.getByTestId('drill-dialog').waitFor({ state: 'hidden' });
  await a.getByTestId('widget-value-1').click();
  const rateRows = await drillCount();
  check((await a.getByTestId('drill-table').textContent()).includes(w1.code), '按期完成率钻取明细包含刚完成的工作');
  // 与看板"本月"相同的区间（右开）
  const from = today.slice(0, 8) + '01';
  const next = new Date(d.getFullYear(), d.getMonth() + 1, 1);
  const to = `${next.getFullYear()}-${String(next.getMonth() + 1).padStart(2, '0')}-01`;
  const q = (await admin.api('POST', '/api/metrics/ls.production.onTimeRate/query', { from, to })).data.rows[0];
  const num = (await admin.api('POST', '/api/metrics/ls.production.onTimeRate/drill', { from, to, numeratorOnly: true, page: 1, size: 1 })).data
    .total;
  check(
    rateRows === Number(q.denominator) && num === Number(q.numerator),
    `按期完成率钻取：分母 ${rateRows} 条、分子 ${num} 条，与指标值 ${q.value} 一致`
  );
  const rateCard = (await a.getByTestId('widget-value-1').textContent()).replace(/\s/g, '');
  check(rateCard.includes((Number(q.value) * 100).toFixed(0)), `看板显示 ${rateCard}`);
  for (const s of [admin, leader, team, worker]) check(s.errors.length === 0, '页面无脚本错误：' + s.errors.join('；'));
} finally {
  await admin.browser.close();
}
