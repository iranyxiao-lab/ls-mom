/**
 * modernize-ui-ux 3.1：应用外壳——三种导航布局 × 三档视口（手机 / 平板 / 桌面）。
 * 每个组合：截图留档、无整页横向滚动、没有被裁掉的可见内容；侧栏在手机上是抽屉、平板上收起、桌面上展开；
 * 平板上临时展开不改写桌面端记住的展开偏好。浏览器无头。
 */
import { expect, test, type Page } from '@playwright/test';
import { loggedIn } from '../support/auth';

const VIEWPORTS = [
  { name: '手机', bp: 'mobile', width: 390, height: 844 },
  { name: '平板', bp: 'tablet', width: 820, height: 1180 },
  { name: '桌面', bp: 'desktop', width: 1440, height: 900 }
] as const;
const NAVS = [
  { name: '侧栏', value: 'left' },
  { name: '顶栏', value: 'top' },
  { name: '混合', value: 'mix' }
] as const;
const PAGES = ['/index?desktop=1', '/production/requests', '/production/board', '/asset/center', '/quality/nc', '/monitor/ui-usage'];

/** 整页横向滚动，以及超出视口却没有放进自身滚动容器（被裁掉看不到）的可见元素。 */
async function overflow(page: Page) {
  return page.evaluate(() => {
    const d = document.scrollingElement!;
    const shell = new Set(['app-wrapper', 'main-container', 'app-main']);
    const reachable = (e: Element) => {
      for (let x: Element | null = e; x; x = x.parentElement) {
        const cs = getComputedStyle(x);
        if (cs.display === 'none' || cs.visibility === 'hidden') return false;
        if (cs.position === 'fixed' && x !== e) return true;
        if (x === e || x === document.body || x === document.documentElement || [...x.classList].some((c) => shell.has(c))) continue;
        // 放在自身可横向滚动、且自身没超出视口的容器里，算可达
        if (/(auto|scroll)/.test(cs.overflowX) && x.getBoundingClientRect().right <= innerWidth + 1) return false;
        if (cs.overflowX === 'hidden' && x.getBoundingClientRect().right <= innerWidth + 1) return false;
      }
      return true;
    };
    const wide = [...document.querySelectorAll('#app *')].filter((e) => {
      const b = e.getBoundingClientRect();
      return b.width > 0 && b.height > 0 && b.right > innerWidth + 2 && b.left < innerWidth && reachable(e);
    });
    const top = wide.filter((e) => !wide.includes(e.parentElement!)).map((e) => `${e.tagName.toLowerCase()}.${String(e.className).slice(0, 40)}`);
    return { pageScroll: d.scrollWidth > d.clientWidth, clipped: top.slice(0, 5) };
  });
}

const sidebarWidth = (page: Page) => page.locator('.sidebar-container').evaluate((e) => Math.round(e.getBoundingClientRect().right));

for (const nav of NAVS) {
  for (const vp of VIEWPORTS) {
    test(`${nav.name}布局 × ${vp.name}（${vp.width}px）：无整页横向滚动，侧栏随断点变化`, async ({ browser }, testInfo) => {
      const { context, page } = await loggedIn(browser, 'admin', { viewport: { width: vp.width, height: vp.height } });
      await context.addInitScript((navType) => {
        // 只在本次测试的第一次加载时设置，刷新后保留页面自己写入的偏好
        if (sessionStorage.getItem('shell-spec-init')) return;
        sessionStorage.setItem('shell-spec-init', '1');
        const v = JSON.parse(localStorage.getItem('layout-setting') || '{}');
        localStorage.setItem('layout-setting', JSON.stringify({ ...v, navType }));
        localStorage.setItem('sidebarStatus', '1');
      }, nav.value);

      for (const path of PAGES) {
        await page.goto(path);
        await page.waitForLoadState('networkidle').catch(() => undefined);
        await page.waitForTimeout(300);
        expect(await page.evaluate(() => document.documentElement.dataset.breakpoint), path).toBe(vp.bp);
        const o = await overflow(page);
        expect(o.pageScroll, `${path} 出现整页横向滚动`).toBe(false);
        expect(o.clipped, `${path} 有内容超出视口且无法滚动查看`).toEqual([]);
      }

      await page.goto('/production/requests');
      await page.waitForLoadState('networkidle').catch(() => undefined);
      await testInfo.attach(`外壳-${nav.value}-${vp.bp}`, { body: await page.screenshot(), contentType: 'image/png' });

      if (nav.value === 'top') return;
      const hamburger = page.locator('#hamburger-container');
      if (vp.bp === 'mobile') {
        expect(await sidebarWidth(page), '手机上侧栏默认收在屏幕外').toBeLessThanOrEqual(0);
        await hamburger.click();
        await expect.poll(() => sidebarWidth(page), { message: '点汉堡按钮后侧栏以抽屉滑出' }).toBeGreaterThan(150);
        await page.locator('.drawer-bg').click({ position: { x: vp.width - 20, y: 400 } });
        await expect.poll(() => sidebarWidth(page), { message: '点遮罩收起抽屉' }).toBeLessThanOrEqual(0);
      } else if (vp.bp === 'tablet') {
        expect(await sidebarWidth(page), '平板上侧栏收起为图标栏').toBeLessThan(80);
        await hamburger.click();
        await expect.poll(() => sidebarWidth(page)).toBeGreaterThan(150);
        expect(await page.evaluate(() => localStorage.getItem('sidebarStatus')), '平板上临时展开不改写桌面端偏好').toBe('1');
      } else {
        expect(await sidebarWidth(page), '桌面上按偏好展开').toBeGreaterThan(150);
        const active = await page.locator('.sidebar-container .el-menu-item.is-active').first().evaluate((e) => {
          const cs = getComputedStyle(e);
          return { bg: cs.backgroundColor, color: cs.color };
        });
        expect(active.bg, '当前菜单有实色底，且与文字颜色不同').not.toBe(active.color);
        expect(active.bg).not.toBe('rgb(255, 255, 255)');
        if (nav.value === 'left') await expect(page.locator('#breadcrumb-container')).toBeVisible();
        await hamburger.click();
        await expect.poll(() => sidebarWidth(page)).toBeLessThan(80);
        expect(await page.evaluate(() => localStorage.getItem('sidebarStatus')), '桌面上收起会被记住').toBe('0');
        await page.reload();
        await page.waitForLoadState('networkidle').catch(() => undefined);
        expect(await sidebarWidth(page), '刷新后保持收起').toBeLessThan(80);
      }
      expect(await overflow(page)).toEqual({ pageScroll: false, clipped: [] });
      await context.close();
    });
  }
}

test('品牌：页面标题与侧栏 Logo 不再显示上游项目名，顶栏没有上游仓库与文档链接', async ({ browser }) => {
  const { context, page } = await loggedIn(browser, 'admin', { viewport: { width: 1440, height: 900 } });
  await page.goto('/production/requests');
  await page.waitForLoadState('networkidle').catch(() => undefined);
  await expect(page).toHaveTitle(/LS-MOM/);
  await expect(page.locator('.sidebar-logo-container')).not.toContainText('RuoYi');
  await expect(page.locator('#ruoyi-git, #ruoyi-doc')).toHaveCount(0);
  await context.close();
});
