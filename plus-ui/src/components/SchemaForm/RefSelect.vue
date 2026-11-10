<template>
  <div class="ls-ref" @keydown.capture="onKey">
    <el-select
      ref="selectRef"
      v-bind="$attrs"
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
      class="ls-ref__select"
      @update:model-value="(v: any) => emit('update:modelValue', v)"
      @visible-change="onOpen"
    >
      <el-option v-for="o in options" :key="o.id" :label="o.label" :value="o.id" />
    </el-select>
    <el-button
      v-if="scannable"
      class="ls-ref__scan"
      :icon="FullScreen"
      :aria-label="`扫码选择${label ?? ''}`"
      title="扫码"
      :data-testid="`scan-btn-${refSpec.concept}`"
      @click="dialog = true"
    />
    <ScanDialog v-if="scannable && dialog" v-model="dialog" :title="`扫码选择${label ?? ''}`" @scanned="resolve" />
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue';
import { FullScreen } from '@element-plus/icons-vue';
import type { RefSpec } from '@/types/lsView';
import type { RefOption, RendererApi } from '@/api/lsmom/form';
import { createWedgeDetector, normalizeCode } from '@/utils/scan/scanCode';

const ScanDialog = defineAsyncComponent(() => import('./ScanDialog.vue'));

defineOptions({ inheritAttrs: false });

/**
 * 引用远程搜索：过滤（如 orgId = $context.orgId）在服务端解析，与提交校验同一套条件。
 * 扫码：点扫码按钮用摄像头扫，或在输入框里直接用扫码枪扫（极快的连续输入以 Enter 结束）；按编码在服务端精确查找并选中，
 * 找不到、不唯一或无权使用时在字段下提示原因。人员、组织不提供扫码。
 */
const props = defineProps<{
  modelValue: unknown;
  refSpec: RefSpec;
  api: RendererApi;
  multiple?: boolean;
  disabled?: boolean;
  placeholder?: string;
  orgId?: number;
  model?: Record<string, unknown>;
  label?: string;
}>();
const emit = defineEmits<{
  (e: 'update:modelValue', v: unknown): void;
  (e: 'scanned', v: RefOption): void;
  (e: 'scan-error', message: string): void;
}>();

const options = ref<RefOption[]>([]);
const loading = ref(false);
const selectRef = ref<{ blur: () => void }>();
const dialog = ref(false);
const NO_SCAN = /\.core\.(Party|OrgUnit)$/;
const scannable = computed(() => !!props.api.refScan && !props.disabled && !props.multiple && !NO_SCAN.test(props.refSpec.concept));

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

/** 按扫到的编码找到对象并选中；失败时把服务端给的原因交给字段显示。 */
async function resolve(text: string) {
  const code = normalizeCode(text);
  if (!code || !props.api.refScan) return;
  seq++; // 扫码前输入引发的搜索结果作废
  loading.value = false;
  try {
    const found = await props.api.refScan({ ref: props.refSpec, code, orgId: props.orgId, model: props.model });
    options.value = [found, ...options.value.filter((o) => o.id !== found.id)];
    emit('update:modelValue', found.id);
    emit('scanned', found);
  } catch (e: any) {
    emit('scan-error', e?.message ?? `没有找到编码为「${code}」的对象`);
  } finally {
    // 清掉扫码枪输入的编码文字，显示（原来或新选中的）对象名称
    selectRef.value?.blur();
  }
}

const wedge = createWedgeDetector();
function onKey(e: KeyboardEvent) {
  if (!scannable.value) return;
  const code = wedge.feed(e);
  // 直接写在元素上（不等界面刷新）：外层表单处理 Enter 跳字段时据此让行
  const box = e.currentTarget as HTMLElement;
  if (wedge.pending()) box.dataset.lsScanning = '1';
  else delete box.dataset.lsScanning;
  if (code) {
    e.preventDefault();
    e.stopPropagation();
    void resolve(code);
  }
}

onMounted(hydrate);
watch(() => props.modelValue, hydrate);
defineExpose({ search, options, resolve });
</script>

<style scoped>
.ls-ref {
  display: flex;
  width: 100%;
  gap: 4px;
}
.ls-ref__select {
  flex: 1;
  min-width: 0;
}
.ls-ref__scan {
  flex: none;
  margin-left: 0;
}
</style>
