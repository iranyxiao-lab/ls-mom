import { computed, ref, type ComputedRef } from 'vue';
import type { RouteRecordRaw } from 'vue-router';
import request from '@/utils/request';
import { useUserStore } from '@/store/modules/user';
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
  return computed(() => groupMenus(source(), entries.value, user.permissions));
}
