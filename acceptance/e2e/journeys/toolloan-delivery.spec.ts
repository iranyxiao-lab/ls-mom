/**
 * 旅程 J-B-LOAN：配置交付"工器具借还"台账并完成审批与版本升级（切片 B，同时覆盖"带在途流程的版本升级"）。
 * 角色：实施顾问（陈顾问）从模板生成配置草稿（没有角色管理权限，授权清单随草稿提交）；管理员授权、评审、批准并发布（发布前必须通过升级演练门禁）；
 * 班组长（吴班长）登记借还单；车间主任（周主任）部门审核；设备员（王工）确认归档。
 * 升级：有一张借还单停在部门审核时，实施顾问以同一编码再次生成（标题改为 v2）并发布升级，在途单据按旧版本继续办理到归档。
 * 负向：实施顾问自行授权、批准自己的变更、班组长发布变更、操作工登记借还单、二车间主任办理一车间待办、重复办理同一待办。
 */
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { consistency } from '../support/consistency.ts';
import { denied, get, ok, until } from '../support/ops.ts';
import { expect, test, type Session } from '../support/walk.ts';

const ROOT = path.resolve(import.meta.dirname, '../../..');
const PROJECT = process.env.LSMOM_ACC_PROJECT ?? 'lsmom-acc';
const BASE = process.env.ACC_BASE ?? 'http://127.0.0.1:18101';

/** 升级演练门禁：备份目标环境 → 沙箱恢复并安装制品 → 回归、越权、升级检查 → 结果回写 ChangeSet */
function drill(id: number) {
  const r = spawnSync(path.join(ROOT, 'deploy/scripts/upgrade-drill.sh'), ['--changeset', String(id), '--backup-from', PROJECT], {
    env: { ...process.env, LSMOM_E2E_BASE: BASE },
    encoding: 'utf8',
    timeout: 25 * 60 * 1000
  });
  const out = (r.stdout ?? '') + (r.stderr ?? '');
  expect(r.status === 0 && out.includes('门禁通过'), `升级演练（ChangeSet #${id}）：${out.slice(-3000)}`).toBe(true);
}

/** 协同应用中心：从"借还登记"模板生成配置草稿 */
async function fromTemplate(s: Session, title: string, ns: string) {
  const p = s.page;
  await p.setViewportSize({ width: 1440, height: 900 });
  await p.goto('/lsmom/apps');
  await p.locator('#tab-changes').click({ timeout: 20_000 });
  await p.getByTestId('btn-from-template').click();
  const wz = p.getByTestId('tpl-wizard');
  await wz.getByTestId('tpl-loan').click();
  await wz.getByTestId('tpl-title').fill(title);
  await wz.getByTestId('tpl-ns').fill(ns);
  await wz.getByTestId('tpl-node-confirm').click();
  await p.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: '设备员' }).first().click();
  await wz.getByTestId('tpl-next').click();
  await wz.getByTestId('tpl-preview').waitFor();
  await wz.getByTestId('tpl-create').click();
  await p.locator('.el-message--success', { hasText: /已生成/ }).first().waitFor({ timeout: 60_000 });
}

