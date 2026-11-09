<template>
  <div class="p-2">
    <el-card shadow="never">
      <SchemaTable v-if="concept" :key="concept" ref="table" :concept="concept" @action-done="onDone" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="LedgerList">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import SchemaTable from '@/components/SchemaTable/index.vue';

/** 通用台账列表页：/lsmom/ledger/<Concept 编码>，菜单可直接指向该路径。 */
const route = useRoute();
const concept = computed(() => String(route.params.concept || ''));
const table = ref();

function onDone(p: { operation: string; ok: number; failed: { id: unknown; message: string }[] }) {
  if (p.failed.length) ElMessage.error(p.failed.map((f) => `#${f.id}：${f.message}`).join('；'));
}
</script>
