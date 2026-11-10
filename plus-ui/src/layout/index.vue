<template>
  <div :class="classObj" class="app-wrapper" :style="{ '--current-color': theme }">
    <div v-if="device === 'mobile' && sidebar.opened" class="drawer-bg" @click="handleClickOutside" />
    <side-bar v-if="!sidebar.hide" class="sidebar-container" />
    <div :class="{ hasTagsView: needTagsView, sidebarHide: sidebar.hide }" class="main-container">
      <!-- <el-scrollbar>
        <div :class="{ 'fixed-header': fixedHeader }">
          <navbar ref="navbarRef" @setLayout="setLayout" />
          <tags-view v-if="needTagsView" />
        </div>
        <app-main />
        <settings ref="settingRef" />
      </el-scrollbar> -->
      <div :class="{ 'fixed-header': fixedHeader }">
        <navbar ref="navbarRef" @set-layout="setLayout" />
        <tags-view v-if="needTagsView" />
      </div>
      <app-main />
      <settings ref="settingRef" />
    </div>
  </div>
</template>

<script setup lang="ts">
import SideBar from './components/Sidebar/index.vue';
import { AppMain, Navbar, Settings, TagsView } from './components';
import { useAppStore } from '@/store/modules/app';
import { useSettingsStore } from '@/store/modules/settings';
import { NavTypeEnum } from '@/enums/NavTypeEnum';
import { initWebSocket } from '@/utils/websocket';
import { initSSE } from '@/utils/sse';
import { breakpoints } from '@/design/tokens';
import { useStorage, useWindowSize } from '@vueuse/core';

const settingsStore = useSettingsStore();
const theme = computed(() => settingsStore.theme);
const sidebar = computed(() => useAppStore().sidebar);
const device = computed(() => useAppStore().device);
const needTagsView = computed(() => settingsStore.tagsView);
const fixedHeader = computed(() => settingsStore.fixedHeader);
const layout = computed(() => settingsStore.navType);

// 根据布局模式判断是否显示侧边栏
const showSidebar = computed(() => {
  if (sidebar.value.hide) return false;
  return layout.value === NavTypeEnum.LEFT || layout.value === NavTypeEnum.MIX;
});

const classObj = computed(() => ({
  hideSidebar: !sidebar.value.opened,
  openSidebar: sidebar.value.opened,
  withoutAnimation: sidebar.value.withoutAnimation,
  mobile: device.value === 'mobile'
}));

// 三档断点（与设计令牌一致）：手机 <768 侧栏为抽屉；平板 768～1279 侧栏收起为图标栏；桌面 ≥1280 按用户上次的展开状态
const { width } = useWindowSize();
const sidebarPref = useStorage('sidebarStatus', '1');
const bp = computed(() => (width.value < breakpoints.tablet ? 'mobile' : width.value < breakpoints.desktop ? 'tablet' : 'desktop'));

watch(
  bp,
  (b, prev) => {
    const app = useAppStore();
    document.documentElement.dataset.breakpoint = b;
    if (b === 'mobile') {
      app.toggleDevice('mobile');
      app.sidebar.opened = false;
      app.sidebar.withoutAnimation = true;
      return;
    }
    app.toggleDevice('desktop');
    if (b === 'tablet') {
      // 只收起，不改写用户的展开偏好；回到桌面宽度时恢复
      app.sidebar.opened = false;
      app.sidebar.withoutAnimation = prev === undefined;
    } else {
      app.sidebar.opened = sidebarPref.value !== '0';
    }
  },
  { immediate: true }
);

const navbarRef = ref<InstanceType<typeof Navbar>>();
const settingRef = ref<InstanceType<typeof Settings>>();

onMounted(() => {
  nextTick(() => {
    navbarRef.value?.initTenantList();
  });
});

onMounted(() => {
  const protocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
  initWebSocket(protocol + window.location.host + import.meta.env.VITE_APP_BASE_API + '/resource/websocket');
});

onMounted(() => {
  initSSE(import.meta.env.VITE_APP_BASE_API + '/resource/sse');
});

const handleClickOutside = () => {
  const app = useAppStore();
  app.sidebar.opened = false;
  app.sidebar.withoutAnimation = false;
};

const setLayout = () => {
  settingRef.value?.openSetting();
};
</script>

<style lang="scss" scoped>
@use '@/assets/styles/mixin.scss';
@use '@/assets/styles/variables.module.scss' as *;

.app-wrapper {
  @include mixin.clearfix;
  position: relative;
  height: 100%;
  width: 100%;

  &.mobile.openSidebar {
    position: fixed;
    top: 0;
  }
}

.drawer-bg {
  background: #000;
  opacity: 0.3;
  width: 100%;
  top: 0;
  height: 100%;
  position: absolute;
  z-index: 999;
}

.fixed-header {
  position: fixed;
  top: 0;
  right: 0;
  z-index: 9;
  width: calc(100% - #{$base-sidebar-width});
  transition: width 0.28s;
  background: $fixed-header-bg;
  box-shadow: 0 2px 8px rgba(0, 21, 41, 0.1);
}

.hideSidebar .fixed-header {
  width: calc(100% - 54px);
}

.sidebarHide .fixed-header {
  width: 100%;
}

.mobile .fixed-header {
  width: 100%;
}
</style>
