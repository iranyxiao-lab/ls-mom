/**
 * 15.x 端到端（无头 Chromium，部署环境 --profile dcs，演示账号）：
 *   模拟器 1Hz 推送 → 业务库只有分钟快照 → 设备员在「测点」页把 P101.VIB 映射到新设备 → 设备详情显示测点与最近快照 →
 *   DCS 报警页：自动草稿的报警确认为正式问题 →
 *   先人工后报警：设备员先登记泵振动缺陷，现场系统随后报出该泵振动报警 → 系统建议关联 → 确认后报警挂在该缺陷的 Issue 下、不新建 →
 *   只读边界：已发布 Operation 清单经黑名单扫描无 DCS 写入能力；无 DCS 权限的操作工被拒。
 * 运行：node tests/e2e/dcs-alarm.e2e.mjs（LSMOM_E2E_BASE 指定环境；LSMOM_E2E_TSDB 为时序库容器名，默认 lsmom-verify-tsdb-1）
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE, check, open } from './lib.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const TSDB = process.env.LSMOM_E2E_TSDB ?? 'lsmom-verify-tsdb-1';
const ASSET = 'ls.asset.Asset';
const DEFECT = 'ls.asset.Defect';
const ts = Date.now().toString(36).toUpperCase();
const assetCode = `P-${ts}`;
const pointCode = `E2E${ts}.VIB`;

/** 现场系统（模拟器）向 Historian 写入一条报警事件。业务系统本身只读 Historian。 */
function fieldAlarm(point, eventId) {
  const sql = `insert into historian_alarm_event (source_system, source_event_id, point_code, alarm_type, dedup_key, occurred_at, received_at,
    quality, severity, value, message, trace_id) values ('DCS-A', '${eventId}', '${point}', 'HI', 'DCS-A:${point}:HI', now(), now(), 'GOOD',
    'HIGH', 5.6, 'E2E 泵振动越上限 4.5mm/s', 'e2e-${eventId}')`;
  execFileSync('docker', ['exec', TSDB, 'psql', '-U', 'lsmom', '-d', 'lsmom_ts', '-qc', sql]);
}

const keeper = await open({ user: 'demo_asset' });
const operator = await open({ user: 'demo_operator', browser: keeper.browser });
const admin = await open({ user: 'admin', browser: keeper.browser });
const k = keeper.page;

const toast = async (page, text) => {
  const m = page.locator('.el-message', { hasText: text }).first();
  await m.waitFor({ timeout: 15000 });
  await m.waitFor({ state: 'detached', timeout: 15000 }).catch(() => undefined);
};
const pickOption = async (page, testId, text) => {
  await page.getByTestId(testId).click();
  const input = page.getByTestId(testId).locator('input').first();
  if (await input.isEditable().catch(() => false)) await input.fill(text);
  await page.locator('.el-select-dropdown__item:visible', { hasText: text }).first().click();
};
const one = async (s, concept, filters) =>
  (await s.api('POST', `/api/crud/${encodeURIComponent(concept)}/query`, { filters, sort: [{ field: 'id', dir: 'desc' }], page: 1, size: 1 })).data
    .rows[0];
const waitFor = async (fn, what, ms = 60000) => {
  const end = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > end) throw new Error('等待超时：' + what);
    await new Promise((r) => setTimeout(r, 2000));
  }
};

