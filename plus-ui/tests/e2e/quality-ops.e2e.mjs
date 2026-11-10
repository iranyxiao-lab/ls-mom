/**
 * 14.2 端到端（无头 Chromium，部署环境，演示账号）：
 *   DOM-04：质检员登记一条无关联的质量记录、一条同时关联工作与设备的质量记录，两者都保存成功并出现在工作与设备时间轴；
 *   高严重度不符合项缺立即措施：前端拦下，直接调 API 也被后端拒绝；
 *   受控质量闭环：质检员登记 → 车间主任审核（问题描述只读，API 改描述被拒）并派甲班班长整改 →
 *   班长执行整改，无证据提交验证被拒 → 上传证据后提交 → 质检员终判关闭。
 * 运行：node tests/e2e/quality-ops.e2e.mjs（LSMOM_E2E_BASE 指定环境）
 */
import { BASE, check, open } from './lib.mjs';

const REC = 'ls.quality.QualityRecord';
const NC = 'ls.quality.NonConformance';
const ts = Date.now().toString(36).toUpperCase();
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

const admin = await open();
const qa = await open({ user: 'demo_quality', browser: admin.browser });
const leader = await open({ user: 'demo_leader', browser: admin.browser });
const team = await open({ user: 'demo_team', browser: admin.browser });

const exec = async (s, code, targetId, version, input = {}) => {
  const r = await s.api('POST', `/api/op/${code}`, { targetId: targetId == null ? undefined : String(targetId), version, input });
  if (r.code !== 200) throw new Error(`${code} 失败：${r.msg}`);
  return r.data;
};
const one = async (s, concept, filters) =>
  (await s.api('POST', `/api/crud/${encodeURIComponent(concept)}/query`, { filters, sort: [{ field: 'id', dir: 'desc' }], page: 1, size: 1 })).data
    .rows[0];
const toast = async (page, text) => {
  const m = page.locator('.el-message', { hasText: text }).first();
  await m.waitFor({ timeout: 15000 });
  await m.waitFor({ state: 'detached', timeout: 15000 }).catch(() => undefined);
};
const pickOption = async (page, testId, text, typed) => {
  await page.getByTestId(testId).click();
  if (typed) await page.getByTestId(testId).locator('input').first().fill(typed);
  await page.locator('.el-select-dropdown__item:visible', { hasText: text }).first().click();
  await page
    .locator('.el-select-dropdown:visible')
    .first()
    .waitFor({ state: 'hidden', timeout: 5000 })
    .catch(() => undefined);
};

