import { computed } from 'vue';
import { usePermissionStore } from '@/store/modules/permission';
import { menuLeaves, useGroupedMenus } from '@/layout/useGroupedMenus';

/** 当前用户菜单里的全部页面地址。 */
export function useMenuPaths() {
  const permissionStore = usePermissionStore();
  const grouped = useGroupedMenus(() => permissionStore.getDefaultRoutes());
  return computed(() => new Set(menuLeaves(grouped.value).map((m) => m.path)));
}
