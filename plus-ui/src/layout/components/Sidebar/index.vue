<template>
  <div :class="{ 'has-logo': showLogo }" :style="{ backgroundColor: bgColor }">
    <logo v-if="showLogo" :collapse="isCollapse" />
    <el-scrollbar :class="sideTheme" wrap-class="scrollbar-wrapper">
      <transition :enter-active-class="proxy?.animate.menuSearchAnimate.enter" mode="out-in">
        <el-menu
          :default-active="activeMenu"
          :collapse="isCollapse"
          :background-color="bgColor"
          :text-color="textColor"
          :unique-opened="true"
          :active-text-color="activeTextColor"
          :collapse-transition="false"
          :popper-offset="12"
          mode="vertical"
        >
          <sidebar-item v-for="(r, index) in sidebarRouters" :key="r.path + index" :item="r" :base-path="r.path" />
        </el-menu>
      </transition>
    </el-scrollbar>
  </div>
</template>

<script setup lang="ts">
import Logo from './Logo.vue';
import SidebarItem from './SidebarItem.vue';
import { useAppStore } from '@/store/modules/app';
import { useSettingsStore } from '@/store/modules/settings';
import { usePermissionStore } from '@/store/modules/permission';
import { mixGroupKey, useGroupedMenus } from '@/layout/useGroupedMenus';
import { NavTypeEnum } from '@/enums/NavTypeEnum';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const route = useRoute();
const appStore = useAppStore();
const settingsStore = useSettingsStore();
const permissionStore = usePermissionStore();
// 固定分组：工作 / 台账 / 分析 / 配置（最多三级）；混合布局下只显示顶栏所选分组的内容
const grouped = useGroupedMenus(() => permissionStore.getDefaultRoutes());
const sidebarRouters = computed(() =>
  settingsStore.navType === NavTypeEnum.MIX ? (grouped.value.find((g) => g.path === mixGroupKey.value)?.children ?? []) : grouped.value
);
const showLogo = computed(() => settingsStore.sidebarLogo);
const sideTheme = computed(() => settingsStore.sideTheme);
const theme = computed(() => settingsStore.theme);
const isCollapse = computed(() => !appStore.sidebar.opened);

const activeMenu = computed(() => {
  const { meta, path } = route;
  // if set path, the sidebar will highlight the path you set
  if (meta.activeMenu) {
    return meta.activeMenu;
  }
  return path;
});

// 颜色全部来自设计令牌（明暗主题自动切换）
const dark = computed(() => sideTheme.value === 'theme-dark');
const bgColor = computed(() => (dark.value ? 'var(--ls-color-sidebar-bg)' : 'var(--ls-color-bg)'));
const textColor = computed(() => (dark.value ? 'var(--ls-color-sidebar-text)' : 'var(--ls-color-text-regular)'));
// 上游样式把「激活文字色」用作当前菜单的底色（文字固定白色），所以这里给的是底色令牌
const activeTextColor = computed(() => (dark.value ? 'var(--ls-color-sidebar-active-bg)' : 'var(--ls-color-primary)'));
</script>
