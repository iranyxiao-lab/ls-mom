<template>
  <div class="schema-mobile" data-testid="schema-mobile">
    <div v-if="restored" class="schema-mobile__hint" data-testid="draft-restored">已恢复本机暂存的草稿</div>
    <SchemaForm
      ref="formRef"
      :schema="form"
      :model-value="modelValue"
      :only="only"
      single
      :capture="true"
      :org-id="orgId"
      :context="context"
      :api="api"
      :show-actions="false"
      @update:model-value="onInput"
    />
    <el-alert v-if="hiddenErrors.length" type="error" :closable="false" :title="'以下字段需在电脑端补充：' + hiddenErrors.join('；')" data-testid="hidden-errors" />
    <div class="schema-mobile__bar">
      <el-button v-if="schema.offlineDraft" data-testid="save-draft" @click="saveLocal">暂存</el-button>
      <el-button v-for="a in schema.actions ?? []" :key="a.key" type="primary" :data-testid="'m-action-' + a.key" @click="act(a)">{{ a.label }}</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import type { FormAction, FormSchema, MobileSchema } from '@/types/lsView';
import type { RendererApi } from '@/api/lsmom/form';
import { lazyFormApi } from '@/api/lsmom/lazyForm';
import SchemaForm from '@/components/SchemaForm/index.vue';

/**
 * 移动端填报：只显示 MobileSchema 中的字段（单列、拍照直传），提交前仍按完整 FormSchema 校验（服务端同样按完整表单复核）；
 * 开启离线草稿时暂存到本机，重新打开自动恢复。
 */
const props = withDefaults(
  defineProps<{
    schema: MobileSchema;
    form: FormSchema;
    modelValue: Record<string, any>;
    orgId?: number;
    context?: Record<string, unknown>;
    api?: RendererApi;
  }>(),
  { api: () => lazyFormApi, context: () => ({}) }
);
const emit = defineEmits<{
  (e: 'update:modelValue', v: Record<string, any>): void;
  (e: 'action', payload: { action: FormAction; model: Record<string, any>; errors: Record<string, string> }): void;
}>();

const formRef = ref<InstanceType<typeof SchemaForm>>();
const restored = ref(false);
const hiddenErrors = ref<string[]>([]);
const draftKey = computed(() => `lsmom:mobile-draft:${props.schema.formCode}`);
const only = computed(() => {
  const keys = [...props.schema.fields];
  if (props.schema.photo) keys.push(props.schema.photo.field);
  return keys;
});

function onInput(v: Record<string, any>) {
  emit('update:modelValue', v);
}

function saveLocal() {
  try {
    localStorage.setItem(draftKey.value, JSON.stringify(props.modelValue));
  } catch {
    /* 隐私模式等无法写入时忽略 */
  }
}

function clearLocal() {
  try {
    localStorage.removeItem(draftKey.value);
  } catch {
    /* 忽略 */
  }
}

function act(a: FormAction) {
  const r = formRef.value!.validate();
  const shown = new Set(only.value);
  hiddenErrors.value = Object.entries(r.errors)
    .filter(([k]) => !shown.has(k.split(/[.[]/)[0]))
    .map(([, m]) => m);
  emit('action', { action: a, model: formRef.value!.payload(), errors: r.errors });
  if (!Object.keys(r.errors).length) clearLocal();
}

onMounted(() => {
  if (!props.schema.offlineDraft) return;
  try {
    const raw = localStorage.getItem(draftKey.value);
    if (raw) {
      emit('update:modelValue', { ...JSON.parse(raw), ...Object.fromEntries(Object.entries(props.modelValue).filter(([, v]) => v != null)) });
      restored.value = true;
    }
  } catch {
    /* 忽略损坏的暂存 */
  }
});

defineExpose({ saveLocal, clearLocal, form: formRef });
</script>

<style scoped>
.schema-mobile {
  max-width: 480px;
  margin: 0 auto;
  padding: 12px;
}
.schema-mobile__hint {
  color: var(--el-color-success);
  font-size: 12px;
  margin-bottom: 8px;
}
.schema-mobile__bar {
  position: sticky;
  bottom: 0;
  display: flex;
  gap: 8px;
  padding: 8px 0;
  background: var(--el-bg-color);
}
.schema-mobile__bar .el-button {
  flex: 1;
}
</style>
