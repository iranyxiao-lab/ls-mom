<template>
  <div data-testid="agent-approvals">
    <div class="bar">
      <el-button @click="load">刷新</el-button>
      <span class="sub">Agent 申请执行需真人批准的操作。批准内容绑定操作、输入、目标与版本，有效期内只能使用一次；内容变化须重新申请。</span>
    </div>
    <el-empty v-if="!loading && !rows.length" description="没有待审批的 Agent 申请" />
    <el-table v-else v-loading="loading" :data="rows" data-testid="table-approvals">
      <el-table-column label="申请时间" width="140"
        ><template #default="{ row }">{{ fmt(row.createdAt) }}</template></el-table-column
      >
      <el-table-column prop="operation" label="操作" min-width="220" />
      <el-table-column label="目标" width="140">
        <template #default="{ row }">{{ row.targetId ? `#${row.targetId} v${row.targetVersion ?? ''}` : '—' }}</template>
      </el-table-column>
      <el-table-column label="输入" min-width="240">
        <template #default="{ row }"
          ><code class="input">{{ JSON.stringify(row.input) }}</code></template
        >
      </el-table-column>
      <el-table-column prop="reason" label="理由" min-width="160" />
      <el-table-column label="审批" width="250">
        <template #default="{ row }">
          <el-select v-model="ttl[row.id]" size="small" style="width: 90px" placeholder="有效期">
            <el-option v-for="m in [10, 30, 60, 240]" :key="m" :label="`${m} 分钟`" :value="m" />
          </el-select>
          <el-button size="small" type="primary" :data-testid="`btn-approve-${row.id}`" @click="decide(row, true)">批准</el-button>
          <el-button size="small" :data-testid="`btn-reject-${row.id}`" @click="decide(row, false)">驳回</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { AgentApproval, ApprovalApi } from '@/api/lsmom/agent';

/** 真人处理 Agent 的审批申请（只列出自己有对应操作权限的）。 */
const props = defineProps<{ api: ApprovalApi }>();

const loading = ref(false);
const rows = ref<AgentApproval[]>([]);
const ttl = reactive<Record<string, number>>({});
const fmt = (s?: string) => (s ? s.replace('T', ' ').substring(0, 16) : '');

async function load() {
  loading.value = true;
  try {
    rows.value = await props.api.pending();
    rows.value.forEach((r) => (ttl[r.id] = ttl[r.id] ?? 30));
  } finally {
    loading.value = false;
  }
}

async function decide(r: AgentApproval, ok: boolean) {
  const a = await props.api.decide(r.id, ok, ok ? ttl[r.id] : undefined);
  ElMessage.success(ok ? `已批准，有效期至 ${fmt(a.expiresAt)}` : '已驳回');
  await load();
}

onMounted(load);
defineExpose({ load });
</script>

<style scoped>
.bar {
  display: flex;
  gap: var(--ls-space-2);
  align-items: center;
  margin-bottom: var(--ls-space-3);
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.input {
  font-size: 12px;
  word-break: break-all;
}
</style>
