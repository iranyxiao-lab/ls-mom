<template>
  <el-form
    class="schema-form"
    :class="{ 'schema-form--single': single }"
    :label-width="single ? 'auto' : (schema.labelWidth ?? 100) + 'px'"
    :label-position="single ? 'top' : 'right'"
    data-testid="schema-form"
    @submit.prevent
  >
    <el-alert v-if="formError" type="error" :title="formError" :closable="false" class="schema-form__alert" />
    <el-alert v-for="w in warnings" :key="w" type="warning" :title="w" :closable="false" class="schema-form__alert" />
    <LayoutNodes :nodes="nodes" :ctx="ctx" />
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
import { computed, reactive, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import type { FormAction, FormSchema, LayoutNode } from '@/types/lsView';
import type { RendererApi } from '@/api/lsmom/form';
import { lazyFormApi } from '@/api/lsmom/lazyForm';
import { resolveFieldPolicy, type Access } from '@/utils/lsform/fieldPolicy';
import { computeFields, validateForm, type FormCheck, type FormContext } from '@/utils/lsform/validate';
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
    capture?: boolean;
    context?: FormContext;
    orgId?: number;
    api?: RendererApi;
    showActions?: boolean;
    metrics?: Record<string, unknown>;
  }>(),
  { api: () => lazyFormApi, showActions: true, context: () => ({}) }
);
const emit = defineEmits<{
  (e: 'update:modelValue', v: Record<string, any>): void;
  (e: 'action', payload: { action: FormAction; model: Record<string, any> }): void;
}>();

const errors = reactive<Record<string, string>>({});
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
  const next = { ...props.modelValue, [key]: v };
  computeFields(props.schema, next, context.value);
  delete errors[key];
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
  single: props.single,
  capture: props.capture,
  metrics: props.metrics
}));

/** 默认值与计算字段初始化（只补缺失的键）。 */
function init() {
  if (props.readonly) return;
  const next: Record<string, any> = { ...props.modelValue };
  let changed = false;
  for (const f of props.schema.fields) {
    if (f.default !== undefined && next[f.key] === undefined) {
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
watch(() => props.schema, init, { immediate: true });

function validate(): FormCheck {
  const r = validateForm(props.schema, { ...props.modelValue }, access.value, context.value);
  Object.keys(errors).forEach((k) => delete errors[k]);
  Object.assign(errors, r.errors);
  warnings.value = r.warnings;
  return r;
}

/** 服务端返回的字段错误（VALIDATION_FAILED details.fields）回填到字段。 */
function setServerErrors(fieldErrors: Record<string, string>) {
  Object.keys(errors).forEach((k) => delete errors[k]);
  Object.assign(errors, fieldErrors);
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

defineExpose({ validate, setServerErrors, payload, access, errors });
</script>

<style scoped>
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
