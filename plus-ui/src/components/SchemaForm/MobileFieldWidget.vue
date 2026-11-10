<template>
  <!-- 只读、引用、附件、注册组件等沿用桌面控件实现 -->
  <FieldWidget v-if="!editable || !mobileKind" v-bind="$props" @update:model-value="set" @scan-error="(m: string | null) => emit('scan-error', m)" />
  <div v-else-if="mobileKind === 'text'" class="m-field m-field--text">
    <van-field
      :model-value="(modelValue as any) ?? ''"
      :type="field.widget === 'textarea' ? 'textarea' : 'text'"
      :rows="field.widget === 'textarea' ? 3 : undefined"
      autosize
      :maxlength="field.maxLength"
      :placeholder="field.placeholder || '输入或按住话筒说话'"
      :aria-label="ariaLabel ?? field.label"
      :data-testid="'input-' + field.key"
      class="m-field__input"
      @update:model-value="set"
    />
    <VoiceButton :testid="'voice-' + field.key" @text="appendText" />
  </div>
  <van-stepper
    v-else-if="mobileKind === 'number'"
    :model-value="(modelValue as any) ?? ''"
    :min="field.min ?? 0"
    :max="field.max ?? Infinity"
    :step="(field as any).step ?? 1"
    :decimal-length="decimals"
    :integer="decimals === 0"
    allow-empty
    button-size="44px"
    input-width="96px"
    class="m-field__stepper"
    :data-testid="'input-' + field.key"
    @update:model-value="setNumber"
  />
  <van-switch
    v-else-if="mobileKind === 'switch'"
    :model-value="!!modelValue"
    size="28px"
    :data-testid="'input-' + field.key"
    @update:model-value="set"
  />
  <!-- 选项少时平铺成按钮（一次点按），多时用选择器 -->
  <div
    v-else-if="mobileKind === 'chips'"
    class="m-chips"
    role="radiogroup"
    :aria-label="ariaLabel ?? field.label"
    :data-testid="'input-' + field.key"
  >
    <button
      v-for="o in options"
      :key="String(o.value)"
      type="button"
      role="radio"
      :aria-checked="isOn(o.value)"
      class="m-chip"
      :class="{ 'is-on': isOn(o.value) }"
      :data-testid="`opt-${field.key}-${o.value}`"
      @click="toggle(o.value)"
    >
      {{ o.label }}
    </button>
  </div>
  <template v-else-if="mobileKind === 'picker'">
    <van-field
      :model-value="pickedLabel"
      readonly
      is-link
      :placeholder="field.placeholder || '请选择'"
      :aria-label="ariaLabel ?? field.label"
      :data-testid="'input-' + field.key"
      @click="pickerOpen = true"
    />
    <van-popup v-model:show="pickerOpen" position="bottom" round teleport="body">
      <van-picker :columns="pickerColumns" :title="field.label" @confirm="onPick" @cancel="pickerOpen = false" />
    </van-popup>
  </template>
  <input
    v-else-if="mobileKind === 'date'"
    class="m-field__date"
    :type="field.widget === 'date' ? 'date' : 'datetime-local'"
    :value="dateValue"
    :aria-label="ariaLabel ?? field.label"
    :data-testid="'input-' + field.key"
    @change="onDate"
  />
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { Field as VanField, Picker as VanPicker, Popup as VanPopup, Stepper as VanStepper, Switch as VanSwitch } from 'vant';
import FieldWidget from './FieldWidget.vue';
import VoiceButton from '@/mobile/VoiceButton.vue';
import { dictOptions as loadDictOptions } from './dictCache';
import type { FormField, Option } from '@/types/lsView';
import type { RendererApi } from '@/api/lsmom/form';

/**
 * 移动端字段控件（渲染器 platform = mobile）：文字可语音输入，数字用数字键盘与 ± 步进，
 * 少量选项平铺为大按钮一次点选，日期用系统选择器；触控目标不小于 44px。其余控件沿用桌面实现。
 */
