/**
 * 点击计数（与 plus-ui/tests/ui/support/clicks.ts 同一口径）：只统计可信的指针点击 / 触控点按，文字与数字输入、键盘操作不计；
 * 计数存在 sessionStorage 中，整页跳转后继续累计。
 */
import type { Page } from '@playwright/test';

const INIT = () => {
  const w = window as any;
  const describe = (el: Element | null) => {
    if (!el) return '?';
    const t = el.closest('[data-testid]');
    const label = (el.closest('button,a,[role="button"],[role="tab"],[role="menuitem"],[role="option"]') as HTMLElement | null)?.innerText?.trim();
    return (t?.getAttribute('data-testid') ?? el.tagName.toLowerCase()) + (label ? `「${label.slice(0, 20)}」` : '');
  };
  if (!w.__accClicks) {
    try {
      w.__accClicks = JSON.parse(sessionStorage.getItem('__accClicks') ?? 'null') ?? { n: 0, log: [] };
    } catch {
      w.__accClicks = { n: 0, log: [] };
    }
  }
  if (w.__accClicksBound) return;
  w.__accClicksBound = true;
  document.addEventListener(
    'pointerup',
    (e) => {
      if (!e.isTrusted || (e.pointerType === 'mouse' && e.button !== 0)) return;
      w.__accClicks.n++;
      w.__accClicks.log.push(describe(e.target as Element));
      try {
        sessionStorage.setItem('__accClicks', JSON.stringify(w.__accClicks));
      } catch {
        /* 存储不可用时只统计当前文档 */
      }
    },
    { capture: true }
  );
};

export async function countClicks(page: Page) {
  await page.addInitScript(INIT);
  await page.evaluate(INIT).catch(() => undefined);
  const read = async (): Promise<{ n: number; log: string[] }> => page.evaluate(() => (window as any).__accClicks ?? { n: 0, log: [] });
  let base = 0;
  let baseLog = 0;
  let t0 = Date.now();
  return {
    async reset() {
      const s = await read();
      base = s.n;
      baseLog = s.log.length;
      t0 = Date.now();
    },
    async result() {
      const s = await read();
      return { clicks: s.n - base, log: s.log.slice(baseLog), seconds: Math.round((Date.now() - t0) / 100) / 10 };
    }
  };
}
