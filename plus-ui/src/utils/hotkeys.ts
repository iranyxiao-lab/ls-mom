import { onBeforeUnmount, onMounted, ref } from 'vue';

/** 是否显示快捷键提示（偏好中心可关闭；由 prefSync 与服务端同步）。不依赖请求模块，基础组件可直接使用。 */
export const hotkeyHints = ref(readHints());

function readHints() {
  try {
    return localStorage.getItem('ls-hotkey-hints') !== 'false';
  } catch {
    return true;
  }
}

export const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

/** 组合键是否匹配，写法如 mod+s、mod+enter、escape（mod 为 Ctrl，Mac 上也接受 ⌘）。 */
export function matchHotkey(e: KeyboardEvent, combo: string): boolean {
  const parts = combo.toLowerCase().split('+');
  const key = parts.pop()!;
  const mod = parts.includes('mod');
  const shift = parts.includes('shift');
  const alt = parts.includes('alt');
  if (mod !== (e.ctrlKey || e.metaKey)) return false;
  if (shift !== e.shiftKey || alt !== e.altKey) return false;
  return e.key.toLowerCase() === key || (key === 'enter' && e.key === 'Enter') || (key === 'escape' && e.key === 'Escape');
}

/** 显示用的快捷键文字：Ctrl+S / ⌘S。 */
export function hotkeyLabel(combo: string): string {
  const parts = combo.split('+');
  const key = parts.pop()!;
  const k = key === 'enter' ? 'Enter' : key.length === 1 ? key.toUpperCase() : key;
  const mods = parts.map((p) => (p === 'mod' ? (isMac ? '⌘' : 'Ctrl') : p === 'shift' ? (isMac ? '⇧' : 'Shift') : isMac ? '⌥' : 'Alt'));
  return isMac ? mods.join('') + k : [...mods, k].join('+');
}

const visible = (el: HTMLElement) => el.isConnected && el.getClientRects().length > 0;

/**
 * 在一个区域内注册快捷键：焦点在区域内（或区域可见且焦点不在任何输入框）时生效，触发后阻止浏览器默认动作（如 Ctrl+S 保存网页）。
 * 多个区域同时可见时，焦点所在的区域优先；都不含焦点时最后注册的优先。
 */
type Entry = { scope: () => HTMLElement | null | undefined; bindings: Record<string, (e: KeyboardEvent) => void>; enabled: () => boolean };
const entries: Entry[] = [];

function onKey(e: KeyboardEvent) {
  if (e.defaultPrevented) return;
  const active = document.activeElement as HTMLElement | null;
  const live = entries.filter((x) => x.enabled() && x.scope() && visible(x.scope()!));
  const owner = live.find((x) => active && x.scope()!.contains(active)) ?? (active && active !== document.body ? undefined : live[live.length - 1]);
  if (!owner) return;
  for (const [combo, fn] of Object.entries(owner.bindings)) {
    if (matchHotkey(e, combo)) {
      e.preventDefault();
      e.stopPropagation();
      fn(e);
      return;
    }
  }
}

export function useHotkeys(scope: Entry['scope'], bindings: Entry['bindings'], enabled: () => boolean = () => true) {
  const entry: Entry = { scope, bindings, enabled };
  onMounted(() => {
    if (!entries.length) window.addEventListener('keydown', onKey, true);
    entries.push(entry);
  });
  onBeforeUnmount(() => {
    const i = entries.indexOf(entry);
    if (i >= 0) entries.splice(i, 1);
    if (!entries.length) window.removeEventListener('keydown', onKey, true);
  });
}
