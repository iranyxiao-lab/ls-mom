/**
 * 最近访问与收藏（页面与单据）：命令面板空输入时展示，侧栏优先展示收藏。
 * 最近访问保留 30 条（至少 20 条的要求留有余量），同一地址只保留最新一次。
 */
import { defineStore } from 'pinia';
import { ref } from 'vue';

export interface NavEntry {
  /** page 页面；object 单据或对象 */
  kind: 'page' | 'object';
  path: string;
  title: string;
  /** 所在分组或对象类型，例如"台账 / 设备"、"工作" */
  sub?: string;
  icon?: string;
  at: number;
}

export const RECENT_MAX = 30;
const KEY = 'ls-nav-history';

function load(): { recent: NavEntry[]; favorites: NavEntry[] } {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) || '{}');
    return { recent: Array.isArray(v.recent) ? v.recent : [], favorites: Array.isArray(v.favorites) ? v.favorites : [] };
  } catch {
    return { recent: [], favorites: [] };
  }
}

export const useNavHistoryStore = defineStore('navHistory', () => {
  const init = load();
  const recent = ref<NavEntry[]>(init.recent);
  const favorites = ref<NavEntry[]>(init.favorites);

  const save = () => {
    try {
      localStorage.setItem(KEY, JSON.stringify({ recent: recent.value, favorites: favorites.value }));
    } catch {
      /* 隐私模式等无法写入时只在内存中保留 */
    }
  };

  function visit(e: Omit<NavEntry, 'at'>) {
    if (!e.path || !e.title) return;
    recent.value = [{ ...e, at: Date.now() }, ...recent.value.filter((r) => r.path !== e.path)].slice(0, RECENT_MAX);
    save();
  }
  const isFavorite = (path: string) => favorites.value.some((f) => f.path === path);
  function toggleFavorite(e: Omit<NavEntry, 'at'>) {
    favorites.value = isFavorite(e.path) ? favorites.value.filter((f) => f.path !== e.path) : [{ ...e, at: Date.now() }, ...favorites.value];
    save();
  }
  /** 由服务端同步的数据整体替换（偏好中心）。 */
  function replace(next: { recent?: NavEntry[]; favorites?: NavEntry[] }) {
    if (next.recent) recent.value = next.recent.slice(0, RECENT_MAX);
    if (next.favorites) favorites.value = next.favorites;
    save();
  }
  return { recent, favorites, visit, isFavorite, toggleFavorite, replace };
});
