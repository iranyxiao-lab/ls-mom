<template>
  <el-card shadow="never" class="widget" :data-testid="`widget-${index}`">
    <template #header>
      <div class="w-head">
        <span class="w-title">{{ widget.title }}</span>
        <el-tag v-if="widget.range" size="small" type="info">{{ RANGE_TEXT[widget.range] }}</el-tag>
      </div>
    </template>
    <div v-if="error" class="w-error" :data-testid="`widget-error-${index}`">{{ error }}</div>
    <div v-else-if="loading" v-loading="true" class="w-loading" />
    <template v-else>
      <!-- 指标卡 -->
      <div v-if="widget.type === 'metricCard'" class="card-value" :data-testid="`widget-value-${index}`" title="点击查看明细" @click="drill({})">
        {{ formatValue(result?.rows[0]?.value, result?.fn, result?.unit) }}
        <div v-if="result?.fn === 'ratio'" class="card-sub">{{ result.rows[0]?.numerator ?? 0 }} / {{ result.rows[0]?.denominator ?? 0 }}</div>
      </div>
      <!-- 图表 -->
      <div v-else-if="widget.type !== 'table'" ref="chartEl" class="chart" :data-testid="`widget-chart-${index}`" />
      <!-- 表格：命名 Query 或指标分组 -->
      <el-table v-else :data="tableRows" size="small" max-height="320" :data-testid="`widget-table-${index}`" @row-click="onRowClick">
        <el-table-column v-for="c in tableColumns" :key="c" :prop="c" :label="c" min-width="100" show-overflow-tooltip />
      </el-table>
      <div class="w-foot">
        <span v-if="widget.metric">{{ widget.metric }} · v{{ result?.version }}</span>
        <span v-else-if="widget.query">命名 Query {{ widget.query }} · v{{ queryResult?.version }}</span>
      </div>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import * as echarts from 'echarts';
import { useChartTheme } from '@/design/echarts';
import type { DashboardWidget } from '@/types/lsView';
import type { DrillRequest, MetricApi, MetricResult, NamedQueryResult } from '@/api/lsmom/metric';
import { formatValue, rangeOf, RANGE_TEXT } from './range';

const props = defineProps<{ widget: DashboardWidget; index: number; api: MetricApi; now?: Date }>();
const emit = defineEmits<{ (e: 'drill', metric: string, label: string, r: DrillRequest): void }>();

const loading = ref(true);
const error = ref('');
const result = ref<MetricResult>();
const queryResult = ref<NamedQueryResult>();
const chartEl = ref<HTMLElement>();
const chart = shallowRef<echarts.ECharts>();
// 图表主题来自设计令牌，明暗与品牌色变化时重绘
const chartTheme = useChartTheme();
watch(chartTheme, () => render());
const range = computed(() => rangeOf(props.widget.range, props.now));
const dims = computed(() => props.widget.dimensions ?? []);

const tableColumns = computed(() =>
  queryResult.value ? queryResult.value.columns : result.value ? [...(result.value.grain ? ['bucket'] : []), ...dims.value, 'value'] : []
);
const tableRows = computed(() => queryResult.value?.rows ?? result.value?.rows ?? []);

function drill(cell: { bucket?: string; dims?: Record<string, unknown> }, label = props.widget.title) {
  if (!props.widget.metric) return;
  emit('drill', props.widget.metric, label, {
    ...range.value,
    grain: cell.bucket ? props.widget.grain : undefined,
    ...cell,
    version: result.value?.version
  });
}

function onRowClick(row: Record<string, any>) {
  if (!props.widget.metric) return;
  const d: Record<string, unknown> = {};
  dims.value.forEach((k) => (d[k] = row[k]));
  drill(
    { bucket: row.bucket, dims: d },
    `${props.widget.title}（${[row.bucket, ...dims.value.map((k) => row[k])].filter((x) => x != null).join(' / ')}）`
  );
}

function seriesValue(r: Record<string, any>) {
  return r.value == null ? null : result.value?.fn === 'ratio' ? Math.round(Number(r.value) * 1000) / 10 : Number(r.value);
}

function render() {
  if (!chartEl.value || !result.value) return;
  chart.value?.dispose();
  const c = echarts.init(chartEl.value, chartTheme.value);
  chart.value = c;
  const rows = result.value.rows;
  const w = props.widget;
  const pct = result.value.fn === 'ratio';
  if (w.type === 'pie') {
    const key = dims.value[0];
    c.setOption({
      tooltip: { trigger: 'item' },
      series: [{ type: 'pie', radius: ['35%', '65%'], data: rows.map((r) => ({ name: String(r[key] ?? '空'), value: seriesValue(r), raw: r })) }]
    });
  } else {
    const xKey = w.type === 'trend' ? 'bucket' : dims.value[0];
    c.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 16, top: 16, bottom: 28 },
      xAxis: { type: 'category', data: rows.map((r) => String(r[xKey] ?? '空')) },
      yAxis: { type: 'value', axisLabel: { formatter: pct ? '{value}%' : '{value}' } },
      series: [{ type: w.type === 'trend' ? 'line' : 'bar', data: rows.map(seriesValue), smooth: true }]
    });
  }
  c.on('click', (p: any) => {
    const r = rows[p.dataIndex];
    if (!r) return;
    const d: Record<string, unknown> = {};
    dims.value.forEach((k) => (d[k] = r[k]));
    drill({ bucket: r.bucket, dims: d }, `${w.title}（${p.name}）`);
  });
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const w = props.widget;
    if (w.query) {
      queryResult.value = await props.api.namedQuery(w.query, (w.params ?? {}) as Record<string, unknown>);
    } else if (w.metric) {
      result.value = await props.api.query(w.metric, {
        ...range.value,
        grain: w.type === 'trend' || w.grain ? w.grain : undefined,
        groupBy: w.type === 'metricCard' ? [] : dims.value
      });
    }
  } catch (e: any) {
    error.value = e?.message ?? '加载失败';
  } finally {
    loading.value = false;
  }
  await nextTick();
  if (props.widget.type !== 'metricCard' && props.widget.type !== 'table') render();
}

const onResize = () => chart.value?.resize();
onMounted(() => {
  load();
  window.addEventListener('resize', onResize);
});
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize);
  chart.value?.dispose();
});

defineExpose({ result, queryResult, reload: load, chart });
</script>

<style scoped>
.widget {
  height: 100%;
}
.w-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.w-title {
  font-weight: 600;
}
.card-value {
  font-size: 36px;
  font-weight: 700;
  color: var(--el-color-primary);
  cursor: pointer;
  text-align: center;
  padding: 12px 0;
}
.card-sub {
  font-size: 13px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.chart {
  height: 260px;
}
.w-loading {
  height: 120px;
}
.w-error {
  color: var(--el-color-danger);
  padding: 12px 0;
}
.w-foot {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
