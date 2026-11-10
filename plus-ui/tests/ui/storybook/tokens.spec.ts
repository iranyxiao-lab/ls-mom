import { expect, test } from '@playwright/test';
import { openStory, rgb } from './story';

const styleOf = (page, testid: string, prop: string) =>
  page.locator(`[data-testid="${testid}"]`).evaluate((el, p) => getComputedStyle(el as Element).getPropertyValue(p), prop);

/** 解析 rgb()/rgba()/color(srgb …) 为 0~255 通道。 */
function channels(css: string): number[] {
  const srgb = css.match(/color\(srgb ([\d.]+) ([\d.]+) ([\d.]+)/);
  if (srgb) return srgb.slice(1, 4).map((v) => Number(v) * 255);
  return css.match(/[\d.]+/g)!.slice(0, 3).map(Number);
}
/** c 是否为 brand 与白色按同一比例混合（允许透明度叠加带来的少量误差）。 */
function derivedFrom(css: string, brand: string): boolean {
  const c = channels(css);
  const b = channels(rgb(brand));
  const t = c.map((v, i) => (b[i] === 255 ? null : (v - b[i]) / (255 - b[i]))).filter((x): x is number => x !== null);
  return t.every((x) => x >= -0.02 && x <= 0.9) && Math.max(...t) - Math.min(...t) < 0.06;
}

for (const brand of ['#0f8a6c', '#b5371f']) {
  test(`品牌换色 ${brand}：按钮、链接、焦点环、图表同步变化`, async ({ page }) => {
    await openStory(page, '设计系统-令牌--brand', { brand });
    expect(await styleOf(page, 'brand-button', 'background-color')).toBe(rgb(brand));
    expect(await styleOf(page, 'brand-link', 'color')).toBe(rgb(brand));
    // 焦点环：键盘聚焦（Tab）时的 outline 由主色派生
    await page.locator('[data-testid="brand-focus"]').focus();
    await page.keyboard.press('Shift+Tab');
    await page.keyboard.press('Tab');
    const outline = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement;
      return { testid: el.dataset.testid, color: getComputedStyle(el).outlineColor, width: getComputedStyle(el).outlineWidth };
    });
    expect(outline.testid).toBe('brand-focus');
    expect(outline.width).toBe('2px');
    // 焦点环颜色由主色与底色混合派生：各通道按同一比例介于主色与白色之间
    expect(derivedFrom(outline.color, brand), `焦点环 ${outline.color} 应由 ${brand} 派生`).toBe(true);
    // 图表第一个系列颜色 = 主色（图表色 1 默认跟随主色）
    const fill = await page.locator('[data-testid="brand-chart"] path[fill]').evaluateAll((els) =>
      els.map((e) => e.getAttribute('fill')).filter((f) => f && f !== 'none' && f !== 'transparent')
    );
    expect(fill.map((f) => f!.toLowerCase())).toContain(brand);
  });
}

test('默认主色来自令牌', async ({ page }) => {
  await openStory(page, '设计系统-令牌--brand');
  expect(await styleOf(page, 'brand-button', 'background-color')).toBe(rgb('#1a63d1'));
});

test('Element Plus 与 Vant 主色、圆角、字号一致', async ({ page }) => {
  for (const g of [{}, { brand: '#0f8a6c' }, { theme: 'dark' }]) {
    await openStory(page, '设计系统-组件库一致性--side-by-side', g);
    for (const prop of ['background-color', 'border-top-left-radius', 'font-size']) {
      const el = await styleOf(page, 'el-primary', prop);
      const van = await styleOf(page, 'van-primary', prop);
      expect(van, `${JSON.stringify(g)} ${prop}`).toBe(el);
    }
  }
});