try {
  // —— 业务库只有分钟快照 ——
  const points = (await keeper.api('GET', '/api/dcs/points')).data;
  const vib = points.find((p) => p.code === 'P101.VIB');
  check(vib?.last_bucket && vib.last_avg !== null, `P101.VIB 有最近快照（${vib?.last_avg} ${vib?.unit}）`);
  const snaps = (await keeper.api('GET', '/api/dcs/points/P101.VIB/snapshots')).data;
  check(snaps.length >= 300 && snaps.length <= 362 && snaps.every((s) => s.bucket_seconds === 60), `最近 6 小时为 ${snaps.length} 个 60 秒快照`);

  // —— 新设备并把 P101.VIB 映射过去 ——
  await k.goto(BASE + '/asset/center');
  await k.getByTestId('btn-new-asset').click();
  await k.getByTestId('asset-code').fill(assetCode);
  await k.getByTestId('asset-name').fill('E2E DCS 循环泵');
  await pickOption(k, 'asset-org', '一车间');
  await k.getByTestId('btn-create-asset').click();
  await toast(k, '设备已投用');
  const asset = await one(keeper, ASSET, [{ field: 'assetCode', op: 'eq', value: assetCode }]);
  check(asset?.status === 'RUNNING', `新增设备 ${assetCode}`);

  await k.goto(BASE + '/dcs/points');
  await k.getByTestId('point-row-P101.VIB').click();
  await k.getByTestId('trend-line').waitFor();
  await pickOption(k, 'point-asset', assetCode);
  await k.getByTestId('btn-point-save').click();
  await toast(k, '测点已保存');
  check(
    (await keeper.api('GET', `/api/dcs/points?assetId=${asset.id}`)).data.some((p) => p.code === 'P101.VIB'),
    '测点映射到设备'
  );

  await k.goto(BASE + '/asset/center');
  await k.getByTestId(`asset-row-${asset.id}`).click();
  await k.getByTestId('asset-point-P101.VIB').waitFor();
  check((await k.getByTestId('asset-points').textContent()).includes('mm/s'), '设备详情显示映射的测点及最近快照值');
  await k.keyboard.press('Escape');

  // —— 自动草稿：确认为正式问题 ——
  await k.goto(BASE + '/dcs/alarms');
  await k.getByTestId('alarm-row-P101.VIB').click();
  await k.getByTestId('alarm-detail').waitFor();
  const drafted = (await keeper.api('GET', '/api/dcs/alarms?pointCode=P101.VIB')).data[0];
  check(
    drafted.occurrence_count >= 40 && drafted.issue_id,
    `P101.VIB 24 小时 ${drafted.occurrence_count} 次越限只有一条业务报警，已生成问题草稿 ${drafted.issue_code}`
  );
  check(
    (await k.getByTestId('trend-chart').count()) === 1 && (await k.getByTestId('alarm-events').textContent()).includes('DCS-A:P101.VIB:HI'),
    '报警上下文：前后窗口快照与原始事件引用'
  );
  if (drafted.issue_status === 'DRAFT') {
    await k.getByTestId('btn-alarm-confirm').click();
    await toast(k, '已确认');
  }
  const confirmed = (await keeper.api('GET', `/api/dcs/alarms/${drafted.id}`)).data;
  check(confirmed.issue_status === 'OPEN', `人工确认后 ${confirmed.issue_code} 成为正式问题`);
  await k.keyboard.press('Escape');

  // —— 先人工后报警 ——
  const orgs = (await keeper.api('GET', '/api/asset/orgs')).data;
  const workshop = orgs.find((o) => o.name === '一车间');
  const saved = await keeper.api('PUT', '/api/dcs/points', {
    code: pointCode,
    name: 'E2E 泵振动',
    unit: 'mm/s',
    sourceSystem: 'DCS-A',
    assetId: asset.id,
    orgUnitId: workshop.id,
    alarmPolicy: 'AUTO_DRAFT',
    enabled: true
  });
  check(saved.code === 200, `测点 ${pointCode} 映射到 ${assetCode}，策略自动草稿`);
  await k.goto(BASE + '/asset/center');
  await k.getByTestId(`asset-row-${asset.id}`).click();
  await k.getByTestId('btn-asset-defect').click();
  await k.getByTestId('defect-title').fill('泵体振动偏大');
  await k.getByTestId('btn-defect-report').click();
  await toast(k, '缺陷');
  const defect = await one(keeper, DEFECT, [{ field: 'asset', op: 'eq', value: asset.id }]);
  check(defect?.issue, `巡检先登记泵振动缺陷（Issue ${defect.issue}）`);
  await k.keyboard.press('Escape');

  fieldAlarm(pointCode, `${pointCode}:HI:1`);
  const sug = await waitFor(async () => (await keeper.api('GET', `/api/dcs/alarms?pointCode=${pointCode}`)).data[0], '报警接入');
  check(
    sug.status === 'SUGGESTED' && Number(sug.suggested_issue_id) === Number(defect.issue) && !sug.issue_id,
    '报警到达后建议关联已有缺陷，未新建问题'
  );
  await k.goto(BASE + '/dcs/alarms');
  await k.getByTestId(`alarm-row-${pointCode}`).click();
  await k.getByTestId('alarm-suggestion').waitFor();
  check((await k.getByTestId('alarm-suggestion').textContent()).includes('泵体振动偏大'), '页面提示关联到「泵体振动偏大」');
  await k.getByTestId('btn-alarm-link').click();
  await toast(k, '已关联');
  const linked = (await keeper.api('GET', `/api/dcs/alarms/${sug.id}`)).data;
  check(linked.status === 'LINKED' && Number(linked.issue_id) === Number(defect.issue), '确认后报警挂在该缺陷的 Issue 下');
  fieldAlarm(pointCode, `${pointCode}:HI:2`);
  fieldAlarm(pointCode, `${pointCode}:HI:2`);
  await waitFor(async () => (await keeper.api('GET', `/api/dcs/alarms/${sug.id}`)).data.occurrence_count === 2, '再次发生累计');
  const dcsIssues = (await keeper.api('GET', `/api/dcs/alarms?pointCode=${pointCode}`)).data;
  check(dcsIssues.length === 1 && dcsIssues[0].issue_id === linked.issue_id, '再次发生（含重复送达）只累计次数为 2，不新建问题');

  // —— 只读边界与权限 ——
  const catalog = (await admin.api('GET', '/api/op/catalog')).data;
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'dcs-scan-')), 'catalog.json');
  fs.writeFileSync(file, JSON.stringify(catalog));
  const out = execFileSync('python3', [path.join(root, 'scripts/ci/scan-dcs-write.py'), '--catalog', file], { encoding: 'utf8' });
  check(out.includes('不存在任何 DCS 写入类能力'), `已发布 ${catalog.length} 个 Operation 经黑名单扫描无 DCS 写入能力`);
  const denied = await operator.api('GET', '/api/dcs/alarms');
  check(denied.code !== 200, '无 DCS 权限的操作工查看报警被拒');

  check(keeper.errors.length === 0, '页面无脚本错误');
  console.log('DCS 端到端通过');
} finally {
  await keeper.browser.close();
}
