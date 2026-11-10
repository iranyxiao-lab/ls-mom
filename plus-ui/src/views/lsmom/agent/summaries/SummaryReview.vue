<template>
  <div data-testid="summary-review">
    <div class="bar">
      <el-radio-group v-model="status" data-testid="filter-status" @change="load">
        <el-radio-button value="DRAFT">待确认</el-radio-button>
        <el-radio-button value="PUBLISHED">已发布</el-radio-button>
        <el-radio-button value="DISCARDED">已作废</el-radio-button>
      </el-radio-group>
      <el-button @click="load">刷新</el-button>
      <span class="sub">摘要的每个要点都引用原始记录；Agent 只能草拟，须由真人核对引用后发布。发布不修改原始记录。</span>
    </div>
    <el-empty v-if="!loading && !rows.length" :description="status === 'DRAFT' ? '没有待确认的摘要' : '暂无摘要'" />
    <div v-else v-loading="loading" class="list">
      <el-card v-for="s in rows" :key="s.id" shadow="never" class="item" :data-testid="`summary-${s.id}`">
        <div class="head">
          <el-tag size="small">{{ KIND_LABEL[s.kind] ?? s.kind }}</el-tag>
          <b>{{ s.title }}</b>
          <el-tag v-if="s.source === 'AGENT'" size="small" type="warning" :data-testid="`source-${s.id}`"
            >Agent 草拟{{ s.model ? ` · ${s.model}` : '' }}</el-tag
          >
          <el-tag v-else size="small" type="info">人工草拟</el-tag>
          <span class="time">{{ fmt(s.createdAt) }}</span>
          <el-tag size="small" :type="s.status === 'PUBLISHED' ? 'success' : s.status === 'DISCARDED' ? 'info' : 'primary'">{{
            STATUS[s.status]
          }}</el-tag>
        </div>
        <p class="content">{{ s.content }}</p>
        <ol class="points">
          <li v-for="(p, i) in s.points" :key="i">
            {{ p.text }}
            <el-tag v-for="r in p.refs" :key="r.type + r.id" size="small" effect="plain" class="ref" :data-testid="`ref-${s.id}-${r.type}-${r.id}`"
              >{{ REF_LABEL[r.type] ?? r.type }} {{ r.label || `#${r.id}` }}</el-tag
            >
          </li>
        </ol>
        <div v-if="s.status === 'DRAFT'" class="actions">
          <el-input v-model="reason[s.id]" size="small" placeholder="作废原因" style="width: 220px" :data-testid="`reason-${s.id}`" />
          <el-button size="small" :data-testid="`btn-discard-${s.id}`" @click="discard(s)">作废</el-button>
          <el-button size="small" type="primary" :data-testid="`btn-publish-${s.id}`" @click="publish(s)">核对无误，确认发布</el-button>
        </div>
        <div v-else-if="s.status === 'PUBLISHED'" class="sub">发布于 {{ fmt(s.publishedAt) }}</div>
        <div v-else class="sub">作废原因：{{ s.discardReason }}</div>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { SummaryApi, SummaryRecord } from '@/api/lsmom/summary';
import { KIND_LABEL, REF_LABEL } from './labels';

/** 摘要确认：查看要点与引用，真人确认发布或作废。 */
const props = defineProps<{ api: SummaryApi }>();

const STATUS: Record<string, string> = { DRAFT: '待确认', PUBLISHED: '已发布', DISCARDED: '已作废' };
const status = ref<'DRAFT' | 'PUBLISHED' | 'DISCARDED'>('DRAFT');
const loading = ref(false);
const rows = ref<SummaryRecord[]>([]);
const reason = reactive<Record<number, string>>({});
const fmt = (s?: string) => (s ? s.replace('T', ' ').substring(0, 16) : '');

async function load() {
  loading.value = true;
  try {
    rows.value = await props.api.list({ status: status.value });
  } finally {
    loading.value = false;
  }
}

async function publish(s: SummaryRecord) {
  await props.api.publish(s);
  ElMessage.success('已发布');
  await load();
}

async function discard(s: SummaryRecord) {
  const r = (reason[s.id] ?? '').trim();
  if (!r) {
    ElMessage.warning('请填写作废原因');
    return;
  }
  await props.api.discard(s, r);
  ElMessage.success('已作废');
  await load();
}

onMounted(load);
defineExpose({ load });
</script>

<style scoped>
.bar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.head {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.time {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-left: auto;
}
.content {
  margin: 8px 0;
  white-space: pre-wrap;
}
.points {
  margin: 0 0 8px;
  padding-left: 20px;
}
.ref {
  margin-left: 4px;
}
.actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>
