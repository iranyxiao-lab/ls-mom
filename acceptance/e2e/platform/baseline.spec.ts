/**
 * 平台基线走查（platform/project-baseline）：在 acceptance-reset.sh 重置后的全新环境中运行。
 */
import { execFileSync } from 'node:child_process';
import { snapshot } from '../support/consistency.ts';
import { expect, test } from '../support/walk.ts';

const COURSE = 'ls.training.Course';

test('[AC-PLAT-PB-005] 全新环境启动：服务健康，种子管理员登录后看到演示组织与标准 Pack', async ({ walk }) => {
  const admin = await walk.as('acc_admin');
  await walk.step('服务健康', async () => {
    const r = await admin.api('GET', '/api/asset/orgs');
    expect(r.status).toBe(200);
  }, admin);
  await walk.step('演示组织齐全', async () => {
    const orgs = (await admin.api('GET', '/api/asset/orgs')).body.data as any[];
    for (const n of ['一车间', '二车间', '甲班']) expect(orgs.some((o) => o.name.includes(n)), n).toBe(true);
  }, admin);
  await walk.step('标准 Pack 已安装并启用', async () => {
    const packs = (await admin.api('GET', '/api/packs')).body.data as any[];
    const enabled = packs.filter((p) => p.status === 'ENABLED').map((p) => p.code);
    for (const p of ['ls.asset', 'ls.production', 'ls.quality', 'ls.training', 'ls.consultation']) expect(enabled, p).toContain(p);
    await admin.page.goto('/');
    await expect(admin.page.locator('body')).not.toContainText('系统错误');
  }, admin);
});

test('[AC-PLAT-PB-009] 跨组件追踪：提交单据触发流程与 Outbox 事件，日志、审计、流程绑定与 Outbox 可查到同一 trace_id', async ({ walk }) => {
  const impl = await walk.as('acc_implementer');
  const admin = await walk.as('acc_admin');
  const ts = Date.now().toString(36).toUpperCase();
  const { id, traceId } = await walk.step('实施顾问新建并提交课程', async () => {
    const orgs = (await impl.api('GET', '/api/asset/orgs')).body.data as any[];
    const ws = orgs.find((o) => o.unit_type === 'WORKSHOP' && o.name.includes('一车间'));
    const c = (await impl.api('POST', `/api/crud/${COURSE}`, { courseCode: `ACC-T-${ts}`, title: `追踪课程 ${ts}`, positions: '操作工', passScore: 60, validMonths: 12, origin: 'MANUAL', orgId: ws.id })).body.data;
    const r = await impl.api('POST', `/api/op/${COURSE}.submit`, { targetId: String(c.id), version: c.version, input: {} });
    expect(r.body.code, r.body.msg).toBe(200);
    expect(r.traceId).toMatch(/^[0-9a-f]{16,64}$/);
    return { id: c.id, traceId: r.traceId! };
  }, impl);
  await walk.step('审计、流程绑定、领域事件与 Outbox 中是同一 trace_id', async () => {
    let snap = await snapshot(admin, COURSE, id);
    for (let i = 0; i < 20 && !snap.instances.length; i++) {
      await admin.page.waitForTimeout(300);
      snap = await snapshot(admin, COURSE, id);
    }
    const audit = snap.audit.find((a) => a.opCode === `${COURSE}.submit`);
    expect(audit?.traceId, '审计').toBe(traceId);
    expect(snap.instances[0]?.traceId, `流程绑定：${JSON.stringify(snap.instances[0])}`).toBe(traceId);
    const ev = snap.events.filter((e) => e.traceId === traceId);
    expect(ev.length, '领域事件').toBeGreaterThan(0);
    expect(ev.some((e) => e.outbox.length > 0), 'Outbox').toBe(true);
    const traced = (await admin.api('POST', '/api/q/ls.audit.trace', { traceId })).body.data.rows as any[];
    expect(traced.map((x) => x.kind)).toEqual(expect.arrayContaining(['OPERATION', 'EVENT']));
  }, admin);
  await walk.step('应用请求日志带同一 trace_id', async () => {
    const project = process.env.LSMOM_ACC_PROJECT ?? 'lsmom-acc';
    const logs = execFileSync('docker', ['compose', '-p', project, 'logs', '--since', '5m', 'app'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
    expect(logs.includes(`[${traceId}]`), '日志中有该 trace_id').toBe(true);
  });
});
