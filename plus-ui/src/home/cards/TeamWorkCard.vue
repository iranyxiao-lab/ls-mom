<template>
  <HomeCard
    code="ls.home.teamWork"
    title="本班组今日工作"
    :count="total"
    :link="link"
    :loading="loading"
    :error="error"
    :empty="!rows.length"
    empty-text="今天没有安排的工作"
    @retry="load"
  >
    <ul class="hc-list">
      <li v-for="w in rows" :key="w.id">
        <router-link :to="`/lsmom/object/work/${w.id}`" class="hc-row" data-testid="home-row">
          <span class="hc-code">{{ w.code }}</span>
          <span class="hc-title">{{ w.title }}</span>
          <LsStatusTag :status="w.status" :label="WORK_STATUS[w.status] ?? w.status" />
        </router-link>
      </li>
    </ul>
  </HomeCard>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { LsStatusTag } from '@/components/LsState';
import { WORK_STATUS } from '@/api/lsmom/productionModel';
import HomeCard from '../HomeCard.vue';
import { firstAvailable, useQueryCard } from '../useCardData';
import { useMenuPaths } from '../useMenuPaths';

const { rows, total, loading, error, load } = useQueryCard('ls.ui.homeTeamWork');
const menu = useMenuPaths();
const link = computed(() => firstAvailable(['/production/board', '/production/dispatch'], menu.value) ?? '/lsmom/workbench');
</script>

<style scoped src="./list.css"></style>
