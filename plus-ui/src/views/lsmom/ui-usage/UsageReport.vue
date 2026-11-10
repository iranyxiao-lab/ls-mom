<template>
  <div class="usage" data-testid="usage-report">
    <div class="toolbar">
      <el-select v-model="days" aria-label="统计范围" class="days" data-testid="usage-days" @change="load">
        <el-option :value="7" label="最近 7 天" />
        <el-option :value="30" label="最近 30 天" />
        <el-option :value="90" label="最近 90 天" />
      </el-select>
      <span class="grow" />
      <span class="hint">匿名记录关键任务的步骤与耗时，不记录用户和填写内容</span>
      <el-switch
        v-if="canManage"
        v-model="enabled"
        inline-prompt
        active-text="埋点开"
        inactive-text="埋点关"
        aria-label="使用埋点开关"
        data-testid="usage-switch"
        :loading="saving"
        @change="toggle"
      />
      <LsStatusTag
        v-else
        :status="enabled ? 'ENABLED' : 'DISABLED'"
        :label="enabled ? '埋点已开启' : '埋点已关闭'"
        :tone="enabled ? 'success' : 'neutral'"
      />
    </div>

    <LsError v-if="error" title="报表加载失败" :reason="error" retryable @retry="load" />
    <LsSkeleton v-else-if="loading && !rows.length" />
    <LsEmpty
      v-else-if="!rows.length"
      title="暂无埋点数据"
      :description="enabled ? '用户使用关键任务后，这里会显示各步骤的放弃情况' : '埋点已关闭，开启后开始统计'"
    />
    <el-table v-else v-loading="loading" :data="rows" row-key="task" size="small" data-testid="usage-table" default-expand-all>
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="steps">
            <div v-if="!row.steps.length" class="hint">没有放弃记录</div>
            <div v-for="s in row.steps" :key="s.step" class="step" :data-testid="`usage-step-${row.task}-${s.step || 'none'}`">
              <span class="step-name">{{ stepLabel(row.task, s.step) }}</span>
              <el-progress :percentage="Math.round(s.rate * 100)" :status="s.high ? 'exception' : undefined" :stroke-width="8" class="bar" />
              <span class="num">{{ s.abandoned }} 次</span>
              <LsStatusTag v-if="s.high" status="HIGH_ABANDON" label="高放弃率" tone="danger" />
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="任务" min-width="160">
        <template #default="{ row }">{{ TASK_LABELS[row.task] ?? row.task }}</template>
      </el-table-column>
      <el-table-column label="开始" prop="started" width="80" align="right" />
      <el-table-column label="完成" prop="completed" width="80" align="right" />
      <el-table-column label="放弃" prop="abandoned" width="80" align="right" />
      <el-table-column label="完成率" width="90" align="right">
        <template #default="{ row }">{{ row.started ? Math.round((row.completed / row.started) * 100) + '%' : '—' }}</template>
      </el-table-column>
      <el-table-column label="出错" prop="errors" width="80" align="right" />
      <el-table-column label="完成耗时中位数" width="140" align="right">
        <template #default="{ row }">{{ row.medianMs == null ? '—' : formatMs(row.medianMs) }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { LsEmpty, LsError, LsSkeleton, LsStatusTag, lsSuccess } from '@/components/LsState';
import type { UiUsageApi } from '@/api/lsmom/uiUsage';
import { TASK_LABELS, stepLabel, type UsageTask } from '@/api/lsmom/uiUsageModel';

/** 2.5 放弃点报表：按任务看开始／完成／放弃次数，展开看各步骤放弃率；放弃率达到三成（样本 ≥5）标为高放弃率。 */
const props = defineProps<{ api: UiUsageApi; canManage?: boolean }>();

const days = ref(30);
const rows = ref<UsageTask[]>([]);
const enabled = ref(true);
const loading = ref(false);
const saving = ref(false);
const error = ref('');

const formatMs = (ms: number) =>
  ms < 60_000 ? `${(ms / 1000).toFixed(1)} 秒` : `${Math.floor(ms / 60_000)} 分 ${Math.round((ms % 60_000) / 1000)} 秒`;

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const [cfg, report] = await Promise.all([props.api.config(), props.api.report(days.value)]);
    enabled.value = cfg.enabled;
    rows.value = report;
  } catch (e: any) {
    error.value = e?.message ?? String(e);
  } finally {
    loading.value = false;
  }
}

async function toggle(v: string | number | boolean) {
  saving.value = true;
  try {
    enabled.value = (await props.api.setEnabled(Boolean(v))).enabled;
    lsSuccess(enabled.value ? '已开启使用埋点' : '已关闭使用埋点，前端将停止上报');
  } catch {
    enabled.value = !v;
  } finally {
    saving.value = false;
  }
}

onMounted(load);
defineExpose({ load });
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: var(--ls-space-3);
  align-items: center;
  margin-bottom: var(--ls-space-3);
  flex-wrap: wrap;
}
.days {
  width: 140px;
}
.grow {
  flex: 1;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--el-font-size-small);
}
.steps {
  padding: var(--ls-space-2) var(--ls-space-6);
  display: grid;
  gap: var(--ls-space-2);
}
.step {
  display: grid;
  grid-template-columns: 140px minmax(160px, 320px) 60px auto;
  gap: var(--ls-space-3);
  align-items: center;
}
.num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
</style>
