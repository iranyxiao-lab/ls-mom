/**
 * modernize-ui-ux 2.5 端到端（无头 Chromium，部署环境，演示账号）：
 *   质检员打开「登记不符合项」，填写问题后在「选择设备」一步取消 → 浏览器上报的埋点只有任务名、步骤名、事件类型和耗时，不含任何填写内容；
 *   放弃点报表（系统监控 → 体验分析）显示该步骤放弃次数，放弃率达到三成的步骤标为高放弃率；非管理员看不到报表；
 *   管理员在报表页关闭埋点 → 质检员再操作，浏览器不再发送埋点、服务端无新增；重新开启后恢复。
 * 运行：node tests/e2e/ui-usage.e2e.mjs（LSMOM_E2E_BASE 指定环境）
 */
import { BASE, check, open } from './lib.mjs';

const SECRET = 'E2E泵站漏油' + Date.now().toString(36);
const admin = await open();
const qa = await open({ user: 'demo_quality', browser: admin.browser });

const report = async () => (await admin.api('GET', '/api/ui/usage/report?days=1')).data.find((t) => t.task === 'nc.register');
const sentBodies = [];
qa.page.on('request', (r) => {
  if (r.method() === 'POST' && r.url().includes('/api/ui/usage/events')) sentBodies.push(r.postData() ?? '');
});

/** leave=true 时取消后立刻离开页面（走卸载时的 keepalive 上报，Playwright 看不到该请求，只能从服务端核对）；否则停留等批量上报。 */
async function abandonAtAsset({ leave = false } = {}) {
  await qa.page.goto(BASE + '/quality/nc');
  await qa.page.getByTestId('btn-new-nc').click();
  await qa.page.getByTestId('nc-title').fill(SECRET);
  await qa.page.getByTestId('nc-description').fill(SECRET + '：法兰渗漏，联系人 13800000000');
  await qa.page.getByTestId('nc-asset').click();
  await qa.page.keyboard.press('Escape');
  await qa.page.locator('.el-dialog__footer button', { hasText: '取消' }).click();
  if (leave) await qa.page.goto(BASE + '/index');
  await qa.page.waitForTimeout(leave ? 1500 : 6500);
}

try {
  check((await admin.api('PUT', '/api/ui/usage/config', { enabled: true })).data.enabled === true, '管理员开启使用埋点');
  const before = (await report()) ?? { started: 0, abandoned: 0, steps: [] };
  const assetBefore = before.steps.find((s) => s.step === 'asset')?.abandoned ?? 0;

  // —— 真实操作：在「选择设备」一步放弃 ——
  await abandonAtAsset();
  check(sentBodies.length > 0, `浏览器发送了埋点（${sentBodies.length} 批）`);
  const events = sentBodies.flatMap((b) => JSON.parse(b));
  check(
    events.every((e) => Object.keys(e).every((k) => ['task', 'step', 'kind', 'durationMs'].includes(k))),
    '每条埋点只有任务名、步骤名、事件类型、耗时四个字段'
  );
  const raw = sentBodies.join('\n');
  check(!raw.includes(SECRET) && !raw.includes('13800000000') && !raw.includes('法兰'), '上报内容中不含填写的问题、描述与电话');
  const last = events.filter((e) => e.task === 'nc.register').at(-1);
  check(last?.kind === 'abandon' && last.step === 'asset', '最后一条是在 asset（选择设备）步骤放弃');
  check(
    events
      .filter((e) => e.kind === 'step')
      .map((e) => e.step)
      .join(',')
      .startsWith('title,description'),
    '步骤顺序：填写问题 → 问题描述 → 选择设备'
  );

  const after = await report();
  check(after.started === before.started + 1, `服务端记录开始次数 +1（${before.started} → ${after.started}）`);
  check((after.steps.find((s) => s.step === 'asset')?.abandoned ?? 0) === assetBefore + 1, '服务端记录「选择设备」放弃 +1');

  // 取消后立刻离开页面：剩余事件在卸载时发出，不丢
  await abandonAtAsset({ leave: true });
  const leaveAfter = await report();
  check(
    leaveAfter.started === after.started + 1 && leaveAfter.steps.find((s) => s.step === 'asset').abandoned === assetBefore + 2,
    '取消后立刻离开页面，埋点在卸载时送达'
  );

  // —— 放弃率达到三成：补足样本后报表标为高放弃率 ——
  const batch = [];
  for (let i = 0; i < 6; i++)
    batch.push({ task: 'nc.register', kind: 'start' }, { task: 'nc.register', step: 'asset', kind: 'abandon', durationMs: 20000 });
  for (let n = 0; n < 4 && !(await report()).steps.find((s) => s.step === 'asset')?.high; n++) await qa.api('POST', '/api/ui/usage/events', batch);
  const flagged = (await report()).steps.find((s) => s.step === 'asset');
  check(flagged.high === true && flagged.rate >= 0.3, `「选择设备」放弃率 ${Math.round(flagged.rate * 100)}% 标为高放弃率`);

  await admin.page.goto(BASE + '/monitor/ui-usage');
  const step = admin.page.getByTestId('usage-step-nc.register-asset');
  await step.waitFor({ timeout: 20000 });
  const stepText = await step.innerText();
  check(stepText.includes('选择设备') && stepText.includes('高放弃率'), '报表页「登记不符合项 → 选择设备」显示高放弃率');
  check(admin.errors.length === 0, '报表页无前端异常');

  const denied = await qa.api('GET', '/api/ui/usage/report?days=1');
  check(denied.code !== 200, `非管理员不能查看报表（${denied.code}）`);
  const deniedToggle = await qa.api('PUT', '/api/ui/usage/config', { enabled: false });
  check(deniedToggle.code !== 200, '非管理员不能关闭埋点');

  // —— 管理员在报表页关闭埋点 → 不再上报 ——
  await admin.page.getByTestId('usage-switch').click();
  await admin.page.locator('.el-message', { hasText: '已关闭使用埋点' }).first().waitFor({ timeout: 10000 });
  check((await admin.api('GET', '/api/ui/usage/config')).data.enabled === false, '报表页开关关闭后，服务端埋点状态为关闭');
  const offBefore = await report();
  sentBodies.length = 0;
  await qa.page.reload();
  await abandonAtAsset();
  await abandonAtAsset({ leave: true });
  check(sentBodies.length === 0, '关闭后浏览器不再发送埋点');
  const direct = await qa.api('POST', '/api/ui/usage/events', [{ task: 'nc.register', kind: 'start' }]);
  check(direct.data.accepted === 0, '关闭后直接调用上报接口也被拒收');
  check((await report()).started === offBefore.started, '关闭后服务端无新增');

  check((await admin.api('PUT', '/api/ui/usage/config', { enabled: true })).data.enabled === true, '重新开启');
  sentBodies.length = 0;
  await abandonAtAsset();
  check(sentBodies.length > 0, '重新开启后恢复上报');
  check(qa.errors.length === 0, '质检员页面无前端异常');
} finally {
  await admin.api('PUT', '/api/ui/usage/config', { enabled: true }).catch(() => undefined);
  await admin.browser.close();
}
