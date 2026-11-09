<template>
  <div class="ls-dashboard" data-testid="dashboard">
    <h3 v-if="schema.title" class="d-title">{{ schema.title }}</h3>
    <el-row :gutter="12">
      <el-col v-for="(w, i) in schema.widgets" :key="i" :span="w.span ?? (w.type === 'metricCard' ? 6 : 12)" class="mb-3">
        <Widget :widget="w" :index="i" :api="api" :now="now" @drill="onDrill" />
      </el-col>
    </el-row>
    <DrillDialog ref="dialog" :api="api" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import type { DashboardSchema } from '@/types/lsView';
import type { DrillRequest, MetricApi } from '@/api/lsmom/metric';
import Widget from './Widget.vue';
import DrillDialog from './DrillDialog.vue';

/** 看板渲染器：组件只取已发布指标或命名 Query；点击指标值钻取到参与计算的记录，并显示指标编码与口径版本。 */
defineProps<{ schema: DashboardSchema; api: MetricApi; now?: Date }>();
const dialog = ref<InstanceType<typeof DrillDialog>>();
function onDrill(metric: string, label: string, r: DrillRequest) {
  dialog.value?.open(metric, label, r);
}
defineExpose({ dialog });
</script>

<style scoped>
.d-title {
  margin: 0 0 12px;
}
</style>
