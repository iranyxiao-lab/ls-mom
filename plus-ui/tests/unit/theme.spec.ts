import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { nextTick } from 'vue';
import 'virtual:ls-tokens.css';
import indexHtml from '../../index.html?raw';
import { BOOT_SCRIPT, STORAGE, applyMode, cssVar, resolveMode, useDensity, useThemeMode } from '@/design/theme';

const html = document.documentElement;
const reset = () => {
  localStorage.clear();
  html.classList.remove('dark');
  delete html.dataset.density;
  delete html.dataset.theme;
  html.style.removeProperty('--ls-color-primary');
};

/** 模拟刷新：清空 html 状态后执行首屏内联脚本（与 index.html 中的同一份）。 */
const reload = () => {
  html.classList.remove('dark');
  delete html.dataset.density;
  delete html.dataset.theme;
  new Function(BOOT_SCRIPT)();
};

describe('明暗主题与密度', () => {
  beforeEach(reset);
  afterEach(reset);

  it('index.html 内联了与源码一致的首屏脚本（无闪白）', () => {
    expect(indexHtml).toContain(`<script>${BOOT_SCRIPT}</script>`);
  });

  it('切换深色后 html.dark 生效、令牌切换为深色，刷新后保持', async () => {
    const t = useThemeMode();
    t.pref.value = 'light';
    await nextTick();
    const lightBg = cssVar('--ls-color-bg');
    const t0 = performance.now();
    t.pref.value = 'dark';
    await nextTick();
    expect(html.classList.contains('dark')).toBe(true);
    expect(performance.now() - t0).toBeLessThan(200);
    expect(cssVar('--ls-color-bg')).not.toBe(lightBg);
    expect(localStorage.getItem(STORAGE.theme)).toBe('dark');
    reload();
    expect(html.classList.contains('dark')).toBe(true);
    t.pref.value = 'light';
    await nextTick();
    reload();
    expect(html.classList.contains('dark')).toBe(false);
  });

  it('默认跟随系统', () => {
    const sys = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    expect(resolveMode('system')).toBe(sys);
    reload();
    expect(html.dataset.theme).toBe(sys);
  });

  it('切换紧凑密度后 data-density 生效、组件令牌变小，刷新后保持', async () => {
    const d = useDensity();
    d.pref.value = 'compact';
    await nextTick();
    expect(html.dataset.density).toBe('compact');
    expect(cssVar('--ls-control-height')).toBe('28px');
    expect(cssVar('--el-component-size')).toBe('28px');
    reload();
    expect(html.dataset.density).toBe('compact');
    d.pref.value = 'comfortable';
    await nextTick();
    expect(cssVar('--ls-control-height')).toBe('36px');
    reload();
    expect(html.dataset.density).toBe('comfortable');
  });

  it('自动密度：列表紧凑、表单与移动端舒适', async () => {
    const d = useDensity();
    d.pref.value = 'auto';
    await nextTick();
    expect(html.dataset.density).toBeUndefined();
    expect(d.attrFor('list')).toBe('compact');
    expect(d.attrFor('form')).toBe('comfortable');
    expect(d.attrFor('mobile')).toBe('comfortable');
    const box = document.createElement('div');
    box.dataset.density = d.attrFor('list');
    document.body.appendChild(box);
    expect(cssVar('--ls-table-row-height', box)).toBe('32px');
    box.remove();
  });

  it('深色主题下 Element Plus 变量也跟随令牌', () => {
    applyMode('dark');
    expect(cssVar('--el-bg-color')).toBe(cssVar('--ls-color-bg'));
    applyMode('light');
  });
});
