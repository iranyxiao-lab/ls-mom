/**
 * 11.3 部署环境实测（无头）：「已读不等于完成」——催办消息标为已读后待办仍在，在待办中办理后才消失。
 * 发布流程（不符合项，办理人与发起人都是当前用户）→ 提交 → 催办 → 消息标已读 → 待办仍在 → 打开办理（版本快照、时间轴）→ 通过 → 待办消失、已办出现。
 * 运行：node tests/e2e/workbench.e2e.mjs（LSMOM_E2E_BASE 指定地址；只用于测试环境）
 */
import { open, check, BASE } from './lib.mjs';

let code = null;
const { browser, page, api, errors } = await open({ width: 1600, height: 1000 });
const shot = process.env.LSMOM_E2E_SHOT;
try {
  code = 'acme.wb' + Date.now().toString(36) + '.issueCheck';
  const me = (await api('GET', '/api/flow/me')).data;
  const i = 'ls.core.Issue.';
  const pub = await api('POST', '/api/flow/definitions/publish', {
    code,
    name: '不符合项核查',
    concept: 'ls.core.Issue',
    startOn: i + 'submit',
    orgUnitId: me.orgUnitId,
    callbacks: { approved: i + 'startRectification', rejected: i + 'reject' },
    nodes: [
      { key: 'check', type: 'AUDIT', name: '质量核查', assignee: { strategy: 'USER', value: [me.partyId] }, timeout: { hours: 8, action: 'REMIND' } }
    ]
  });
  check(pub.code === 200, '发布流程');
  const title = '工作中心E2E' + Date.now().toString(36);
  const r = await api('POST', '/api/op/' + i + 'raise', { input: { type: 'DEFECT', title, severity: 'LOW', orgUnitId: me.orgUnitId } });
  const id = r.data.targetId;
  const a = await api('GET', `/api/op/allowed?concept=ls.core.Issue&id=${id}`);
  await api('POST', '/api/op/' + i + 'submit', { input: {}, targetId: id, version: a.data.version });
  let task = null;
  for (let n = 0; n < 30 && !task; n++) {
    task = (await api('GET', '/api/workbench/todo')).data.find((x) => x.task.bizId === id && x.task.bizType === 'ls.core.Issue');
    if (!task) await page.waitForTimeout(300);
  }
  check(task && task.bizTitle === title, '待办出现：' + (task?.bizTitle ?? '无'));
  check(task.task.dueAt, '节点时限生成期限 ' + task.task.dueAt);
  const urged = await api('POST', `/api/flow/tasks/${task.task.taskId}/urge`);
  check(urged.code === 200, '发起人催办');
  const again = await api('POST', `/api/flow/tasks/${task.task.taskId}/urge`);
  check(again.code !== 200 && /分钟后再试/.test(again.msg), '限制间隔内再次催办被拒：' + again.msg);

  await page.goto(`${BASE}/lsmom/workbench`);
  await page.locator('[data-testid="work-center"]').waitFor({ timeout: 30000 });
  await page.waitForTimeout(800);
  const tab = (re) => page.locator('.el-tabs__item').filter({ hasText: re }).first();

  // 消息：标为已读
  await tab(/消息/).click();
  await page.waitForTimeout(800);
  const msgRow = page.locator('[data-testid="table-messages"] .el-table__body tr').filter({ hasText: '催办：质量核查' }).first();
  check((await msgRow.count()) === 1 && (await msgRow.textContent()).includes('未读'), '消息中心收到未读催办');
  await msgRow.locator('[data-testid^="read-"]').click();
  await page.waitForTimeout(800);
  check((await msgRow.textContent()).includes('已读'), '催办消息已标为已读');

  // 待办仍在
  await tab(/我的待办/).click();
  await page.waitForTimeout(800);
  const row = page.locator('[data-testid="table-todo"] .el-table__body tr').filter({ hasText: title }).first();
  check((await row.count()) === 1, '已读不等于完成：待办仍在「我的待办」');

  // 在待办中办理
  await row.click();
  await page.locator('[data-testid="snapshot"]').waitFor({ timeout: 10000 });
  await page.waitForTimeout(500);
  check((await page.locator('[data-testid="snapshot"]').textContent()).includes(code + ' v1'), '版本快照显示流程版本');
  await page.locator('.el-drawer .el-tabs__item', { hasText: '时间轴' }).click();
  await page.waitForTimeout(300);
  const tl = await page.locator('[data-testid="task-timeline"]').textContent();
  check(tl.includes('催办'), '时间轴记录催办');
  await page.locator('[data-testid="task-comment"]').fill('已核查，安排整改');
  await page.locator('[data-testid="btn-approve"]').click();
  await page.locator('.el-message--success', { hasText: '已通过' }).waitFor({ timeout: 10000 });
  await page.waitForTimeout(800);
  check((await page.locator('[data-testid="table-todo"] .el-table__body tr').filter({ hasText: title }).count()) === 0, '办理后待办消失');
  await tab(/^已办$/).click();
  await page.waitForTimeout(800);
  check(
    (await page.locator('[data-testid="table-done"] .el-table__body tr').filter({ hasText: title }).filter({ hasText: '质量核查' }).count()) === 1,
    '已办列表出现该任务'
  );
  check(
    (
      await page.locator('[data-testid="table-done"] .el-table__body tr').filter({ hasText: title }).filter({ hasText: '发起人' }).textContent()
    ).includes('已提交'),
    '发起人节点在已办中显示「已提交」'
  );
  let st = '';
  for (let n = 0; n < 20 && st !== 'IN_RECTIFICATION'; n++) {
    st = (await api('GET', `/api/op/allowed?concept=ls.core.Issue&id=${id}`)).data.status;
    if (st !== 'IN_RECTIFICATION') await page.waitForTimeout(300);
  }
  check(st === 'IN_RECTIFICATION', '回调后业务状态 IN_RECTIFICATION');
  if (shot) await page.screenshot({ path: shot, fullPage: true });
  check(errors.length === 0, '页面无脚本错误' + (errors.length ? '：' + errors.join('; ') : ''));
} finally {
  // 停用本次发布的流程，避免与其他用例争用同一发起动作
  if (code) await api('POST', `/api/flow/definitions/${encodeURIComponent(code)}/retire`).catch(() => undefined);
  await browser.close();
}
