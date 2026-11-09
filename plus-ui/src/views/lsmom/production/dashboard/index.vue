<template>
  <div class="p-2">
    <el-card v-if="error" shadow="never"><el-result icon="warning" :title="error" sub-title="请在协同应用中心确认轻生产 Pack 已启用" /></el-card>
    <el-card v-else-if="!schema" v-loading="true" shadow="never" style="height: 300px" />
    <SchemaDashboard v-else :schema="schema" :api="metricApi" />
  </div>
</template>

<script setup lang="ts" name="LsProductionDashboard">
import { onMounted, ref } from 'vue';
import { formApi } from '@/api/lsmom/form';
import { metricApi } from '@/api/lsmom/metric';
import type { DashboardSchema } from '@/types/lsView';
import SchemaDashboard from '@/components/SchemaDashboard/index.vue';

/** 轻生产：经营看板（Pack 视图 ls.production.opsDashboard，指标均已注册，可钻取明细）。 */
const schema = ref<DashboardSchema>();
const error = ref('');
onMounted(async () => {
  try {
    schema.value = (await formApi.get<DashboardSchema>('ls.production.opsDashboard')).schema as DashboardSchema;
  } catch (e: any) {
    error.value = e?.message ?? String(e);
  }
});
</script>
