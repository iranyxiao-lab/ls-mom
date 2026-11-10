/**
 * modernize-ui-ux 3.6：偏好中心与收藏、最近访问（部署环境，无头）。
 * 在一个浏览器里改主题、密度、布局、快捷键提示并收藏页面；换一个全新的浏览器（无本地存储）登录后全部保持；
 * 收藏出现在侧栏「收藏」分组；最近访问至少保留 20 条；退出登录后本机不留上一个用户的记录。
 */
import { expect, test } from '@playwright/test';
import { api, freshLogin, loggedIn } from '../support/auth';

const USER = 'demo_leader';
const DEFAULTS = { theme: 'system', density: 'auto', navType: 'left', tagsView: true, hotkeyHints: true };

test.describe.configure({ timeout: 180_000 });

test('偏好与收藏保存在服务端：换浏览器登录后保持', async ({ browser }) => {
  // —— 浏览器 A：恢复默认后在偏好中心修改 ——
  const a = await freshLogin(browser, USER, { viewport: { width: 1440, height: 900 } });
  await api(a.page)('PUT', '/api/ui/prefs', DEFAULTS);
  await api(a.page)('PUT', '/api/ui/prefs/nav', { recent: [], favorites: [] });
  await a.page.reload();
  await a.page.goto('/user/prefs');
  const center = a.page.getByTestId('pref-center');
  await center.getByText('深色', { exact: true }).click();
  await center.getByText('紧凑', { exact: true }).click();
  await center.getByText('顶部菜单', { exact: true }).click();
  await a.page.getByTestId('pref-hints').click();
  await expect(a.page.locator('html')).toHaveClass(/dark/);
  await expect(a.page.locator('html')).toHaveAttribute('data-density', 'compact');

  // 访问 25 个对象（最近访问至少 20 条）：管理员在一车间建 25 条工作，车间主任（一车间数据域）逐个打开
  const admin = await loggedIn(browser, 'admin');
  await admin.page.goto('/index');
  const orgs = (await api(admin.page)('GET', '/api/asset/orgs')).data;
  const ws = orgs.find((o: any) => o.name === '一车间');
  const ids: string[] = [];
  for (let i = 0; i < 25; i++) {
    const r = await api(admin.page)('POST', '/api/op/ls.core.Work.create', { input: { workType: 'OTHER', title: `偏好测试${i}`, orgUnitId: ws.id } });
    expect(r.code, r.msg).toBe(200);
    ids.push(r.data.targetId);
  }
  await admin.context.close();
  for (const id of ids) {
    await a.page.evaluate(
      (p) => (document.querySelector('#app') as any).__vue_app__.config.globalProperties.$router.push(p),
      `/lsmom/object/work/${id}`
    );
    await expect(a.page.getByTestId('object-title')).toContainText(`偏好测试${ids.indexOf(id)}`);
  }
  // 收藏最后一个对象
  await a.page.getByTestId('object-star').click();
  await expect(a.page.getByTestId('object-star')).toContainText('已收藏');
  // 等同步写回服务端
  await expect
    .poll(async () => (await api(a.page)('GET', '/api/ui/prefs')).data, { timeout: 10_000 })
    .toMatchObject({
      prefs: { theme: 'dark', density: 'compact', navType: 'top', hotkeyHints: false },
      favorites: [{ title: expect.stringContaining('偏好测试24') }]
    });
  const saved = (await api(a.page)('GET', '/api/ui/prefs')).data;
  expect(saved.recent.length, '最近访问至少 20 条').toBeGreaterThanOrEqual(20);
  await a.context.close();

  // —— 浏览器 B：全新上下文登录 ——
  const b = await freshLogin(browser, USER, { viewport: { width: 1440, height: 900 } });
  await b.page.goto('/index');
  await expect(b.page.locator('html')).toHaveClass(/dark/);
  await expect(b.page.locator('html')).toHaveAttribute('data-density', 'compact');
  await expect(b.page.locator('.sidebar-container'), '顶部菜单布局下不显示侧栏').toBeHidden();
  await expect(b.page.locator('.topmenu-container, .el-menu--horizontal').first()).toContainText('收藏');
  // 命令面板：空输入显示收藏与最近访问；快捷键提示已关闭
  await b.page.getByTestId('open-palette').waitFor();
  await b.page.keyboard.press('Control+k');
  const palette = b.page.getByTestId('command-palette');
  await expect(palette.getByTestId('palette-item-favorite').first()).toContainText('偏好测试24');
  expect(await palette.getByTestId('palette-item-recent').count()).toBeGreaterThanOrEqual(20);
  await expect(palette.getByTestId('palette-hints')).toHaveCount(0);
  await b.page.keyboard.press('Escape');

  // 偏好中心显示的是服务端的值；恢复左侧菜单后侧栏出现「收藏」分组
  await b.page.goto('/user/prefs');
  await expect(b.page.getByTestId('pref-favorites')).toContainText('偏好测试24');
  await b.page.getByTestId('pref-center').getByText('左侧菜单', { exact: true }).click();
  await expect(b.page.locator('.sidebar-container')).toBeVisible();
  await expect(b.page.locator('.sidebar-container')).toContainText('收藏');

  // 退出登录：本机不留该用户的收藏与最近访问
  await b.page.evaluate(async () => {
    const pinia = (document.querySelector('#app') as any).__vue_app__.config.globalProperties.$pinia;
    await pinia._s.get('user').logout();
  });
  const local = await b.page.evaluate(() => JSON.parse(localStorage.getItem('ls-nav-history') || '{}'));
  expect(local.recent ?? []).toEqual([]);
  expect(local.favorites ?? []).toEqual([]);
  await b.context.close();

  // 恢复默认，避免影响其他用例
  const c = await freshLogin(browser, USER);
  await api(c.page)('PUT', '/api/ui/prefs', DEFAULTS);
  await api(c.page)('PUT', '/api/ui/prefs/nav', { recent: [], favorites: [] });
  await c.context.close();
});
