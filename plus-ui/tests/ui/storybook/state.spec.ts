import { expect, test } from '@playwright/test';
import { openStory } from './story';

test('状态标签的 Lucide 图标经 UnoCSS 渲染（非空图形）', async ({ page }) => {
  await openStory(page, '设计系统-状态与反馈--status-tags');
  const icons = page.locator('.ls-status__icon');
  expect(await icons.count()).toBeGreaterThan(20);
  const info = await icons.first().evaluate((el) => {
    const cs = getComputedStyle(el);
    return { mask: cs.maskImage || cs.webkitMaskImage, w: el.getBoundingClientRect().width };
  });
  expect(info.mask).toMatch(/^url\("data:image\/svg\+xml/);
  expect(info.w).toBeGreaterThan(8);
});

test('状态与反馈文档页可打开', async ({ page }) => {
  await page.goto('/iframe.html?id=设计系统-状态与反馈--docs&viewMode=docs');
  await expect(page.getByText('统一的空态、骨架屏、错误态')).toBeVisible();
  await expect(page.locator('.ls-empty').first()).toBeVisible();
});

test('深色主题下空态与错误态可读', async ({ page }) => {
  for (const id of ['设计系统-状态与反馈--empty', '设计系统-状态与反馈--error-state']) {
    await openStory(page, id, { theme: 'dark' });
    expect(await page.evaluate(() => document.documentElement.classList.contains('dark'))).toBe(true);
    const title = page.locator('.ls-empty__title, .ls-error__title').first();
    const color = await title.evaluate((el) => getComputedStyle(el).color);
    expect(color).not.toBe('rgb(23, 27, 34)'); // 不是浅色主题的正文色
  }
});
