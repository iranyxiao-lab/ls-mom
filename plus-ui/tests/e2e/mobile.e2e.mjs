/**
 * modernize-ui-ux 第 7 组端到端（无头 Chromium，手机视口 390×844 触屏，部署环境）：手机版。
 *   7.1 操作工在手机上登录自动进入 /m；管理员电脑宽度进入桌面；「我的」可切到电脑版并记住；渲染器按 platform 用手机控件
 *   7.2 首屏是本人本班次任务卡片（开工／报工主按钮），本班次在前、按紧急程度排序；待我确认、快捷动作
 *   7.3 报工只显示必要字段，数字 ± 步进，提交固定在底部；常规报工点击 ≤5；触控目标 ≥44px
 *   7.4 拍照自动压缩、可标注，提交后作为证据附件出现在报工单时间轴；按住语音按钮说话，文字填入缺陷描述
 *   7.5 断网报工显示"待同步"，恢复网络后自动提交且服务端只有一条；任务已取消时同步失败可丢弃；PWA 外壳离线可打开并显示缓存的今日任务
 *   7.6 扫设备码进入点检清单，异常项一键生成缺陷草稿；从设备登记缺陷点击 ≤4
 * 运行：node tests/e2e/mobile.e2e.mjs（LSMOM_E2E_BASE 指定环境）
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { BASE, check, open } from './lib.mjs';

const ts = Date.now().toString(36).toUpperCase();
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'lsmom-mobile-'));
const PLAN = 'ls.production.ShiftPlan';

// 大照片（4000×3000，验证上传前压缩）
const bigPhoto = path.join(work, 'site.jpg');
execFileSync('python3', [
  '-I',
  '-c',
  "import sys;from PIL import Image,ImageDraw;im=Image.new('RGB',(4000,3000));d=ImageDraw.Draw(im)\nfor i in range(0,4000,40): d.line([(i,0),(4000-i,3000)],fill=(i%255,(i*7)%255,(i*13)%255),width=9)\nim.save(sys.argv[1],quality=97)",
  bigPhoto
]);
const bigSize = fs.statSync(bigPhoto).size;

const admin = await open({ user: 'admin' });
const op = await open({ user: 'demo_operator', mobile: true, browser: admin.browser });
let p = op.page;

const day = (offset = 0) => {
  const d = new Date(Date.now() + offset * 864e5);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};
const today = day(0);
const h = new Date().getHours();
const SHIFT = h < 8 ? 'NIGHT' : h < 16 ? 'DAY' : 'EVENING';
const OTHER = SHIFT === 'DAY' ? 'EVENING' : 'DAY';
const SHIFT_LABEL = { NIGHT: '夜班', DAY: '早班', EVENING: '中班' };
const ok = (r, what) => {
  if (r.code !== 200) throw new Error(`${what} 失败：${r.msg}`);
  return r.data;
};
const exec = (s, code, targetId, version, input = {}) => s.api('POST', `/api/op/${code}`, { targetId: String(targetId), version, input });
const board = async (s, f = {}) => (await s.api('GET', '/api/production/board?' + new URLSearchParams({ from: day(-7), to: day(1), ...f }))).data;

/** 点击计数：只统计可信的 pointerup（一次点按算一次），文字输入与 setInputFiles 不计 */
const probes = () => {
  if (window.__mc) return;
  window.__mc = { n: 0, log: [] };
  document.addEventListener(
    'pointerup',
    (e) => {
      if (!e.isTrusted) return;
      window.__mc.n++;
      window.__mc.log.push((e.target.closest('[data-testid]')?.getAttribute('data-testid') ?? e.target.tagName) + '');
    },
    true
  );
  // 语音识别桩：按住说话、松开时返回固定识别结果（无头浏览器没有麦克风）
  window.SpeechRecognition = window.webkitSpeechRecognition = class {
    start() {}
    stop() {
      const r = [{ transcript: '三号泵轴承异响' }];
      r.isFinal = true;
      this.onresult?.({ resultIndex: 0, results: [r] });
      this.onend?.();
    }
  };
};
await op.page.context().addInitScript(probes);
const clicks = {
  reset: () => p.evaluate(() => Object.assign(window.__mc, { n: 0, log: [] })),
  get: () => p.evaluate(() => ({ ...window.__mc }))
};
/** 可见交互元素的触控尺寸 ≥44×44（输入框按所在字段整体计） */
const smallTargets = () =>
  p.evaluate(() => {
    const out = [];
    for (const el of document.querySelectorAll(
      '.m-app button, .m-app a, .m-app [role="button"], .m-app [role="radio"], .m-app input:not([type="file"])'
    )) {
      const target = el.closest('.van-field, .van-stepper') && el.tagName === 'INPUT' ? el.closest('.van-cell, .van-stepper') : el;
      const r = target.getBoundingClientRect();
      if (!r.width || !r.height || getComputedStyle(el).visibility === 'hidden') continue;
      if (r.width < 44 || r.height < 44) out.push(`${el.getAttribute('data-testid') ?? el.tagName}:${Math.round(r.width)}×${Math.round(r.height)}`);
    }
    return out;
  });

