/**
 * modernize-ui-ux 4.8：便利功能不绕过服务端（部署环境，无头）。
 * 1. 记忆值已失效：不符合项记住了上次的相关设备，该设备随后报废 → 再次新增时仍带出（标"上次填写"），直接保存被服务端拒绝，
 *    设备字段提示"该设备已报废，请重新选择"；改选在用设备后保存成功。
 * 2. 粘贴非法值：借还单明细粘贴一行超长的工器具名称（表单未声明长度，前端放行）→ 服务端拒绝，该单元格提示原因。
 * 3. 扫码到无权限对象：班长在缺陷台账的设备字段扫车间设备的编码 → 字段下提示找不到或无权；绕过界面直接提交该设备编号，服务端同样拒绝。
 */
import { expect, test, type Page } from '@playwright/test';
import { api, freshLogin, loggedIn } from '../support/auth';

test.describe.configure({ timeout: 180_000 });
const NC = 'ls.quality.NonConformance';
const ASSET = 'ls.asset.Asset';

async function pick(page: Page, scope: ReturnType<Page['getByTestId']>, testId: string, text: string | RegExp, typed?: string) {
  const sel = scope.getByTestId(testId);
  await sel.click();
  if (typed) await sel.locator('input').first().fill(typed);
  await page.locator('.el-select-dropdown__item:visible', { hasText: text }).first().click();
  await page.keyboard.press('Escape').catch(() => undefined);
}

function ops(call: ReturnType<typeof api>) {
  return async (concept: string, op: string, id: number, input: Record<string, unknown> = {}) => {
    const cur = (await call('GET', `/api/crud/${concept}/${id}`)).data;
    const r = await call('POST', `/api/op/${concept}.${op}`, { targetId: String(id), version: cur.version, input });
    expect(r.code, JSON.stringify(r)).toBe(200);
  };
}

test('记忆的设备已报废：带出后直接保存被拒，设备字段提示原因；改选后保存成功', async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'admin', { viewport: { width: 1440, height: 900 } });
  await page.goto('/index');
  const call = api(page);
  const op = ops(call);
  const run = Date.now().toString(36);
  const running = (await call('POST', `/api/crud/${ASSET}/query`, { filters: [{ field: 'status', op: 'eq', value: 'RUNNING' }], page: 1, size: 5 }))
    .data.rows as any[];
  const keep = running[0];
  const created = await call('POST', `/api/crud/${ASSET}`, {
    assetCode: `G-${run}`.toUpperCase(),
    name: `守卫测试泵${run}`,
    assetType: 'PUMP',
    orgId: keep.orgId
  });
  expect(created.code, JSON.stringify(created)).toBe(200);
  const assetId = created.data.id as number;
  await op(ASSET, 'commission', assetId);

  const fill = async (title: string) => {
    await page.getByTestId('create').click();
    const ed = page.getByTestId('ledger-editor');
    await expect(ed.getByTestId('schema-form')).toBeVisible();
    await ed.getByTestId('input-title').fill(title);
    await ed.getByTestId('input-description').fill('便利功能守卫测试');
    await pick(page, ed, 'input-severity', /^低$/);
    return ed;
  };

  await page.goto(`/lsmom/ledger/${NC}`);
  let ed = await fill(`守卫-${run}-1`);
  await pick(page, ed, 'input-asset', `守卫测试泵${run}`, `守卫测试泵${run}`);
  await ed.getByTestId('btn-save-draft').click();
  await expect(page.locator('.el-message').filter({ hasText: '已保存草稿' }).first()).toBeVisible();
  await expect(ed).toBeHidden();

  // 设备报废后再新增：仍带出上次的设备（名称照常显示），保存被服务端拒绝
  await op(ASSET, 'scrap', assetId, { reason: '便利功能守卫测试' });
  ed = await fill(`守卫-${run}-2`);
  await expect(ed.getByTestId('origin-asset')).toHaveText('上次填写');
  await expect(ed.getByTestId('field-asset')).toContainText(`守卫测试泵${run}`);
  await ed.getByTestId('btn-save-draft').click();
  await expect(ed.locator('[data-field-key="asset"]')).toContainText('该设备已报废，请重新选择');
  await expect(ed.getByTestId('error-summary')).toContainText('该设备已报废');
  const none = (await call('POST', `/api/crud/${NC}/query`, { filters: [{ field: 'title', op: 'eq', value: `守卫-${run}-2` }], page: 1, size: 1 }))
    .data.rows;
  expect(none, '被拒的记录没有写入').toHaveLength(0);

  // 改选在用设备后保存成功（也更新了记忆值，后续新增不再带出报废设备）
  await pick(page, ed, 'input-asset', keep.name, keep.name);
  await ed.getByTestId('btn-save-draft').click();
  await expect(ed).toBeHidden();
  const saved = (await call('POST', `/api/crud/${NC}/query`, { filters: [{ field: 'title', op: 'eq', value: `守卫-${run}-2` }], page: 1, size: 1 }))
    .data.rows;
  expect(saved[0]?.asset).toBe(keep.id);
  await context.close();
});

