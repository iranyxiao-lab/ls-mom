/**
 * 流程与待办走查（flow/workflow-collaboration）：实施顾问提交课程，一车间车间主任审批。
 */
import { consistency, metric } from '../support/consistency.ts';
import { expect, test, type Session } from '../support/walk.ts';

const COURSE = 'ls.training.Course';

async function submitCourse(impl: Session, tag: string) {
  const ts = Date.now().toString(36).toUpperCase();
  const orgs = (await impl.api('GET', '/api/asset/orgs')).body.data as any[];
  const ws = orgs.find((o) => o.unit_type === 'WORKSHOP' && o.name.includes('一车间'));
  const c = (await impl.api('POST', `/api/crud/${COURSE}`, { courseCode: `ACC-${tag}-${ts}`, title: `验收课程 ${tag} ${ts}`, positions: '操作工', passScore: 60, validMonths: 12, origin: 'MANUAL', orgId: ws.id })).body.data;
  const r = await impl.api('POST', `/api/op/${COURSE}.submit`, { targetId: String(c.id), version: c.version, input: {} });
  expect(r.body.code, r.body.msg).toBe(200);
  return c;
}

async function todoOf(s: Session, id: string | number) {
  for (let i = 0; i < 30; i++) {
    const t = ((await s.api('GET', '/api/flow/todo')).body.data as any[]).find((x) => x.bizType === COURSE && String(x.bizId) === String(id) && x.status === 'TODO');
    if (t) return t;
    await s.page.waitForTimeout(300);
  }
  throw new Error('没有收到待办');
}

test('[AC-FLOW-WC-001][AC-FLOW-WC-013] 审批通过后业务状态由回调 Operation 推进，待办读模型随之完成，各模块一致', async ({ walk }) => {
  const impl = await walk.as('acc_implementer');
  const leader = await walk.as('acc_leader');
  const admin = await walk.as('acc_admin');
  const reviews = { query: 'ls.ui.homeMyReviews', field: 'total', as: leader } as const;
  const course = await walk.step('实施顾问新建并提交课程', () => submitCourse(impl, 'WC'), impl);
  const todo = await walk.step('车间主任收到审批待办', () => todoOf(leader, course.id), leader);
  const before = await metric(admin, reviews);
  const done = await walk.step('车间主任在待办中审批通过', async () => {
    await leader.page.goto('/');
    const r = await leader.api('POST', `/api/flow/tasks/${todo.taskId}/complete`, { action: 'APPROVE', comment: '同意发布（验收）' });
    expect(r.body.code, r.body.msg).toBe(200);
    return r;
  }, leader);
  await walk.step('待办读模型数秒内变为已完成，且不在我的待办中', async () => {
    const t0 = Date.now();
    let status = '';
    while (Date.now() - t0 < 5000) {
      const flow = (await leader.api('GET', `/api/flow/biz/${COURSE}/${course.id}`)).body.data;
      status = flow.tasks.find((x: any) => x.taskId === todo.taskId)?.status;
      if (status === 'DONE') break;
      await leader.page.waitForTimeout(200);
    }
    expect(status).toBe('DONE');
    const mine = (await leader.api('GET', '/api/flow/todo')).body.data as any[];
    expect(mine.some((x) => x.taskId === todo.taskId && x.status === 'TODO')).toBe(false);
  }, leader);
  await walk.step('业务状态、流程、时间轴、审计、事件、Outbox、看板一致', async () => {
    const r = await consistency(admin, COURSE, course.id, {
      status: 'PUBLISHED',
      flowStatus: 'APPROVED',
      doneNode: 'approve',
      timeline: /approved|通过|PUBLISHED/,
      auditOp: 'ls.training.Course.publish',
      eventType: /publish/i,
      traceId: done.traceId,
      metric: { ...reviews, before, delta: -1 }
    });
    await test.info().attach('consistency.json', { body: JSON.stringify(r.checks, null, 2), contentType: 'application/json' });
    expect(r.failed, r.failed.map((c) => `${c.name}：${c.detail}`).join('；')).toEqual([]);
  }, admin);
});

test('[AC-FLOW-WC-011] 非办理人办理被拒：操作工与二车间车间主任都不能办理一车间的审批待办，失败有审计', async ({ walk }) => {
  const impl = await walk.as('acc_implementer');
  const leader = await walk.as('acc_leader');
  const operator = await walk.as('acc_operator');
  const other = await walk.as('acc_leader_b');
  const admin = await walk.as('acc_admin');
  const course = await walk.step('实施顾问提交课程', () => submitCourse(impl, 'NA'), impl);
  const todo = await walk.step('一车间车间主任收到待办', () => todoOf(leader, course.id), leader);
  for (const [who, s] of [['操作工', operator], ['二车间车间主任', other]] as const) {
    await walk.step(`${who}直接调用办理接口被拒`, async () => {
      const r = await s.api('POST', `/api/flow/tasks/${todo.taskId}/complete`, { action: 'APPROVE', comment: '越权办理' });
      expect(r.body.code).not.toBe(200);
      expect(['TASK_NOT_ASSIGNED', 'ACCESS_DENIED']).toContain(r.body.data?.errorCode ?? r.body.errorCode ?? (/不是该待办的办理人/.test(r.body.msg) ? 'TASK_NOT_ASSIGNED' : r.body.msg));
    }, s);
  }
  await walk.step('待办仍在办理人名下，越权尝试有失败审计', async () => {
    const flow = (await leader.api('GET', `/api/flow/biz/${COURSE}/${course.id}`)).body.data;
    expect(flow.tasks.find((x: any) => x.taskId === todo.taskId)?.status).toBe('TODO');
    const audit = (await admin.api('POST', '/api/q/ls.audit.objectOperations', { concept: COURSE, id: String(course.id) })).body.data.rows as any[];
    expect(audit.filter((a) => a.opCode === 'ls.flow.task.approve' && a.result === 'FAILED' && a.errorCode === 'TASK_NOT_ASSIGNED').length).toBeGreaterThanOrEqual(2);
  }, admin);
  await walk.step('办理人本人可以办理', async () => {
    const r = await leader.api('POST', `/api/flow/tasks/${todo.taskId}/complete`, { action: 'APPROVE', comment: '同意' });
    expect(r.body.code, r.body.msg).toBe(200);
  }, leader);
});
