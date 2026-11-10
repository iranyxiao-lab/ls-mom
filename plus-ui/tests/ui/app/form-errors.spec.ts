/**
 * modernize-ui-ux 4.4：服务端字段错误就近定位（部署环境，无头）。
 * 在通用台账新增借还单，子表第 3 行工器具名称超过 Concept 允许的 100 个字符（表单未声明，只有服务端能发现）→
 * 保存后第 3 行工器具单元格高亮并显示原因，页面滚动到该处并聚焦；顶部汇总可点击定位；改正后保存成功。
 * 依赖测试环境中已发布的工器具借还示例配置包（toolloan-config 端到端用例会发布）。
 */
import { expect, test, type Page } from '@playwright/test';
import { api, loggedIn } from '../support/auth';
import { expectAccessible } from '../support/a11y';

test.describe.configure({ timeout: 150_000 });

async function pick(page: Page, scope: ReturnType<Page['getByTestId']>, testId: string, text: string | RegExp, typed?: string) {
  const sel = scope.getByTestId(testId);
  await sel.click();
  if (typed) await sel.locator('input').first().fill(typed);
  await page.locator('.el-select-dropdown__item:visible', { hasText: text }).first().click();
  await page.keyboard.press('Escape').catch(() => undefined);
}

test('子表第 3 行服务端错误：单元格高亮、滚动到该处并聚焦、汇总可点击定位', async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'admin', { viewport: { width: 1280, height: 720 } });
  await page.goto('/index');
  const views = (await api(page)('GET', '/api/form/views?type=FORM')).data as any[];
  const loan = views
    .filter((v) => /^acme\.tl[a-z0-9]+\.loanForm$/.test(v.code) && v.publishedVersion)
    .sort((a, b) => String(b.code).localeCompare(String(a.code)))[0];
  expect(loan, '测试环境有已发布的工器具借还配置包').toBeTruthy();
  const concept = loan.code.replace(/\.loanForm$/, '.LoanOrder');
  const run = Date.now().toString(36);

  await page.goto(`/lsmom/ledger/${concept}`);
  await page.getByTestId('create').click();
  const ed = page.getByTestId('ledger-editor');
  await ed.getByTestId('input-loanNo').fill(`TL-${run}-E`);
  await pick(page, ed, 'input-borrower', '郑师傅', '郑');
  await ed.getByTestId('field-loanDate').locator('input').fill('2026-11-10');
  await ed.getByTestId('field-loanDate').locator('input').press('Enter');
  await pick(page, ed, 'input-purpose', /^检修$/);
  const line = ed.getByTestId('subtable-loanLine');
  for (let i = 0; i < 3; i++) {
    await ed.getByTestId('add-row-loanLine').click();
    await line.getByTestId('input-tool').nth(i).fill(i === 2 ? '超长名称'.repeat(26) : `扳手${i + 1}`);
    await line.getByTestId('input-qty').nth(i).locator('input').fill('1');
  }
  // 失焦校验：清空单号后离开，字段旁立即提示
  await ed.getByTestId('input-loanNo').fill('');
  await ed.getByTestId('input-remark').click();
  await expect(ed.getByTestId('field-loanNo')).toContainText('必填');
  await ed.getByTestId('input-loanNo').fill(`TL-${run}-E`);

  // 前端校验通过 → 服务端拒绝第 3 行
  await ed.getByTestId('input-remark').click();
  await page.mouse.wheel(0, -2000);
  await ed.getByTestId('btn-save-draft').click();
  const cell = ed.getByTestId('cell-loanLine-2-tool');
  await expect(cell).toHaveClass(/is-error/);
  await expect(cell).toContainText('第 3 行');
  await expect(cell).toContainText('100');
  await expect(cell).toBeInViewport();
  await expect(cell.locator('input')).toBeFocused();
  await expect(ed.getByTestId('cell-loanLine-1-tool')).not.toHaveClass(/is-error/);
  const summary = ed.getByTestId('error-summary');
  await expect(summary).toContainText('借出明细');
  await expectAccessible(page, '错误汇总', { include: '[data-testid="error-summary"]' });

  // 焦点移开后点汇总项回到该单元格
  await ed.getByTestId('input-remark').click();
  await summary.getByTestId('error-summary-item').first().click();
  await expect(cell.locator('input')).toBeFocused();

  // 改正后保存成功
  await cell.locator('input').fill('扳手3');
  await expect(cell).not.toHaveClass(/is-error/);
  await ed.getByTestId('btn-save-draft').click();
  await expect(page.locator('.el-message--success').first()).toContainText('已保存草稿');
  const saved = (await api(page)('POST', `/api/crud/${concept}/query`, { filters: [{ field: 'loanNo', op: 'eq', value: `TL-${run}-E` }], page: 1, size: 1 })).data
    .rows;
  expect(saved).toHaveLength(1);
  await context.close();
});
