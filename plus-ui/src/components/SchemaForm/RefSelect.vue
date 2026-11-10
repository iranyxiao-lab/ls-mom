<template>
  <el-select
    :model-value="modelValue"
    filterable
    remote
    default-first-option
    clearable
    :multiple="multiple"
    :disabled="disabled"
    :remote-method="search"
    :loading="loading"
    :placeholder="placeholder ?? '输入关键字搜索'"
    style="width: 100%"
    @update:model-value="(v: any) => emit('update:modelValue', v)"
    @visible-change="onOpen"
  >
    <el-option v-for="o in options" :key="o.id" :label="o.label" :value="o.id" />
  </el-select>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import type { RefSpec } from '@/types/lsView';
import type { RefOption, RendererApi } from '@/api/lsmom/form';

/** 引用远程搜索：过滤（如 orgId = $context.orgId）在服务端解析，与提交校验同一套条件。 */
const props = defineProps<{
  modelValue: unknown;
  refSpec: RefSpec;
  api: RendererApi;
  multiple?: boolean;
  disabled?: boolean;
  placeholder?: string;
  orgId?: number;
  model?: Record<string, unknown>;
}>();
const emit = defineEmits<{ (e: 'update:modelValue', v: unknown): void }>();

const options = ref<RefOption[]>([]);
const loading = ref(false);

let seq = 0;

/** 搜索候选；只采用最后一次搜索的结果（先发出的空关键字搜索晚返回时不覆盖输入后的结果）。 */
async function search(keyword: string) {
  const mine = ++seq;
  loading.value = true;
  try {
    const found = await props.api.refSearch({ ref: props.refSpec, keyword, orgId: props.orgId, model: props.model });
    if (mine !== seq) return;
    const keep = options.value.filter((o) => selected().includes(o.id) && !found.some((x) => x.id === o.id));
    options.value = [...keep, ...found];
  } finally {
    if (mine === seq) loading.value = false;
  }
}

/** 展开时还没搜过就列出默认候选；输入关键字打开下拉时已在搜索，不再用空关键字覆盖。 */
function onOpen(open: boolean) {
  if (open && !options.value.length && !seq) void search('');
}

function selected(): number[] {
  const v = props.modelValue;
  if (v === null || v === undefined || v === '') return [];
  return (Array.isArray(v) ? v : [v]).map((x) => Number(x));
}

/** 已有取值时按编号回显名称。 */
async function hydrate() {
  const ids = selected().filter((id) => !options.value.some((o) => o.id === id));
  if (!ids.length) return;
  const found = await props.api.refSearch({ ref: props.refSpec, ids, orgId: props.orgId, model: props.model });
  options.value = [...options.value, ...found];
}

onMounted(hydrate);
watch(() => props.modelValue, hydrate);
defineExpose({ search, options });
</script>
