<template>
  <div class="m-app" data-testid="m-app">
    <header class="m-bar">
      <button v-if="back" type="button" class="m-bar__back" aria-label="返回" data-testid="m-back" @click="goBack">‹</button>
      <h1 class="m-bar__title">{{ title }}</h1>
      <router-link v-if="pending" to="/m/me" class="m-bar__sync" data-testid="m-pending">待同步 {{ pending }}</router-link>
    </header>
    <div v-if="!outbox.online" class="m-offline" role="status" data-testid="m-offline">当前离线：可以继续填写，提交会在恢复网络后自动同步</div>
    <main class="m-main">
      <router-view v-slot="{ Component }">
        <component :is="Component" @scan="openScan" />
      </router-view>
    </main>
    <nav class="m-tabs" aria-label="手机版导航">
      <router-link to="/m" class="m-tab" :class="{ 'is-on': route.path === '/m' }" data-testid="m-tab-home">
        <span aria-hidden="true">⌂</span>工作台
      </router-link>
      <button type="button" class="m-tab m-tab--scan" data-testid="m-tab-scan" @click="openScan()"><span aria-hidden="true">⌗</span>扫一扫</button>
      <router-link to="/m/me" class="m-tab" :class="{ 'is-on': route.path === '/m/me' }" data-testid="m-tab-me"
        ><span aria-hidden="true">☺</span>我的</router-link
      >
    </nav>
    <ScanDialog v-if="scanVisible" v-model="scanVisible" title="扫设备码" @scanned="onScanned" />
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import 'vant/lib/index.css';
import { flush, loadOutbox, outbox, watchOnline } from './outbox';
import { outboxSender } from './api';
import { normalizeCode } from '@/utils/scan/scanCode';
import { rememberOfflineUser } from './device';

/**
 * 手机版外壳（/m）：顶栏（返回、标题、待同步数）、离线提示、底部导航（工作台、扫一扫、我的）。
 * 不显示桌面管理菜单；离线队列在这里监听联网并自动同步。
 */
const ScanDialog = defineAsyncComponent(() => import('@/components/SchemaForm/ScanDialog.vue'));
const route = useRoute();
const router = useRouter();
const title = computed(() => (route.meta.title as string) || '工作台');
const back = computed(() => route.path !== '/m' && route.path !== '/m/me');
const pending = computed(() => outbox.items.length);

function goBack() {
  if (window.history.length > 1) router.back();
  else void router.push('/m');
}

const scanVisible = ref(false);
let scanTarget: ((code: string) => void) | null = null;
/** 扫一扫：默认按设备码进入点检；页面可传回调自己处理结果 */
function openScan(cb?: (code: string) => void) {
  scanTarget = cb ?? null;
  scanVisible.value = true;
}
function onScanned(text: string) {
  scanVisible.value = false;
  const code = normalizeCode(text);
  if (!code) return;
  if (scanTarget) return scanTarget(code);
  void router.push({ path: '/m/inspect', query: { code } });
}

let unwatch: (() => void) | null = null;
onMounted(async () => {
  if (navigator.onLine) rememberOfflineUser();
  await loadOutbox();
  unwatch = watchOnline(outboxSender);
  if (navigator.onLine) void flush(outboxSender);
});
onBeforeUnmount(() => unwatch?.());
</script>

<style>
/* 手机版：触控目标不小于 44px，主要动作在底部拇指区 */
.m-app {
  --m-tab-h: 56px;
  min-height: 100vh;
  min-height: 100dvh;
  background: var(--ls-color-bg, #f5f6f8);
  color: var(--ls-color-text, #1f2329);
  font-size: 16px;
  padding-bottom: calc(var(--m-tab-h) + env(safe-area-inset-bottom));
}
.m-app button,
.m-app a,
.m-app [role='button'],
.m-app input,
.m-app select {
  min-height: 44px;
}
.m-bar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 52px;
  padding: 0 12px;
  background: var(--ls-color-surface, #fff);
  border-bottom: 1px solid var(--ls-color-border, #e5e6eb);
}
.m-bar__title {
  flex: 1;
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.m-bar__back {
  min-width: 44px;
  border: 0;
  background: none;
  font-size: 28px;
  line-height: 1;
  color: inherit;
}
.m-bar__sync {
  display: inline-flex;
  align-items: center;
  padding: 0 12px;
  border-radius: 22px;
  background: var(--ls-color-warning-bg, #fff7e6);
  color: var(--ls-color-warning, #d46b08);
  font-size: 14px;
  text-decoration: none;
}
.m-offline {
  padding: 8px 12px;
  background: #fff7e6;
  color: #ad4e00;
  font-size: 14px;
}
.m-main {
  padding: 12px;
}
.m-tabs {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 30;
  display: flex;
  height: calc(var(--m-tab-h) + env(safe-area-inset-bottom));
  padding-bottom: env(safe-area-inset-bottom);
  background: var(--ls-color-surface, #fff);
  border-top: 1px solid var(--ls-color-border, #e5e6eb);
}
.m-tab {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  border: 0;
  background: none;
  color: var(--ls-color-text-secondary, #646a73);
  font-size: 12px;
  text-decoration: none;
}
.m-tab span {
  font-size: 20px;
  line-height: 1;
}
.m-tab.is-on {
  color: var(--ls-color-primary, #1677ff);
}
.m-tab--scan span {
  color: var(--ls-color-primary, #1677ff);
}
/* 页面底部固定的主操作区（在导航栏之上） */
.m-actions {
  position: fixed;
  left: 0;
  right: 0;
  bottom: calc(var(--m-tab-h) + env(safe-area-inset-bottom));
  z-index: 25;
  display: flex;
  gap: 12px;
  padding: 10px 12px;
  background: var(--ls-color-surface, #fff);
  border-top: 1px solid var(--ls-color-border, #e5e6eb);
}
.m-actions .m-btn {
  flex: 1;
}
.m-page--with-actions {
  padding-bottom: 76px;
}
.m-btn {
  min-height: 48px;
  border-radius: 10px;
  border: 1px solid var(--ls-color-border, #dcdfe6);
  background: var(--ls-color-surface, #fff);
  color: inherit;
  font-size: 16px;
  padding: 0 16px;
}
.m-btn--primary {
  background: var(--ls-color-primary, #1677ff);
  border-color: var(--ls-color-primary, #1677ff);
  color: #fff;
  font-weight: 600;
}
.m-btn--danger-text {
  border: 0;
  background: none;
  color: var(--ls-color-danger, #f53f3f);
  font-size: 14px;
}
.m-btn:disabled {
  opacity: 0.6;
}
.m-card {
  background: var(--ls-color-surface, #fff);
  border-radius: 12px;
  padding: 12px;
  margin-bottom: 12px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.m-section {
  margin: 16px 0 8px;
  font-size: 14px;
  color: var(--ls-color-text-secondary, #646a73);
}
.m-sub {
  font-size: 13px;
  color: var(--ls-color-text-secondary, #646a73);
}
.m-label {
  display: block;
  margin: 12px 0 6px;
  font-size: 15px;
  font-weight: 500;
}
.m-empty {
  padding: 24px 0;
  text-align: center;
  color: var(--ls-color-text-secondary, #8f959e);
}
.m-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
  background: var(--ls-color-fill, #f2f3f5);
}
.m-tag--warn {
  background: #fff7e6;
  color: #d46b08;
}
.m-tag--danger {
  background: #ffece8;
  color: #cb2634;
}
</style>
