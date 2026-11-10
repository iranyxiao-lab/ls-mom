<template>
  <div ref="rootEl" class="ls-subtable" :data-testid="'subtable-' + table.key" @paste="onPaste">
    <div class="ls-subtable__head">
      <span class="ls-subtable__title">{{ table.label ?? table.key }}</span>
      <span class="ls-subtable__tools">
        <template v-if="tableAccess.editable">
          <textarea
            ref="pasteEl"
            class="ls-subtable__paste"
            aria-label="粘贴 Excel 数据：复制 Excel 或 WPS 中的行后按 Ctrl+V"
            :data-testid="'paste-' + table.key"
            rows="1"
            @paste="onPaste"
          />
          <el-button size="small" :data-testid="'paste-btn-' + table.key" @click="pasteEl?.focus()">从 Excel 粘贴</el-button>
        </template>
        <el-button v-if="tableAccess.editable && rows.length < maxRows" size="small" :data-testid="'add-row-' + table.key" @click="add"
          >添加一行</el-button
        >
      </span>
    </div>
    <VxeTable
      :data="rows"
      border
      size="small"
      :row-config="{ keyField: '__k', isHover: true }"
      :column-config="{ resizable: true }"
      :scroll-y="{ enabled: rows.length > 200, gt: 200 }"
      :max-height="rows.length > 12 ? 520 : undefined"
      :show-overflow="false"
      :data-testid="'subtable-grid-' + table.key"
    >
      <VxeColumn type="seq" title="#" width="50" />
      <VxeColumn
        v-for="f in columns"
        :key="f.key"
        :field="f.key"
        :title="(f.label ?? f.key) + (access[table.key + '.' + f.key]?.required ? ' *' : '')"
        min-width="140"
      >
        <template #default="{ row, rowIndex }">
          <div
            :class="['ls-subtable__cell', { 'is-error': !!errors[cellKey(rowIndex, f.key)] }]"
            :data-field-key="cellKey(rowIndex, f.key)"
            :data-testid="`cell-${table.key}-${rowIndex}-${f.key}`"
          >
            <FieldWidget
              :field="f"
              :aria-label="`${f.label ?? f.key} 第 ${rowIndex + 1} 行`"
              :model-value="row[f.key]"
              :editable="!!access[table.key + '.' + f.key]?.editable"
              :api="api"
              :org-id="orgId"
              @update:model-value="(v: unknown) => setCell(rowIndex, f.key, v)"
            />
            <div v-if="errors[cellKey(rowIndex, f.key)]" class="ls-subtable__err" role="alert">{{ errors[cellKey(rowIndex, f.key)] }}</div>
            <div v-if="cands[cellKey(rowIndex, f.key)]?.length" class="ls-subtable__cands" :data-testid="`cands-${table.key}-${rowIndex}-${f.key}`">
              可能是：
              <button
                v-for="o in cands[cellKey(rowIndex, f.key)]"
                :key="String(o.value)"
                type="button"
                class="ls-subtable__cand"
                @click="setCell(rowIndex, f.key, o.value)"
              >
                {{ o.label }}
              </button>
            </div>
          </div>
        </template>
      </VxeColumn>
      <VxeColumn v-if="tableAccess.editable" title="操作" width="80">
        <template #default="{ rowIndex }">
          <el-button link type="danger" :data-testid="'remove-row-' + table.key + '-' + rowIndex" @click="remove(rowIndex)">删除</el-button>
        </template>
      </VxeColumn>
    </VxeTable>
    <div v-if="errors[table.key]" class="ls-subtable__err">{{ errors[table.key] }}</div>
  </div>
</template>

<script setup lang="ts">
/**
 * 子表编辑器（vxe-table）：逐格录入、Enter 跳格（由 SchemaForm 处理）、从 Excel / WPS 粘贴多行多列。
 * 粘贴：第一行是表头时按表头对应列，否则从当前单元格起按列顺序填入（从「从 Excel 粘贴」进入时追加到末尾）；
 * 逐格转换并校验，字典或选项对不上的单元格标红并给出候选值，点候选即可改正。提交时仍由服务端按表单校验。
 */
