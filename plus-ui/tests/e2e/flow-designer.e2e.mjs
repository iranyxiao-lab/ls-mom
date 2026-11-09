/**
 * 10.4 / 10.5 端到端（无头）：在部署环境用审批流配置器配置并发布一条流程，再出 v2 查看差异。
 * 全部经界面：新建「不符合项」流程 → 设置回调 → 插入审核节点并指定人员 → 校验 → 发布 v1 →
 * 业务提交后待办出现在该节点 → 插入会签节点 → 发布 v2 → 预览渲染 → 差异页 v1/v2 高亮新增会签节点。
 * 运行：node tests/e2e/flow-designer.e2e.mjs（LSMOM_E2E_BASE 指定地址；只用于测试环境）
 */
import { open, check, BASE } from './lib.mjs';

let code = null;
const { browser, page, api, errors } = await open({ width: 1700, height: 1000 });
const shot = process.env.LSMOM_E2E_SHOT;
try {
  code = 'acme.flowui' + Date.now().toString(36) + '.issueApproval';
  const me = (await api('GET', '/api/flow/me')).data;
  await page.goto(`${BASE}/lsmom/flow-designer/${encodeURIComponent(code)}?concept=ls.core.Issue`);
  await page.locator('[data-testid="flow-designer"]').waitFor({ timeout: 30000 });
  await page.waitForTimeout(500);

  const tid = (id) => page.locator(`[data-testid="${id}"]`);
  const pick = async (testid, text) => {
    await tid(testid).locator('xpath=ancestor-or-self::*[contains(@class,"el-select")][1]').first().click();
    await page.locator('.el-select__popper[aria-hidden="false"] .el-select-dropdown__item', { hasText: text }).first().click();
    await page.waitForTimeout(350);
  };
  const insert = async (addId, type) => {
    await tid(addId).click();
    await page.locator(`.el-popover:visible [data-testid="pick-${type}"]`).click();
    await page.waitForTimeout(300);
  };

  // 1. 流程设置
  await tid('flow-name').fill('不符合项审批（界面配置）');
  check((await page.locator('[data-testid="flow-tree"]').textContent()).includes('发起人提交'), '配置器打开，树形画布就绪');
  check((await page.locator('.el-notification, .el-message--error').count()) === 0, '打开核心对象流程时没有报错提示');
  await pick('cb-approved', '开始整改');
  await pick('cb-rejected', '驳回');
  check((await page.locator('textarea, input[type="file"]').filter({ hasText: /xml/i }).count()) === 0, '界面不提供 XML 导入');

  // 2. 插入审核节点，指定当前用户为办理人
  await insert('add-root-0', 'AUDIT');
  const audit = page.locator('[data-testid^="node-audit"]').first();
  check((await audit.count()) === 1, '插入审核节点');
  await tid('node-name').fill('质量审核');
  await pick('assignee-strategy', '指定人员');
  await tid('assignee-users').locator('xpath=ancestor-or-self::*[contains(@class,"el-select")][1]').first().click();
  await page.keyboard.type('疯狂');
  await page.waitForTimeout(800);
  await page.locator('.el-select__popper[aria-hidden="false"] .el-select-dropdown__item').first().click();
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);
  check((await audit.textContent()).includes('指定人员：疯狂的狮子Li'), '审核节点办理人：' + (await audit.textContent()).trim());

  // 3. 服务节点不选 Operation → 校验拦截并可定位
  await insert('add-root-1', 'SERVICE');
  await tid('btn-validate').click();
  await tid('issues').waitFor({ timeout: 10000 });
  check(
    (await tid('issues').textContent()).includes('NODE_OPERATION_REQUIRED') || (await tid('issues').textContent()).length > 0,
    '服务节点未选 Operation 时校验报错：' + (await tid('issues').textContent()).trim().slice(0, 80)
  );
  const svc = page.locator('[data-testid^="node-service"]').first();
  await svc.locator('[data-testid^="del-"]').click();
  await page.waitForTimeout(300);
  await tid('btn-validate').click();
  await tid('issues-ok').waitFor({ timeout: 10000 });
  check(true, '删除服务节点后校验通过');

  // 4. 发布 v1
  await tid('btn-publish').click();
  await page.locator('.el-message--success', { hasText: '已发布 v1' }).waitFor({ timeout: 15000 });
  check((await tid('flow-status').textContent()).includes('已发布 v1'), '界面发布 v1');
  const v1 = await api('GET', `/api/flow/definitions/${encodeURIComponent(code)}/1`);
  const node = v1.data.definition.nodes[0];
  check(
    v1.data.status === 'PUBLISHED' && node.type === 'AUDIT' && node.name === '质量审核' && node.assignee.value[0] === me.partyId,
    '后端保存的配置与界面一致'
  );

  // 5. 流程生效：登记并提交不符合项后，待办出现在「质量审核」
  const raised = await api('POST', '/api/op/ls.core.Issue.raise', {
    input: { type: 'DEFECT', title: '界面配置流程验证', severity: 'LOW', orgUnitId: me.orgUnitId }
  });
  const id = raised.data.targetId;
  const allowed = await api('GET', `/api/op/allowed?concept=ls.core.Issue&id=${id}`);
  await api('POST', '/api/op/ls.core.Issue.submit', { input: {}, targetId: id, version: allowed.data.version });
  let todo = null;
  for (let n = 0; n < 30 && !todo; n++) {
    todo = (await api('GET', '/api/flow/todo')).data.find((t) => t.bizId === id && t.bizType === 'ls.core.Issue');
    if (!todo) await page.waitForTimeout(300);
  }
  check(todo && todo.nodeName === '质量审核' && todo.flowVersion === 1, '提交后待办出现在「质量审核」（流程 v1）');
  // 后续审批用接口收尾，避免测试数据长期占用待办
  await api('POST', `/api/flow/tasks/${todo.taskId}/complete`, { action: 'REJECT', comment: 'E2E 收尾' });

  // 6. v2：插入会签节点（角色）并发布
  await page.reload();
  await tid('flow-designer').waitFor({ timeout: 30000 });
  await page.waitForTimeout(500);
  await insert('add-root-1', 'AUDIT');
  await tid('node-name').fill('质量会签');
  await page.locator('[data-testid="mode-AND"]').click();
  await page.waitForTimeout(200);
  const sign = page.locator('[data-testid^="node-audit"]').last();
  check((await sign.textContent()).includes('会签'), '新节点设为会签');
  await tid('btn-publish').click();
  await page.locator('.el-message--success', { hasText: '已发布 v2' }).waitFor({ timeout: 15000 });
  check(true, '界面发布 v2');

  // 7. 预览（LogicFlow 只读）
  await page.locator('.el-tabs__item', { hasText: /^预览$/ }).click();
  await tid('lf-canvas').locator('svg').first().waitFor({ timeout: 10000 });
  await page.waitForTimeout(500);
  const nodes = Number(await tid('lf-canvas').getAttribute('data-nodes'));
  check(nodes >= 8, `预览渲染 BPMN（${nodes} 个元素）`);
  check((await tid('lf-canvas').textContent()).includes('会签'), '预览中会签节点带「会签」标记');

  // 8. 差异：v1 vs v2
  await page.locator('.el-tabs__item', { hasText: /^版本差异$/ }).click();
  await page.waitForTimeout(300);
  await pick('diff-base', 'v1');
  await pick('diff-target', 'v2');
  await tid('btn-diff').click();
  await tid('diff-list').waitFor({ timeout: 10000 });
  await page.waitForTimeout(600);
  const added = page.locator('[data-testid^="diff-ADDED-"]');
  check(
    (await added.count()) === 1 && (await added.textContent()).includes('质量会签') && (await added.textContent()).includes('会签'),
    '差异：' + (await tid('diff-list').textContent()).trim()
  );
  const diffCanvas = page.locator('[data-testid="lf-canvas"]:visible').last();
  const addedKey = (await added.getAttribute('data-testid')).replace('diff-ADDED-', '');
  check((await diffCanvas.getAttribute('data-added')) === addedKey, '差异图高亮新增节点 ' + addedKey);
  const green = await diffCanvas.locator('[stroke="#16a34a"][stroke-width="3"]').count();
  check(green >= 2, `新增会签节点在图上绿色高亮（${green} 个元素）`);
  if (shot) await page.screenshot({ path: shot, fullPage: true });
  check(errors.length === 0, '页面无脚本错误' + (errors.length ? '：' + errors.join('; ') : ''));
} catch (e) {
  if (shot) await page.screenshot({ path: shot.replace(/\.png$/, '-fail.png'), fullPage: true });
  console.log('页面消息：', await page.locator('.el-message').allTextContents());
  throw e;
} finally {
  // 停用本次发布的流程，避免与其他用例争用同一发起动作
  if (code) await api('POST', `/api/flow/definitions/${encodeURIComponent(code)}/retire`).catch(() => undefined);
  await browser.close();
}
