/** 统一状态组件（modernize-ui-ux 1.5 / spec ui/design-system「统一的状态与反馈组件」「无障碍基线」「动效克制且可关闭」）。 */
import { afterEach, describe, expect, it } from 'vitest';
import { createApp, h, nextTick, type App, type Component } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import 'virtual:ls-tokens.css';
import { LsBadge, LsEmpty, LsError, LsSkeleton, LsStatusTag, STATUS, lsSuccess, prefersReducedMotion, statusStyle } from '@/components/LsState';

let app: App | undefined;
let host: HTMLElement | undefined;
afterEach(() => {
  app?.unmount();
  host?.remove();
  delete document.documentElement.dataset.motion;
  document.querySelectorAll('.el-message').forEach((e) => e.remove());
});

async function mount(c: Component, props: Record<string, unknown> = {}, slots?: Record<string, () => unknown>) {
  host = document.createElement('div');
  document.body.appendChild(host);
  app = createApp({ render: () => h(c, props, slots) });
  app.use(ElementPlus);
  app.mount(host);
  await nextTick();
  return host;
}

describe('状态标签', () => {
  it('每个状态同时有颜色、图标和文字', async () => {
    for (const code of Object.keys(STATUS)) {
      const el = await mount(LsStatusTag, { status: code });
      const tag = el.querySelector('.ls-status')!;
      expect(tag.getAttribute('data-tone'), code).toBeTruthy();
      expect(tag.querySelector('[class*="i-lucide-"]'), code).not.toBeNull();
      expect(tag.querySelector('.ls-status__label')!.textContent!.trim(), code).toBe(STATUS[code].label);
      app!.unmount();
      host!.remove();
    }
    app = undefined;
  });

  it('状态色调之间不只靠颜色区分：图标各不相同', () => {
    const icons = new Set(['DRAFT', 'IN_PROGRESS', 'DONE', 'BLOCKED', 'REJECTED', 'CANCELLED'].map((c) => statusStyle(c).icon));
    expect(icons.size).toBe(6);
  });

  it('未知状态显示原编码、覆盖色调时图标随色调变化', () => {
    expect(statusStyle('CUSTOM_X').label).toBe('CUSTOM_X');
    expect(statusStyle('DRAFT', { tone: 'danger' }).icon).toBe('i-lucide-circle-x');
  });

  it('文字对比度满足 AA（≥4.5:1）', async () => {
    const lum = (css: string) => {
      const unit = css.startsWith('color(srgb') ? 1 : 255;
      const [r, g, b] = css.replace(/^color\(srgb/, '').match(/[\d.]+/g)!.slice(0, 3).map((v) => {
        const c = Number(v) / unit;
        return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
      });
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };
    for (const code of ['DRAFT', 'PENDING', 'IN_PROGRESS', 'DONE', 'BLOCKED', 'REJECTED']) {
      const el = await mount(LsStatusTag, { status: code });
      const tag = el.querySelector('.ls-status') as HTMLElement;
      const cs = getComputedStyle(tag);
      const [a, b] = [lum(cs.color), lum(cs.backgroundColor)].sort((x, y) => y - x);
      expect((a + 0.05) / (b + 0.05), code).toBeGreaterThanOrEqual(4.5);
      app!.unmount();
      host!.remove();
    }
    app = undefined;
  });
});

describe('空态、错误态、骨架屏、徽标', () => {
  it('空态显示说明与主操作，点击触发 action', async () => {
    let clicked = 0;
    const el = await mount(LsEmpty, { title: '暂无待办', description: '都处理完了', actionText: '查看我发起的', onAction: () => clicked++ });
    expect(el.querySelector('svg[aria-hidden="true"]')).not.toBeNull();
    expect(el.textContent).toContain('都处理完了');
    (el.querySelector('[data-testid="ls-empty-action"]') as HTMLButtonElement).click();
    expect(clicked).toBe(1);
  });

  it('错误态显示原因、追踪编号与重试', async () => {
    let retried = 0;
    const el = await mount(LsError, { title: '加载失败', reason: '网络中断', traceId: 'abc123', retryable: true, onRetry: () => retried++ });
    expect(el.querySelector('[role="alert"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="ls-error-trace"]')!.textContent).toBe('abc123');
    expect(el.querySelector('[aria-label="复制追踪编号"]')).not.toBeNull();
    (el.querySelector('[data-testid="ls-error-retry"]') as HTMLButtonElement).click();
    expect(retried).toBe(1);
  });

  it('徽标超过上限显示 99+，读屏读出条数', async () => {
    const el = await mount(LsBadge, { count: 120 }, { default: () => h('button', '通知') });
    const b = el.querySelector('[data-testid="ls-badge"]')!;
    expect(b.textContent).toBe('99+');
    expect(b.getAttribute('aria-label')).toBe('120 条未读');
  });

  it('徽标为 0 时默认不显示', async () => {
    const el = await mount(LsBadge, { count: 0 }, { default: () => h('button', '通知') });
    expect(el.querySelector('[data-testid="ls-badge"]')).toBeNull();
  });

  it('骨架屏标记加载中；开启减少动态效果后不再闪烁', async () => {
    const el = await mount(LsSkeleton, { variant: 'table', rows: 3 });
    expect(el.querySelector('[aria-busy="true"]')).not.toBeNull();
    const bar = el.querySelectorAll('.ls-skeleton__row')[1] as HTMLElement;
    expect(getComputedStyle(bar).animationName).not.toBe('none');
    document.documentElement.dataset.motion = 'reduced';
    expect(prefersReducedMotion()).toBe(true);
    expect(getComputedStyle(bar).animationName).toBe('none');
  });

  it('减少动态效果时过渡时长归零', () => {
    const probe = document.createElement('div');
    probe.style.transition = 'transform 300ms';
    document.body.appendChild(probe);
    document.documentElement.dataset.motion = 'reduced';
    expect(parseFloat(getComputedStyle(probe).transitionDuration)).toBeLessThan(0.001);
    probe.remove();
  });
});

describe('成功提示', () => {
  it('带撤销的成功提示：点击撤销执行回调并关闭', async () => {
    let undone = 0;
    lsSuccess('已提交审核', { undo: () => undone++ });
    await nextTick();
    await new Promise((r) => setTimeout(r, 50));
    const btn = document.querySelector('[data-testid="toast-undo"]') as HTMLButtonElement;
    expect(btn.textContent).toBe('撤销');
    btn.click();
    expect(undone).toBe(1);
  });
});
