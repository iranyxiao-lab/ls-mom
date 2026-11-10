<template>
  <svg :viewBox="`0 0 ${W} ${H}`" class="trend" role="img" :aria-label="label" data-testid="trend-chart">
    <rect x="0" y="0" :width="W" :height="H" class="bg" />
    <path v-if="band" :d="band" class="band" />
    <path v-if="line" :d="line" class="line" data-testid="trend-line" />
    <line v-if="markX !== undefined" :x1="markX" :x2="markX" y1="0" :y2="H" class="mark" data-testid="trend-mark" />
    <text x="4" y="12" class="axis">{{ fmt(max) }}</text>
    <text x="4" :y="H - 4" class="axis">{{ fmt(min) }}</text>
    <text v-if="!points.length" :x="W / 2" :y="H / 2" class="empty" text-anchor="middle">暂无快照</text>
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { Snapshot } from '@/api/lsmom/dcsModel';
import { num } from '@/api/lsmom/dcsModel';

/** 快照趋势：均值折线 + 最小 / 最大区间；mark 为报警时间。只画降采样快照，不画原始高频值。 */
const props = withDefaults(defineProps<{ snapshots: Snapshot[]; mark?: string; label?: string }>(), { mark: undefined, label: '快照趋势' });

const W = 640;
const H = 160;
const points = computed(() => props.snapshots.filter((s) => s.avg_value !== null && s.avg_value !== undefined));
const t0 = computed(() => (props.snapshots.length ? Date.parse(props.snapshots[0].bucket_start) : 0));
const t1 = computed(() => (props.snapshots.length ? Date.parse(props.snapshots[props.snapshots.length - 1].bucket_start) : 1));
const min = computed(() => Math.min(...points.value.map((s) => s.min_value ?? s.avg_value!)));
const max = computed(() => Math.max(...points.value.map((s) => s.max_value ?? s.avg_value!)));

const x = (iso: string) => ((Date.parse(iso) - t0.value) / Math.max(t1.value - t0.value, 1)) * (W - 8) + 4;
const y = (v: number) => H - 16 - ((v - min.value) / Math.max(max.value - min.value, 1e-9)) * (H - 32);

const line = computed(() =>
  points.value.length ? points.value.map((s, i) => `${i ? 'L' : 'M'}${x(s.bucket_start).toFixed(1)},${y(s.avg_value!).toFixed(1)}`).join(' ') : ''
);
const band = computed(() => {
  if (points.value.length < 2) return '';
  const up = points.value.map((s, i) => `${i ? 'L' : 'M'}${x(s.bucket_start).toFixed(1)},${y(s.max_value ?? s.avg_value!).toFixed(1)}`);
  const down = [...points.value].reverse().map((s) => `L${x(s.bucket_start).toFixed(1)},${y(s.min_value ?? s.avg_value!).toFixed(1)}`);
  return `${up.join(' ')} ${down.join(' ')} Z`;
});
const markX = computed(() => {
  if (!props.mark || !props.snapshots.length) return undefined;
  const m = Date.parse(props.mark);
  return m < t0.value || m > t1.value + 60_000 ? undefined : Math.min(x(props.mark), W - 4);
});
const fmt = (v: number) => (Number.isFinite(v) ? num(v) : '');
</script>

<style scoped>
.trend {
  width: 100%;
  height: auto;
  display: block;
}
.bg {
  fill: var(--el-fill-color-lighter);
}
.band {
  fill: var(--el-color-primary-light-8);
  stroke: none;
}
.line {
  fill: none;
  stroke: var(--el-color-primary);
  stroke-width: 1.5;
}
.mark {
  stroke: var(--el-color-danger);
  stroke-dasharray: 4 3;
}
.axis,
.empty {
  font-size: 11px;
  fill: var(--el-text-color-secondary);
}
</style>
