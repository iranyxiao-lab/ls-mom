/**
 * modernize-ui-ux 8.5 端到端（无头 Chromium，部署环境）：发布前问题清单。
 *   审批流配置器新建不符合项流程、绑定表单 → 插入"车间审核"（按角色、未设超时、无字段策略）与未选 Operation 的服务节点 →
 *   校验：错误 / 警告分级，错误在前 → 有错误时发布被拒 → 点击"未配置字段策略"问题：选中"车间审核"并打开、高亮字段策略面板 →
 *   点击回调警告定位到设置项 → 删除服务节点后只剩警告，可以发布。
 * 运行：node tests/e2e/flow-issues.e2e.mjs（只用于测试环境）
 */
import { BASE, check, open } from './lib.mjs';

const code = 'acme.flowissue' + Date.now().toString(36) + '.ncApproval';
const s = await open({ width: 1700, height: 1000 });
try {
  const { page, api } = s;
  await page.goto(`${BASE}/lsmom/flow-designer/${encodeURIComponent(code)}?concept=ls.quality.NonConformance`);
  await page.getByTestId('flow-designer').waitFor({ timeout: 30000 });
  await page.waitForTimeout(500);
  const tid = (id) => page.getByTestId(id);
  const pick = async (testid, text) => {
    await tid(testid).locator('xpath=ancestor-or-self::*[contains(@class,"el-select")][1]').first().click();
    await page.locator('.el-select__popper[aria-hidden="false"] .el-select-dropdown__item', { hasText: text }).first().click();
    await page.waitForTimeout(300);
  };
  const insert = async (addId, type) => {
    await tid(addId).click();
    await page.locator(`.el-popover:visible [data-testid="pick-${type}"]`).click();
    await page.waitForTimeout(300);
  };

  await tid('flow-name').fill('不符合项审批（问题清单）');
  await pick('flow-form', '不符合项');
  await pick('cb-approved', '车间审核通过并派整改');
  await pick('cb-rejected', '审核驳回');
  await insert('add-root-0', 'AUDIT');
  await tid('node-name').fill('车间审核');
  await pick('assignee-strategy', /^角色$/);
  await pick('assignee-role', '车间主任');
  await insert('add-root-1', 'SERVICE');

  // —— 校验：分级 ——
  await tid('btn-validate').click();
  await tid('issues').waitFor({ timeout: 10000 });
  const summary = (await tid('issues-summary').textContent()) ?? '';
  check(/1 个错误/.test(summary) && /\d+ 个警告/.test(summary), '问题清单分级：' + summary.trim());
  const levels = await page.locator('[data-testid^="issue-"][data-level]').evaluateAll((els) => els.map((e) => e.getAttribute('data-level')));
  check(levels[0] === 'ERROR' && levels.lastIndexOf('ERROR') < levels.indexOf('WARNING'), '错误排在警告前：' + levels.join(','));
  const texts = await page.locator('[data-testid^="issue-"][data-level]').allTextContents();
  check(
    texts.some((t) => t.includes('服务节点必须选择已发布 Operation')),
    '错误：服务节点未选 Operation'
  );
  check(
    texts.some((t) => t.includes('未配置字段策略')) && texts.some((t) => t.includes('未设置超时')) && texts.some((t) => t.includes('未限定业务组织')),
    '警告：未配字段策略、未设超时、角色未限定业务组织'
  );

  // —— 有错误时不能发布 ——
  await tid('btn-publish').click();
  await page.locator('.el-message--error', { hasText: '有 1 个错误，不能发布' }).first().waitFor({ timeout: 10000 });
  const hist = await api('GET', `/api/flow/definitions/${encodeURIComponent(code)}/history`);
  check(!(hist.data ?? []).some((v) => v.status === 'PUBLISHED'), '有错误时发布被拒，没有发布版本');

  // —— 点击问题定位：选中车间审核并打开字段策略面板 ——
  await page.getByTestId('flow-tree').click({ position: { x: 5, y: 5 } });
  await page.locator('[data-testid^="issue-"][data-level]', { hasText: '未配置字段策略' }).first().click();
  await tid('node-panel').waitFor();
  check((await tid('node-name').inputValue()) === '车间审核', '点击问题后选中"车间审核"节点');
  const sec = tid('section-policy');
  await page.waitForTimeout(600);
  check((await sec.getAttribute('class'))?.includes('is-focus'), '字段策略面板高亮');
  check(await sec.isVisible(), '字段策略面板已打开');
  const box = await sec.boundingBox();
  check(box && box.y >= 0 && box.y < 1000, '字段策略面板滚动到可见区域');
  check((await page.locator('[data-testid^="node-"].selected').count()) === 1, '左侧节点树选中该节点');
  // 定位到办理人
  await page.locator('[data-testid^="issue-"][data-level]', { hasText: '未限定业务组织' }).first().click();
  await page.waitForTimeout(400);
  check((await tid('section-assignee').getAttribute('class'))?.includes('is-focus'), '角色警告定位到办理人区块');
  // 流程级问题定位到设置项
  await page.locator('[data-testid^="issue-"][data-level]', { hasText: '未配置撤回回调' }).first().click();
  await page.waitForTimeout(400);
  check(await tid('cb-withdrawn').evaluate((el) => !!el.closest('.el-form-item')?.classList.contains('is-located')), '回调警告定位到"撤回"设置项');

  // —— 修正错误后，只剩警告可以发布 ——
  await page.locator('[data-testid^="node-service"]').first().locator('[data-testid^="del-"]').click();
  await page.waitForTimeout(300);
  await tid('btn-validate').click();
  await tid('issues-ok').waitFor({ timeout: 10000 });
  check(/个警告，可以发布/.test((await tid('issues-ok').textContent()) ?? ''), '只剩警告：校验通过并提示警告数');
  await tid('btn-publish').click();
  await page.locator('.el-message--success', { hasText: '已发布 v1' }).waitFor({ timeout: 15000 });
  check(true, '警告不阻止发布');
  // 收尾：停用，避免留下在用流程
  await api('POST', `/api/flow/definitions/${encodeURIComponent(code)}/retire`);
  check(s.errors.length === 0, '页面无脚本错误' + (s.errors.length ? '：' + s.errors.join(' | ') : ''));
} finally {
  await s.browser.close();
}
