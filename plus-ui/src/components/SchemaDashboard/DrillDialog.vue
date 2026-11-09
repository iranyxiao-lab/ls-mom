<template>
  <el-dialog v-model="visible" :title="title" width="900px" append-to-body data-testid="drill-dialog">
    <div class="drill-meta" data-testid="drill-meta">
      共 <b data-testid="drill-total">{{ result?.total ?? 0 }}</b> 条 · 指标 <code>{{ result?.code }}</code> 口径 v{{ result?.version }}
      <span v-if="condText"> · {{ condText }}</span>
    </div>
    <el-table v-loading="loading" :data="result?.rows ?? []" size="small" max-height="460" data-testid="drill-table">
      <el-table-column v-for="c in result?.columns ?? []" :key="c" :prop="c" :label="c" min-width="110" show-overflow-tooltip />
    </el-table>
    <el-pagination
      v-if="(result?.total ?? 0) > size"
      v-model:current-page="page"
      class="mt-2"
      layout="prev, pager, next, total"
      :page-size="size"
      :total="result?.total ?? 0"
      @current-change="load"
    />
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import type { DrillRequest, DrillResult, MetricApi } from '@/api/lsmom/metric';

/** 钻取明细：与指标计算条件完全相同，条数等于计数类指标的值。 */
const props = defineProps<{ api: Pick<MetricApi, 'drill'> }>();
const visible = ref(false);
const loading = ref(false);
const title = ref('');
const code = ref('');
const req = ref<DrillRequest>({});
const result = ref<DrillResult>();
const page = ref(1);
const size = 50;

const condText = computed(() => {
  const parts: string[] = [];
  if (req.value.bucket) parts.push(`时间桶 ${req.value.bucket}`);
  for (const [k, v] of Object.entries(req.value.dims ?? {})) parts.push(`${k} = ${v}`);
  if (req.value.from || req.value.to) parts.push(`${req.value.from ?? ''} ~ ${req.value.to ?? ''}`);
  return parts.join('，');
});

async function load() {
  loading.value = true;
  try {
    result.value = await props.api.drill(code.value, { ...req.value, page: page.value, size });
  } finally {
    loading.value = false;
  }
}

async function open(metric: string, label: string, r: DrillRequest) {
  code.value = metric;
  title.value = `钻取：${label}`;
  req.value = r;
  page.value = 1;
  result.value = undefined;
  visible.value = true;
  await load();
}

defineExpose({ open, result });
</script>

<style scoped>
.drill-meta {
  margin-bottom: 8px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}
</style>
