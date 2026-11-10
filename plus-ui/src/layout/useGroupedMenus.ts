import { computed, ref, type ComputedRef } from 'vue';
import type { RouteRecordRaw } from 'vue-router';
import request from '@/utils/request';
import { useUserStore } from '@/store/modules/user';
import { useNavHistoryStore } from '@/store/modules/navHistory';
import { groupMenus, type PackMenuEntry } from './menuGroups';

const entries = ref<PackMenuEntry[]>([]);
/** 混合布局下顶栏选中的分组（侧栏显示该分组的内容）。 */
export const mixGroupKey = ref<string>();
let loaded = false;

/** 重新读取 Pack 入口（安装、启用、停用 Pack 后调用）。 */
export async function reloadPackMenu(): Promise<void> {
  try {
    const res: any = await request({ url: '/api/packs/menu', method: 'get' });
    entries.value = (res.data ?? []) as PackMenuEntry[];
  } catch {
    /* 读取失败时只显示后端菜单 */
  }
}

/** 固定分组后的导航菜单（侧栏与顶栏共用）。 */
export function useGroupedMenus(source: () => RouteRecordRaw[]): ComputedRef<RouteRecordRaw[]> {
  if (!loaded) {
    loaded = true;
    void reloadPackMenu();
  }
  const user = useUserStore();
  const history = useNavHistoryStore();
  return computed(() => withFavorites(groupMenus(source(), entries.value, user.permissions), history.favorites));
}

/** 收藏分组的路径（命令面板等需要区分时用）。 */
export const FAVORITES_GROUP = '/menu-group/fav';

/** 收藏放在首页之后、固定分组之前（侧栏优先展示）。带查询参数的收藏（台账记录）只在命令面板中显示。 */
function withFavorites(menus: RouteRecordRaw[], favorites: { path: string; title: string; icon?: string }[]): RouteRecordRaw[] {
  const items = favorites.filter((f) => !f.path.includes('?')).slice(0, 15);
  if (!items.length) return menus;
  const fav = {
    path: FAVORITES_GROUP,
    alwaysShow: true,
    meta: { title: '收藏', icon: 'star' },
    children: items.map((f) => ({ path: f.path, meta: { title: f.title, icon: f.icon || 'link' } }))
  } as unknown as RouteRecordRaw;
  const at = menus.findIndex((r) => r.path.startsWith('/menu-group/'));
  return at < 0 ? [...menus, fav] : [...menus.slice(0, at), fav, ...menus.slice(at)];
}
