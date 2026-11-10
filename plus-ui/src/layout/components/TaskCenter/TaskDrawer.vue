<template>
  <el-drawer v-model="taskCenter.open" title="任务中心" size="460px" append-to-body data-testid="task-drawer">
    <p v-if="!taskCenter.tasks.length" class="task-empty" data-testid="task-empty">
      还没有任务。导入、导出、批量操作和应用安装会在这里显示进度与结果。
    </p>
    <ul class="task-list">
      <li
        v-for="t in taskCenter.tasks"
        :key="t.id"
        class="task-item"
        :class="{ 'task-item--focus': t.id === taskCenter.focus }"
        :data-testid="`task-${t.id}`"
        :data-kind="t.kind"
        :data-status="t.status"
      >
        <div class="task-head">
          <span class="task-title">{{ t.title }}</span>
          <el-tag size="small" :type="tagType(t)" data-testid="task-status">{{ STATUS[t.status] ?? t.status }}</el-tag>
        </div>
        <el-progress
          v-if="t.status === 'RUNNING'"
          :percentage="t.total ? Math.min(100, Math.round((t.done / t.total) * 100)) : 0"
          :indeterminate="!t.total"
          data-testid="task-progress"
        />
        <p class="task-summary" :class="{ 'task-summary--error': t.status === 'FAILED' }" data-testid="task-summary">{{ taskSummary(t) }}</p>
        <template v-if="t.status === 'DONE' && t.kind === 'IMPORT_CHECK'">
          <el-table
            v-if="t.result?.report?.errors?.length"
            :data="t.result.report.errors.slice(0, 100)"
            size="small"
            max-height="220"
            data-testid="task-errors"
          >
            <el-table-column prop="row" label="行" width="60" />
            <el-table-column prop="field" label="字段" width="110" />
            <el-table-column prop="message" label="原因" />
          </el-table>
          <p v-if="(t.result?.report?.errors?.length ?? 0) > 100" class="task-more">
            另有 {{ t.result!.report.errors.length - 100 }} 条错误，修改后重新导入可再次检查
          </p>
          <el-button
            v-if="(t.result?.report?.valid ?? 0) > 0 && !t.result?.report?.invalid && !confirmed(t)"
            type="primary"
            size="small"
            :loading="busy === t.id"
            data-testid="task-confirm-import"
            @click="confirmImport(t)"
            >确认导入 {{ t.result!.report.valid }} 行</el-button
          >
          <span v-else-if="confirmed(t)" class="task-more">已确认导入</span>
          <span v-else-if="t.result?.report?.invalid" class="task-more">有错误行，修改文件后重新导入</span>
        </template>
        <el-button
          v-if="t.status === 'DONE' && t.kind === 'EXPORT' && t.result?.jobId"
          type="primary"
          link
          size="small"
          data-testid="task-download"
          @click="download(t)"
          >下载</el-button
        >
        <el-button
          v-if="t.status === 'DONE' && t.kind === 'IMPORT' && t.result?.concept"
          link
          size="small"
          data-testid="task-open-ledger"
          @click="openLedger(t)"
          >查看台账</el-button
        >
        <div class="task-time">{{ when(t.createdAt) }}</div>
      </li>
    </ul>
  </el-drawer>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { BgTask } from '@/api/lsmom/tasks';
import { crudApi } from '@/api/lsmom/crud';
import { saveBlob } from '@/views/lsmom/ledger/ledgerModel';
import { taskCenter, taskSummary, trackTask } from '@/utils/taskCenter';

/** 任务中心抽屉（首次打开时才加载）：任务列表、进度、错误行报告、确认导入、下载导出文件。 */
const STATUS: Record<string, string> = { RUNNING: '进行中', DONE: '已完成', FAILED: '失败' };
const router = useRouter();
const busy = ref<number | null>(null);

const tagType = (t: BgTask) =>
  t.status === 'FAILED' ? 'danger' : t.status === 'RUNNING' ? 'primary' : t.kind === 'BATCH' && t.result?.failed ? 'warning' : 'success';
const when = (s: string) => new Date(s).toLocaleString('zh-CN', { hour12: false });
/** 同一批次已经有导入任务（进行中或已完成）就不再显示确认按钮 */
const confirmed = (t: BgTask) =>
  taskCenter.tasks.some((x) => x.kind === 'IMPORT' && x.status !== 'FAILED' && String(x.title).includes(`批次 ${t.result?.report?.batchId}）`));

async function confirmImport(t: BgTask) {
  busy.value = t.id;
  try {
    const id = await crudApi.importConfirmAsync(t.result!.concept, t.result!.report.batchId);
    ElMessage.success('已开始导入，完成后会通知你');
    await trackTask(id);
  } finally {
    busy.value = null;
  }
}

async function download(t: BgTask) {
  saveBlob(await crudApi.exportFile(t.result!.jobId), `${t.result!.concept}-${t.result!.jobId}.xlsx`);
}

function openLedger(t: BgTask) {
  taskCenter.open = false;
  void router.push(`/lsmom/ledger/${encodeURIComponent(t.result!.concept)}`);
}

onBeforeUnmount(() => (taskCenter.open = false));
</script>

<style scoped>
.task-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.task-item {
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding: 10px 0;
}
.task-item--focus {
  background: var(--el-color-primary-light-9);
}
.task-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
  margin-bottom: 4px;
}
.task-title {
  font-weight: 500;
}
.task-summary {
  margin: 4px 0;
  color: var(--el-text-color-regular);
}
.task-summary--error {
  color: var(--el-color-danger);
}
.task-more,
.task-time,
.task-empty {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
