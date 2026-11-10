/**
 * 旅程 J-C-QUALITY：普通台账流程（质量记录登记）与受控质量流程（不符合项：登记 → 车间审核 → 整改 → 证据 → 质检终判）（切片 C）。
 * 角色：质检员（冯工）登记记录与不符合项、终判关闭；车间主任（周主任）审核并派整改；甲班班长（吴班长）整改并上传证据。
 * 负向：二车间主任读取一车间不符合项、审核时改问题描述、无证据提交验证、班长终判关闭（非质检员）、重复关闭。
 * 一致性：不符合项状态、Issue 外壳、时间轴、审计、事件、trace_id、整改负责人可见。
 */
import { consistency } from '../support/consistency.ts';
import { auditedDenial, create, denied, get, ok, op } from '../support/ops.ts';
import { expect, test } from '../support/walk.ts';

const REC = 'ls.quality.QualityRecord';
const NC = 'ls.quality.NonConformance';
const WORK = 'ls.core.Work';
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

test('[AC-J-C-QUALITY-001] 旅程：质量记录登记与受控不符合项闭环', async ({ walk }) => {
  const qa = await walk.as('acc_quality');
  const leader = await walk.as('acc_leader');
  const team = await walk.as('acc_team');
  const leaderB = await walk.as('acc_leader_b');
  const admin = await walk.as('acc_admin');
  const ts = Date.now().toString(36);
  const orgs = ok<any[]>(await qa.api('GET', '/api/asset/orgs'), '组织');
  const workshop = orgs.find((o) => o.unit_type === 'WORKSHOP' && o.name.includes('一车间'));
  expect(workshop, '一车间').toBeTruthy();

  await walk.step('普通台账流程：质检员登记一条来料抽检记录', async () => {
    const r = await create(qa, REC, { title: `来料抽检（验收 ${ts}）`, recordType: 'SAMPLING', result: 'PASS', orgId: workshop.id });
    await op(qa, `${REC}.submit`, r.id);
    expect((await get(qa, REC, r.id)).status).toBe('SUBMITTED');
    expect((await get(leader, REC, r.id)).title, '车间主任可见本车间记录').toContain(ts);
    await qa.page.goto('/quality/records');
  }, qa);

  const nc = await walk.step('质检员：登记不符合项并提交，生成 Issue，等待车间审核', async () => {
    const n = await create(qa, NC, { title: `尺寸超差（验收 ${ts}）`, description: '轴径 50.08，公差 ±0.05', severity: 'MEDIUM', orgId: workshop.id });
    await op(qa, `${NC}.submit`, n.id);
    const cur = await get(qa, NC, n.id);
    expect(cur.status).toBe('PENDING_AUDIT');
    expect(cur.issue, '产生 Issue 外壳').toBeTruthy();
    return cur;
  }, qa);

  await walk.step('负向：二车间主任读取被拒；审核时改问题描述被拒并留审计', async () => {
    await denied(leaderB.api('GET', `/api/crud/${NC}/${nc.id}`), '跨车间读取不符合项');
    await denied(leader.api('PUT', `/api/crud/${NC}/${nc.id}`, { version: nc.version, description: '改一下' }), '审核节点直接改描述');
    await denied(leader.api('POST', `/api/op/${NC}.audit`, { targetId: String(nc.id), version: nc.version, input: { auditOpinion: '同意', owner: 1, description: '改一下' } }), '审核 Operation 夹带改描述');
    await auditedDenial(admin, NC, nc.id, `${NC}.audit`);
  }, leader);

  const work = await walk.step('车间主任：审核通过，派吴班长整改', async () => {
    const owner = ok<any>(await team.api('GET', '/api/flow/me'), '班长身份').partyId;
    await op(leader, `${NC}.audit`, nc.id, { auditOpinion: '同意整改，先隔离（验收）', immediateAction: '隔离 3 件', owner });
    expect((await get(leader, NC, nc.id)).status).toBe('IN_RECTIFICATION');
    const rect = ok<any[]>(await leader.api('GET', `/api/quality/nc/${nc.id}/rectifications`), '整改工作');
    expect(rect).toHaveLength(1);
    await leader.page.goto('/quality/nc');
    return rect[0];
  }, leader);

  await walk.step('班长：整改负责人可见并办理整改工作；车间主任确认；负向：无证据提交验证被拒', async () => {
    expect((await get(team, NC, nc.id)).status, '整改负责人可见').toBe('IN_RECTIFICATION');
    const ver = async () => ok<any[]>(await leader.api('GET', `/api/quality/nc/${nc.id}/rectifications`), '整改工作').find((w) => w.id === work.id).version;
    await op(team, `${WORK}.start`, work.id, {}, await ver());
    await op(team, `${WORK}.submitConfirm`, work.id, {}, await ver());
    await op(leader, `${WORK}.confirm`, work.id, {}, await ver());
    await denied(team.api('POST', `/api/op/${NC}.submitVerify`, { targetId: String(nc.id), version: (await get(team, NC, nc.id)).version, input: {} }), '无整改证据提交验证');
  }, team);

  await walk.step('班长：在页面上传整改证据后提交验证', async () => {
    const t = team.page;
    await t.goto('/quality/nc');
    await t.getByTestId(`nc-row-${nc.id}`).click();
    await t.getByTestId(`nc-evidence-${work.id}`).setInputFiles({ name: `after-${ts}.png`, mimeType: 'image/png', buffer: PNG });
    await t.getByTestId(`nc-work-evidence-${work.id}`).filter({ hasText: '1 个' }).waitFor({ timeout: 15_000 });
    await op(team, `${NC}.submitVerify`, nc.id);
    expect((await get(team, NC, nc.id)).status).toBe('PENDING_VERIFY');
  }, team);

  const closed = await walk.step('负向：班长终判被拒；质检员终判关闭；重复关闭被拒', async () => {
    const cur = await get(qa, NC, nc.id);
    await denied(team.api('POST', `/api/op/${NC}.close`, { targetId: String(nc.id), version: cur.version, input: { conclusion: '自己说合格' } }), '非质检员终判');
    const r = await op(qa, `${NC}.close`, nc.id, { conclusion: '复检 5 件合格（验收）' }, cur.version);
    await denied(qa.api('POST', `/api/op/${NC}.close`, { targetId: String(nc.id), version: cur.version, input: { conclusion: '再关一次' } }), '重复关闭');
    await qa.page.goto('/quality/nc');
    return r;
  }, qa);

  await walk.step('跨模块一致性：不符合项、Issue、时间轴、审计、事件、trace_id', async () => {
    const r = await consistency(admin, NC, nc.id, { status: 'CLOSED', timeline: /关闭|终判|CLOSED/, auditOp: `${NC}.close`, eventType: /close|CLOSED/i, traceId: closed.traceId });
    await test.info().attach('consistency.json', { body: JSON.stringify(r.checks, null, 2), contentType: 'application/json' });
    expect(r.failed, r.failed.map((c) => `${c.name}：${c.detail}`).join('；')).toEqual([]);
    const done = await get(qa, NC, nc.id);
    expect(done.closedBy, '记录终判人').toBeTruthy();
    expect(done.verifyConclusion).toContain('复检 5 件合格');
  }, admin);
});
