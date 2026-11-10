<template>
  <el-form
    ref="rootRef"
    class="schema-form"
    :class="{ 'schema-form--single': single }"
    :label-width="single ? 'auto' : (schema.labelWidth ?? 100) + 'px'"
    :label-position="single ? 'top' : 'right'"
    data-testid="schema-form"
    @submit.prevent
    @focusout="onBlur"
    @keydown.capture="onKeydown"
  >
    <div v-if="draftState.found.value" class="schema-form__draft" role="status" data-testid="draft-found">
      <span>发现未提交的草稿（{{ draftTime(draftState.found.value.savedAt) }}）</span>
      <el-button size="small" type="primary" data-testid="draft-restore" @click="restoreDraft">恢复</el-button>
      <el-button size="small" data-testid="draft-discard" @click="discardDraft">丢弃</el-button>
    </div>
    <div v-else-if="draftState.savedAt.value" class="schema-form__draft-saved" data-testid="draft-saved">
      草稿已自动保存 {{ draftTime(draftState.savedAt.value) }}
    </div>
    <div v-if="showSummary && summary.length" class="schema-form__summary" role="alert" data-testid="error-summary">
      <div class="schema-form__summary-title">有 {{ summary.length }} 处需要修改，点击定位：</div>
      <ul>
        <li v-for="item in summary" :key="item.key">
          <button type="button" class="schema-form__summary-item" data-testid="error-summary-item" :data-key="item.key" @click="locate(item.key)">
            <b>{{ item.label }}</b
            >：{{ item.message }}<span v-if="item.fix" class="schema-form__fix">（{{ item.fix }}）</span>
          </button>
        </li>
      </ul>
    </div>
    <el-alert v-if="formError" type="error" :title="formError" :closable="false" class="schema-form__alert" />
    <el-alert v-for="w in warnings" :key="w" type="warning" :title="w" :closable="false" class="schema-form__alert" />
    <LayoutNodes :nodes="nodes" :ctx="ctx" />
    <div v-if="hotkeys && hotkeyHints && !readonly" class="schema-form__hints" data-testid="form-hotkey-hints">
      Enter 下一项 · {{ hotkeyLabel('mod+s') }} 保存草稿 · {{ hotkeyLabel('mod+enter') }} 提交
    </div>
    <div v-if="showActions && actions.length" class="schema-form__actions">
      <el-button
        v-for="a in actions"
        :key="a.key"
        :type="a.style === 'default' ? undefined : (a.style ?? 'primary')"
        :disabled="readonly || busy"
        :data-testid="'action-' + a.key"
        @click="onAction(a)"
      >
        {{ a.label }}
      </el-button>
    </div>
  </el-form>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import type { FormAction, FormSchema, LayoutNode } from '@/types/lsView';
import type { RendererApi } from '@/api/lsmom/form';
import { lazyFormApi } from '@/api/lsmom/lazyForm';
import { resolveFieldPolicy, type Access } from '@/utils/lsform/fieldPolicy';
import { computeFields, validateForm, type FormCheck, type FormContext } from '@/utils/lsform/validate';
import { rememberValues, smartDefaults, usesSmartDefaults, type FillOrigin } from '@/utils/lsform/smartDefaults';
import { useDraft } from '@/utils/lsform/useDraft';
import { mapServerErrors, summaryItems } from '@/utils/lsform/serverErrors';
import { fieldHosts, focusHost, parseCell, selectOpen } from '@/utils/lsform/keyboardNav';
import { hotkeyHints, hotkeyLabel, useHotkeys } from '@/utils/hotkeys';
import { draftTime } from '@/utils/lsform/draftModel';
import { currentUserId } from '@/utils/currentUser';
import LayoutNodes from './LayoutNodes.vue';
import type { RenderCtx } from './types';

/**
 * LS FormSchema 运行时渲染器：录入、只读详情、流程节点、移动端、设计器预览共用（"预览即运行"）。
 * 字段可见/可编辑/必填由 resolveFieldPolicy 计算（与服务端同一算法）；动作只发出事件，由调用方经 Operation 执行。
 */