const props = defineProps<{
  ariaLabel?: string;
  field: FormField;
  modelValue: unknown;
  editable: boolean;
  api: RendererApi;
  orgId?: number;
  model?: Record<string, unknown>;
  capture?: boolean;
  alwaysInteractive?: boolean;
}>();
const emit = defineEmits<{ (e: 'update:modelValue', v: unknown): void; (e: 'scan-error', message: string | null): void }>();

const set = (v: unknown) => emit('update:modelValue', v === '' ? null : v);

const dict = ref<Option[]>([]);
const options = computed<Option[]>(() => (props.field.widget === 'dict' ? dict.value : (props.field.options ?? [])));
async function loadDict() {
  if (props.field.widget === 'dict' && props.field.dictType) dict.value = await loadDictOptions(props.api, props.field.dictType);
}
onMounted(loadDict);
watch(() => props.field.dictType, loadDict);

const mobileKind = computed(() => {
  const w = props.field.widget;
  if (w === 'input' || w === 'textarea') return 'text';
  if (w === 'number' || w === 'money') return 'number';
  if (w === 'switch') return 'switch';
  if (w === 'date' || w === 'datetime') return 'date';
  if (w === 'radio' || w === 'checkbox' || w === 'select' || w === 'dict') {
    if (w === 'checkbox' || props.field.multiple) return 'chips';
    return options.value.length && options.value.length <= 6 ? 'chips' : 'picker';
  }
  return null;
});

const decimals = computed(() => (props.field.widget === 'money' ? (props.field.precision ?? 2) : (props.field.precision ?? 0)));
function setNumber(v: string | number) {
  set(v === '' || v === null || v === undefined ? null : Number(v));
}

function appendText(t: string) {
  const cur = (props.modelValue as string | null) ?? '';
  set(cur ? `${cur}${/[，。,.\s]$/.test(cur) ? '' : '，'}${t}` : t);
}

const multi = computed(() => props.field.widget === 'checkbox' || !!props.field.multiple);
const isOn = (v: unknown) => (multi.value ? ((props.modelValue as unknown[]) ?? []).includes(v) : props.modelValue === v);
function toggle(v: unknown) {
  if (!multi.value) return set(props.modelValue === v && !props.field.required ? null : v);
  const cur = [...(((props.modelValue as unknown[]) ?? []) as unknown[])];
  const i = cur.indexOf(v);
  if (i >= 0) cur.splice(i, 1);
  else cur.push(v);
  set(cur);
}

const pickerOpen = ref(false);
const pickerColumns = computed(() => options.value.map((o) => ({ text: o.label, value: o.value as any })));
const pickedLabel = computed(() => options.value.find((o) => o.value === props.modelValue)?.label ?? '');
function onPick({ selectedValues }: { selectedValues: unknown[] }) {
  pickerOpen.value = false;
  set(selectedValues[0]);
}

const dateValue = computed(() => {
  const v = props.modelValue as string | null;
  if (!v) return '';
  return props.field.widget === 'date' ? String(v).slice(0, 10) : String(v).slice(0, 16);
});
function onDate(e: Event) {
  const v = (e.target as HTMLInputElement).value;
  set(!v ? null : props.field.widget === 'date' ? v : v.length === 16 ? `${v}:00` : v);
}
</script>

<style scoped>
.m-field--text {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  width: 100%;
}
.m-field__input {
  flex: 1;
  border: 1px solid var(--ls-color-border, #dcdfe6);
  border-radius: 8px;
  padding: 6px 10px;
  min-height: 44px;
}
.m-field__date {
  min-height: 44px;
  width: 100%;
  font-size: 16px;
  border: 1px solid var(--ls-color-border, #dcdfe6);
  border-radius: 8px;
  padding: 0 10px;
  background: var(--ls-color-bg-elevated);
  color: inherit;
}
.m-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.m-chip {
  min-height: 44px;
  min-width: 64px;
  padding: 0 14px;
  border-radius: 22px;
  border: 1px solid var(--ls-color-border, #dcdfe6);
  background: var(--ls-color-bg-elevated);
  color: inherit;
  font-size: 15px;
}
.m-chip.is-on {
  border-color: var(--ls-color-primary, #1677ff);
  background: var(--ls-color-primary, #1677ff);
  color: var(--ls-color-on-solid);
}
</style>
