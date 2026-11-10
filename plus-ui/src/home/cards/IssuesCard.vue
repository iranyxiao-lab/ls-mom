<template>
  <HomeCard
    code="ls.home.openIssues"
    title="未关闭异常"
    :count="total"
    :link="link"
    :loading="loading"
    :error="error"
    :empty="!rows.length"
    empty-text="没有未关闭的异常"
    @retry="load"
  >
    <ul class="hc-list">
      <li v-for="i in rows" :key="i.id">
        <router-link :to="link ?? '/index'" class="hc-row" data-testid="home-row">
          <span class="hc-code">{{ i.code }}</span>
          <span class="hc-title">{{ i.title }}</span>
          <LsStatusTag :status="i.severity" :label="SEVERITY[i.severity] ?? i.severity" />
        </router-link>
      </li>
    </ul>
  </HomeCard>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { LsStatusTag } from '@/components/LsState';
import HomeCard from '../HomeCard.vue';
import { firstAvailable, useQueryCard } from '../useCardData';
import { useMenuPaths } from '../useMenuPaths';

const SEVERITY: Record<string, string> = { CRITICAL: '紧急', HIGH: '高', MEDIUM: '中', LOW: '低' };
const { rows, total, loading, error, load } = useQueryCard('ls.ui.homeOpenIssues');
const menu = useMenuPaths();
const link = computed(() => firstAvailable(['/quality/nc', '/asset/defects'], menu.value));
</script>

<style scoped src="./list.css"></style>