import { computed, nextTick, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { VxeColumn, VxeTable } from '@/plugins/vxe';
import type { FormField, Option, SubTable } from '@/types/lsView';
import type { RendererApi } from '@/api/lsmom/form';
import type { Access } from '@/utils/lsform/fieldPolicy';
import { checkField } from '@/utils/lsform/validate';
import { convertCell, headerMapping, parseTsv } from '@/utils/lsform/pasteGrid';
import { fieldHosts, focusHost, parseCell } from '@/utils/lsform/keyboardNav';
import { refOf, REF_WIDGETS } from './refDefaults';
import FieldWidget from './FieldWidget.vue';
import { dictOptions } from './dictCache';

const props = defineProps<{
  table: SubTable;
  modelValue: unknown;
  access: Record<string, Access>;
  errors: Record<string, string>;
  api: RendererApi;
  orgId?: number;
}>();
const emit = defineEmits<{
  (e: 'update:modelValue', v: Record<string, unknown>[]): void;
  /** 单元格问题（null 表示该格已无问题） */
  (e: 'cell-errors', v: Record<string, string | null>): void;
}>();

let seq = 0;
const rows = computed<Record<string, any>[]>(() => {
  const list = Array.isArray(props.modelValue) ? (props.modelValue as Record<string, any>[]) : [];
  for (const r of list) if (r.__k === undefined) Object.defineProperty(r, '__k', { value: ++seq, enumerable: false });
  return list;
});
const tableAccess = computed(() => props.access[props.table.key] ?? { visible: true, editable: true, required: false });
const maxRows = computed(() => props.table.maxRows ?? 1000);
const cellKey = (i: number, f: string) => `${props.table.key}[${i}].${f}`;
const columns = computed(() => props.table.fields.filter((f) => props.access[props.table.key + '.' + f.key]?.visible !== false));
const editable = (f: FormField) => !!props.access[props.table.key + '.' + f.key]?.editable && !f.computed;

const rootEl = ref<HTMLElement>();
const pasteEl = ref<HTMLTextAreaElement>();
/** 粘贴时对不上的单元格的候选值 */
const cands = reactive<Record<string, Option[]>>({});

const keyed = (r: Record<string, any>, k?: number) => {
  Object.defineProperty(r, '__k', { value: k ?? ++seq, enumerable: false });
  return r;
};

function newRow(): Record<string, unknown> {
  const row: Record<string, unknown> = {};
  for (const f of props.table.fields) if (f.default !== undefined) row[f.key] = f.default;
  return row;
}

/** 新增一行并聚焦新行第一个可录入的单元格。 */
async function add() {
  const index = rows.value.length;
  emit('update:modelValue', [...rows.value, newRow()]);
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
  delete cands[cellKey(i, key)];
  const next = rows.value.map((r, j) => (j !== i ? r : keyed({ ...r, [key]: v }, r.__k)));
  emit('update:modelValue', next);
}

// —— 从 Excel / WPS 粘贴 ——

async function optionsOf(f: FormField): Promise<Option[]> {
  if (f.widget !== 'dict') return f.options ?? [];
  return f.dictType ? dictOptions(props.api, f.dictType) : [];
}

const KEEP_EMPTY = new Set(['number', 'money', 'rate', 'date', 'datetime', 'switch']);
const plain = (s: string) => s.replace(/[\s（）()]/g, '').toLowerCase();

/** 引用列：按显示名称查找对象（同名多个或找不到时给出候选）。 */
async function resolveRefs(f: FormField, raws: string[]) {
  const spec = refOf(f);
  const out = new Map<string, { value?: number; error?: string; candidates?: Option[] }>();
  if (!spec) return out;
  for (const raw of [...new Set(raws)].slice(0, 50)) {
    const found = await props.api.refSearch({ ref: spec, keyword: raw, orgId: props.orgId }).catch(() => []);
    const exact = found.filter(
      (o) => plain(o.label) === plain(raw) || plain(o.label).startsWith(plain(raw) + '（') || plain(o.label).startsWith(plain(raw))
    );
    const label = f.label ?? f.key;
    if (exact.length === 1) out.set(raw, { value: exact[0].id });
    else
      out.set(raw, {
        error: exact.length > 1 ? `${label}「${raw}」有多个同名对象，请选择` : `${label}「${raw}」不存在或无权使用`,
        candidates: (exact.length ? exact : found).slice(0, 3).map((o) => ({ value: o.id, label: o.label }))
      });
  }
  return out;
}

async function onPaste(e: ClipboardEvent) {
  if (!tableAccess.value.editable || e.defaultPrevented) return;
  const text = e.clipboardData?.getData('text/plain') ?? '';
  const grid = parseTsv(text);
  const target = e.target as HTMLElement;
  const fromArea = target === pasteEl.value;
  // 单个值粘贴到单元格：按普通输入处理
  if (!fromArea && grid.length <= 1 && (grid[0]?.length ?? 0) <= 1) return;
  e.preventDefault();
  e.stopPropagation();
  if (!grid.length) return;

  const cell = fromArea ? null : parseCell((target.closest('[data-field-key]') as HTMLElement | null)?.dataset.fieldKey ?? '');
  const anchorRow = cell && cell.table === props.table.key ? cell.row : rows.value.length;
  const anchorCol =
    cell && cell.table === props.table.key
      ? Math.max(
          0,
          columns.value.findIndex((c) => c.key === cell.field)
        )
      : 0;
  const header = headerMapping(grid[0], columns.value);
  const body = header ? grid.slice(1) : grid;
  const mapping: (string | null)[] = header ?? grid[0].map((_, j) => columns.value[anchorCol + j]?.key ?? null);
  const room = maxRows.value - anchorRow;
  const take = body.slice(0, Math.max(0, room));
  if (!take.length) {
    ElMessage.warning(`${props.table.label ?? props.table.key}最多 ${maxRows.value} 行`);
    return;
  }

  const fieldsByKey = new Map(columns.value.map((f) => [f.key, f]));
  const refLookups = new Map<string, Awaited<ReturnType<typeof resolveRefs>>>();
  for (const [j, key] of mapping.entries()) {
    const f = key ? fieldsByKey.get(key) : undefined;
    if (f && editable(f) && REF_WIDGETS.has(f.widget)) refLookups.set(f.key, await resolveRefs(f, take.map((r) => r[j] ?? '').filter(Boolean)));
  }

  const next = rows.value.map((r) => keyed({ ...r }, r.__k));
  const problems: Record<string, string | null> = {};
  for (const [i, src] of take.entries()) {
    const ri = anchorRow + i;
    if (!next[ri]) next[ri] = keyed(newRow());
    for (const [j, key] of mapping.entries()) {
      const f = key ? fieldsByKey.get(key) : undefined;
      if (!f || !editable(f) || src[j] === undefined) continue;
      const raw = src[j];
      const k = cellKey(ri, f.key);
      delete cands[k];
      let value: unknown;
      let error: string | undefined;
      let cs: Option[] | undefined;
      if (REF_WIDGETS.has(f.widget)) {
        const hit = raw ? refLookups.get(f.key)?.get(raw) : undefined;
        value = hit?.value;
        error = hit?.error;
        cs = hit?.candidates;
      } else {
        const r = convertCell(f, raw, await optionsOf(f));
        value = r.value;
        error = r.error;
        cs = r.candidates;
      }
      // 对不上的引用、数字、日期、开关先留空（原文在问题说明里）；选择类保留原文便于对照候选
      next[ri][f.key] = error && (REF_WIDGETS.has(f.widget) || KEEP_EMPTY.has(f.widget)) ? undefined : value;
      if (!error) error = checkField(f, props.access[props.table.key + '.' + f.key], value);
      problems[k] = error ? `第 ${ri + 1} 行：${error}` : null;
      if (cs?.length) cands[k] = cs;
    }
    // 粘贴行里没给的必填列
    for (const f of columns.value) {
      const k = cellKey(ri, f.key);
      if (problems[k] !== undefined || !editable(f)) continue;
      const err = checkField(f, props.access[props.table.key + '.' + f.key], next[ri][f.key]);
      if (err) problems[k] = `第 ${ri + 1} 行：${err}`;
    }
  }
  emit('update:modelValue', next);
  emit('cell-errors', problems);
  if (fromArea && pasteEl.value) pasteEl.value.value = '';
  const bad = Object.values(problems).filter(Boolean).length;
  const cut = body.length - take.length;
  const msg = `已粘贴 ${take.length} 行${bad ? `，${bad} 处需要修改（已标红）` : ''}${cut > 0 ? `；超出上限的 ${cut} 行未粘贴` : ''}`;
  if (bad || cut) ElMessage.warning(msg);
  else ElMessage.success(msg);
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
.ls-subtable__tools {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
/* 接收粘贴的输入框：不占位置，聚焦时可见轮廓 */
.ls-subtable__paste {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
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
.ls-subtable__cands {
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.ls-subtable__cand {
  margin: 2px 4px 0 0;
  padding: 0 6px;
  border: 1px solid var(--el-border-color);
  border-radius: 3px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  font: inherit;
  cursor: pointer;
}
.ls-subtable__cand:focus-visible {
  outline: 2px solid var(--el-color-primary);
}
</style>
