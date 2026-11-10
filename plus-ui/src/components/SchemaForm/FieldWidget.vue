<template>
  <!-- 只读展示 -->
  <div v-if="!editable && !alwaysInteractive" class="ls-field__text" :data-testid="'text-' + field.key">
    <RefSelect
      v-if="refSpec && !empty"
      :model-value="modelValue"
      :ref-spec="refSpec"
      :api="api"
      :multiple="field.multiple"
      disabled
      :org-id="orgId"
    />
    <UploadField v-else-if="isFile" :model-value="modelValue" :api="api" :image="field.widget === 'image'" disabled />
    <span v-else>{{ display }}</span>
  </div>
  <component
    :is="registered"
    v-else-if="field.widget === 'registered' && registered"
    v-bind="field.component?.props ?? {}"
    :model-value="modelValue"
    :disabled="!editable"
    @update:model-value="set"
  />
  <el-alert v-else-if="field.widget === 'registered'" type="error" :closable="false" :title="'组件未注册：' + (field.component?.code ?? '')" />
  <el-input
    :aria-label="ariaLabel"
    v-else-if="field.widget === 'input'"
    :model-value="modelValue as any"
    :maxlength="field.maxLength"
    :placeholder="field.placeholder"
    clearable
    :data-testid="'input-' + field.key"
    @update:model-value="set"
  />
  <el-input
    :aria-label="ariaLabel"
    v-else-if="field.widget === 'textarea'"
    type="textarea"
    :rows="3"
    :model-value="modelValue as any"
    :maxlength="field.maxLength"
    :show-word-limit="!!field.maxLength"
    :placeholder="field.placeholder"
    :data-testid="'input-' + field.key"
    @update:model-value="set"
  />
  <el-input-number
    :aria-label="ariaLabel"
    v-else-if="field.widget === 'number' || field.widget === 'money'"
    :model-value="modelValue as any"
    :min="field.min"
    :max="field.max"
    :precision="field.widget === 'money' ? (field.precision ?? 2) : field.precision"
    :controls="false"
    style="width: 100%"
    :data-testid="'input-' + field.key"
    @update:model-value="set"
  />
  <el-date-picker
    :aria-label="ariaLabel"
    v-else-if="field.widget === 'date' || field.widget === 'datetime'"
    :model-value="modelValue as any"
    :type="field.widget"
    :value-format="field.widget === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DDTHH:mm:ss'"
    style="width: 100%"
    :data-testid="'input-' + field.key"
    @update:model-value="set"
  />
  <el-switch
    :aria-label="ariaLabel"
    v-else-if="field.widget === 'switch'"
    :model-value="!!modelValue"
    :data-testid="'input-' + field.key"
    @update:model-value="set"
  />
  <el-select
    :aria-label="ariaLabel"
    v-else-if="field.widget === 'select' || field.widget === 'dict'"
    :model-value="modelValue as any"
    :multiple="field.multiple"
    filterable
    default-first-option
    clearable
    style="width: 100%"
    :data-testid="'input-' + field.key"
    @update:model-value="set"
  >
    <el-option v-for="o in options" :key="String(o.value)" :label="o.label" :value="o.value" />
  </el-select>
  <el-radio-group
    :aria-label="ariaLabel"
    v-else-if="field.widget === 'radio'"
    :model-value="modelValue as any"
    :data-testid="'input-' + field.key"
    @update:model-value="set"
  >
    <el-radio v-for="o in options" :key="String(o.value)" :value="o.value">{{ o.label }}</el-radio>
  </el-radio-group>
  <el-checkbox-group
    :aria-label="ariaLabel"
    v-else-if="field.widget === 'checkbox'"
    :model-value="(modelValue as any) ?? []"
    :data-testid="'input-' + field.key"
    @update:model-value="set"
  >
    <el-checkbox v-for="o in options" :key="String(o.value)" :value="o.value">{{ o.label }}</el-checkbox>
  </el-checkbox-group>
  <el-cascader
    :aria-label="ariaLabel"
    v-else-if="field.widget === 'cascader'"
    :model-value="modelValue as any"
    :options="field.options as any"
    :props="{ value: 'value', label: 'label', children: 'children', emitPath: false, multiple: field.multiple }"
    clearable
    style="width: 100%"
    @update:model-value="set"
  />
  <el-rate
    :aria-label="ariaLabel"
    v-else-if="field.widget === 'rate'"
    :model-value="(modelValue as any) ?? 0"
    :max="field.max ?? 5"
    @update:model-value="set"
  />
  <RefSelect
    :aria-label="ariaLabel"
    v-else-if="refSpec"
    :model-value="modelValue"
    :ref-spec="refSpec"
    :api="api"
    :multiple="field.multiple"
    :org-id="orgId"
    :model="model"
    :placeholder="field.placeholder"
    :label="field.label"
    :data-testid="'input-' + field.key"
    @update:model-value="set"
    @scanned="emit('scan-error', null)"
    @scan-error="(m: string) => emit('scan-error', m)"
  />
  <UploadField
    v-else-if="isFile"
    :model-value="modelValue"
    :api="api"
    :image="field.widget === 'image'"
    :max-count="field.maxCount"
    :accept="field.accept"
    :capture="capture"
    @update:model-value="set"
  />
  <el-input
    :aria-label="ariaLabel"
    v-else-if="field.widget === 'sourceRef'"
    :model-value="modelValue as any"
    placeholder="来源编号或链接"
    @update:model-value="set"
  />
  <el-checkbox v-else-if="field.widget === 'confirm'" :model-value="!!modelValue" :data-testid="'input-' + field.key" @update:model-value="set">
    {{ field.help ?? '我已确认' }}
  </el-checkbox>
  <span v-else class="ls-field__text">{{ display }}</span>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { dictOptions as loadDictOptions } from './dictCache';
