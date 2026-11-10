/**
 * 运行期主题：明暗（跟随系统／手动并记住）、密度（自动／舒适／紧凑）、品牌主色、减少动态效果。
 * 首屏前由 index.html 的内联脚本按同一存储键先设置 html 属性，避免闪白；这里负责后续切换与持久化。
 * 偏好同时写入服务端用户偏好（见偏好中心），本地存储只做缓存。
 */
import { ref, watch, type Ref } from 'vue';

export type ThemePref = 'system' | 'light' | 'dark';
export type DensityPref = 'auto' | 'comfortable' | 'compact';
export type DensityContext = 'list' | 'form' | 'mobile';

export const STORAGE = { theme: 'ls-theme', density: 'ls-density', brand: 'ls-brand', motion: 'ls-motion' } as const;

const safeGet = (k: string) => {
  try {
    return localStorage.getItem(k);
  } catch {
    return null;
  }
};
const safeSet = (k: string, v: string | null) => {
  try {
    if (v == null) localStorage.removeItem(k);
    else localStorage.setItem(k, v);
  } catch {
    /* 隐私模式等不可用时只在本次会话生效 */
  }
};

const media = (q: string) => (typeof window !== 'undefined' && window.matchMedia ? window.matchMedia(q) : null);

/** 当前实际明暗（解析 system 后）。 */
export function resolveMode(pref: ThemePref): 'light' | 'dark' {
  if (pref === 'system') return media('(prefers-color-scheme: dark)')?.matches ? 'dark' : 'light';
  return pref;
}

const listeners = new Set<() => void>();
/** 主题或品牌色变化时通知（图表等 canvas 组件需要重新取色）。 */
export function onThemeChange(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
const notify = () => listeners.forEach((fn) => fn());

export function applyMode(pref: ThemePref) {
  const el = document.documentElement;
  const mode = resolveMode(pref);
  // 切换瞬间关闭过渡，避免颜色逐个渐变造成的闪烁（切换在一帧内完成）
  el.classList.add('ls-theme-switching');
  el.classList.toggle('dark', mode === 'dark');
  el.dataset.theme = mode;
  requestAnimationFrame(() => requestAnimationFrame(() => el.classList.remove('ls-theme-switching')));
  notify();
}

export function applyDensity(pref: DensityPref) {
  const el = document.documentElement;
  if (pref === 'auto') delete el.dataset.density;
  else el.dataset.density = pref;
  notify();
}

/** 品牌主色：null 表示使用令牌默认值。派生色（hover、浅底、焦点环、图表）通过 color-mix 自动跟随。 */
export function applyBrand(color: string | null) {
  const el = document.documentElement;
  if (color && /^#[0-9a-fA-F]{6}$/.test(color)) el.style.setProperty('--ls-color-primary', color);
  else el.style.removeProperty('--ls-color-primary');
  notify();
}

export function applyMotion(reduced: boolean) {
  const el = document.documentElement;
  if (reduced) el.dataset.motion = 'reduced';
  else delete el.dataset.motion;
}

const themePref = ref<ThemePref>((safeGet(STORAGE.theme) as ThemePref) || 'system');
const densityPref = ref<DensityPref>((safeGet(STORAGE.density) as DensityPref) || 'auto');
let initialized = false;

function init() {
  if (initialized || typeof document === 'undefined') return;
  initialized = true;
  watch(themePref, (v) => {
    safeSet(STORAGE.theme, v);
    applyMode(v);
  });
  watch(densityPref, (v) => {
    safeSet(STORAGE.density, v);
    applyDensity(v);
  });
  media('(prefers-color-scheme: dark)')?.addEventListener('change', () => {
    if (themePref.value === 'system') applyMode('system');
  });
}

/** 明暗主题偏好（system／light／dark），修改即生效并记住。 */
export function useThemeMode(): { pref: Ref<ThemePref>; isDark: () => boolean; toggle: () => void } {
  init();
  return {
    pref: themePref,
    isDark: () => document.documentElement.classList.contains('dark'),
    toggle: () => {
      themePref.value = resolveMode(themePref.value) === 'dark' ? 'light' : 'dark';
    }
  };
}

/** 密度偏好。auto 时桌面列表紧凑、表单与移动端舒适。 */
export function useDensity(): { pref: Ref<DensityPref>; attrFor: (ctx: DensityContext) => 'compact' | 'comfortable' | undefined } {
  init();
  return {
    pref: densityPref,
    attrFor: (ctx) => (densityPref.value !== 'auto' ? undefined : ctx === 'list' ? 'compact' : 'comfortable')
  };
}

/** 设置品牌色并记住（管理员在偏好中心设置的系统品牌色由服务端下发后调用）。 */
export function setBrand(color: string | null) {
  safeSet(STORAGE.brand, color);
  applyBrand(color);
}

/** 读取 CSS 变量的计算值（供 canvas 图表取色）。 */
export function cssVar(name: string, el: Element = document.documentElement): string {
  return getComputedStyle(el).getPropertyValue(name).trim();
}

/** 首屏前执行的内联脚本（与上面的存储键一致）。index.html 中有同样的一份，单测比对两者一致。 */
export const BOOT_SCRIPT = `(function(){try{var d=document.documentElement,s=localStorage,t=s.getItem('${STORAGE.theme}')||'system',`
  + `m=t==='system'?(window.matchMedia&&matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'):t;`
  + `if(m==='dark')d.classList.add('dark');d.setAttribute('data-theme',m);var n=s.getItem('${STORAGE.density}');`
  + `if(n==='compact'||n==='comfortable')d.setAttribute('data-density',n);var b=s.getItem('${STORAGE.brand}');`
  + `if(b&&/^#[0-9a-fA-F]{6}$/.test(b))d.style.setProperty('--ls-color-primary',b);`
  + `if(s.getItem('${STORAGE.motion}')==='reduced')d.setAttribute('data-motion','reduced');}catch(e){}})();`;
