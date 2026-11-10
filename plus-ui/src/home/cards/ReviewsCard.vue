<template>
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
        <router-link :to="{ path: '/lsmom/workbench', query: { task: t.taskId } }" class="hc-row" data-testid="home-row">
          <span class="hc-title">{{ t.nodeName || '待办' }} · {{ bizLabel(t.bizType) }} {{ t.bizId }}</span>
          <span :class="['hc-sub', { 'hc-overdue': t.overdue }]">{{ t.overdue ? '已逾期' : due(t.dueAt) }}</span>
        </router-link>
      </li>
    </ul>
  </HomeCard>
</template>

<script setup lang="ts">
import HomeCard from '../HomeCard.vue';
import { useQueryCard } from '../useCardData';

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
