<template>
  <!-- 混合布局：顶栏显示首页与固定分组，侧栏显示所选分组下的模块与页面 -->
  <el-menu :default-active="activeKey" mode="horizontal" :ellipsis="false" @select="handleSelect">
    <el-menu-item v-for="item in topMenus" :key="item.path" :index="item.path"
      ><svg-icon v-if="item.meta?.icon && item.meta.icon !== '#'" :icon-class="String(item.meta.icon)" /> {{ item.meta?.title }}</el-menu-item
    >
  </el-menu>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue';
import { useRoute, useRouter, type RouteRecordRaw } from 'vue-router';
import { useAppStore } from '@/store/modules/app';
import { usePermissionStore } from '@/store/modules/permission';
import { mixGroupKey, useGroupedMenus, FAVORITES_GROUP } from '@/layout/useGroupedMenus';

const appStore = useAppStore();
const permissionStore = usePermissionStore();
const route = useRoute();
const router = useRouter();

const grouped = useGroupedMenus(() => permissionStore.getDefaultRoutes());
/** 顶栏项：首页等单页面入口取其页面，分组取分组本身。 */
const topMenus = computed<RouteRecordRaw[]>(() =>
  grouped.value.filter((r) => !(r as any).hidden).map((r) => (r.path.startsWith('/menu-group/') ? r : (r.children?.[0] ?? r)))
);
const contains = (r: RouteRecordRaw, path: string): boolean => r.path === path || (r.children ?? []).some((c) => contains(c, path));

// 当前页面所在的分组；首页等不在分组中的页面隐藏侧栏
// 收藏的页面同时在所属分组里：优先高亮所属分组
const activeKey = computed(
  () =>
    (
      grouped.value.find((g) => g.path.startsWith('/menu-group/') && g.path !== FAVORITES_GROUP && contains(g, route.path)) ??
      grouped.value.find((g) => g.path === FAVORITES_GROUP && contains(g, route.path))
    )?.path ?? route.path
);
watch(
  activeKey,
  (k) => {
    if (k.startsWith('/menu-group/')) {
      mixGroupKey.value = k;
      appStore.toggleSideBarHide(false);
    } else {
      appStore.toggleSideBarHide(true);
    }
  },
  { immediate: true }
);

const handleSelect = (key: string) => {
  if (key.startsWith('/menu-group/')) {
    mixGroupKey.value = key;
    appStore.toggleSideBarHide(false);
    return;
  }
  appStore.toggleSideBarHide(true);
  router.push(key);
};
</script>

<style lang="scss">
.topmenu-container.el-menu--horizontal > .el-menu-item {
  float: left;
  height: 50px !important;
  line-height: 50px !important;
  color: var(--ls-color-text-secondary) !important;
  padding: 0 5px !important;
  margin: 0 10px !important;
}

.topmenu-container.el-menu--horizontal > .el-menu-item.is-active,
.el-menu--horizontal > .el-sub-menu.is-active .el-submenu__title {
  border-bottom: 2px solid var(--ls-color-primary) !important;
  color: var(--ls-color-text);
}

/* sub-menu item */
.topmenu-container.el-menu--horizontal > .el-sub-menu .el-sub-menu__title {
  float: left;
  height: 50px !important;
  line-height: 50px !important;
  color: var(--ls-color-text-secondary) !important;
  padding: 0 5px !important;
  margin: 0 10px !important;
}

/* 背景色隐藏 */
.topmenu-container.el-menu--horizontal > .el-menu-item:not(.is-disabled):focus,
.topmenu-container.el-menu--horizontal > .el-menu-item:not(.is-disabled):hover,
.topmenu-container.el-menu--horizontal > .el-submenu .el-submenu__title:hover {
  background-color: var(--ls-color-header-bg) !important;
}

/* 图标右间距 */
.topmenu-container .svg-icon {
  margin-right: 4px;
}
</style>
