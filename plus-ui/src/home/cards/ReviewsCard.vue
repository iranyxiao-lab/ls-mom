<template>
  <div class="hc-wrap">
    <HomeCard
      code="ls.home.myReviews"
      title="待我审核"
      :count="total"
      link="/lsmom/workbench"
      :loading="loading"
      :error="error"
      :empty="!rows.length"
      empty-text="没有等你处理的审核"
      @retry="load"
    >
      <ul class="hc-list">
        <li v-for="t in rows" :key="t.taskId">
          <a :href="taskHref(t.taskId)" class="hc-row" data-testid="home-row" @click="(e) => onRow(e, t.taskId)">
            <span class="hc-title">{{ t.nodeName || '待办' }} · {{ bizLabel(t.bizType) }} {{ t.bizId }}</span>
            <span :class="['hc-sub', { 'hc-overdue': t.overdue }]">{{ t.overdue ? '已逾期' : due(t.dueAt) }}</span>
          </a>
        </li>
      </ul>
    </HomeCard>
    <!-- 原地办理：不离开首页，办完自动打开下一条（放在卡片外，卡片刷新时不重建） -->
    <TaskPanel v-if="panelOn" ref="panel" :api="lazyFlowProgressApi" :actions="lazyFlowProgressApi" :queue="queue" @done="load" />
  </div>
</template>

<script setup lang="ts">
import { defineAsyncComponent, nextTick, ref } from 'vue';
import { useRouter } from 'vue-router';
import HomeCard from '../HomeCard.vue';
import { useQueryCard } from '../useCardData';
import { lazyFlowProgressApi } from '@/api/lsmom/flowProgress';

// 办理面板按需加载，首页首屏不带表单渲染器
const TaskPanel = defineAsyncComponent(() => import('@/views/lsmom/workbench/TaskPanel.vue'));
const panelOn = ref(false);
const panel = ref<{ open: (taskId: string) => Promise<void> } | null>(null);
const router = useRouter();
const taskHref = (taskId: string) => router.resolve({ path: '/lsmom/workbench', query: { task: taskId } }).href;
/** 普通点击在首页原地办理；Ctrl/⌘/中键等仍按链接在新页签打开 */
function onRow(e: MouseEvent, taskId: string) {
  if (e.ctrlKey || e.metaKey || e.shiftKey || e.altKey || e.button !== 0) return;
  e.preventDefault();
  void openHere(taskId);
}
async function openHere(taskId: string) {
  panelOn.value = true;
  for (let i = 0; i < 50 && !panel.value; i++) await nextTick().then(() => new Promise((r) => setTimeout(r, 20)));
  await panel.value?.open(taskId);
}
/** 本人当前待办（与工作台同序） */
const queue = async () => {
  const { workbenchApi } = await import('@/api/lsmom/workbench');
  return (await workbenchApi.todo({})).filter((i) => i.task.kind !== 'CC').map((i) => i.task.taskId);
};

const { rows, total, loading, error, load } = useQueryCard('ls.ui.homeMyReviews');
const LABEL: Record<string, string> = {
  'ls.quality.NonConformance': '不符合项',
  'ls.production.ProductionRequest': '生产需求',
  'ls.asset.Defect': '设备缺陷',
  'ls.consultation.Topic': '议题'
};
const bizLabel = (t: string) => LABEL[t] ?? t?.split('.').pop() ?? '';
const due = (d?: string) => (d ? `${String(d).slice(5, 16).replace('T', ' ')} 前` : '');
</script>

<style scoped src="./list.css"></style>
<style scoped>
.hc-wrap {
  display: contents;
}
</style>
