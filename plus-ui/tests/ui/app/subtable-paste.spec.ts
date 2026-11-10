/**
 * modernize-ui-ux 4.6：从 Excel 粘贴 30 行到子表（部署环境，无头）。
 * 临时发布一版借还单表单（规格改为下拉：M8 / M10 / M12 / M16），在通用台账新增借还单，点「从 Excel 粘贴」后粘贴 30 行（含表头）：
 * 生成 30 行，规格不在下拉范围内的单元格标红并给出候选值；点候选改正后保存成功（服务端照常校验）。结束后恢复原表单。
 * 依赖测试环境中已发布的工器具借还示例配置包（toolloan-config 端到端用例会发布）。
 */
import { expect, test, type Page } from '@playwright/test';
import { api, loggedIn } from '../support/auth';
import { expectAccessible } from '../support/a11y';

test.describe.configure({ timeout: 180_000 });

async function pick(page: Page, scope: ReturnType<Page['getByTestId']>, testId: string, text: string | RegExp, typed?: string) {
  const sel = scope.getByTestId(testId);
  await sel.click();
  if (typed) await sel.locator('input').first().fill(typed);
  await page.locator('.el-select-dropdown__item:visible', { hasText: text }).first().click();
  await page.keyboard.press('Escape').catch(() => undefined);
}

async function publish(call: ReturnType<typeof api>, schema: any) {
  const draft = await call('POST', '/api/form/drafts', { schema, note: '粘贴测试' });
  expect(draft.code, draft.msg).toBe(200);
  const r = await call('POST', `/api/form/views/${schema.formCode}/${schema.version}/publish`);
  expect(r.code, r.msg).toBe(200);
  if (r.data.status !== 'APPLIED') {
    await call('POST', `/api/form/changesets/${r.data.changeSetId}/review`, { approve: true });
    await call('POST', `/api/form/changesets/${r.data.changeSetId}/apply`);
  }
}

test('粘贴 30 行：规格不在下拉范围的单元格标红并给出候选，改正后保存', async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'admin', { viewport: { width: 1440, height: 900 } });
  await page.goto('/index');
  const call = api(page);
  const views = (await call('GET', '/api/form/views?type=FORM')).data as any[];
  const loan = views
    .filter((v) => /^acme\.tl[a-z0-9]+\.loanForm$/.test(v.code) && v.publishedVersion)
    .sort((a, b) => String(b.code).localeCompare(String(a.code)))[0];
  expect(loan, '测试环境有已发布的工器具借还配置包').toBeTruthy();
  const concept = loan.code.replace(/\.loanForm$/, '.LoanOrder');
  const original = (await call('GET', `/api/form/views/${loan.code}`)).data.schema;
  const latest = Number(loan.latestVersion);
  const variant = JSON.parse(JSON.stringify(original));
  variant.version = latest + 1;
  const spec = variant.subTables[0].fields.find((f: any) => f.key === 'spec');
  spec.widget = 'select';
  spec.options = ['M8', 'M10', 'M12', 'M16'].map((v) => ({ value: v, label: v }));
  await publish(call, variant);
  try {
    const run = Date.now().toString(36);
    await page.goto(`/lsmom/ledger/${concept}`);
    await page.getByTestId('create').click();
    const ed = page.getByTestId('ledger-editor');
    await ed.getByTestId('input-loanNo').fill(`TL-${run}-P`);
    await pick(page, ed, 'input-borrower', '郑师傅', '郑');
    await ed.getByTestId('field-loanDate').locator('input').fill('2026-11-10');
    await ed.getByTestId('field-loanDate').locator('input').press('Enter');
    await page.keyboard.press('Escape');
    await pick(page, ed, 'input-purpose', /^检修$/);
    await expect(ed.getByTestId('field-purpose')).toContainText('检修');

    const lines = ['工器具\t规格\t数量'];
    for (let i = 1; i <= 30; i++) lines.push(`工具${i}\t${i === 7 || i === 21 ? 'M11' : ['M8', 'M10', 'M12', 'M16'][i % 4]}\t${(i % 5) + 1}`);
    await ed.getByTestId('paste-btn-loanLine').click();
    await expect(ed.getByTestId('paste-loanLine')).toBeFocused();
    // 无头页面不持有系统剪贴板焦点：在接收粘贴的输入框上派发与 Ctrl+V 相同的 paste 事件（Excel 复制的制表符文本）
    await ed.getByTestId('paste-loanLine').evaluate(
      (el, text) => {
        const dt = new DataTransfer();
        dt.setData('text/plain', text);
        el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
      },
      lines.join('\r\n') + '\r\n'
    );

    await expect(ed.getByTestId('cell-loanLine-29-tool').locator('input')).toHaveValue('工具30');
    await expect(page.locator('.el-message').first()).toContainText('已粘贴 30 行，2 处需要修改');
    for (const r of [6, 20]) {
      await expect(ed.getByTestId(`cell-loanLine-${r}-spec`)).toHaveClass(/is-error/);
      await expect(ed.getByTestId(`cell-loanLine-${r}-spec`)).toContainText(`第 ${r + 1} 行：规格「M11」不在可选范围内`);
      await expect(ed.getByTestId(`cands-loanLine-${r}-spec`)).toContainText(/M10|M12/);
    }
    await expect(ed.getByTestId('cell-loanLine-5-spec')).not.toHaveClass(/is-error/);
    await expectAccessible(page, '粘贴后的子表', { include: '[data-testid="subtable-loanLine"]' });

    // 点候选改正两处后保存
    await ed.getByTestId('cands-loanLine-6-spec').locator('button', { hasText: 'M12' }).click();
    await ed.getByTestId('cands-loanLine-20-spec').locator('button', { hasText: 'M10' }).click();
    await expect(ed.locator('.ls-subtable__cell.is-error')).toHaveCount(0);
    await ed.getByTestId('btn-save-draft').click();
    await expect(page.locator('.el-message--success').first()).toContainText('已保存草稿');
    const rec = (
      await call('POST', `/api/crud/${concept}/query`, { filters: [{ field: 'loanNo', op: 'eq', value: `TL-${run}-P` }], page: 1, size: 1 })
    ).data.rows[0];
    const full = (await call('GET', `/api/crud/${concept}/${rec.id}`)).data;
    expect(full.loanLine).toHaveLength(30);
    expect(full.loanLine[6]).toMatchObject({ tool: '工具7', spec: 'M12' });
    expect(full.loanLine[20]).toMatchObject({ tool: '工具21', spec: 'M10' });
    expect(Number(full.loanLine[29].qty)).toBe(1);
  } finally {
    // 恢复原表单（新版本，内容与原来相同）
    await publish(call, { ...original, version: latest + 2 });
    await context.close();
  }
});
