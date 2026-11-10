/**
 * 旅程 J-E-DCS：DCS 报警 → 去重 → 问题草稿 → 人工确认（切片 E）。
 * 现场系统（模拟器）向 Historian 写报警事件；业务系统只读 Historian。设备员（王工）查看报警上下文并确认草稿为正式问题。
 * 负向：操作工查看报警被拒、重复送达与再次发生不新建问题、已发布 Operation 中不存在 DCS 写入能力、二车间主任看不到一车间报警问题。
 * 一致性：报警与 Issue 关联、Issue 状态、审计、事件、trace_id。
 */
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { consistency } from '../support/consistency.ts';
import { denied, ok, op, until } from '../support/ops.ts';
import { expect, test } from '../support/walk.ts';

const ROOT = path.resolve(import.meta.dirname, '../../..');
const PROJECT = process.env.LSMOM_ACC_PROJECT ?? 'lsmom-acc';
const POINT = 'ACC-PT-001';
const ISSUE = 'ls.core.Issue';

/** 模拟现场 DCS 向 Historian 写一条越限报警（同一 dedup_key 视为同一业务报警） */
function fieldAlarm(eventId: string, value: number) {
  const sql = `insert into historian_alarm_event (source_system, source_event_id, point_code, alarm_type, dedup_key, occurred_at, received_at,
    quality, severity, value, message, trace_id) values ('DCS-ACC', '${eventId}', '${POINT}', 'HI', 'DCS-ACC:${POINT}:HI', now(), now(), 'GOOD',
    'HIGH', ${value}, '空压机排气温度越上限（验收）', 'acc-${eventId}') on conflict do nothing`;
  execFileSync('docker', ['compose', '-p', PROJECT, 'exec', '-T', 'tsdb', 'sh', '-c', 'psql -q -U "$POSTGRES_USER" -d lsmom_ts'], {
    cwd: path.join(ROOT, 'deploy'),
    input: sql + ';\n'
  });
}

test('[AC-J-E-DCS-001] 旅程：DCS 报警去重生成问题草稿并人工确认', async ({ walk }) => {
  const keeper = await walk.as('acc_asset');
  const operator = await walk.as('acc_operator');
  const leaderB = await walk.as('acc_leader_b');
  const admin = await walk.as('acc_admin');
  const run = Date.now().toString(36);
  const alarms = async () => ok<any[]>(await keeper.api('GET', `/api/dcs/alarms?pointCode=${POINT}`), '报警列表');

  const before = (await alarms())[0];
  const alarm = await walk.step('现场 DCS 越限两次（含一次重复送达）：只形成一条业务报警，自动生成问题草稿', async () => {
    fieldAlarm(`${run}-1`, 102);
    fieldAlarm(`${run}-1`, 102); // 重复送达
    fieldAlarm(`${run}-2`, 104);
    const a = await until(async () => {
      const x = (await alarms())[0];
      const base = before && before.id === x?.id ? Number(before.occurrence_count) : 0;
      return x && x.issue_id && Number(x.occurrence_count) >= base + 2 ? x : null;
    }, '报警接入并去重', 60_000);
    expect((await alarms()).filter((x) => x.status !== 'CLOSED' && x.status !== 'IGNORED').length, '同一测点只有一条进行中的业务报警').toBe(1);
    return a;
  }, keeper);

  await walk.step('设备员：在报警板查看上下文（趋势与原始事件引用）', async () => {
    const ctx = ok<any>(await keeper.api('GET', `/api/dcs/alarms/${alarm.id}/context?beforeMinutes=60&afterMinutes=10`), '报警上下文');
    expect(JSON.stringify(ctx)).toContain(`DCS-ACC:${POINT}:HI`);
    await keeper.page.goto('/dcs/alarms');
    await keeper.page.getByTestId(`alarm-row-${POINT}`).click();
    await keeper.page.getByTestId('alarm-detail').waitFor({ timeout: 15_000 });
  }, keeper);

  await walk.step('负向：操作工查看报警被拒；二车间主任读取该问题被拒；已发布 Operation 中没有 DCS 写入能力', async () => {
    await denied(operator.api('GET', '/api/dcs/alarms'), '无 DCS 权限查看报警');
    await denied(leaderB.api('GET', `/api/crud/${ISSUE}/${alarm.issue_id}`), '跨车间读取报警问题');
    const catalog = ok<any[]>(await admin.api('GET', '/api/op/catalog'), 'Operation 目录');
    const writes = catalog.filter((o) => /dcs|historian/i.test(String(o.code)) && /write|set|command|control/i.test(String(o.code)));
    expect(writes, '不存在 DCS 写入类 Operation').toEqual([]);
  }, operator);

  const confirmed = await walk.step('设备员：确认问题草稿为正式问题', async () => {
    const a = ok<any>(await keeper.api('GET', `/api/dcs/alarms/${alarm.id}`), '报警');
    let r: Awaited<ReturnType<typeof op>> | null = null;
    if (a.issue_status === 'DRAFT') r = await op(keeper, `${ISSUE}.submit`, a.issue_id, {}, a.issue_version ?? 0);
    const after = ok<any>(await keeper.api('GET', `/api/dcs/alarms/${alarm.id}`), '报警');
    expect(after.issue_status).toBe('OPEN');
    return { r, issueId: after.issue_id };
  }, keeper);

  await walk.step('再次越限：累计到同一问题，不新建', async () => {
    const n = Number(ok<any>(await keeper.api('GET', `/api/dcs/alarms/${alarm.id}`), '报警').occurrence_count);
    fieldAlarm(`${run}-3`, 103);
    await until(async () => Number(ok<any>(await keeper.api('GET', `/api/dcs/alarms/${alarm.id}`), '报警').occurrence_count) > n, '再次发生累计', 60_000);
    expect(ok<any>(await keeper.api('GET', `/api/dcs/alarms/${alarm.id}`), '报警').issue_id).toBe(confirmed.issueId);
  }, keeper);

  await walk.step('跨模块一致性：问题状态、审计、事件与 trace_id', async () => {
    const r = await consistency(admin, ISSUE, confirmed.issueId, {
      status: 'OPEN',
      record: async () => ({ status: ok<any>(await keeper.api('GET', `/api/dcs/alarms/${alarm.id}`), '报警').issue_status }),
      ...(confirmed.r ? { auditOp: `${ISSUE}.submit`, traceId: confirmed.r.traceId } : {})
    });
    await test.info().attach('consistency.json', { body: JSON.stringify(r.checks, null, 2), contentType: 'application/json' });
    expect(r.failed, r.failed.map((c) => `${c.name}：${c.detail}`).join('；')).toEqual([]);
  }, admin);
});
