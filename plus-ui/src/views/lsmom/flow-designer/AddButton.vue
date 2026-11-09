<template>
  <div class="add-wrap">
    <div class="line" />
    <el-popover v-model:visible="open" placement="right" :width="180" trigger="click">
      <template #reference>
        <el-button circle size="small" :icon="Plus" :data-testid="testid" title="插入节点" @click.stop />
      </template>
      <div class="menu">
        <div v-for="t in NODE_TYPES" :key="t.type" class="item" :data-testid="`pick-${t.type}`" @click.stop="pick(t.type)">
          <span class="dot" :style="{ background: t.color }" />{{ t.label }}
        </div>
      </div>
    </el-popover>
    <div class="line" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { Plus } from '@element-plus/icons-vue';
import { NODE_TYPES, type NodeType } from './model';

defineProps<{ testid: string }>();
const emit = defineEmits<{ (e: 'pick', t: NodeType): void }>();
const open = ref(false);
function pick(t: NodeType) {
  open.value = false;
  emit('pick', t);
}
</script>

<style scoped>
.add-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
}
.line {
  width: 2px;
  height: 10px;
  background: var(--el-border-color);
}
.menu .item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  cursor: pointer;
  border-radius: 4px;
}
.menu .item:hover {
  background: var(--el-fill-color-light);
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
</style>
