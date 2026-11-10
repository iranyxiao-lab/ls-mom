import type { Page } from '@playwright/test';

/** 打开单个 Story 的 iframe（globals 形如 { theme: 'dark', brand: '#0f8a6c' }）。 */
export async function openStory(page: Page, id: string, globals: Record<string, string> = {}) {
  const g = Object.entries(globals)
    .map(([k, v]) => `${k}:${v.startsWith('#') ? `!hex(${v.slice(1)})` : v}`)
    .join(';');
  await page.goto(`/iframe.html?id=${id}&viewMode=story${g ? `&globals=${g}` : ''}`);
  await page.locator('#storybook-root').waitFor();
  await page.waitForLoadState('networkidle');
}

export const rgb = (hex: string) => {
  const n = parseInt(hex.slice(1), 16);
  return `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`;
};
