/**
 * modernize-ui-ux 5.5：草稿行内编辑与行内动作（部署环境，无头）。
 * 不符合项台账：双击草稿行的"问题"改写后 Enter → 服务端保存，单元格显示已保存；
 * 把严重程度改成"高"而没有相关设备 → 服务端规则拒绝，单元格保留原值并标出原因；
 * 已驳回（终态）的行没有行内编辑入口，也不出现"登记提交"等动作。
 */
import { expect, test } from '@playwright/test';
import { api, loggedIn, orgIds } from '../support/auth';

test.describe.configure({ timeout: 180_000 });
const NC = 'ls.quality.NonConformance';

test('草稿行内编辑经服务端校验；非草稿不可编辑、不显示不可执行的动作', async ({ browser }) => {
  const run = Date.now().toString(36).toUpperCase();
  const marker = `IE${run}`;
  const { context, page } = await loggedIn(browser, 'admin', { viewport: { width: 1600, height: 900 } });
  await page.goto('/index');
  const call = api(page);
  const { workshop } = await orgIds(page);
  const make = async (s: string) => {
    const r = await call('POST', `/api/crud/${NC}`, { title: `${marker}-${s}`, description: '行内编辑测试', severity: 'LOW', orgId: workshop });
    expect(r.code, JSON.stringify(r)).toBe(200);
    return r.data.id as number;
  };
  const draft = await make('D');
  const done = await make('R');
  for (const [op, input] of [
    ['submit', {}],
    ['reject', { auditOpinion: '不成立' }]
  ] as const) {
    const cur = (await call('GET', `/api/crud/${NC}/${done}`)).data;
    expect((await call('POST', `/api/op/${NC}.${op}`, { targetId: String(done), version: cur.version, input })).code).toBe(200);
  }
  await call('PUT', '/api/ui/saved-views/default', { concept: NC, viewId: null });

  await page.goto(`/lsmom/ledger/${NC}`);
  await page.getByTestId('keyword').fill(marker);
  await page.getByTestId('keyword').press('Enter');
  const rows = page.locator('[data-testid="table"] .vxe-table--main-wrapper .vxe-body--row');
  await expect(rows).toHaveCount(2);

  // 终态行：没有编辑入口和动作
  await expect(page.getByTestId(`cell-title-${done}`)).toHaveCount(0);
  await expect(page.getByTestId(`op-${NC}.submit-${done}`)).toHaveCount(0);
  await expect(page.getByTestId(`row-edit-${done}`)).toHaveCount(0);
  await expect(page.getByTestId(`row-more-${done}`)).toHaveCount(0);
  // 草稿行：编辑、登记提交
  await expect(page.getByTestId(`row-edit-${draft}`)).toBeVisible();
  await expect(page.getByTestId(`op-${NC}.submit-${draft}`)).toBeVisible();

  // 行内改"问题"
  await page.getByTestId(`cell-title-${draft}`).dblclick();
  const editor = page.getByTestId(`cell-editor-title-${draft}`);
  await expect(editor).toBeFocused();
  await editor.fill(`${marker}-D 已改`);
  await editor.press('Enter');
  await expect(page.getByTestId(`cell-state-title-${draft}`)).toContainText('已保存');
  await expect(page.getByTestId(`cell-title-${draft}`)).toHaveText(`${marker}-D 已改`);
  expect((await call('GET', `/api/crud/${NC}/${draft}`)).data.title).toBe(`${marker}-D 已改`);

  // 严重程度改为"高"：没有相关设备，服务端规则拒绝
  await page.getByTestId(`cell-severity-${draft}`).dblclick();
  await page.locator('.el-select-dropdown__item:visible', { hasText: /^高$/ }).click();
  const mark = page.getByTestId(`cell-state-severity-${draft}`);
  await expect(mark).toContainText('未保存');
  await expect(mark).toHaveAttribute('title', /严重程度为高时必须填写/);
  await expect(page.getByTestId(`cell-severity-${draft}`)).toHaveText('低');
  expect((await call('GET', `/api/crud/${NC}/${draft}`)).data.severity).toBe('LOW');

  // 修改后版本号前进：再次编辑不会冲突
  await page.getByTestId(`cell-title-${draft}`).dblclick();
  await page.getByTestId(`cell-editor-title-${draft}`).fill(`${marker}-D 第二次`);
  await page.getByTestId(`cell-editor-title-${draft}`).press('Enter');
  await expect(page.getByTestId(`cell-state-title-${draft}`)).toContainText('已保存');
  await context.close();
});
