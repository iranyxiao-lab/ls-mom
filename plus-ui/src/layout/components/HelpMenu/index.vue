<template>
  <el-dropdown class="right-menu-item hover-effect" trigger="click" data-testid="help-menu" @command="onCommand">
    <div class="help-trigger" role="button" aria-label="帮助" tabindex="0" data-testid="help-open">
      <span class="help-icon" aria-hidden="true">?</span>
    </div>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-if="tour" command="tour" data-testid="help-tour">重看本页引导（{{ tour.name }}）</el-dropdown-item>
        <el-dropdown-item command="sop" data-testid="help-sop">{{ tour ? `操作手册：${tour.name}` : '操作手册与知识问答' }}</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { tourFor } from '@/guide/tours';
import { autoTour, startTour } from '@/guide/tour';

/** 顶栏帮助：重看本页新手引导、打开对应的操作手册（知识中心检索）；进入有引导的页面时首次自动开始。 */
const route = useRoute();
const router = useRouter();
const tour = computed(() => tourFor(route.path));

watch(
  () => route.path,
  () => {
    if (tour.value) void autoTour(tour.value.key);
  },
  { immediate: true }
);

function onCommand(c: string) {
  if (c === 'tour' && tour.value) void startTour(tour.value.key);
  if (c === 'sop') void router.push({ path: '/agent/knowledge', query: tour.value ? { q: tour.value.sop } : {} });
}
</script>

<style scoped>
.help-trigger {
  display: flex;
  align-items: center;
  height: 100%;
}
.help-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 1.5px solid currentColor;
  font-size: 12px;
  font-weight: 600;
}
</style>
