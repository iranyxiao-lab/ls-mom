<template>
  <div ref="rootEl" class="ls-subtable" :data-testid="'subtable-' + table.key">
    <div class="ls-subtable__head">
      <span class="ls-subtable__title">{{ table.label ?? table.key }}</span>
      <el-button v-if="tableAccess.editable && rows.length < (table.maxRows ?? 1000)" size="small" :data-testid="'add-row-' + table.key" @click="add">
        添加一行
      </el-button>
    </div>
    <el-table :data="rows" border size="small" :row-key="(r: any) => r.__k">
      <el-table-column type="index" label="#" width="50" />
      <template v-for="f in columns" :key="f.key">
        <el-table-column :label="(f.label ?? f.key) + (access[table.key + '.' + f.key]?.required ? ' *' : '')" min-width="140">
          <template #default="{ row, $index }">
            <div
              :class="['ls-subtable__cell', { 'is-error': !!errors[cellKey($index, f.key)] }]"
              :data-field-key="cellKey($index, f.key)"
              :data-testid="`cell-${table.key}-${$index}-${f.key}`"
            >
              <FieldWidget
                :field="f"
                :model-value="row[f.key]"
                :editable="!!access[table.key + '.' + f.key]?.editable"
                :api="api"
                :org-id="orgId"
                @update:model-value="(v: unknown) => setCell($index, f.key, v)"
              />
              <div v-if="errors[cellKey($index, f.key)]" class="ls-subtable__err" role="alert">{{ errors[cellKey($index, f.key)] }}</div>
            </div>
          </template>
        </el-table-column>
      </template>
      <el-table-column v-if="tableAccess.editable" label="操作" width="80">
        <template #default="{ $index }">
          <el-button link type="danger" :data-testid="'remove-row-' + table.key + '-' + $index" @click="remove($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="errors[table.key]" class="ls-subtable__err">{{ errors[table.key] }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { fieldHosts, focusHost, parseCell } from '@/utils/lsform/keyboardNav';
import type { SubTable } from '@/types/lsView';
import type { RendererApi } from '@/api/lsmom/form';
import type { Access } from '@/utils/lsform/fieldPolicy';
import FieldWidget from './FieldWidget.vue';

const props = defineProps<{
  table: SubTable;
  modelValue: unknown;
  access: Record<string, Access>;
  errors: Record<string, string>;
  api: RendererApi;
  orgId?: number;
}>();
const emit = defineEmits<{ (e: 'update:modelValue', v: Record<string, unknown>[]): void }>();

let seq = 0;
const rows = computed<Record<string, any>[]>(() => {
  const list = Array.isArray(props.modelValue) ? (props.modelValue as Record<string, any>[]) : [];
  for (const r of list) if (r.__k === undefined) Object.defineProperty(r, '__k', { value: ++seq, enumerable: false });
  return list;
});
const tableAccess = computed(() => props.access[props.table.key] ?? { visible: true, editable: true, required: false });
const cellKey = (i: number, f: string) => `${props.table.key}[${i}].${f}`;
const columns = computed(() => props.table.fields.filter((f) => props.access[props.table.key + '.' + f.key]?.visible !== false));

const rootEl = ref<HTMLElement>();

/** 新增一行并聚焦新行第一个可录入的单元格。 */
async function add() {
  const row: Record<string, unknown> = {};
  for (const f of props.table.fields) if (f.default !== undefined) row[f.key] = f.default;
  const index = rows.value.length;
  emit('update:modelValue', [...rows.value, row]);
  await nextTick();
  await nextTick();
  const first = rootEl.value
    ? fieldHosts(rootEl.value).find((h) => parseCell(h.dataset.fieldKey!)?.row === index && parseCell(h.dataset.fieldKey!)?.table === props.table.key)
    : undefined;
  focusHost(first);
}

function remove(i: number) {
  emit(
    'update:modelValue',
    rows.value.filter((_, j) => j !== i)
  );
}

function setCell(i: number, key: string, v: unknown) {
  const next = rows.value.map((r, j) => {
    if (j !== i) return r;
    const copy = { ...r, [key]: v };
    Object.defineProperty(copy, '__k', { value: r.__k, enumerable: false });
    return copy;
  });
  emit('update:modelValue', next);
}
</script>

<style scoped>
.ls-subtable__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.ls-subtable__title {
  font-weight: 600;
}
.ls-subtable__cell.is-error :deep(.el-input__wrapper),
.ls-subtable__cell.is-error :deep(.el-select__wrapper),
.ls-subtable__cell.is-error :deep(.el-textarea__inner) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}
.ls-subtable__cell.is-error {
  background: var(--el-color-danger-light-9);
}
.ls-subtable__err {
  color: var(--el-color-danger-dark-2);
  font-size: 12px;
  line-height: 1.4;
}
</style>
