<template>
  <div
    class="right-menu-item hover-effect task-trigger"
    role="button"
    aria-label="任务中心"
    tabindex="0"
    data-testid="tasks-open"
    @click="openTaskCenter()"
    @keydown.enter="openTaskCenter()"
  >
    <el-badge :value="runningCount" :hidden="!runningCount" :max="99" data-testid="tasks-running">
      <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" class="task-icon" :class="{ 'task-icon--spin': runningCount }">
        <path
          fill="currentColor"
          d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46A7.93 7.93 0 0 0 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74A7.93 7.93 0 0 0 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z"
        />
      </svg>
    </el-badge>
  </div>
  <task-drawer v-if="opened" />
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue';
import { openTaskCenter, refreshTasks, running, taskCenter } from '@/utils/taskCenter';

/** 顶栏任务中心：进行中的任务数；抽屉首次打开时才加载，不进首屏包。 */
const TaskDrawer = defineAsyncComponent(() => import('./TaskDrawer.vue'));
const runningCount = computed(() => taskCenter.tasks.filter(running).length);
const opened = ref(false);
watch(
  () => taskCenter.open,
  (v) => {
    if (v) opened.value = true;
  },
  { immediate: true }
);

// 登录后拉一次：有进行中的任务会继续轮询，离线期间完成的任务补发通知
onMounted(() => void refreshTasks());
</script>

<style scoped>
.task-trigger {
  display: inline-flex;
  align-items: center;
  padding: 0 8px;
}
.task-icon {
  color: var(--el-text-color-regular);
}
.task-icon--spin {
  animation: task-spin 1.6s linear infinite;
  color: var(--el-color-primary);
}
@keyframes task-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .task-icon--spin {
    animation: none;
  }
}
</style>