import type { FormField, Option } from '@/types/lsView';
import type { RendererApi } from '@/api/lsmom/form';
import RefSelect from './RefSelect.vue';
import UploadField from './UploadField.vue';
import { refOf, REF_WIDGETS } from './refDefaults';
import { resolveFormComponent } from './registry';

const props = defineProps<{
  /** 无可见标签时的无障碍名称（子表单元格：列名 第 n 行） */
  ariaLabel?: string;
  field: FormField;
  modelValue: unknown;
  editable: boolean;
  api: RendererApi;
  orgId?: number;
  model?: Record<string, unknown>;
  capture?: boolean;
  /** 注册组件在只读时仍以组件自身渲染 */
  alwaysInteractive?: boolean;
}>();
const emit = defineEmits<{ (e: 'update:modelValue', v: unknown): void; (e: 'scan-error', message: string | null): void }>();

const set = (v: unknown) => emit('update:modelValue', v === '' ? null : v);
const refSpec = computed(() => (REF_WIDGETS.has(props.field.widget) ? refOf(props.field) : undefined));
const isFile = computed(() => ['attachment', 'image', 'file'].includes(props.field.widget));
const registered = computed(() => resolveFormComponent(props.field.component?.code, props.field.component?.version));
const empty = computed(() => props.modelValue === null || props.modelValue === undefined || props.modelValue === '');

const dictOptions = ref<Option[]>([]);
const options = computed<Option[]>(() => (props.field.widget === 'dict' ? dictOptions.value : (props.field.options ?? [])));

async function loadDict() {
  if (props.field.widget !== 'dict' || !props.field.dictType) return;
  dictOptions.value = await loadDictOptions(props.api, props.field.dictType);
}
onMounted(loadDict);
watch(() => props.field.dictType, loadDict);

function labelOf(v: unknown, opts: Option[]): string | undefined {
  for (const o of opts) {
    if (String(o.value) === String(v)) return o.label;
    const c = o.children && labelOf(v, o.children);
    if (c) return c;
  }
  return undefined;
}

const display = computed(() => {
  const v = props.modelValue;
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'boolean') return v ? '是' : '否';
  if (Array.isArray(v)) return v.map((x) => labelOf(x, options.value) ?? String(x)).join('、');
  const label = labelOf(v, options.value);
  if (label) return label;
  if (props.field.widget === 'money') return Number(v).toFixed(props.field.precision ?? 2) + (props.field.unit ? ' ' + props.field.unit : '');
  return String(v) + (props.field.unit ? ' ' + props.field.unit : '');
});
</script>

<style scoped>
.ls-field__text {
  min-height: 32px;
  line-height: 32px;
  word-break: break-all;
}
</style>