try {
  // —— 数据：两条分派给操作工的今日工作（本班次一条、其他班次一条），本班次的已开工 ——
  const me = ok(await op.api('GET', '/api/flow/me'), '操作工身份');
  const teams = (await admin.api('GET', '/api/production/teams')).data;
  const teamA = teams.find((t) => t.name === '一车间甲班');
  const mk = async (title, shift, priority) => {
    const w = ok(
      await admin.api('POST', '/api/op/ls.core.Work.create', {
        input: {
          workType: 'PRODUCTION',
          title,
          orgUnitId: teamA.id,
          priority,
          plannedStart: `${today}T08:00:00`,
          plannedEnd: `${today}T16:00:00`,
          plannedQty: 300,
          unit: '件'
        }
      }),
      '新建工作'
    );
    const c = ok(await admin.api('POST', `/api/crud/${PLAN}`, { workItem: Number(w.targetId), team: teamA.id, shiftDate: today, shift }), '排班');
    ok(await exec(admin, `${PLAN}.activate`, c.id, c.version), '排班生效');
    let cur = (await board(admin)).find((x) => String(x.id) === String(w.targetId));
    ok(await exec(admin, 'ls.core.Work.ready', cur.id, cur.version), '就绪');
    cur = (await board(admin)).find((x) => x.id === cur.id);
    ok(
      await exec(admin, 'ls.core.Work.assign', cur.id, cur.version, { targets: [{ resourceType: 'PARTY', resourceRef: String(me.partyId) }] }),
      '分派'
    );
    return cur.id;
  };
  const wNow = await mk(`手机报工 本班次 ${ts}`, SHIFT, 1);
  const wOther = await mk(`手机报工 其他班次 ${ts}`, OTHER, 3);
  const wCancel = await mk(`手机报工 将取消 ${ts}`, SHIFT, 2);

  // —— 7.1 自动进入手机版；电脑宽度的管理员进入桌面 ——
  await p.goto(BASE + '/index');
  await p.waitForURL(/\/m$/, { timeout: 20000 });
  check(true, '7.1 操作工在手机视口登录后自动进入 /m');
  check((await p.locator('.sidebar-container').count()) === 0, '7.1 手机版不显示桌面管理菜单');
  await admin.page.goto(BASE + '/index');
  await admin.page.waitForTimeout(1500);
  check(admin.page.url().endsWith('/index'), '7.1 电脑宽度的管理员留在桌面');

  // —— 7.2 首屏：本班次任务卡片在前，带主按钮 ——
  const home = p.getByTestId('m-home');
  await home.waitFor();
  await p.getByTestId(`m-task-${wNow}`).waitFor({ timeout: 20000 });
  const sectionNow = await p.getByTestId('m-section-current').textContent();
  check(sectionNow.includes(SHIFT_LABEL[SHIFT]), `7.2 首屏是本班次（${SHIFT_LABEL[SHIFT]}）任务：${sectionNow}`);
  const order = await p.locator('[data-testid^="m-task-"][data-status]').evaluateAll((els) => els.map((e) => e.getAttribute('data-testid')));
  check(order.indexOf(`m-task-${wNow}`) < order.indexOf(`m-task-${wOther}`), '7.2 本班次任务排在其他班次之前');
  check(order.indexOf(`m-task-${wNow}`) < order.indexOf(`m-task-${wCancel}`), '7.2 同班次按紧急程度排序（1 最急在前）');
  check((await p.getByTestId(`m-task-start-${wNow}`).textContent()).includes('开工'), '7.2 已分派的任务卡片主按钮为「开工」');
  for (const q of ['m-quick-report', 'm-quick-inspect', 'm-quick-defect', 'm-quick-scan'])
    check(await p.getByTestId(q).isVisible(), `7.2 快捷动作 ${q}`);
  check((await p.getByText(/待我确认（\d+）/).count()) === 1, '7.2 显示待我确认');
  check((await smallTargets()).length === 0, '7.3 工作台触控目标都 ≥44px：' + (await smallTargets()).join('、'));

  // 一键开工 → 报工页
  await p.getByTestId(`m-task-start-${wNow}`).click();
  await p.waitForURL(new RegExp(`/m/report/${wNow}$`));
  await p.getByTestId('m-report-form').waitFor({ timeout: 15000 });
  check(true, '7.2 一键开工后进入报工页');

  // —— 7.3 报工页：必要字段、数字步进、底部主按钮、触控尺寸 ——
  const labels = await p.getByTestId('m-report-form').locator('.m-label').allTextContents();
  check(
    labels.length === 4 && labels[0].startsWith('产出') && labels.some((l) => l.startsWith('进度')) && labels.some((l) => l.startsWith('说明')),
    '7.3 只显示必要字段：' + labels.join('、')
  );
  check((await p.getByTestId('input-outputQty').locator('.van-stepper__plus').count()) === 1, '7.3 数字字段带 ± 步进');
  check((await p.getByTestId('input-outputQty').locator('input').getAttribute('inputmode')) !== null, '7.3 数字字段弹数字键盘（inputmode）');
  const sub = await p.getByTestId('m-report-submit').boundingBox();
  check(sub && sub.y + sub.height > 844 - 140 && sub.height >= 44, '7.3 「提交」固定在屏幕底部拇指区');
  check((await smallTargets()).length === 0, '7.3 报工页触控目标都 ≥44px：' + (await smallTargets()).join('、'));
  await p.getByTestId('m-back').click();
  await p.getByTestId(`m-task-report-${wNow}`).waitFor({ timeout: 15000 });

  // 常规报工（不拍照）：点「报工」→ 输入 120 → 点「提交」
  await clicks.reset();
  await p.getByTestId(`m-task-report-${wNow}`).click();
  await p.getByTestId('m-report-form').waitFor();
  await p.getByTestId('input-outputQty').locator('input').fill('120');
  await p.getByTestId('m-report-submit').click();
  await p.waitForURL(/\/m$/, { timeout: 15000 });
  const c1 = await clicks.get();
  check(c1.n <= 5, `7.3 常规报工点击 ${c1.n} 次 ≤5（${c1.log.join(' → ')}）`);
  const eventsOf = async (id) => (await admin.api('GET', `/api/timeline/ls.core.Work/${id}`)).data ?? [];
  const reports = async (id) => (await eventsOf(id)).filter((e) => e.type === 'ls.core.Work.submitReport');
  check((await reports(wNow)).length === 1 && (await reports(wNow))[0].payload?.reportId, '7.3 报工已提交到服务端');

  // —— 7.4 拍照（压缩、标注）作为证据附件 ——
  await p.getByTestId(`m-task-report-${wNow}`).click();
  await p.getByTestId('m-report-form').waitFor();
  await p.getByTestId('input-outputQty').locator('input').fill('30');
  await p.getByTestId('m-photo-input').setInputFiles(bigPhoto);
  await p.getByTestId('m-photo-0').waitFor({ timeout: 15000 });
  await p.getByTestId('m-photo-mark-0').click();
  const cv = p.getByTestId('m-mark-canvas');
  await cv.waitFor();
  const box = await cv.boundingBox();
  await p.mouse.move(box.x + 40, box.y + 40);
  await p.mouse.down();
  await p.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 8 });
  await p.mouse.up();
  await p.getByTestId('m-mark-done').click();
  await p.getByTestId('m-mark').waitFor({ state: 'detached' });
  const shrunk = Number(await p.getByTestId('m-photo-0').getAttribute('data-size'));
  check(shrunk > 0 && shrunk < bigSize / 3, `7.4 照片上传前已压缩（${Math.round(bigSize / 1024)}KB → ${Math.round(shrunk / 1024)}KB）`);
  await p.getByTestId('m-report-submit').click();
  await p.waitForURL(/\/m$/, { timeout: 30000 });
  const r2 = (await reports(wNow)).at(-1);
  const tl = (await admin.api('GET', `/api/timeline/ls.core.WorkReport/${r2.payload.reportId}`)).data ?? [];
  check(
    tl.some((e) => e.type === 'DOCUMENT' && e.action.startsWith('上传现场证据：site')),
    '7.4 照片作为证据附件出现在报工单时间轴'
  );

  // —— 7.5 断网报工 → 待同步 → 恢复网络自动提交，服务端只多一条 ——
  const before = (await reports(wNow)).length;
  await p.getByTestId(`m-task-report-${wNow}`).click();
  await p.getByTestId('m-report-form').waitFor();
  await p.context().setOffline(true);
  await p.getByTestId('m-offline').waitFor({ timeout: 5000 });
  await p.getByTestId('input-outputQty').locator('input').fill('45');
  await p.getByTestId('m-report-submit').click();
  await p.waitForURL(/\/m$/, { timeout: 15000 });
  await p.getByTestId(`m-task-pending-${wNow}`).waitFor({ timeout: 10000 });
  check((await p.getByTestId('m-pending').textContent()).includes('待同步 1'), '7.5 断网提交显示"待同步"');
  check((await reports(wNow)).length === before, '7.5 断网期间服务端没有新报工');
  await p.context().setOffline(false);
  await p.getByTestId('m-pending').waitFor({ state: 'detached', timeout: 20000 });
  await p.waitForTimeout(1000);
  check((await reports(wNow)).length === before + 1, '7.5 恢复网络后自动提交，服务端只多一条报工');

  // 任务被取消：同步失败，可查看并丢弃
  let cc = (await board(admin)).find((x) => x.id === wCancel);
  ok(await exec(op, 'ls.core.Work.start', wCancel, cc.version), '开工待取消的任务');
  await p.reload();
  await p.getByTestId(`m-task-report-${wCancel}`).click({ timeout: 20000 });
  await p.getByTestId('m-report-form').waitFor();
  await p.context().setOffline(true);
  await p.getByTestId('input-outputQty').locator('input').fill('10');
  await p.getByTestId('m-report-submit').click();
  await p.waitForURL(/\/m$/, { timeout: 15000 });
  cc = (await board(admin)).find((x) => x.id === wCancel);
  ok(await exec(admin, 'ls.core.Work.cancel', wCancel, cc.version, { reason: '计划调整' }), '班组长取消任务');
  await p.context().setOffline(false);
  await p.getByTestId('m-tab-me').click();
  const failed = p.locator('[data-testid^="m-outbox-"][data-status="FAILED"]').first();
  await failed.waitFor({ timeout: 20000 });
  const failText = await failed.getByTestId('m-outbox-error').textContent();
  check(failText.includes('同步失败：任务已取消'), '7.5 同步时任务已取消：标为"同步失败：任务已取消"：' + failText);
  check((await reports(wCancel)).length === 0, '7.5 已取消的任务没有生成报工');
  await failed.getByTestId('m-outbox-discard').click();
  await p.locator('.van-dialog__confirm').click();
  await p.getByTestId('m-outbox-empty').waitFor({ timeout: 10000 });
  check(true, '7.5 用户丢弃失败条目');

  // PWA：manifest 可添加到主屏、全屏；外壳与今日任务离线可打开
  const manifest = await (await fetch(BASE + '/manifest.webmanifest')).json();
  check(
    manifest.display === 'standalone' && manifest.start_url.endsWith('/m') && manifest.icons.some((i) => i.sizes === '512x512'),
    '7.5 PWA manifest：全屏、从 /m 打开、图标齐全'
  );
  const swReady = await p.evaluate(async () => {
    const reg = await Promise.race([navigator.serviceWorker.ready, new Promise((r) => setTimeout(() => r(null), 15000))]);
    return !!reg?.active;
  });
  check(swReady, '7.5 Service Worker 已激活');
  await p.goto(BASE + '/m');
  await p.getByTestId(`m-task-${wNow}`).waitFor({ timeout: 20000 });
  await p.waitForTimeout(1500);
  await p.context().setOffline(true);
  await p.reload();
  await p.getByTestId('m-app').waitFor({ timeout: 20000 });
  await p.getByTestId('m-offline').waitFor({ timeout: 10000 });
  await p.getByTestId(`m-task-${wNow}`).waitFor({ timeout: 15000 });
  check((await p.getByTestId('m-cached').count()) === 1, '7.5 无网络打开：外壳与缓存的今日任务可以显示，并提示离线');
  await p.context().setOffline(false);

  // —— 7.6 点检（班组长在手机上点检）：扫设备码进入清单，异常项一键生成缺陷草稿 ——
  const tm = await open({ user: 'demo_team', mobile: true, browser: admin.browser });
  await tm.page.context().addInitScript(probes);
  p = tm.page;
  const tmMe = ok(await tm.api('GET', '/api/flow/me'), '班组长身份');
  // 甲班的新设备并投用（班组长的数据范围内）
  const na = ok(
    await admin.api('POST', '/api/crud/ls.asset.Asset', { assetCode: `MB-${ts}`, name: `三号泵 ${ts}`, assetType: 'STATION', orgId: teamA.id }),
    '新建设备'
  );
  const commission = await exec(admin, 'ls.asset.Asset.commission', na.id, na.version, { reason: '投用' });
  if (commission.code !== 200) throw new Error('投用设备失败：' + commission.msg);
  const asset = (await admin.api('GET', `/api/crud/ls.asset.Asset/${na.id}`)).data;
  check(asset?.status === 'RUNNING', '甲班新设备已投用');
  const plan = ok(
    await admin.api('POST', '/api/crud/ls.asset.InspectionPlan', {
      name: `手机点检 ${ts}`,
      asset: asset.id,
      cycle: 'DAILY',
      responsible: tmMe.partyId,
      nextDue: today,
      orgId: asset.orgId,
      inspectionPlanItem: [
        { itemCode: 'TEMP', name: '轴承温度', valueType: 'NUMBER', unit: '℃', upperLimit: 70 },
        { itemCode: 'LEAK', name: '机封泄漏', valueType: 'CHECK' }
      ]
    }),
    '建点检计划'
  );
  ok(await exec(admin, 'ls.asset.InspectionPlan.activate', plan.id, plan.version), '启用点检计划');
  const pl = (await admin.api('GET', `/api/crud/ls.asset.InspectionPlan/${plan.id}`)).data;
  ok(await exec(admin, 'ls.asset.InspectionPlan.generate', plan.id, pl.version), '生成点检');
  await p.goto(BASE + '/m');
  await p.getByTestId('m-tab-scan').click();
  const manual = p.locator('[data-testid="scan-manual"] input, input[data-testid="scan-manual"]').first();
  await manual.fill(asset.assetCode, { timeout: 15000 });
  await manual.press('Enter');
  await p
    .getByTestId('m-item-TEMP')
    .waitFor({ timeout: 15000 })
    .catch(async (e) => {
      const mineNow = (await op.api('GET', '/api/asset/inspections/mine?status=DRAFT')).data;
      throw new Error(
        `没有打开点检清单：${p.url()} 提示「${await p
          .getByTestId('m-inspect-hint')
          .textContent()
          .catch(() => '')}」 我的待点检 ${JSON.stringify(mineNow?.map((m) => m.asset_code))} 设备 ${asset.assetCode}`
      );
    });
  check(p.url().includes('/m/inspect'), '7.6 扫设备码直接打开该设备的待点检清单');
  await p.getByTestId('m-item-TEMP').locator('input').fill('82');
  await p.getByTestId('m-item-LEAK-ok').click();
  await p.getByTestId('m-inspect-submit').click();
  await p.getByTestId('m-inspect-abnormal').filter({ hasText: '轴承温度' }).waitFor({ timeout: 15000 });
  check(true, '7.6 温度超限自动判异常');
  await p.getByTestId('m-inspect-raise').click();
  await p.waitForURL(/\/m\/defect\?id=\d+/, { timeout: 15000 });
  const draftId = Number(new URL(p.url()).searchParams.get('id'));
  const draft = (await admin.api('GET', `/api/crud/ls.asset.Defect/${draftId}`)).data;
  check(draft.status === 'DRAFT' && String(draft.asset) === String(asset.id), '7.6 异常项一键生成缺陷草稿并打开补充');
  await p.getByTestId('m-defect-discard').waitFor({ timeout: 15000 });
  const del = await p.getByTestId('m-defect-discard').boundingBox();
  const subm = await p.getByTestId('m-defect-submit').boundingBox();
  check(del.y < 200 && subm.y > 600 && subm.y - (del.y + del.height) > 300, '7.3 删除草稿在顶部，不与底部「提交」相邻');
  // 补一张现场照片后登记
  const dbg = [];
  p.on(
    'response',
    async (r) =>
      /Defect|DocumentLink|oss\/upload/.test(r.url()) &&
      dbg.push(r.request().method() + ' ' + r.url().replace(/.*\/api/, '') + ' ' + (await r.text().catch(() => '')).slice(0, 160))
  );
  p.on('console', (m) => m.type() === 'error' && dbg.push('console ' + m.text().slice(0, 200)));
  await p.getByTestId('m-photo-input').setInputFiles(bigPhoto);
  await p.getByTestId('m-photo-0').waitFor({ timeout: 15000 });
  await p.getByTestId('m-defect-submit').click();
  await p.waitForURL(/\/m$/, { timeout: 30000 }).catch(async (e) => {
    throw new Error(
      '登记缺陷草稿没有完成：' +
        (await p.locator('.van-toast, .el-notification, .el-message, .el-form-item__error').allTextContents()).join(' | ') +
        '\n' +
        dbg.join('\n')
    );
  });
  check((await admin.api('GET', `/api/crud/ls.asset.Defect/${draftId}`)).data.status === 'REPORTED', '7.6 补充照片后登记缺陷');

  // 从设备登记缺陷：扫码打开设备 → 报缺陷 → 按住说话 → 拍照 → 提交（≤4 次点击）
  const pl2 = (await admin.api('GET', `/api/crud/ls.asset.InspectionPlan/${plan.id}`)).data;
  ok(await exec(admin, 'ls.asset.InspectionPlan.generate', plan.id, pl2.version), '再生成一次点检');
  await p.goto(BASE + `/m/inspect?code=${encodeURIComponent(asset.assetCode)}`);
  await p.getByTestId('m-inspect-defect').waitFor({ timeout: 15000 });
  await clicks.reset();
  await p.getByTestId('m-inspect-defect').click();
  await p.getByTestId('m-defect').waitFor();
  await p.getByTestId('voice-title').waitFor({ timeout: 15000 });
  const vb = await p.getByTestId('voice-title').boundingBox();
  await p.mouse.move(vb.x + vb.width / 2, vb.y + vb.height / 2);
  await p.mouse.down();
  await p.waitForTimeout(400);
  await p.mouse.up();
  await p.waitForTimeout(300);
  const said = await p.getByTestId('input-title').locator('textarea, input').first().inputValue();
  check(said.includes('三号泵轴承异响'), '7.4 按住语音按钮说话，文字填入缺陷描述：' + said);
  const [chooser] = await Promise.all([p.waitForEvent('filechooser'), p.getByTestId('m-photo-add').click()]);
  await chooser.setFiles(bigPhoto);
  await p.getByTestId('m-photo-0').waitFor({ timeout: 15000 });
  await p.getByTestId('m-defect-submit').click();
  await p.waitForURL(/\/m$/, { timeout: 30000 });
  const c2 = await clicks.get();
  check(c2.n <= 4, `7.6 从设备登记缺陷点击 ${c2.n} 次 ≤4（${c2.log.join(' → ')}）`);
  const d2 = (
    await admin.api('POST', '/api/crud/ls.asset.Defect/query', {
      filters: [
        { field: 'title', op: 'like', value: '三号泵轴承异响' },
        { field: 'asset', op: 'eq', value: asset.id }
      ],
      sort: [{ field: 'id', dir: 'desc' }],
      page: 1,
      size: 1
    })
  ).data.rows[0];
  check(d2?.status === 'REPORTED', '7.6 缺陷已登记');
  const dtl = (await admin.api('GET', `/api/timeline/ls.asset.Defect/${d2.id}`)).data ?? [];
  check(
    dtl.some((e) => e.type === 'DOCUMENT' && e.action.startsWith('上传现场证据')),
    '7.4 缺陷照片作为证据附件出现在单据时间轴'
  );

  check(tm.errors.length === 0, '班组长页面无脚本错误：' + tm.errors.join('；'));
  // —— 7.1 手动切换：「我的」切到电脑版，回首页不再自动跳转；切回自动 ——
  p = op.page;
  await p.goto(BASE + '/m/me');
  await p.getByTestId('m-to-desktop').click();
  await p.waitForURL(/\/index$/, { timeout: 15000 });
  await p.goto(BASE + '/index');
  await p.waitForTimeout(1500);
  check(p.url().endsWith('/index'), '7.1 手动切到电脑版后手机上也留在桌面');
  ok(await op.api('PUT', '/api/ui/prefs', { uiMode: 'auto' }), '恢复自动');
  check(op.errors.length === 0, '页面无脚本错误：' + op.errors.join('；'));
} finally {
  await op.api('PUT', '/api/ui/prefs', { uiMode: 'auto' }).catch(() => undefined);
  await admin.browser.close();
  fs.rmSync(work, { recursive: true, force: true });
}
