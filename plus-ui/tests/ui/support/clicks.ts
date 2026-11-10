/**
 * 点击预算（spec ui/ux-quality「关键任务操作预算」）：统计用户真实点击（指针点击、触控点按），
 * 不含文字与数字输入、键盘操作。超出预算时断言失败。
 */
import { expect, type Page } from '@playwright/test';

declare global {
  interface Window {
    __lsClicks?: { n: number; log: string[] };
  }
}

const INIT = () => {
  const describe = (el: Element | null) => {
    if (!el) return '?';
    const t = el.closest('[data-testid]');
    const label = (el.closest('button,a,[role="button"],[role="tab"],[role="menuitem"],[role="option"]') as HTMLElement | null)?.innerText?.trim();
    return (t?.getAttribute('data-testid') ?? el.tagName.toLowerCase()) + (label ? `「${label.slice(0, 20)}」` : '');
  };
  // 计数存在 sessionStorage 中，整页跳转后继续累计
  if (!window.__lsClicks) {
    try {
      window.__lsClicks = JSON.parse(sessionStorage.getItem('__lsClicks') ?? 'null') ?? { n: 0, log: [] };
    } catch {
      window.__lsClicks = { n: 0, log: [] };
    }
  }
  const state = window.__lsClicks!;
  if ((window as any).__lsClicksBound) return;
  (window as any).__lsClicksBound = true;
  // 只统计可信的 pointerup（一次点击或点按算一次），合成事件与键盘触发的 click 不计
  document.addEventListener(
    'pointerup',
    (e) => {
      if (!e.isTrusted || (e.pointerType === 'mouse' && e.button !== 0)) return;
      state.n++;
      state.log.push(describe(e.target as Element));
      try {
        sessionStorage.setItem('__lsClicks', JSON.stringify(state));
      } catch {
        /* 存储不可用时只统计当前文档 */
      }
    },
    { capture: true }
  );
};

/** 开始计数（整页跳转后继续累计）。返回读取与断言方法。 */
export async function countClicks(page: Page) {
  await page.addInitScript(INIT);
  await page.evaluate(INIT).catch(() => undefined);
  const read = async () => page.evaluate(() => window.__lsClicks ?? { n: 0, log: [] });
  let base = 0;
  return {
    /** 重置起点（例如登录后再开始统计任务的点击）。 */
    async reset() {
      base = (await read()).n;
    },
    async count() {
      return (await read()).n - base;
    },
    async log() {
      return (await read()).log;
    },
    /** 断言自 reset 以来的点击数不超过预算。 */
    async expectWithin(budget: number, task: string) {
      const n = await this.count();
      const log = await this.log();
      expect(n, `${task}：点击 ${n} 次，预算 ${budget} 次（${log.slice(-n).join(' → ')}）`).toBeLessThanOrEqual(budget);
      return n;
    }
  };
}
