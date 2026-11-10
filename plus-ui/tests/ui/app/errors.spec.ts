/**
 * modernize-ui-ux 3.8：错误页（部署环境，无头）。
 * 无权限链接显示缺少的权限与追踪编号，可申请权限（管理员收到通知）；不存在的地址显示 404；
 * 断网时顶部提示、页面资源加载失败显示断网页并可重试；服务出错的提示带追踪编号。
 */
import { expect, test, type Page } from '@playwright/test';
import { api, loggedIn } from '../support/auth';
import { expectAccessible } from '../support/a11y';

test.describe.configure({ timeout: 120_000 });

// 应用内跳转；跳转失败（如断网时页面资源取不到）由应用自己处理，这里不抛出
const spaGo = (page: Page, path: string) =>
  page.evaluate((p) => (document.querySelector('#app') as any).__vue_app__.config.globalProperties.$router.push(p).catch(() => undefined), path);

test('无权限链接：显示缺少的权限与追踪编号，申请权限后管理员收到通知', async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'demo_quality', { viewport: { width: 1280, height: 900 } });
  await page.goto('/system/user');
  await expect(page.getByTestId('error-title')).toHaveText('没有访问权限');
  await expect(page.getByTestId('error-page-trail')).toHaveText('系统管理 / 用户管理');
  await expect(page.getByTestId('error-permission')).toHaveText('system:user:list');
  await expect(page.getByTestId('error-trace')).toHaveText(/^[0-9a-zA-Z-]{8,}$/);
  await expectAccessible(page, '无权限页');

  await page.getByTestId('access-reason').fill('需要查看班组成员');
  await page.getByTestId('btn-request-access').click();
  // 24 小时内重复运行时提示"已经申请过"
  await expect(page.locator('.el-message--success')).toContainText(/已提交申请|已经申请过/);
  await expect(page.getByTestId('btn-request-access')).toHaveText('已提交申请');
  await context.close();

  const admin = await loggedIn(browser, 'admin');
  await admin.page.goto('/index');
  const n = (await api(admin.page)('GET', '/api/workbench/notifications')).data;
  expect(
    n.system.some((x: any) => x.title === '权限申请' && x.message.includes('系统管理 / 用户管理') && x.message.includes('system:user:list')),
    '管理员的系统消息里有权限申请'
  ).toBe(true);
  await admin.context.close();
});

test('不存在的地址显示 404，可返回首页', async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'demo_quality');
  await page.goto('/no/such/page-x');
  await expect(page.getByTestId('error-title')).toHaveText('找不到这个页面');
  await expect(page.getByTestId('error-reason')).toContainText('/no/such/page-x');
  await page.getByTestId('btn-error-home').click();
  await page.waitForURL('**/index');
  await context.close();
});

test('断网：顶部提示；页面资源加载失败显示断网页，恢复后重试', async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'admin');
  await page.goto('/index');
  await page.getByTestId('open-palette').waitFor();
  await context.setOffline(true);
  await expect(page.getByTestId('offline-banner')).toBeVisible();
  // 没加载过的页面（偏好设置）资源取不到 → 断网页
  await spaGo(page, '/user/prefs');
  await page.waitForURL((u) => u.pathname === '/offline');
  await expect(page.getByTestId('error-title')).toHaveText('网络已断开');
  expect(new URL(page.url()).searchParams.get('from')).toBe('/user/prefs');
  await context.setOffline(false);
  await page.getByTestId('btn-error-retry').click();
  await page.waitForURL((u) => u.pathname === '/user/prefs');
  await expect(page.getByTestId('pref-center')).toBeVisible();
  await context.close();
});

test('服务出错：提示带追踪编号；出错页显示追踪编号', async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'admin');
  await page.route('**/api/workbench/notifications', (r) =>
    r.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'X-Trace-Id': 'trace-test-500' },
      body: JSON.stringify({ code: 500, msg: '服务器内部错误' })
    })
  );
  await page.goto('/index');
  await expect(page.locator('.el-message--error').first()).toContainText('追踪编号 trace-test-500');
  await page.goto('/500?trace=abc123def456&from=/index');
  await expect(page.getByTestId('error-title')).toHaveText('服务出错了');
  await expect(page.getByTestId('error-trace')).toHaveText('abc123def456');
  await context.close();
});
