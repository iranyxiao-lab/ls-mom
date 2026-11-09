<template>
  <div class="flow-preview">
    <div v-if="error" class="text-red-500 p-2" data-testid="preview-error">{{ error }}</div>
    <div ref="host" class="lf-host" :style="{ height: height + 'px' }" data-testid="lf-canvas" />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import LogicFlow from '@logicflow/core';
import '@logicflow/core/dist/index.css';
import { parseBpmn, toLogicFlow, type Graph } from './bpmnGraph';

/** LogicFlow 只读预览：渲染后端生成的 BPMN；highlight 为按配置节点键标出的新增/变化节点。 */
const props = withDefaults(defineProps<{ xml: string; added?: string[]; changed?: string[]; keys?: string[]; height?: number }>(), {
  added: () => [],
  changed: () => [],
  keys: () => [],
  height: 460
});
const emit = defineEmits<{ (e: 'rendered', g: Graph): void }>();

const host = ref<HTMLElement>();
const error = ref('');
let lf: LogicFlow | null = null;

function render() {
  if (!host.value || !props.xml) return;
  error.value = '';
  try {
    const g = parseBpmn(props.xml);
    const data = toLogicFlow(g, { added: new Set(props.added), changed: new Set(props.changed) }, new Set(props.keys));
    if (!lf) {
      lf = new LogicFlow({ container: host.value, isSilentMode: true, grid: false, stopScrollGraph: false, stopZoomGraph: false });
    }
    lf.render(data as any);
    lf.fitView(40, 40);
    // 供端到端测试与无障碍读取：节点标记写到容器属性上
    host.value.dataset.added = props.added.join(',');
    host.value.dataset.changed = props.changed.join(',');
    host.value.dataset.nodes = String(g.nodes.length);
    emit('rendered', g);
  } catch (e: any) {
    error.value = e?.message ?? String(e);
  }
}

onMounted(render);
watch(() => [props.xml, props.added.join(), props.changed.join()], render);
onBeforeUnmount(() => {
  lf?.destroy?.();
  lf = null;
});
</script>

<style scoped>
.lf-host {
  width: 100%;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);
}
</style>