const props = withDefaults(
  defineProps<{
    schema: FormSchema;
    modelValue: Record<string, any>;
    /** 流程节点键：按 nodePolicies 计算字段策略 */
    node?: string;
    /** 整单只读（历史记录、详情） */
    readonly?: boolean;
    /** 只渲染这些字段（移动端精简），校验仍按完整表单 */
    only?: string[];
    /** 单列布局（移动端） */
    single?: boolean;
    /** 控件实现：mobile 时用触控控件（并自动单列） */
    platform?: 'desktop' | 'mobile';
    capture?: boolean;
    context?: FormContext;
    orgId?: number;
    api?: RendererApi;
    showActions?: boolean;
    metrics?: Record<string, unknown>;
    /** 发起表单的来源对象（如从设备报缺陷时的设备），供 defaultFrom: $source.<字段> 带入 */
    source?: Record<string, unknown> | null;
    /** 新单据按 defaultFrom / remember 预填（默认开启；已有记录、只读时不预填） */
    smart?: boolean;
    /** 草稿自动保存的键：表单或 Concept 编码 + 记录 id（新单据用 new）；不传则不保存草稿。本机草稿按当前用户隔离 */
    draftKey?: string;
    /** 键盘录入快捷键：Ctrl/⌘+S 触发 save、Ctrl/⌘+Enter 触发 submit（由调用方保存与提交） */
    hotkeys?: boolean;
    /** 打开后聚焦第一个可录入的字段 */
    autofocus?: boolean;
  }>(),
  { api: () => lazyFormApi, showActions: true, context: () => ({}), smart: true }
);
const emit = defineEmits<{
  (e: 'update:modelValue', v: Record<string, any>): void;
  (e: 'action', payload: { action: FormAction; model: Record<string, any> }): void;
  (e: 'save'): void;
  (e: 'submit'): void;
}>();

const errors = reactive<Record<string, string>>({});
/** 服务端给出的修复提示（键同 errors） */
const fixes = reactive<Record<string, string>>({});
/** 提交失败后显示顶部错误汇总 */
const showSummary = ref(false);
const rootRef = ref<{ $el: HTMLElement }>();
/** 预填字段的来源（用户改动后清除），用于在标签旁区分"上次填写"与"自动带入" */
const origin = reactive<Record<string, FillOrigin>>({});
/** 用户是否动过表单（只有预填值时不保存草稿） */
const touched = ref(false);

const draftState = useDraft({
  key: () => (props.readonly ? undefined : props.draftKey),
  user: () => currentUserId.value,
  model: () => props.modelValue,
  dirty: () => touched.value,
  api: props.api
});

/** 恢复草稿：写回录入内容；已有记录保留当前的 id 与版本号（版本冲突仍由服务端判断）。 */
function restoreDraft() {
  const content = draftState.take();
  if (!content) return;
  const keep: Record<string, unknown> = {};
  for (const k of ['id', 'version', 'status', 'schemaVersion']) if (props.modelValue[k] !== undefined) keep[k] = props.modelValue[k];
  const next = { ...props.modelValue, ...content, ...keep };
  computeFields(props.schema, next, context.value);
  Object.keys(origin).forEach((k) => delete origin[k]);
  touched.value = true;
  emit('update:modelValue', next);
}
async function discardDraft() {
  await draftState.clear();
}
const warnings = ref<string[]>([]);
const busy = ref(false);

const access = computed<Record<string, Access>>(() => {
  const base = resolveFieldPolicy(props.schema, props.node ? props.schema.nodePolicies?.[props.node] : undefined);
  if (!props.readonly) return base;
  const ro: Record<string, Access> = {};
  for (const [k, a] of Object.entries(base)) ro[k] = { ...a, editable: false };
  return ro;
});

const fields = computed(() => new Map(props.schema.fields.map((f) => [f.key, f])));
const tables = computed(() => new Map((props.schema.subTables ?? []).map((t) => [t.key, t])));
const nodes = computed<LayoutNode[]>(() => props.schema.layout);
const actions = computed(() => props.schema.actions ?? []);
const formError = computed(() => errors._form);
const context = computed<FormContext>(() => ({ orgId: props.orgId, deptId: props.orgId, ...props.context }));

