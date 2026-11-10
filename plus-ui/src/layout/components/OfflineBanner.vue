<template>
  <div v-if="offline" class="ls-offline" role="status" aria-live="polite" data-testid="offline-banner">
    <strong>网络已断开</strong>
    <span>正在编辑的内容还在，恢复连接后再保存；需要服务端的数据暂时无法加载。</span>
  </div>
  <div v-else-if="back" class="ls-offline ls-offline--back" role="status" aria-live="polite" data-testid="online-banner">网络已恢复</div>
</template>

<script setup lang="ts">
/** 断网提示：断开时在页面顶部常驻说明，恢复后提示 3 秒。 */
import { onBeforeUnmount, onMounted, ref } from 'vue';

const offline = ref(typeof navigator !== 'undefined' && !navigator.onLine);
const back = ref(false);
let timer: ReturnType<typeof setTimeout> | undefined;
const onOffline = () => {
  offline.value = true;
  back.value = false;
};
const onOnline = () => {
  offline.value = false;
  back.value = true;
  clearTimeout(timer);
  timer = setTimeout(() => (back.value = false), 3000);
};
onMounted(() => {
  window.addEventListener('offline', onOffline);
  window.addEventListener('online', onOnline);
});
onBeforeUnmount(() => {
  window.removeEventListener('offline', onOffline);
  window.removeEventListener('online', onOnline);
  clearTimeout(timer);
});
</script>

<style scoped>
.ls-offline {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ls-space-2);
  align-items: center;
  padding: var(--ls-space-2) var(--ls-space-4);
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
  border-bottom: 1px solid var(--el-color-warning-light-5);
  font-size: 13px;
}
.ls-offline--back {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success-dark-2);
  border-bottom-color: var(--el-color-success-light-5);
}
</style>
