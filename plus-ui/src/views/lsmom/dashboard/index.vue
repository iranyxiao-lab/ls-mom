<template>
  <div class="p-2">
    <el-card v-if="error" shadow="never"><el-result icon="error" :title="error" /></el-card>
    <el-card v-else-if="!schema" v-loading="true" shadow="never" style="height: 300px" />
    <SchemaDashboard v-else :schema="schema" :api="metricApi" />
  </div>
</template>

<script setup lang="ts" name="LsDashboard">
import { onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { formApi } from '@/api/lsmom/form';
import { metricApi } from '@/api/lsmom/metric';
import type { DashboardSchema } from '@/types/lsView';
import SchemaDashboard from '@/components/SchemaDashboard/index.vue';

/** 看板页：/lsmom/dashboard/<看板编码>，渲染已发布的 DASHBOARD 视图。 */
const route = useRoute();
const schema = ref<DashboardSchema>();
const error = ref('');
onMounted(async () => {
  try {
    schema.value = (await formApi.get<DashboardSchema>(String(route.params.code))).schema as DashboardSchema;
  } catch (e: any) {
    error.value = e?.message ?? String(e);
  }
});
</script>