function set(key: string, v: unknown) {
  const prev = props.modelValue[key];
  const next = { ...props.modelValue, [key]: v };
  computeFields(props.schema, next, context.value);
  delete errors[key];
  delete fixes[key];
  // 子表：改动过的单元格清掉错误
  if (tables.value.has(key) && Array.isArray(v)) {
    const before = Array.isArray(prev) ? prev : [];
    v.forEach((row: any, i: number) => {
      for (const f of tables.value.get(key)!.fields) {
        if (before[i]?.[f.key] !== row?.[f.key]) {
          delete errors[`${key}[${i}].${f.key}`];
          delete fixes[`${key}[${i}].${f.key}`];
        }
      }
    });
  }
  delete origin[key];
  touched.value = true;
  emit('update:modelValue', next);
}

const ctx = computed<RenderCtx>(() => ({
  fields: fields.value,
  tables: tables.value,
  model: props.modelValue,
  access: access.value,
  errors,
  api: props.api,
  set,
  orgId: props.orgId,
  only: props.only ? new Set(props.only) : undefined,
  single: props.single || props.platform === 'mobile',
  platform: props.platform,
  capture: props.capture,
  metrics: props.metrics,
  origin,
  setCellErrors
}));

function setCellErrors(map: Record<string, string | null>) {
  for (const [k, v] of Object.entries(map)) {
    if (v) errors[k] = v;
    else delete errors[k];
    delete fixes[k];
  }
}

/** 默认值与计算字段初始化（只补缺失的键）。 */
function init() {
  if (props.readonly) return;
  const next: Record<string, any> = { ...props.modelValue };
  let changed = false;
  for (const f of props.schema.fields) {
    // 带入与记忆字段的默认值由 applySmart 按优先级处理
    if (f.default !== undefined && next[f.key] === undefined && !(useSmart() && (f.defaultFrom || f.remember))) {
      next[f.key] = f.default;
      changed = true;
    }
  }
  for (const t of props.schema.subTables ?? []) {
    if (next[t.key] === undefined) {
      next[t.key] = [];
      changed = true;
    }
  }
  const before = JSON.stringify(props.schema.fields.filter((f) => f.computed).map((f) => next[f.key]));
  computeFields(props.schema, next, context.value);
  if (changed || before !== JSON.stringify(props.schema.fields.filter((f) => f.computed).map((f) => next[f.key]))) emit('update:modelValue', next);
}
const useSmart = () => props.smart && !props.readonly && props.modelValue.id == null && usesSmartDefaults(props.schema);

/** 新单据：按来源对象、上次填写、当前人员/组织/班次/时间预填（只填仍为空的字段，不覆盖用户已输入的内容）。 */
async function applySmart() {
  if (!useSmart()) return;
  const schema = props.schema;
  const server = props.api.formContext ? await props.api.formContext(schema.formCode).catch(() => undefined) : undefined;
  if (schema !== props.schema) return;
  const { values, origin: o } = smartDefaults(schema, props.modelValue, { ...(server ?? {}), source: props.source });
  if (!Object.keys(values).length) return;
  const next = { ...props.modelValue, ...values };
  computeFields(schema, next, context.value);
  Object.assign(origin, o);
  emit('update:modelValue', next);
}

watch(
  () => props.schema,
  () => {
    Object.keys(origin).forEach((k) => delete origin[k]);
    touched.value = false;
    init();
    void applySmart();
  },
  { immediate: true }
);
watch(
  () => props.source,
  () => void applySmart()
);

/** 提交成功后调用：记住标了 remember 的字段本次填写的值（保存在服务端，失败不影响业务）。 */
async function commitRemembered() {
  const values = rememberValues(props.schema, props.modelValue);
  if (!props.api.remember || !Object.keys(values).length) return;
  await props.api.remember(props.schema.formCode, values).catch(() => undefined);
}

