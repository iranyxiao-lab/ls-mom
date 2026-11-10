/**
 * modernize-ui-ux 4.5：纯键盘完成借还单录入（部署环境，无头）。
 * 不用鼠标：Enter 打开新增 → 逐项输入并按 Enter 跳到下一项（借用人、用途输入即搜索、Enter 选中）→ Shift+Tab 到「添加一行」→
 * 子表逐格 Enter，最后一格 Enter 自动新增一行 → Ctrl+Enter 保存并提交审核。
 * 依赖测试环境中已发布的工器具借还示例配置包（toolloan-config 端到端用例会发布）。
 */
import { expect, test, type Page } from '@playwright/test';
import { api, loggedIn } from '../support/auth';

test.describe.configure({ timeout: 150_000 });

const activeTestId = (page: Page) => page.evaluate(() => (document.activeElement as HTMLElement | null)?.dataset?.testid ?? document.activeElement?.closest('[data-testid]')?.getAttribute('data-testid'));
const activeKey = (page: Page) => page.evaluate(() => (document.activeElement?.closest('[data-field-key]') as HTMLElement | null)?.dataset.fieldKey);

test('纯键盘完成借还单录入并提交', async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'admin', { viewport: { width: 1440, height: 900 } });
  await page.goto('/index');
  const views = (await api(page)('GET', '/api/form/views?type=FORM')).data as any[];
  const loan = views.filter((v) => /^acme\.tl[a-z0-9]+\.loanForm$/.test(v.code) && v.publishedVersion).sort((a, b) => String(b.code).localeCompare(String(a.code)))[0];
  expect(loan, '测试环境有已发布的工器具借还配置包').toBeTruthy();
  const concept = loan.code.replace(/\.loanForm$/, '.LoanOrder');
  const loanNo = `TL-${Date.now().toString(36)}-K`;
  const kb = page.keyboard;

  await page.goto(`/lsmom/ledger/${concept}`);
  await page.getByTestId('create').focus();
  await kb.press('Enter');
  const ed = page.getByTestId('ledger-editor');
  await expect(ed.getByTestId('input-loanNo'), '打开后焦点在第一个字段').toBeFocused();
  await expect(ed.getByTestId('form-hotkey-hints')).toContainText('保存草稿');

  await kb.type(loanNo);
  await kb.press('Enter');
  expect(await activeKey(page)).toBe('borrower');
  await kb.type('郑');
  await expect(page.locator('.el-select-dropdown__item:visible', { hasText: '郑师傅' }).first()).toBeVisible();
  await kb.press('Enter'); // 选中第一个候选
  await expect(ed.getByTestId('field-borrower')).toContainText('郑师傅');
  await kb.press('Enter');
  expect(await activeKey(page)).toBe('loanDate');
  await kb.type('2026-11-10');
  await kb.press('Enter');
  expect(await activeKey(page)).toBe('returnDue');
  await kb.type('2026-11-12');
  await kb.press('Enter');
  expect(await activeKey(page)).toBe('purpose');
  await kb.type('检修');
  await expect(page.locator('.el-select-dropdown__item:visible', { hasText: '检修' }).first()).toBeVisible();
  await kb.press('Enter');
  await expect(ed.getByTestId('field-purpose')).toContainText('检修');
  // 外协单位、设备不填，Enter 跳过；附件不参与 Enter 跳转
  for (let i = 0; i < 6 && (await activeKey(page)) !== 'remark'; i++) await kb.press('Enter');
  expect(await activeKey(page)).toBe('remark');
  await kb.type('键盘录入');

  // 借出明细在备注之前：Shift+Tab 回到「添加一行」，Enter 新增并聚焦第一格
  for (let i = 0; i < 20 && (await activeTestId(page)) !== 'add-row-loanLine'; i++) await kb.press('Shift+Tab');
  expect(await activeTestId(page)).toBe('add-row-loanLine');
  await kb.press('Enter');
  expect(await activeKey(page)).toBe('loanLine[0].tool');
  await kb.type('扭力扳手');
  await kb.press('Enter');
  expect(await activeKey(page)).toBe('loanLine[0].spec');
  await kb.type('M12');
  await kb.press('Enter');
  expect(await activeKey(page)).toBe('loanLine[0].qty');
  await kb.type('2');
  await kb.press('Enter'); // 最后一格：新增一行
  expect(await activeKey(page)).toBe('loanLine[1].tool');
  await kb.type('万用表');
  await kb.press('Enter');
  await kb.press('Enter');
  expect(await activeKey(page)).toBe('loanLine[1].qty');
  await kb.type('1');

  await kb.press('Control+Enter');
  await expect(page.locator('.el-message--success').first()).toContainText('提交审核');
  await expect(ed).toBeHidden();
  const rec = (await api(page)('POST', `/api/crud/${concept}/query`, { filters: [{ field: 'loanNo', op: 'eq', value: loanNo }], page: 1, size: 1, expand: ['borrower'] }))
    .data.rows[0];
  expect(rec.status).toBe('IN_REVIEW');
  expect(rec.purpose).toBe('MAINTENANCE');
  expect(String(rec.loanDate)).toContain('2026-11-10');
  expect(String(rec.borrowerRef?.label)).toContain('郑师傅');
  const full = (await api(page)('GET', `/api/crud/${concept}/${rec.id}`)).data;
  expect(full.loanLine.map((l: any) => [l.tool, l.spec ?? null, Number(l.qty)])).toEqual([
    ['扭力扳手', 'M12', 2],
    ['万用表', null, 1]
  ]);
  expect(full.remark).toBe('键盘录入');
  await context.close();
});
