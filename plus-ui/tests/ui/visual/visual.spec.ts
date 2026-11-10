/**
 * 视觉回归（spec ui/ux-quality「视觉回归」）：设计系统组件与核心页面，浅色/深色 × 桌面/移动截图对比。
 * 必须在固定的 Playwright 容器中运行（字体与渲染一致）：tests/ui/visual-docker.sh；更新基线加 --update-snapshots。
 * 非预期差异会使测试失败，需人工确认后更新基线再合并。
 */
import fs from 'node:fs';
import { expect, test, type Page } from '@playwright/test';
import { openStory } from '../storybook/story';

const VIEWPORTS = { desktop: { width: 1280, height: 800 }, mobile: { width: 390, height: 844 } } as const;
const THEMES = ['light', 'dark'] as const;
const APP = process.env.LSMOM_E2E_BASE ?? 'http://127.0.0.1:18081';

const index = JSON.parse(fs.readFileSync('storybook-static/index.json', 'utf8'));
// 品牌色示例里的图表是 SVG 渲染，像素稳定；成功提示等需要交互的 Story 只截初始状态
const stories = Object.values(index.entries as Record<string, { id: string; type: string }>).filter((e) => e.type === 'story');

async function settle(page: Page) {
  await page.evaluate(() => document.fonts.ready);
  await page.addStyleTag({ content: '*,*::before,*::after{caret-color:transparent!important}' });
  await page.waitForTimeout(150);
}

for (const [vp, size] of Object.entries(VIEWPORTS)) {
  for (const theme of THEMES) {
    test.describe(`${vp}·${theme}`, () => {
      test.use({ viewport: size });

      for (const s of stories) {
        test(`组件 ${s.id}`, async ({ page }) => {
          await openStory(page, s.id, { theme });
          await settle(page);
          await expect(page.locator('#storybook-root')).toHaveScreenshot(`${s.id}-${vp}-${theme}.png`);
        });
      }

      for (const [name, path] of [['登录页', '/login'], ['404', '/404'], ['401', '/401']] as const) {
        test(`页面 ${name}`, async ({ page, context }) => {
          // 没有部署环境时（PR 流水线）只比对组件；页面截图在 ux-gate 的 Compose 环境中比对
          test.skip(process.env.LS_VISUAL_PAGES === '0', '无部署环境');
          await context.addInitScript((t) => localStorage.setItem('ls-theme', t), theme);
          await page.goto(APP + path);
          await page.waitForLoadState('networkidle');
          await settle(page);
          await expect(page).toHaveScreenshot(`page-${name}-${vp}-${theme}.png`, { fullPage: true });
        });
      }
    });
  }
}

test('自检：修改全局圆角后视觉回归报出差异', async ({ page }, info) => {
  // 更新基线时跳过，否则会用改过圆角的截图覆盖基线
  test.skip(['all', 'changed'].includes(info.config.updateSnapshots), '更新基线时不运行自检');
  test.fail(true, '圆角令牌变化必须被截图对比发现');
  await openStory(page, '设计系统-组件库一致性--side-by-side', { theme: 'light' });
  await settle(page);
  await page.addStyleTag({ content: 'html:root{--ls-radius-md:12px!important}' });
  await page.waitForTimeout(100);
  await expect(page.locator('#storybook-root')).toHaveScreenshot('设计系统-组件库一致性--side-by-side-desktop-light.png');
});