const summary = computed(() => summaryItems(props.schema, errors, fixes));

/** 定位到字段或子表单元格：滚动到可见位置并聚焦其中的输入框。 */
function locate(key: string) {
  const root = rootRef.value?.$el;
  if (!root) return;
  const el = root.querySelector<HTMLElement>(`[data-field-key="${CSS.escape(key)}"]`);
  if (!el) return;
  el.scrollIntoView({ block: 'center' });
  const input = el.querySelector<HTMLElement>(
    'input:not([type="hidden"]):not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  );
  input?.focus({ preventScroll: true });
}

function showErrors() {
  showSummary.value = summary.value.length > 0;
  if (showSummary.value) void nextTick(() => locate(summary.value[0].key));
}

/** 整单校验；有错误时显示顶部汇总并滚动到第一个错误（focus: false 时只校验）。 */
function validate(opts: { focus?: boolean } = {}): FormCheck {
  const r = validateForm(props.schema, { ...props.modelValue }, access.value, context.value);
  Object.keys(errors).forEach((k) => delete errors[k]);
  Object.keys(fixes).forEach((k) => delete fixes[k]);
  Object.assign(errors, r.errors);
  warnings.value = r.warnings;
  if (opts.focus !== false) showErrors();
  return r;
}

/** 子表新增一行（带列默认值）。 */
function addRow(tableKey: string) {
  const t = tables.value.get(tableKey);
  if (!t) return;
  const row: Record<string, unknown> = {};
  for (const f of t.fields) if (f.default !== undefined) row[f.key] = f.default;
  const rows = Array.isArray(props.modelValue[tableKey]) ? props.modelValue[tableKey] : [];
  set(tableKey, [...rows, row]);
}

/**
 * Enter 跳到下一个可录入的字段；子表最后一行的最后一个单元格按 Enter 新增一行并聚焦新行第一个单元格。
 * 多行文本、按钮、展开中的下拉框（Enter 用于选中选项）和输入法组字时不拦截。
 */
async function onKeydown(e: KeyboardEvent) {
  if (e.key !== 'Enter' || e.ctrlKey || e.metaKey || e.altKey || e.shiftKey || e.isComposing || props.readonly) return;
  const t = e.target as HTMLElement;
  if (t.tagName === 'TEXTAREA' || t.tagName === 'BUTTON') return;
  const host = t.closest<HTMLElement>('[data-field-key]');
  const root = rootRef.value?.$el;
  if (!host || !root) return;
  // 日期控件离开时会按输入确认取值，Enter 直接跳到下一项
  if (!t.closest('.el-date-editor') && selectOpen(t)) return;
  // 扫码枪正在输入：Enter 留给引用字段识别编码
  if (t.closest('[data-ls-scanning]')) return;
  e.preventDefault();
  e.stopPropagation();
  const hosts = fieldHosts(root);
  const i = hosts.indexOf(host);
  const cell = parseCell(host.dataset.fieldKey!);
  if (cell) {
    const rows = Array.isArray(props.modelValue[cell.table]) ? props.modelValue[cell.table] : [];
    const rowHosts = hosts.filter((h) => {
      const c = parseCell(h.dataset.fieldKey!);
      return c?.table === cell.table && c.row === cell.row;
    });
    const t2 = tables.value.get(cell.table)!;
    const canAdd = access.value[cell.table]?.editable !== false && rows.length < (t2.maxRows ?? 1000);
    if (rowHosts[rowHosts.length - 1] === host && cell.row === rows.length - 1 && canAdd) {
      addRow(cell.table);
      await nextTick();
      await nextTick();
      const next = fieldHosts(root).find((h) => {
        const c = parseCell(h.dataset.fieldKey!);
        return c?.table === cell.table && c.row === cell.row + 1;
      });
      focusHost(next);
      return;
    }
  }
  if (i >= 0) focusHost(hosts[i + 1]);
}

onMounted(async () => {
  if (!props.autofocus || props.readonly) return;
  await nextTick();
  // 抽屉、对话框的进场动画结束后再聚焦
  setTimeout(() => {
    const root = rootRef.value?.$el;
    if (root && !root.contains(document.activeElement)) focusHost(fieldHosts(root)[0]);
  }, 350);
});

useHotkeys(
  () => rootRef.value?.$el,
  {
    'mod+s': () => emit('save'),
    'mod+enter': () => emit('submit')
  },
  () => !!props.hotkeys && !props.readonly
);

/** 失焦校验：离开字段或单元格时就近显示该处的错误。 */
function onBlur(e: FocusEvent) {
  const host = (e.target as HTMLElement | null)?.closest?.('[data-field-key]') as HTMLElement | null;
  const key = host?.dataset.fieldKey;
  if (!key || props.readonly) return;
  // 焦点仍在同一字段内（如下拉框）时不校验
  if (e.relatedTarget && host!.contains(e.relatedTarget as Node)) return;
  // 日期等控件在失焦时才写回取值：等取值更新后再校验
  setTimeout(() => {
    const r = validateForm(props.schema, { ...props.modelValue }, access.value, context.value);
    // 已有更具体的原因（服务端或扫码给出的）时保留，取值改动后才清除
    if (r.errors[key] && !errors[key]) errors[key] = r.errors[key];
  }, 0);
}

/**
 * 服务端字段错误回填到字段与子表单元格（接受请求错误对象、details 或 键 → 文字），显示汇总并定位到第一个错误。
 * 返回是否识别为字段错误。
 */
function setServerErrors(input: unknown): boolean {
  const mapped = mapServerErrors(input);
  if (!mapped) return false;
  Object.keys(errors).forEach((k) => delete errors[k]);
  Object.keys(fixes).forEach((k) => delete fixes[k]);
  Object.assign(errors, mapped.errors);
  Object.assign(fixes, mapped.fixes);
  showErrors();
  return true;
}

/** 提交报文：去掉不可编辑字段（服务端同样拒绝修改它们），附带表单编码与版本。 */
function payload(): Record<string, any> {
  const out: Record<string, any> = {};
  for (const [k, v] of Object.entries(props.modelValue)) {
    const a = access.value[k];
    if (k.startsWith('_') || (a && !a.editable && !tables.value.has(k))) continue;
    out[k] = v;
  }
  out._form = { code: props.schema.formCode, version: props.schema.version };
  return out;
}

async function onAction(a: FormAction) {
  const r = validate();
  if (Object.keys(r.errors).length) return;
  if (a.confirm) {
    try {
      await ElMessageBox.confirm(a.confirm, a.label, { type: 'warning' });
    } catch {
      return;
    }
  }
  emit('action', { action: a, model: payload() });
}

/** 提交成功后调用：删除本机与服务端草稿。 */
const clearDraft = () => draftState.clear();

defineExpose({ validate, setServerErrors, locate, payload, access, errors, origin, commitRemembered, clearDraft, flushDraft: draftState.flush });
</script>

<style scoped>
.schema-form__draft {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: 4px;
  background: var(--el-color-warning-light-9);
  color: var(--el-text-color-primary);
}
.schema-form__draft > span {
  flex: 1;
}
.schema-form__draft-saved {
  margin-bottom: 8px;
  text-align: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.schema-form__summary {
  margin-bottom: 12px;
  padding: 8px 12px;
  border: 1px solid var(--el-color-danger-light-5);
  border-radius: 4px;
  background: var(--el-color-danger-light-9);
  color: var(--el-text-color-primary);
}
.schema-form__summary ul {
  margin: 4px 0 0;
  padding-left: 18px;
}
.schema-form__summary-item {
  padding: 2px 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  text-decoration: underline;
  cursor: pointer;
}
.schema-form__summary-item:focus-visible {
  outline: 2px solid var(--el-color-primary);
}
.schema-form__fix {
  color: var(--el-text-color-regular);
}
.schema-form__hints {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.schema-form__alert {
  margin-bottom: 12px;
}
.schema-form__actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding-top: 8px;
}
</style>
