import { ref } from 'vue';
import { getPrefs, putPrefs, type UiPrefs } from '@/api/lsmom/uiPrefs';
import { TOURS, type TourDef, type TourKey } from './tours';

type TourState = 'done' | 'skipped' | 'pending';
type Prefs = UiPrefs & Partial<Record<TourDef['pref'], TourState>>;

/** 正在进行的引导（测试与帮助菜单用） */
export const activeTour = ref<TourKey | null>(null);

let prefs: Promise<Prefs> | null = null;
const loadPrefs = () => (prefs ??= getPrefs().then((r) => r.prefs as Prefs).catch(() => ({}) as Prefs));
let destroyActive: (() => void) | null = null;

async function record(def: TourDef, state: TourState) {
  const p = await loadPrefs();
  p[def.pref] = state;
  try {
    await putPrefs({ [def.pref]: state } as UiPrefs);
  } catch {
    /* 记不上只影响下次是否自动出现 */
  }
}

const find = (anchor?: string) => (anchor ? (document.querySelector(`[data-tour="${anchor}"]`) as HTMLElement | null) : null);

/** 开始引导（帮助菜单「重看本页引导」也调用它）。driver.js 按需加载，不进首屏包。 */
export async function startTour(key: TourKey) {
  const def = TOURS.find((t) => t.key === key);
  if (!def) return;
  destroyActive?.();
  const [{ driver }] = await Promise.all([import('driver.js'), import('driver.js/dist/driver.css')]);
  let ended = false;
  // 收尾只做一次：看完记 done，跳过、关闭、按 Esc、点遮罩都记 skipped
  const end = (state: TourState) => {
    if (ended) return;
    ended = true;
    activeTour.value = null;
    destroyActive = null;
    void record(def, state);
    d.destroy();
  };
  const d = driver({
    showProgress: true,
    progressText: '{{current}} / {{total}}',
    nextBtnText: '下一步',
    prevBtnText: '上一步',
    doneBtnText: '知道了',
    allowClose: true,
    popoverClass: 'ls-tour',
    steps: def.steps.map((s, i) => {
      const el = find(s.anchor);
      return {
        ...(el ? { element: el } : {}),
        popover: {
          title: s.title,
          description: s.text,
          onNextClick: () => (i === def.steps.length - 1 ? end('done') : d.moveNext())
        }
      };
    }),
    onPopoverRender: (popover) => {
      popover.wrapper.setAttribute('data-testid', 'tour-popover');
      popover.nextButton.setAttribute('data-testid', 'tour-next');
      const skip = document.createElement('button');
      skip.type = 'button';
      skip.textContent = '跳过';
      skip.className = 'ls-tour__skip';
      skip.setAttribute('data-testid', 'tour-skip');
      skip.addEventListener('click', () => end('skipped'));
      popover.footer.insertBefore(skip, popover.footer.firstChild);
    },
    onDestroyStarted: () => end('skipped')
  });
  destroyActive = () => end('skipped');
  activeTour.value = key;
  d.drive();
}

/** 首次进入页面时自动开始；看完或跳过后不再自动出现 */
export async function autoTour(key: TourKey) {
  const def = TOURS.find((t) => t.key === key)!;
  const p = await loadPrefs();
  if (p[def.pref] === 'done' || p[def.pref] === 'skipped') return;
  // 等页面根元素渲染出来
  for (let i = 0; i < 40 && !document.querySelector(def.root); i++) await new Promise((r) => setTimeout(r, 100));
  if (!document.querySelector(def.root) || activeTour.value) return;
  await startTour(key);
}

/** 测试用：清掉缓存的偏好 */
export function resetTourCache() {
  prefs = null;
}