test('粘贴超长的工器具名称：前端放行，服务端拒绝并标在该单元格', async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'admin', { viewport: { width: 1440, height: 900 } });
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
  await ed.getByTestId('input-loanNo').fill(`TL-${run}-P`);
  await pick(page, ed, 'input-borrower', '郑师傅', '郑');
  await ed.getByTestId('field-loanDate').locator('input').fill('2026-11-10');
  await ed.getByTestId('field-loanDate').locator('input').press('Enter');
  await page.keyboard.press('Escape');
  await pick(page, ed, 'input-purpose', /^检修$/);
  const long = '超长名称'.repeat(26);
  await ed.getByTestId('paste-loanLine').evaluate((el, text) => {
    const dt = new DataTransfer();
    dt.setData('text/plain', text);
    el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
  }, `工器具\t数量\r\n扳手\t1\r\n${long}\t2\r\n`);
  const cell = ed.getByTestId('cell-loanLine-1-tool');
  await expect(cell.locator('input')).toHaveValue(long);
  await expect(cell).not.toHaveClass(/is-error/);

  await ed.getByTestId('btn-save-draft').click();
  await expect(cell).toHaveClass(/is-error/);
  await expect(cell).toContainText(/第 2 行：.*100/);
  await expect(ed.getByTestId('cell-loanLine-0-tool')).not.toHaveClass(/is-error/);
  await cell.locator('input').fill('万用表');
  await ed.getByTestId('btn-save-draft').click();
  await expect(ed).toBeHidden();
  await context.close();
});

test('扫码到无权使用的设备：字段下提示原因；绕过界面直接提交同样被拒', async ({ browser }) => {
  const admin = await loggedIn(browser, 'admin');
  await admin.page.goto('/index');
  const running = (
    await api(admin.page)('POST', `/api/crud/${ASSET}/query`, { filters: [{ field: 'status', op: 'eq', value: 'RUNNING' }], page: 1, size: 20 })
  ).data.rows as any[];
  await admin.context.close();

  const t = await freshLogin(browser, 'demo_team', { viewport: { width: 1440, height: 900 } });
  await t.page.goto('/index');
  const call = api(t.page);
  let hidden: any = null;
  for (const a of running) {
    if ((await call('GET', `/api/crud/${ASSET}/${a.id}`)).code !== 200) {
      hidden = a;
      break;
    }
  }
  expect(hidden, '有班长数据范围外的在用设备').toBeTruthy();

  await t.page.goto('/lsmom/ledger/ls.asset.Defect');
  await t.page.getByTestId('create').click();
  const ed = t.page.getByTestId('ledger-editor');
  await expect(ed.getByTestId('schema-form')).toBeVisible();
  await ed.getByTestId('input-asset').click();
  await t.page.keyboard.type(`${hidden.assetCode}\n`, { delay: 5 });
  await expect(ed.locator('[data-field-key="asset"]')).toContainText(`没有找到编码为「${hidden.assetCode}」的设备，或你无权使用`);
  await expect(ed.getByTestId('field-asset')).not.toContainText(hidden.name);

  // 绕过界面：直接提交该设备编号
  const orgs = (await call('GET', '/api/asset/orgs')).data as any[];
  const r = await call('POST', '/api/crud/ls.asset.Defect', { title: '越权引用测试', severity: 'LOW', asset: hidden.id, orgId: orgs[0]?.id });
  expect(r.code).not.toBe(200);
  expect(r.data?.details?.fields?.asset ?? r.msg).toContain('你无权使用所选设备，请重新选择');
  await t.context.close();
});