test('[AC-J-B-LOAN-001] 旅程：配置交付工器具借还台账、审批闭环与带在途流程的版本升级', async ({ walk }) => {
  test.setTimeout(60 * 60 * 1000);
  const impl = await walk.as('acc_implementer');
  const admin = await walk.as('acc_admin');
  let team = await walk.as('acc_team');
  let leader = await walk.as('acc_leader');
  let keeper = await walk.as('acc_asset');
  let operator = await walk.as('acc_operator');
  let leaderB = await walk.as('acc_leader_b');
  const run = Date.now().toString(36);
  const ns = `acc.loan${run}`;
  const ORDER = `${ns}.LoanOrder`;
  const changeset = async () => ok<any[]>(await admin.api('GET', `/api/changesets?pack=${encodeURIComponent(ns)}`), '读 ChangeSet')[0];

  async function release(expectStatus: RegExp) {
    const cs = await changeset();
    await walk.step('负向：实施顾问没有角色管理权限，自行授权被拒；管理员在「变更发布」中执行授权清单', async () => {
      await denied(impl.api('POST', `/api/pack-templates/changesets/${cs.id}/grants`), '实施顾问授权');
      const st = ok<any>(await admin.api('GET', `/api/pack-templates/changesets/${cs.id}/grants`), '授权清单');
      expect(st.plan?.length, '草稿带授权清单').toBeGreaterThan(0);
      expect(st.appliedAt, '尚未授权').toBeNull();
      const p = admin.page;
      await p.setViewportSize({ width: 1440, height: 900 });
      await p.goto('/lsmom/apps');
      await p.locator('#tab-changes').click({ timeout: 20_000 });
      await p.getByTestId(`btn-grant-${cs.id}`).click({ timeout: 20_000 });
      await p.locator('.el-message-box__btns .el-button--primary').click();
      await p.locator('.el-message--success', { hasText: '已授权' }).first().waitFor({ timeout: 15_000 });
      expect(ok<any>(await admin.api('GET', `/api/pack-templates/changesets/${cs.id}/grants`), '授权状态').appliedAt).toBeTruthy();
      // 与"修改角色"一致：被授权角色的在线用户被要求重新登录，新权限才生效
      const stale = await team.api('GET', '/api/flow/me');
      expect([stale.status, stale.body?.code], '班组长需重新登录').toContain(401);
      team = await walk.as('acc_team', { fresh: true });
      leader = await walk.as('acc_leader', { fresh: true });
      keeper = await walk.as('acc_asset', { fresh: true });
      operator = await walk.as('acc_operator', { fresh: true });
      leaderB = await walk.as('acc_leader_b', { fresh: true });
    }, admin);
    await walk.step(`升级演练门禁（ChangeSet #${cs.id}）`, async () => drill(cs.id), admin);
    await walk.step('管理员：评审批准并发布', async () => {
      ok(await admin.api('POST', `/api/changesets/${cs.id}/approve`, { approve: true, note: '借还台账配置评审通过（验收）' }), '批准');
      const rel = ok<any>(await admin.api('POST', `/api/changesets/${cs.id}/release`), '发布');
      expect(rel.status).toMatch(expectStatus);
      await admin.page.goto('/lsmom/apps');
    }, admin);
    return cs;
  }

  async function submitOrder(no: string) {
    const me = ok<any>(await team.api('GET', '/api/flow/me'), '班组长身份');
    const c = ok<any>(await team.api('POST', `/api/crud/${ORDER}`, { loanNo: no, borrower: Number(me.partyId), loanDate: new Date().toISOString().slice(0, 10), purpose: 'MAINTENANCE', loanLine: [{ item: '扭力扳手', qty: 2 }] }), '登记借还单');
    ok(await team.api('POST', `/api/op/${ORDER}.submit`, { targetId: String(c.id), version: c.version, input: {} }), '提交审核');
    return c;
  }
  const todoOf = (s: Session, id: number, what: string) =>
    until(async () => ((await s.api('GET', '/api/flow/todo')).body.data as any[])?.find((x) => x.bizType === ORDER && String(x.bizId) === String(id) && x.status === 'TODO'), what);

  await walk.step('实施顾问：从模板生成借还台账配置草稿（表单、流程、授权）', async () => {
    await fromTemplate(impl, `借还登记（验收 ${run}）`, ns);
    const cs = await changeset();
    expect(cs?.status).toBe('DRAFT');
    expect(cs.source).toBe('TEMPLATE');
    await expect(impl.page.getByTestId(`cs-grant-pending-${cs.id}`)).toBeVisible({ timeout: 15_000 });
  }, impl);

  await walk.step('负向：实施顾问批准自己的变更被拒；班组长发布变更被拒', async () => {
    const cs = await changeset();
    await denied(impl.api('POST', `/api/changesets/${cs.id}/approve`, { approve: true, note: '自己批准' }), '实施顾问批准变更');
    await denied(team.api('POST', `/api/changesets/${cs.id}/release`), '班组长发布变更');
  }, impl);

  await release(/INSTALLED/);

  const first = await walk.step('班组长登记并提交借还单；负向：操作工登记被拒', async () => {
    await denied(operator.api('POST', `/api/crud/${ORDER}`, { loanNo: `X-${run}`, loanDate: '2026-11-11', purpose: 'MAINTENANCE', loanLine: [{ item: '扳手', qty: 1 }] }), '操作工登记借还单');
    const c = await submitOrder(`ACC-${run}-1`);
    await team.page.goto('/index');
    return c;
  }, team);

  await walk.step('车间主任部门审核；负向：二车间主任办理一车间待办、重复办理被拒', async () => {
    const t = await todoOf(leader, first.id, '车间主任待办');
    expect(((await leaderB.api('GET', '/api/flow/todo')).body.data as any[]).some((x) => x.bizType === ORDER && String(x.bizId) === String(first.id)), '二车间主任没有该待办').toBe(false);
    await denied(leaderB.api('POST', `/api/flow/tasks/${t.taskId}/complete`, { action: 'APPROVE', comment: '越权' }), '二车间主任办理');
    ok(await leader.api('POST', `/api/flow/tasks/${t.taskId}/complete`, { action: 'APPROVE', comment: '同意', input: { auditNote: '同意借用（验收）' } }), '部门审核');
    await denied(leader.api('POST', `/api/flow/tasks/${t.taskId}/complete`, { action: 'APPROVE', comment: '再点一次' }), '重复办理');
  }, leader);

  const archived = await walk.step('设备员确认归档，单据通过', async () => {
    const t = await todoOf(keeper, first.id, '设备员待办');
    const r = await keeper.api('POST', `/api/flow/tasks/${t.taskId}/complete`, { action: 'APPROVE', comment: '已归档（验收）' });
    ok(r, '确认归档');
    await until(async () => (await get(team, ORDER, first.id)).status === 'APPROVED', '单据通过');
    return r;
  }, keeper);

  await walk.step('跨模块一致性：单据、流程实例、待办、审计、事件、trace_id', async () => {
    // 由办理过部门审核的车间主任核对（台账读取权限随授权清单授予业务角色）；审计与事件由管理员查看
    const r = await consistency(leader, ORDER, first.id, { status: 'APPROVED', flowStatus: 'APPROVED', traceId: archived.traceId, auditor: admin });
    await test.info().attach('consistency.json', { body: JSON.stringify(r.checks, null, 2), contentType: 'application/json' });
    expect(r.failed, r.failed.map((c) => `${c.name}：${c.detail}`).join('；')).toEqual([]);
  }, leader);

  const inflight = await walk.step('升级前：第二张借还单停在部门审核（在途）', async () => {
    const c = await submitOrder(`ACC-${run}-2`);
    await todoOf(leader, c.id, '在途单据的部门审核待办');
    const flow = ok<any>(await team.api('GET', `/api/flow/biz/${ORDER}/${c.id}`), '流程进度');
    const inst = (flow.instances ?? []).map((x: any) => x.instance ?? x).at(-1);
    return { id: c.id, defVersion: inst.definitionVersion ?? inst.defVersion ?? inst.version };
  }, team);

  await walk.step('实施顾问：以同一编码再次生成（标题改为 v2），作为升级提交', async () => {
    await fromTemplate(impl, `借还登记 v2（验收 ${run}）`, ns);
    const cs = await changeset();
    expect(cs?.status).toBe('DRAFT');
  }, impl);

  await release(/UPGRADED|INSTALLED/);

  await walk.step('升级后：在途单据按旧版本继续办理到归档；新单据走新版本', async () => {
    const t1 = await todoOf(leader, inflight.id, '升级后的在途待办');
    ok(await leader.api('POST', `/api/flow/tasks/${t1.taskId}/complete`, { action: 'APPROVE', comment: '同意', input: { auditNote: '升级后继续办理' } }), '部门审核');
    const t2 = await todoOf(keeper, inflight.id, '设备员待办');
    ok(await keeper.api('POST', `/api/flow/tasks/${t2.taskId}/complete`, { action: 'APPROVE', comment: '已归档' }), '确认归档');
    await until(async () => (await get(team, ORDER, inflight.id)).status === 'APPROVED', '在途单据通过');
    const flow = ok<any>(await team.api('GET', `/api/flow/biz/${ORDER}/${inflight.id}`), '流程进度');
    const inst = (flow.instances ?? []).map((x: any) => x.instance ?? x).at(-1);
    expect(inst.definitionVersion ?? inst.defVersion ?? inst.version, '在途实例仍按升级前的流程版本').toBe(inflight.defVersion);
    const next = await submitOrder(`ACC-${run}-3`);
    await todoOf(leader, next.id, '新单据的部门审核待办');
  }, leader);
});
