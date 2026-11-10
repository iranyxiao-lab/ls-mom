/**
 * 旅程 J-B-ASSET：设备点检 → 缺陷 → 维修 → 验证关闭（切片 B）。
 * 角色：设备员（王工）生成点检并派维修、确认维修工作、验证关闭；操作工（郑师傅，仅本人数据范围）手机点检并登记缺陷；
 * 维修工（何师傅）办理维修工作并提交验证。负向步骤：二车间主任打开一车间缺陷、其他操作工提交别人的点检、
 * 无验证权限的操作工验证、重复提交验证（LOOSE 约束下不要求验证人与维修人分离）；一致性：缺陷状态、时间轴、审计、领域事件、同一 trace_id、设备时间轴。
 */
import { consistency } from '../support/consistency.ts';
import { auditedDenial, denied, get, ok, op, query } from '../support/ops.ts';
import { expect, test } from '../support/walk.ts';

const PLAN = 'ls.asset.InspectionPlan';
const RECORD = 'ls.asset.InspectionRecord';
const DEFECT = 'ls.asset.Defect';
const WORK = 'ls.core.Work';

test('[AC-J-B-ASSET-001] 旅程：设备点检发现异常 → 缺陷 → 维修 → 验证关闭', async ({ walk }) => {
  const keeper = await walk.as('acc_asset');
  const operator = await walk.as('acc_operator', { mobile: true });
  const otherOp = await walk.as('acc_operator_b', { mobile: true });
  const fitter = await walk.as('acc_repair');
  const leaderB = await walk.as('acc_leader_b');
  const admin = await walk.as('acc_admin');
  const me = async (s: typeof keeper) => ok<any>(await s.api('GET', '/api/flow/me'), '身份').partyId as number;

  const rec = await walk.step('设备员：按点检计划生成今日点检（派给操作工）', async () => {
    const plan = (await query(keeper, PLAN, [{ field: 'name', op: 'eq', value: '一号空压机日点检（验收）' }]))[0];
    expect(plan, '验收种子中的点检计划').toBeTruthy();
    await op(keeper, `${PLAN}.generate`, plan.id, {}, plan.version);
    await keeper.page.goto('/asset/inspection');
    const mine = ok<any[]>(await operator.api('GET', '/api/asset/inspections/mine?status=DRAFT'), '我的点检');
    const r = mine.find((m) => m.asset_code === 'ACC-EQ-001');
    expect(r, '操作工的待点检中有一号空压机').toBeTruthy();
    return r;
  }, keeper);

  await walk.step('负向：丙班操作工提交别人的点检被拒并留审计', async () => {
    const cur = await get(operator, RECORD, rec.id);
    await denied(otherOp.api('POST', `/api/op/${RECORD}.submit`, { targetId: String(rec.id), version: cur.version, input: { items: [] } }), '非点检人提交');
    await auditedDenial(admin, RECORD, rec.id, `${RECORD}.submit`);
  }, otherOp);

  const defectId = await walk.step('操作工（手机）：扫码打开点检，排气温度 102 ℃ 超上限，提交并一键生成缺陷', async () => {
    await operator.page.goto('/m/inspect?code=ACC-EQ-001');
    await operator.page.getByTestId('m-inspect-defect').waitFor({ timeout: 20_000 });
    const r = await op(operator, `${RECORD}.submit`, rec.id, { items: [{ itemCode: 'TEMP', valueNum: 102 }, { itemCode: 'NOISE', outcome: 'OK' }] });
    expect(JSON.stringify(r.raw.body), '提交结果提示异常').toMatch(/异常/);
    const done = await get(operator, RECORD, rec.id);
    expect(done.result).toBe('ABNORMAL');
    const raised = await op(operator, `${RECORD}.raiseDefects`, rec.id);
    const id = Number(raised.data?.data?.defects?.[0] ?? raised.data?.defects?.[0]);
    expect(id, '生成的缺陷草稿').toBeGreaterThan(0);
    // 手机端补充后登记：打开缺陷草稿页，设备显示名称而不是编号
    await operator.page.goto(`/m/defect?id=${id}`);
    await expect(operator.page.getByTestId('m-defect')).toBeVisible();
    await op(operator, `${DEFECT}.report`, id);
    return id;
  }, operator);

  await walk.step('负向：二车间主任打开一车间的缺陷被拒', async () => {
    await denied(leaderB.api('GET', `/api/crud/${DEFECT}/${defectId}`), '跨车间读取缺陷', /ACCESS_DENIED|数据范围|权限/);
  }, leaderB);

  const work = await walk.step('设备员：生成维修工作派给何师傅', async () => {
    await op(keeper, `${DEFECT}.createRepair`, defectId, { assignee: await me(fitter), checklist: ['检查冷却风扇', '清洗散热器', '复测排气温度'] });
    const repairs = ok<any[]>(await keeper.api('GET', `/api/asset/defects/${defectId}/repairs`), '维修工作');
    expect(repairs).toHaveLength(1);
    await keeper.page.goto('/asset/defects');
    return repairs[0];
  }, keeper);

  await walk.step('维修工：开始并完成维修；设备员确认', async () => {
    const ver = async () => (ok<any[]>(await keeper.api('GET', `/api/asset/defects/${defectId}/repairs`), '维修工作').find((w) => w.id === work.id)).version;
    await op(fitter, `${WORK}.start`, work.id, {}, await ver());
    await op(fitter, `${WORK}.submitConfirm`, work.id, {}, await ver());
    await op(keeper, `${WORK}.confirm`, work.id, {}, await ver());
    await fitter.page.goto('/asset/defects');
  }, fitter);

  await walk.step('维修工提交验证；负向：没有验证权限的操作工绕过前端验证被拒并留审计', async () => {
    await op(fitter, `${DEFECT}.submitVerify`, defectId);
    const cur = await get(fitter, DEFECT, defectId);
    await denied(operator.api('POST', `/api/op/${DEFECT}.verifyPass`, { targetId: String(defectId), version: cur.version, input: { conclusion: '我看行' } }), '操作工验证缺陷');
    await auditedDenial(admin, DEFECT, defectId, `${DEFECT}.verifyPass`);
  }, fitter);

  const closed = await walk.step('设备员验证通过，缺陷关闭；负向：重复提交被拒', async () => {
    const cur = await get(keeper, DEFECT, defectId);
    const r = await op(keeper, `${DEFECT}.verifyPass`, defectId, { conclusion: '排气温度恢复 78 ℃（验收）' }, cur.version);
    await denied(keeper.api('POST', `/api/op/${DEFECT}.verifyPass`, { targetId: String(defectId), version: cur.version, input: { conclusion: '再点一次' } }), '重复提交验证');
    return r;
  }, keeper);

  await walk.step('跨模块一致性：缺陷状态、时间轴、审计、事件、trace_id、设备时间轴', async () => {
    const r = await consistency(admin, DEFECT, defectId, {
      status: 'CLOSED',
      timeline: /验证|关闭|CLOSED/,
      auditOp: `${DEFECT}.verifyPass`,
      eventType: /verif|CLOSED|closed/i,
      traceId: closed.traceId
    });
    await test.info().attach('consistency.json', { body: JSON.stringify(r.checks, null, 2), contentType: 'application/json' });
    expect(r.failed, r.failed.map((c) => `${c.name}：${c.detail}`).join('；')).toEqual([]);
    const d = await get(keeper, DEFECT, defectId);
    const tl = ok<any[]>(await keeper.api('GET', `/api/asset/assets/${d.asset}/timeline`), '设备时间轴');
    for (const kind of ['INSPECTION', 'DEFECT', 'REPAIR', 'VERIFY']) expect(tl.map((x) => x.kind), `设备时间轴含 ${kind}`).toContain(kind);
  }, admin);
});
