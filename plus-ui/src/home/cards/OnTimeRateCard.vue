<template>
  <HomeCard
    code="ls.home.onTimeRate"
    title="本周按期完成率"
    :link="link"
    link-text="看板"
    :loading="loading"
    :error="error"
    :empty="value === null"
    empty-text="本周还没有完成的工作"
    @retry="load"
  >
    <p class="kpi" data-testid="home-kpi">{{ pct }}<span class="unit">%</span></p>
    <p class="kpi-sub">按期 {{ numerator }} / 完成 {{ denominator }} 项（数据域内，周一至今）</p>
  </HomeCard>
</template>

<script setup lang="ts">
/** 关键指标：按期完成率（Metric ls.core.workOnTimeRate，本周）。 */
import { computed, onMounted, ref } from 'vue';
import { metricApi } from '@/api/lsmom/metric';
import HomeCard from '../HomeCard.vue';
import { firstAvailable } from '../useCardData';
import { useMenuPaths } from '../useMenuPaths';

const value = ref<number | null>(null);
const numerator = ref(0);
const denominator = ref(0);
const loading = ref(true);
const error = ref('');
const pct = computed(() => (value.value === null ? '—' : (value.value * 100).toFixed(1)));
const menu = useMenuPaths();
const link = computed(() => firstAvailable(['/production/dashboard'], menu.value));

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
async function load() {
  loading.value = true;
  error.value = '';
  try {
    const now = new Date();
    const monday = new Date(now);
    monday.setDate(now.getDate() - ((now.getDay() + 6) % 7));
    const r = await metricApi.query('ls.core.workOnTimeRate', { from: iso(monday), to: iso(now) });
    const row = r.rows[0];
    denominator.value = Number(row?.denominator ?? 0);
    numerator.value = Number(row?.numerator ?? 0);
    value.value = denominator.value ? Number(row?.value ?? 0) : null;
  } catch (e: any) {
    error.value = e?.message || '指标加载失败';
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>

<style scoped>
.kpi {
  margin: var(--ls-space-2) 0 0;
  font-size: 40px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.unit {
  font-size: 18px;
  margin-left: 2px;
}
.kpi-sub {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
