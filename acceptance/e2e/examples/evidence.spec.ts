/**
 * 走查证据示例（不对应 spec 场景）：ACC_DEMO=1 时运行；ACC_DEMO_FAIL=1 时在第 3 步故意失败，
 * 用来确认失败用例的证据目录完整（第 1～3 步截图、失败录像、trace、traces.json 中的 trace_id）。
 */
import { expect, test } from '../support/walk.ts';

test.skip(!process.env.ACC_DEMO, '示例用例：ACC_DEMO=1 时运行');

test('[AC-DEMO-901] 走查证据示例：班组长打开工作中心后核对待办', async ({ walk }) => {
  const team = await walk.as('acc_team');
  await walk.step('打开首页', async () => {
    await team.page.goto('/index');
    await team.page.waitForLoadState('networkidle');
  });
  await walk.step('打开协同工作中心', async () => {
    await team.page.goto('/lsmom/workbench');
    await team.page.waitForLoadState('networkidle');
  });
  await walk.step('核对待办数量', async () => {
    const r = await team.api('GET', '/api/flow/todo');
    expect(r.traceId, '接口响应带 X-Trace-Id').toBeTruthy();
    if (process.env.ACC_DEMO_FAIL) expect(r.body.data.length, '示例故意失败').toBe(-1);
  });
  await walk.step('打开设备中心', async () => {
    await team.page.goto('/asset/center');
  });
});
