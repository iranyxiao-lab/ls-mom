<template>
  <div class="summary" data-testid="summary-card">
    <div class="meta">
      <el-tag size="small" :type="summary.status === 'PUBLISHED' ? 'success' : 'warning'" data-testid="summary-status">
        {{ summary.status === 'PUBLISHED' ? '已发布' : '草稿待确认' }}
      </el-tag>
      <span v-if="summary.origin === 'LLM'" class="sub">LLM 草拟</span>
      <span v-if="summary.confirmedByName" class="sub">确认人 {{ summary.confirmedByName }} · {{ fmtTime(summary.publishedAt) }}</span>
    </div>
    <p class="pre">{{ summary.content }}</p>
    <ol class="points">
      <li v-for="p in summary.points" :key="p.seq" :data-testid="`summary-point-${p.seq}`">
        <div>{{ p.viewpoint }}</div>
        <div class="cites">
          引用意见：
          <el-popover v-for="o in p.opinions" :key="o.id" placement="top" :width="320" trigger="click">
            <template #reference>
              <el-link type="primary" :data-testid="`cite-${p.seq}-${o.id}`" @click="emit('cite', o.id)">#{{ o.id }}</el-link>
            </template>
            <div class="pre" :data-testid="`cite-content-${o.id}`">{{ o.content }}</div>
          </el-popover>
        </div>
      </li>
    </ol>
  </div>
</template>

<script setup lang="ts">
import type { SummaryView } from '@/api/lsmom/consultation';
import { fmtTime } from '@/api/lsmom/consultationModel';

/** 咨询总结：每个观点链接到引用的来源意见。 */
defineProps<{ summary: SummaryView }>();
const emit = defineEmits<{ cite: [id: number] }>();
</script>

<style scoped>
.meta {
  display: flex;
  gap: var(--ls-space-3);
  align-items: center;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.pre {
  white-space: pre-wrap;
}
.points li {
  margin-bottom: var(--ls-space-2);
}
.cites {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: flex;
  gap: var(--ls-space-2);
  align-items: center;
}
</style>
