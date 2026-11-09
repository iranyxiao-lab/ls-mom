<template>
  <div class="schema-detail" data-testid="schema-detail">
    <el-alert
      v-if="frozenVersion && latestVersion && frozenVersion < latestVersion"
      type="info"
      :closable="false"
      :title="`本记录按表单 v${frozenVersion} 保存，以该版本只读显示（当前最新 v${latestVersion}）`"
      class="schema-detail__hint"
      data-testid="frozen-hint"
    />
    <!-- FormSchema：用同一渲染器只读显示 -->
    <SchemaForm v-if="schema.viewType === 'FORM'" :schema="schema" :model-value="record" readonly :show-actions="false" :api="api" />
    <!-- DetailSchema：分组描述 + 子表 -->
    <template v-else>
      <el-descriptions v-for="g in schema.groups" :key="g.title" :title="g.title" :column="g.columns ?? 2" border class="schema-detail__group">
        <el-descriptions-item v-for="k in g.fields" :key="k" :label="labels?.[k] ?? k">{{ text(k) }}</el-descriptions-item>
      </el-descriptions>
      <div v-for="c in schema.subTables ?? []" :key="c" class="schema-detail__group">
        <div class="schema-detail__title">{{ labels?.[childKey(c)] ?? childKey(c) }}</div>
        <el-table :data="(record[childKey(c)] as any[]) ?? []" border size="small">
          <el-table-column type="index" width="50" />
          <el-table-column v-for="col in childColumns(c)" :key="col" :prop="col" :label="labels?.[childKey(c) + '.' + col] ?? col" />
        </el-table>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import type { DetailSchema, FormSchema } from '@/types/lsView';
import type { RendererApi } from '@/api/lsmom/form';
import { lazyFormApi } from '@/api/lsmom/lazyForm';
import SchemaForm from '@/components/SchemaForm/index.vue';

/** 详情：FormSchema 以只读模式复用表单渲染器（历史记录按冻结版本），DetailSchema 按分组展示。 */
const props = withDefaults(
  defineProps<{
    schema: FormSchema | DetailSchema;
    record: Record<string, any>;
    labels?: Record<string, string>;
    frozenVersion?: number;
    latestVersion?: number;
    api?: RendererApi;
  }>(),
  { api: () => lazyFormApi }
);

/** 子表键：子 Concept 简名首字母小写（与台账接口一致）。 */
const childKey = (concept: string) => {
  const simple = concept.substring(concept.lastIndexOf('.') + 1);
  return simple.charAt(0).toLowerCase() + simple.slice(1);
};

const childColumns = (concept: string) => {
  const rows = (props.record[childKey(concept)] as Record<string, unknown>[]) ?? [];
  const keys = new Set<string>();
  rows.forEach((r) => Object.keys(r).forEach((k) => !['id', 'lineNo', 'parentId'].includes(k) && keys.add(k)));
  return [...keys];
};

function text(k: string) {
  const v = props.record[k];
  const label = props.record[k + 'Label'];
  if (label !== undefined && label !== null) return String(label);
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'boolean') return v ? '是' : '否';
  if (Array.isArray(v)) return v.map((x) => (typeof x === 'object' && x ? (x.name ?? JSON.stringify(x)) : String(x))).join('、');
  return String(v);
}
</script>

<style scoped>
.schema-detail__hint,
.schema-detail__group {
  margin-bottom: 16px;
}
.schema-detail__title {
  font-weight: 600;
  margin-bottom: 8px;
}
</style>
