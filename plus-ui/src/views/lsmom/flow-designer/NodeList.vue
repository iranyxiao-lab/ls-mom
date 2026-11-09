<template>
  <div class="node-list">
    <AddButton :testid="`add-${path}-0`" @pick="(t) => ctx.insert(nodes, 0, t)" />
    <template v-for="(n, i) in nodes" :key="n.key">
      <div
        class="node-card"
        :class="{ selected: ctx.selected.value === n.key, invalid: ctx.issueKeys.value.has(n.key) }"
        :style="{ '--accent': typeInfo(n.type).color }"
        :data-testid="`node-${n.key}`"
        @click.stop="ctx.select(n.key)"
      >
        <div class="node-head">
          <span class="node-type">{{ describe(n) }}</span>
          <span class="node-name">{{ n.name }}</span>
          <el-button class="node-del" link type="danger" :icon="Delete" :data-testid="`del-${n.key}`" @click.stop="ctx.remove(n.key)" />
        </div>
        <div class="node-body">
          <template v-if="n.type === 'CONDITION' || n.type === 'PARALLEL'">{{ n.branches?.length ?? 0 }} 个分支</template>
          <template v-else-if="n.type === 'SERVICE'">执行：{{ ctx.opName(n.operation) }}</template>
          <template v-else>
            <div>{{ ctx.assignee(n) }}</div>
            <div v-if="n.operation" class="sub">提交：{{ ctx.opName(n.operation) }}</div>
            <div v-if="n.timeout?.hours" class="sub">超时 {{ n.timeout.hours }} 小时</div>
          </template>
        </div>
      </div>
      <div v-if="n.branches" class="branches" :data-testid="`branches-${n.key}`">
        <div v-for="(b, bi) in n.branches" :key="bi" class="branch" :data-testid="`branch-${n.key}-${bi}`">
          <div class="branch-head" @click.stop="ctx.select(n.key)">
            <span class="branch-name">{{ b.name || `分支 ${bi + 1}` }}</span>
            <span v-if="n.type === 'CONDITION'" class="branch-when">{{ b.when ? b.when : '默认分支' }}</span>
          </div>
          <NodeList :nodes="b.nodes" :ctx="ctx" :path="`${path}-${n.key}-${bi}`" />
        </div>
        <el-button class="add-branch" size="small" :data-testid="`add-branch-${n.key}`" @click.stop="ctx.addBranch(n)">＋ 分支</el-button>
      </div>
      <AddButton :testid="`add-${path}-${i + 1}`" @pick="(t) => ctx.insert(nodes, i + 1, t)" />
    </template>
  </div>
</template>

<script setup lang="ts">
import { Delete } from '@element-plus/icons-vue';
import { describe, typeInfo, type FlowNode } from './model';
import AddButton from './AddButton.vue';
import type { TreeContext } from './types';

defineOptions({ name: 'NodeList' });
defineProps<{ nodes: FlowNode[]; ctx: TreeContext; path: string }>();
</script>

<style scoped>
.node-list {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 220px;
}
.node-card {
  width: 220px;
  border: 1px solid var(--el-border-color);
  border-top: 4px solid var(--accent);
  border-radius: 6px;
  background: var(--el-bg-color);
  box-shadow: var(--el-box-shadow-lighter);
  cursor: pointer;
}
.node-card.selected {
  outline: 2px solid var(--el-color-primary);
}
.node-card.invalid {
  border-color: var(--el-color-danger);
}
.node-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px 2px;
}
.node-type {
  font-size: 12px;
  color: var(--accent);
  white-space: nowrap;
}
.node-name {
  flex: 1;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node-body {
  padding: 2px 8px 8px;
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.node-body .sub {
  color: var(--el-text-color-secondary);
}
.branches {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  padding: 10px;
  margin-top: 4px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  position: relative;
}
.branch {
  display: flex;
  flex-direction: column;
  align-items: center;
}
.branch-head {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 4px 10px;
  border-radius: 12px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  cursor: pointer;
}
.branch-when {
  color: var(--el-text-color-secondary);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.add-branch {
  align-self: center;
}
</style>