try {
  // —— 准备：一车间甲班的一条工作、一台设备 ——
  const orgs = (await admin.api('GET', '/api/asset/orgs')).data;
  const teamA = orgs.find((o) => o.name === '一车间甲班');
  const workshop = orgs.find((o) => o.name === '一车间');
  const work = {
    id: (await exec(admin, 'ls.core.Work.create', null, null, { workType: 'PRODUCTION', title: `焊接 A 段 ${ts}`, orgUnitId: teamA.id })).targetId
  };
  const asset = (
    await admin.api('POST', '/api/crud/ls.asset.Asset', { assetCode: `WM-${ts}`, name: `焊机 ${ts}`, assetType: 'STATION', orgId: workshop.id })
  ).data;
  // 不符合项只能关联在用设备：先投用
  await exec(admin, 'ls.asset.Asset.commission', asset.id, asset.version ?? 0);
  check(work?.id && asset?.id, '准备工作与设备');

  // —— DOM-04：无关联、同时关联工作与设备 ——
  const q = qa.page;
  await q.goto(BASE + '/quality/records');
  await q.getByTestId('btn-new-record').click();
  await q.getByTestId('qr-title').fill(`来料抽检 ${ts}`);
  await q.getByTestId('btn-save-record').click();
  await toast(q, '质量记录已登记');
  await q.getByTestId('btn-new-record').click();
  await q.getByTestId('qr-title').fill(`焊缝外观检验 ${ts}`);
  await q.getByTestId('qr-result-FAIL').click();
  await q.getByTestId('btn-add-link').click();
  await pickOption(q, 'qr-link-obj-0', `焊接 A 段 ${ts}`, `焊接 A 段 ${ts}`);
  await q.getByTestId('btn-add-link').click();
  await pickOption(q, 'qr-link-type-1', '设备');
  await pickOption(q, 'qr-link-obj-1', `焊机 ${ts}`, `WM-${ts}`);
  await q.getByTestId('btn-save-record').click();
  await toast(q, '已关联 2 个对象');
  const free = await one(qa, REC, [{ field: 'title', op: 'eq', value: `来料抽检 ${ts}` }]);
  const linked = await one(qa, REC, [{ field: 'title', op: 'eq', value: `焊缝外观检验 ${ts}` }]);
  check(free?.status === 'SUBMITTED' && linked?.status === 'SUBMITTED', 'DOM-04：无关联与多关联的质量记录都保存成功');
  const wt = (await admin.api('GET', `/api/timeline/ls.core.Work/${work.id}`)).data;
  check(
    wt.some((e) => e.type === `${REC}.linked` && String(e.payload?.title).includes(`焊缝外观检验 ${ts}（不合格）`)),
    'DOM-04：质量记录出现在工作时间轴'
  );
  const at = (await admin.api('GET', `/api/asset/assets/${asset.id}/timeline`)).data;
  check(
    at.some((e) => e.kind === 'EVENT' && e.title.includes(`焊缝外观检验 ${ts}`)),
    'DOM-04：质量记录出现在设备时间轴'
  );

  // —— 高严重度条件必填：前端拦下、API 也拒绝 ——
  await q.goto(BASE + '/quality/nc');
  await q.getByTestId('btn-new-nc').click();
  await q.getByTestId('nc-title').fill(`焊缝气孔 ${ts}`);
  await q.getByTestId('nc-description').fill('抽检发现焊缝气孔');
  await q.getByTestId('nc-sev-HIGH').click();
  await q.getByTestId('btn-save-nc').click();
  await q.locator('.el-form-item__error', { hasText: '严重程度为高时必须填写立即措施' }).waitFor();
  check(await q.locator('.el-form-item__error', { hasText: '必须填写相关设备' }).isVisible(), '前端：高严重度缺设备与立即措施时标出并拦下');
  const bad = await qa.api('POST', `/api/crud/${NC}`, {
    title: `API 气孔 ${ts}`,
    description: 'x',
    severity: 'HIGH',
    asset: asset.id,
    orgId: workshop.id
  });
  check(bad.code !== 200 && String(bad.msg).includes('立即措施'), `后端：API 提交高严重度缺立即措施被拒（${bad.msg}）`);
  await q.keyboard.press('Escape');

  // —— 受控闭环：登记 ——
  await q.getByTestId('btn-new-nc').click();
  await q.getByTestId('nc-title').fill(`尺寸超差 ${ts}`);
  await pickOption(q, 'nc-org', /工厂 \/ 一车间$/);
  await q.getByTestId('nc-description').fill('轴径 50.08，公差 ±0.05');
  await q.getByTestId('btn-save-nc').click();
  await toast(q, '等待车间审核');
  const nc = await one(qa, NC, [{ field: 'title', op: 'eq', value: `尺寸超差 ${ts}` }]);
  check(nc?.status === 'PENDING_AUDIT' && nc.issue, '不符合项登记提交，产生 Issue，待车间审核');

  // —— 车间审核：描述只读，API 改描述被拒；审核通过派班长整改 ——
  const l = leader.page;
  await l.goto(BASE + '/quality/nc');
  await l.getByTestId(`nc-row-${nc.id}`).click();
  check(await l.getByTestId('nc-detail-description').evaluate((e) => e.readOnly), '审核节点：问题描述只读');
  const edit = await leader.api('PUT', `/api/crud/${NC}/${nc.id}`, { version: nc.version, description: '改一下' });
  const sneak = await leader.api('POST', `/api/op/${NC}.audit`, {
    targetId: String(nc.id),
    version: nc.version,
    input: { auditOpinion: '同意', owner: 1, description: '改一下' }
  });
  check(edit.code !== 200 && sneak.code !== 200 && String(sneak.msg).includes('只可填写'), `审核节点：API 修改问题描述被拒（${sneak.msg}）`);
  await l.getByTestId('nc-audit-opinion').fill('同意整改，先隔离');
  await l.getByTestId('nc-audit-action').fill('隔离 3 件');
  await pickOption(l, 'nc-owner', '吴班长', '吴');
  await l.getByTestId('btn-nc-audit').click();
  await l.getByTestId('nc-detail-status').filter({ hasText: '整改中' }).waitFor();
  const rect = (await leader.api('GET', `/api/quality/nc/${nc.id}/rectifications`)).data;
  check(rect.length === 1 && rect[0].assignees.includes('吴班长'), '审核通过：生成整改工作并派给班长');
  const w = rect[0];

  // —— 班长整改；主任确认；无证据提交被拒；上传证据后提交 ——
  const t = team.page;
  await t.goto(BASE + '/quality/nc');
  await t.getByTestId(`nc-row-${nc.id}`).click();
  await t.getByTestId(`btn-nc-work-start-${w.id}`).click();
  await t.getByTestId(`btn-nc-work-done-${w.id}`).click();
  await t.getByTestId(`nc-work-status-${w.id}`).filter({ hasText: '待确认' }).waitFor();
  await l.reload();
  await l.getByTestId(`nc-row-${nc.id}`).click();
  await l.getByTestId(`btn-nc-work-confirm-${w.id}`).click();
  await l.getByTestId(`nc-work-status-${w.id}`).filter({ hasText: '已完成' }).waitFor();
  await t.reload();
  await t.getByTestId(`nc-row-${nc.id}`).click();
  await t.getByTestId('btn-nc-submit-verify').click();
  await t.locator('.el-message, .el-notification', { hasText: '证据' }).first().waitFor();
  check((await one(team, NC, [{ field: 'title', op: 'eq', value: `尺寸超差 ${ts}` }])).status === 'IN_RECTIFICATION', '无整改证据提交验证被拒');
  await t.getByTestId(`nc-evidence-${w.id}`).setInputFiles({ name: `after-${ts}.png`, mimeType: 'image/png', buffer: png });
  await toast(t, '整改证据已上传');
  await t.getByTestId(`nc-work-evidence-${w.id}`).filter({ hasText: '1 个' }).waitFor();
  await t.getByTestId('btn-nc-submit-verify').click();
  await t.getByTestId('nc-detail-status').filter({ hasText: '待验证' }).waitFor();
  check(true, '上传证据后提交验证');

  // —— 质检员终判关闭 ——
  await q.reload();
  await q.getByTestId(`nc-row-${nc.id}`).click();
  await q.getByTestId('nc-conclusion').fill('复检 5 件合格');
  await q.getByTestId('btn-nc-close').click();
  await q.getByTestId('nc-detail-status').filter({ hasText: '已关闭' }).waitFor();
  const closed = (await qa.api('GET', `/api/crud/${NC}/${nc.id}`)).data;
  check(closed.status === 'CLOSED' && closed.verifyConclusion === '复检 5 件合格' && closed.closedBy, '质检员终判关闭，记录终判人与结论');
  const it = (await admin.api('GET', `/api/timeline/ls.core.Issue/${closed.issue?.id ?? closed.issue}`)).data;
  check(Array.isArray(it), 'Issue 外壳可查');
  for (const s of [qa, leader, team]) check(s.errors.length === 0, '页面无脚本错误：' + s.errors.join('；'));
} finally {
  await admin.browser.close();
}
