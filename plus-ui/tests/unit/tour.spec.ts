import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const store: Record<string, string> = {};
vi.mock('@/api/lsmom/uiPrefs', () => ({
  getPrefs: vi.fn(async () => ({ prefs: { ...store }, recent: [], favorites: [] })),
  putPrefs: vi.fn(async (p: Record<string, string>) => Object.assign(store, p))
}));

import { putPrefs } from '@/api/lsmom/uiPrefs';
import { activeTour, autoTour, resetTourCache, startTour } from '@/guide/tour';

const wait = (ms = 80) => new Promise((r) => setTimeout(r, ms));
const q = (id: string) => document.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

let root: HTMLElement;
beforeEach(() => {
  Object.keys(store).forEach((k) => delete store[k]);
  resetTourCache();
  (putPrefs as any).mockClear();
  root = document.createElement('div');
  root.innerHTML = `<div data-testid="work-center"><div data-tour="wc-tabs">tabs</div><div data-tour="wc-filters">f</div><table data-tour="wc-list"></table></div>`;
  document.body.appendChild(root);
});
afterEach(() => root.remove());

describe('新手引导', () => {
  it('首次进入自动开始；第一步点跳过后关闭并记住，不再自动出现；可从帮助菜单重看', async () => {
    await autoTour('workbench');
    await wait();
    expect(activeTour.value).toBe('workbench');
    expect(q('tour-popover')!.textContent).toContain('欢迎使用协同工作中心');
    expect(q('tour-popover')!.textContent).toContain('1 / 4');
    q('tour-skip')!.click();
    await wait();
    expect(activeTour.value).toBeNull();
    expect(q('tour-popover')).toBeNull();
    expect(putPrefs).toHaveBeenCalledWith({ tourWorkbench: 'skipped' });

    resetTourCache();
    await autoTour('workbench');
    await wait();
    expect(activeTour.value).toBeNull();

    // 帮助菜单「重看本页引导」
    await startTour('workbench');
    await wait();
    expect(activeTour.value).toBe('workbench');
    for (let i = 0; i < 4; i++) {
      q('tour-next')!.click();
      await wait();
    }
    expect(activeTour.value).toBeNull();
    expect(putPrefs).toHaveBeenLastCalledWith({ tourWorkbench: 'done' });
  });

  it('步骤高亮页面锚点；页面根元素不在时不自动开始', async () => {
    await startTour('workbench');
    await wait();
    q('tour-next')!.click();
    await wait();
    expect(document.querySelector('[data-tour="wc-tabs"]')!.classList.contains('driver-active-element')).toBe(true);
    q('tour-skip')!.click();
    await wait();
    Object.keys(store).forEach((k) => delete store[k]);
    resetTourCache();
    await autoTour('designer');
    expect(activeTour.value).toBeNull();
  });
});
