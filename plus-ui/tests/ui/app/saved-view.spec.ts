/**
 * modernize-ui-ux 5.2/5.3：保存视图与组合筛选（部署环境，无头）。
 * 车间主任（一车间，可看本车间及下属班组）在不符合项台账：
 * 1. 关键字 → "本组织" → "未完成" 芯片逐步收窄；高级筛选抽屉再加"严重程度"；条件标签逐个删除。
 * 2. 保存"本班组未完成"并设为默认 → 重新打开列表时自动应用（芯片选中、只显示本车间未完成的记录）。
 */
import { expect, test, type Page } from '@playwright/test';
import { api, loggedIn } from '../support/auth';

test.describe.configure({ timeout: 180_000 });
const NC = 'ls.quality.NonConformance';
const ROWS = '[data-testid="table"] .vxe-table--main-wrapper .vxe-body--row';
const WORKSHOP = 17; // 一车间
const TEAM = 18; // 一车间甲班

let marker = '';
async function rows(page: Page) {
  await page.waitForLoadState('networkidle');
  const re = new RegExp(`${marker}-[A-Z]\\d?`);
  return (await page.locator(ROWS).allTextContents()).map((t) => re.exec(t)?.[0] ?? t.slice(0, 40)).sort();
}

test('组合筛选与"本班组未完成"默认视图', async ({ browser }) => {
  const run = Date.now().toString(36).toUpperCase();
  marker = `SV${run}`;
  // 准备数据（管理员）：本车间未完成 ×2（低、中）、甲班未完成、本车间已驳回
  const { context: adminCtx, page: adminPage } = await loggedIn(browser, 'admin');
  await adminPage.goto('/index');
  const admin = api(adminPage);
  const make = async (suffix: string, orgId: number, severity: string) => {
    const r = await admin('POST', `/api/crud/${NC}`, { title: `${marker}-${suffix}`, description: '保存视图测试', severity, orgId });
    expect(r.code, JSON.stringify(r)).toBe(200);
    return r.data.id as number;
  };
  await make('A', WORKSHOP, 'LOW');
  await make('A2', WORKSHOP, 'MEDIUM');
  await make('B', TEAM, 'LOW');
  const c = await make('C', WORKSHOP, 'LOW');
  for (const [op, input] of [
    ['submit', {}],
    ['reject', { auditOpinion: '不成立' }]
  ] as const) {
    const cur = (await admin('GET', `/api/crud/${NC}/${c}`)).data;
    const r = await admin('POST', `/api/op/${NC}.${op}`, { targetId: String(c), version: cur.version, input });
    expect(r.code, JSON.stringify(r)).toBe(200);
  }
  await adminCtx.close();

  const { context, page } = await loggedIn(browser, 'demo_leader', { viewport: { width: 1440, height: 900 } });
  await page.goto('/index');
  const call = api(page);
  // 清掉上次运行留下的默认视图
  await call('PUT', '/api/ui/saved-views/default', { concept: NC, viewId: null });

  await page.goto(`/lsmom/ledger/${NC}`);
  await expect(page.locator(ROWS).first()).toBeVisible();
  await page.getByTestId('keyword').fill(marker);
  await page.getByTestId('keyword').press('Enter');
  await expect.poll(() => rows(page)).toEqual([`${marker}-A`, `${marker}-A2`, `${marker}-B`, `${marker}-C`]);

  await page.getByTestId('chip-myOrg').click();
  await expect.poll(() => rows(page)).toEqual([`${marker}-A`, `${marker}-A2`, `${marker}-C`]);
  await page.getByTestId('chip-open').click();
  await expect.poll(() => rows(page)).toEqual([`${marker}-A`, `${marker}-A2`]);

  // 高级筛选抽屉：再按严重程度
  await page.getByTestId('adv-open').click();
  const drawer = page.getByTestId('adv-drawer');
  await expect(drawer).toBeVisible();
  await drawer.getByTestId('filter-severity').click();
  await page.locator('.el-select-dropdown__item:visible', { hasText: /^中$/ }).first().click();
  await drawer.getByTestId('search').click();
  await expect(drawer).toBeHidden();
  await expect.poll(() => rows(page)).toEqual([`${marker}-A2`]);
  const conds = page.getByTestId('conds');
  await expect(conds).toContainText('本组织');
  await expect(conds).toContainText('未完成');
  await expect(conds).toContainText('严重程度');
  await expect(conds).toContainText(`关键字：${marker}`);

  // 删除"严重程度"标签 → 回到两条
  await page.getByTestId('cond-severity').locator('.el-tag__close').click();
  await expect.poll(() => rows(page)).toEqual([`${marker}-A`, `${marker}-A2`]);

  // 保存为默认视图
  const name = `本班组未完成${run}`;
  await page.getByTestId('view-save').click();
  const dlg = page.getByTestId('view-save-dialog');
  await expect(dlg).toBeVisible();
  await dlg.getByTestId('view-name').fill(name);
  await dlg.getByTestId('view-as-default').click();
  await dlg.getByTestId('view-save-confirm').click();
  await expect(page.locator('.el-message', { hasText: `已保存视图「${name}」并设为默认` })).toBeVisible();

  // 视图按 $context 保存，而不是当前用户的组织编号
  const saved = (await call('GET', `/api/ui/saved-views?concept=${encodeURIComponent(NC)}`)).data;
  const view = saved.views.find((v: any) => v.name === name);
  expect(saved.defaultId).toBe(view.id);
  expect(view.definition.filters).toContainEqual({ field: 'orgId', op: 'eq', value: '$context.orgId' });

  // 重新打开：自动应用
  await page.goto('/index');
  await page.goto(`/lsmom/ledger/${NC}`);
  await expect.poll(() => rows(page)).toEqual([`${marker}-A`, `${marker}-A2`]);
  await expect(page.getByTestId('chip-myOrg')).toHaveClass(/is-checked/);
  await expect(page.getByTestId('chip-open')).toHaveClass(/is-checked/);
  await expect(page.getByTestId('view-select')).toContainText(name);

  // 清空条件后能看到全部；再删除视图
  await page.getByTestId('cond-clear').click();
  await expect.poll(async () => (await rows(page)).length).toBeGreaterThan(2);
  const del = await call('DELETE', `/api/ui/saved-views/${view.id}`);
  expect(del.code).toBe(200);
  await context.close();
});
