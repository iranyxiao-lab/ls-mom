<template>
  <div class="schema-table">
    <el-form v-if="schema && filterFields.length" :model="filterModel" inline class="schema-table__filters" data-testid="filters" @submit.prevent>
      <el-form-item v-for="f in filterFields" :key="f.code" :label="f.label">
        <el-select
          v-if="filterOf(f).widget === 'select'"
          v-model="filterModel[f.code]"
          clearable
          :placeholder="'全部'"
          style="width: 160px"
          :data-testid="'filter-' + f.code"
        >
          <el-option v-for="o in f.options || []" :key="o" :label="f.optionLabels?.[o] ?? o" :value="o" />
        </el-select>
        <el-select
          v-else-if="filterOf(f).widget === 'boolean'"
          v-model="filterModel[f.code]"
          clearable
          style="width: 120px"
          :data-testid="'filter-' + f.code"
        >
          <el-option label="是" :value="true" />
          <el-option label="否" :value="false" />
        </el-select>
        <el-date-picker
          v-else-if="filterOf(f).widget === 'dateRange'"
          v-model="filterModel[f.code]"
          :type="f.type === 'DATE' ? 'daterange' : 'datetimerange'"
          :value-format="f.type === 'DATE' ? 'YYYY-MM-DD' : 'YYYY-MM-DDTHH:mm:ss'"
          range-separator="至"
          :data-testid="'filter-' + f.code"
        />
        <el-input-number
          v-else-if="filterOf(f).widget === 'number'"
          v-model="filterModel[f.code]"
          :controls="false"
          :data-testid="'filter-' + f.code"
        />
        <el-input
          v-else
          v-model="filterModel[f.code]"
          clearable
          :placeholder="'输入' + f.label"
          :data-testid="'filter-' + f.code"
          @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" data-testid="search" @click="search">查询</el-button>
        <el-button data-testid="reset" @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div v-if="schema" class="schema-table__toolbar">
      <el-button v-if="schema.permissions.add" type="primary" plain data-testid="create" @click="emit('create')">新增</el-button>
      <el-button
        v-for="op in batchOperations"
        :key="op.operation"
        :disabled="selection.length === 0 || busy"
        :data-testid="'batch-' + op.operation"
        @click="runBatch(op)"
      >
        批量{{ op.name }}
      </el-button>
      <el-button v-if="schema.permissions.import" data-testid="import" @click="emit('import')">导入</el-button>
      <el-button v-if="schema.permissions.export" :disabled="busy" data-testid="export" @click="doExport">导出</el-button>
    </div>

    <el-table
      v-if="schema"
      v-loading="loading"
      :data="rows"
      row-key="id"
      data-testid="table"
      @selection-change="(s: any[]) => (selection = s)"
      @sort-change="onSort"
      @row-click="(row: any) => emit('row-click', row)"
    >
      <el-table-column v-if="batchOperations.length" type="selection" width="44" />
      <el-table-column
        v-for="f in columns"
        :key="f.code"
        :prop="f.code"
        :label="f.label"
        :sortable="f.sortable ? 'custom' : false"
        show-overflow-tooltip
        min-width="120"
      >
        <template #default="{ row }">{{ cellText(f, row) }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="110" :sortable="'custom'" />
      <el-table-column v-if="rowOps.length || schema.permissions.edit" label="操作" fixed="right" min-width="160">
        <template #default="{ row }">
          <el-button v-if="schema.permissions.edit && row.status === 'DRAFT'" link type="primary" @click.stop="emit('edit', row)">编辑</el-button>
          <el-button
            v-for="op in rowOps.filter((o) => allowedIn(o, row))"
            :key="op.operation"
            link
            type="primary"
            :data-testid="'op-' + op.operation + '-' + row.id"
            @click.stop="runRow(op, row)"
          >
            {{ op.name }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="schema"
      v-model:current-page="page"
      v-model:page-size="size"
      class="schema-table__pager"
      layout="total, sizes, prev, pager, next"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      @current-change="load"
      @size-change="load"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { CrudApi } from '@/api/lsmom/crud';
import { lazyCrudApi } from '@/api/lsmom/lazyCrud';
import type { ExportJob, ListSchema, SchemaOperation } from '@/api/lsmom/types';
import { buildQuerySpec, cellText, columnsOf, filterOf, rowOperations } from './querySpec';

/**
 * ListSchema 驱动的通用台账列表：列、筛选、排序、分页来自服务端白名单；
 * 行/批量动作只绑定已发布的 Operation（经 /api/op 执行，后端重新校验权限、状态与版本）；导出走异步任务。
 */
const props = withDefaults(defineProps<{ concept: string; api?: CrudApi; pageSize?: number }>(), { api: () => lazyCrudApi, pageSize: 20 });
const emit = defineEmits<{
  (e: 'create'): void;
  (e: 'edit', row: Record<string, any>): void;
  (e: 'row-click', row: Record<string, any>): void;
  (e: 'action-done', payload: { operation: string; ok: number; failed: { id: unknown; message: string }[] }): void;
  (e: 'import'): void;
  (e: 'exported', job: ExportJob): void;
}>();

const schema = ref<ListSchema | null>(null);
const rows = ref<Record<string, any>[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(props.pageSize);
const loading = ref(false);
const busy = ref(false);
const selection = ref<Record<string, any>[]>([]);
const sort = ref<{ field: string; dir: 'asc' | 'desc' } | null>(null);
const filterModel = reactive<Record<string, any>>({});

const filterFields = computed(() => (schema.value ? schema.value.fields.filter((f) => f.queryable) : []));
const columns = computed(() => (schema.value ? columnsOf(schema.value) : []));
const rowOps = computed(() => (schema.value ? rowOperations(schema.value) : []));
const batchOperations = computed(() => rowOps.value.filter((o) => !hasRequiredInput(o)));

function hasRequiredInput(op: SchemaOperation) {
  const req = (op.inputSchema as any)?.required;
  return Array.isArray(req) && req.length > 0;
}

function allowedIn(op: SchemaOperation & { from?: string[] }, row: Record<string, any>) {
  return !op.from || op.from.includes(row.status);
}

function spec() {
  return buildQuerySpec(schema.value!, filterModel, sort.value, page.value, size.value);
}

async function load() {
  if (!schema.value) return;
  loading.value = true;
  try {
    const p = await props.api.query(props.concept, spec());
    rows.value = p.rows as Record<string, any>[];
    total.value = p.total;
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  return load();
}

function reset() {
  Object.keys(filterModel).forEach((k) => delete filterModel[k]);
  sort.value = null;
  return search();
}

function onSort({ prop, order }: { prop: string; order: 'ascending' | 'descending' | null }) {
  sort.value = order ? { field: prop, dir: order === 'ascending' ? 'asc' : 'desc' } : null;
  return load();
}

async function runRow(op: SchemaOperation, row: Record<string, any>) {
  busy.value = true;
  try {
    const r = await props.api.execute(op.operation, { targetId: String(row.id), version: Number(row.version), input: {} });
    if (r.warnings?.length) ElMessage.warning(r.warnings.join('；'));
    emit('action-done', { operation: op.operation, ok: 1, failed: [] });
  } catch (e: any) {
    emit('action-done', { operation: op.operation, ok: 0, failed: [{ id: row.id, message: e?.message ?? String(e) }] });
  } finally {
    busy.value = false;
    await load();
  }
}

/** 批量动作逐条执行（每条独立校验、独立事务），汇总成功与失败。 */
async function runBatch(op: SchemaOperation) {
  busy.value = true;
  let ok = 0;
  const failed: { id: unknown; message: string }[] = [];
  for (const row of selection.value) {
    try {
      await props.api.execute(op.operation, { targetId: String(row.id), version: Number(row.version), input: {} });
      ok++;
    } catch (e: any) {
      failed.push({ id: row.id, message: e?.message ?? String(e) });
    }
  }
  busy.value = false;
  if (failed.length) ElMessage.warning(`${op.name}：成功 ${ok} 条，失败 ${failed.length} 条`);
  else ElMessage.success(`${op.name}：成功 ${ok} 条`);
  emit('action-done', { operation: op.operation, ok, failed });
  await load();
}

async function doExport() {
  busy.value = true;
  try {
    const job = await props.api.exportJob(props.concept, { ...spec(), page: 1 });
    ElMessage.success(`已提交导出任务 #${job.id}`);
    emit('exported', job);
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  schema.value = await props.api.schema(props.concept);
  await load();
});

defineExpose({ load, search, reset, spec });
</script>

<style scoped>
.schema-table__toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.schema-table__pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
