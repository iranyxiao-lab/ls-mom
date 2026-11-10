/**
 * 跨模块一致性断言示例（acceptance-walkthrough 2.4）：实施顾问提交一门课程，一车间车间主任审批通过后，
 * 核对业务状态、流程实例、待办读模型、时间轴、审计、领域事件与 Outbox、看板指标（车间主任"待我审核"数），以及同一 trace_id。
 * ACC_DEMO=1 时运行。
 */
import { consistency, metric } from '../support/consistency.ts';
import { expect, test } from '../support/walk.ts';

test.skip(!process.env.ACC_DEMO, '示例用例：ACC_DEMO=1 时运行');

const COURSE = 'ls.training.Course';

test('[AC-DEMO-902] 一致性断言示例：课程发布审批通过后各模块一致', async ({ walk }) => {
  const ts = Date.now().toString(36).toUpperCase();
  const impl = await walk.as('acc_implementer');
  const leader = await walk.as('acc_leader');
  const admin = await walk.as('acc_admin');
  const reviews = { query: 'ls.ui.homeMyReviews', field: 'total', as: leader } as const;

  const course = await walk.step('实施顾问新建并提交课程', async () => {
    const orgs = (await impl.api('GET', '/api/asset/orgs')).body.data as any[];
    const workshop = orgs.find((o) => o.unit_type === 'WORKSHOP' && o.name.includes('一车间'));
    const c = (
      await impl.api('POST', `/api/crud/${COURSE}`, {
        courseCode: `ACC-${ts}`,
        title: `验收课程 ${ts}`,
        positions: '操作工',
        passScore: 60,
        validMonths: 12,
        origin: 'MANUAL',
        orgId: workshop.id
      })
    ).body.data;
    const r = await impl.api('POST', `/api/op/${COURSE}.submit`, { targetId: String(c.id), version: c.version, input: {} });
    expect(r.body.code, r.body.msg).toBe(200);
    return c;
  }, impl);

  const todo = await walk.step('车间主任收到审批待办', async () => {
    for (let i = 0; i < 30; i++) {
      const t = ((await leader.api('GET', '/api/flow/todo')).body.data as any[]).find(
        (x) => x.bizType === COURSE && String(x.bizId) === String(course.id) && x.status === 'TODO'
      );
      if (t) return t;
      await leader.page.waitForTimeout(300);
    }
    throw new Error('车间主任没有收到待办');
  }, leader);

  const before = await metric(admin, reviews);
  const done = await walk.step('车间主任审批通过', async () => {
    const r = await leader.api('POST', `/api/flow/tasks/${todo.taskId}/complete`, { action: 'APPROVE', comment: '同意发布（验收）' });
    expect(r.body.code, r.body.msg).toBe(200);
    expect(r.traceId).toBeTruthy();
    return r;
  }, leader);

  await walk.step('跨模块一致性核对', async () => {
    const r = await consistency(admin, COURSE, course.id, {
      status: 'PUBLISHED',
      flowStatus: 'APPROVED',
      doneNode: 'approve',
      timeline: /approved|通过|PUBLISHED/,
      auditOp: 'ls.training.Course.publish',
      eventType: /publish|PUBLISHED/i,
      traceId: done.traceId,
      metric: { ...reviews, before, delta: -1 }
    });
    await test.info().attach('consistency.json', { body: JSON.stringify(r.checks, null, 2), contentType: 'application/json' });
    for (const c of r.checks) console.log(`${c.ok ? '✓' : '✗'} ${c.name}：${c.detail}`);
    expect(r.failed, r.failed.map((c) => `${c.name}：${c.detail}`).join('；')).toEqual([]);
  }, admin);
});
