<template>
  <div class="wc" data-testid="work-center">
    <el-tabs v-model="tab" @tab-change="load">
      <el-tab-pane name="todo">
        <template #label>
          我的待办 <el-badge v-if="counts.todo" :value="counts.todo" :type="counts.overdue ? 'danger' : 'primary'" data-testid="badge-todo" />
        </template>
      </el-tab-pane>
      <el-tab-pane label="已办" name="done" />
      <el-tab-pane label="我发起的" name="started" />
      <el-tab-pane name="cc">
        <template #label>抄送我的 <el-badge v-if="counts.ccUnread" :value="counts.ccUnread" type="info" /></template>
      </el-tab-pane>
      <el-tab-pane label="委托记录" name="delegations" />
      <el-tab-pane name="messages">
        <template #label
          >消息 <el-badge v-if="counts.messagesUnread" :value="counts.messagesUnread" type="warning" data-testid="badge-messages"
        /></template>
      </el-tab-pane>
    </el-tabs>

    <div v-if="['todo', 'done', 'started', 'cc'].includes(tab)" class="filters">
      <el-select v-model="filter.bizType" clearable placeholder="业务类型" style="width: 220px" data-testid="filter-biz" @change="load">
        <el-option v-for="b in bizTypes" :key="b" :label="b" :value="b" />
      </el-select>
      <el-select
        v-if="tab === 'todo'"
        v-model="filter.urgency"
        clearable
        placeholder="紧急程度"
        style="width: 140px"
        data-testid="filter-urgency"
        @change="load"
      >
        <el-option label="已超时" value="OVERDUE" />
        <el-option label="即将到期" value="DUE_SOON" />
        <el-option label="正常" value="NORMAL" />
      </el-select>
      <el-input
        v-if="tab === 'todo' || tab === 'done'"
        v-model="filter.keyword"
        clearable
        placeholder="节点 / 单据 / 流程"
        style="width: 220px"
        @change="load"
      />
    </div>

    <!-- 待办 / 已办 / 抄送 -->
    <el-table
      v-if="tab === 'todo' || tab === 'done' || tab === 'cc'"
      v-loading="loading"
      :data="items"
      size="small"
      :row-class-name="rowClass"
      :data-testid="`table-${tab}`"
      @row-click="(r: WorkItem) => openTask(r.task.taskId, r)"
    >
      <el-table-column v-if="tab === 'todo'" label="" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.urgency === 'OVERDUE'" type="danger" size="small" effect="dark">已超时</el-tag>
          <el-tag v-else-if="row.urgency === 'DUE_SOON'" type="warning" size="small">即将到期</el-tag>
          <el-tag v-if="row.task.kind === 'EXCEPTION'" type="danger" size="small" plain>异常</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="事项" min-width="180">
        <template #default="{ row }">
          <b>{{ row.task.nodeName }}</b>
          <span class="sub"> · {{ row.bizTitle ?? row.task.bizId }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="task.bizType" label="业务类型" min-width="160" show-overflow-tooltip />
      <el-table-column prop="initiatorName" label="发起人" width="110" />
      <el-table-column v-if="tab === 'todo'" label="期限" width="140">
        <template #default="{ row }">{{ fmt(row.task.dueAt) || '—' }}</template>
      </el-table-column>
      <el-table-column v-if="tab === 'done'" label="结果" width="90">
        <template #default="{ row }">{{ OUTCOME[row.task.outcome] ?? row.task.outcome }}</template>
      </el-table-column>
      <el-table-column v-if="tab === 'done'" label="办理时间" width="140">
        <template #default="{ row }">{{ fmt(row.task.completedAt) }}</template>
      </el-table-column>
      <el-table-column v-if="tab === 'cc'" label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.task.status === 'TODO' ? 'warning' : 'info'">{{ row.task.status === 'TODO' ? '未读' : '已读' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="到达时间" width="140">
        <template #default="{ row }">{{ fmt(row.task.createdAt) }}</template>
      </el-table-column>
    </el-table>

    <!-- 我发起的 -->
    <el-table v-if="tab === 'started'" v-loading="loading" :data="started" size="small" data-testid="table-started">
      <el-table-column label="单据" min-width="160">
        <template #default="{ row }"
          >{{ row.bizTitle ?? row.bizId }} <span class="sub">{{ row.bizType }}</span></template
        >
      </el-table-column>
      <el-table-column label="流程" min-width="160">
        <template #default="{ row }">{{ row.flowCode }} v{{ row.flowVersion }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="STATUS_TYPE[row.status]">{{ STATUS[row.status] ?? row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="currentNodes" label="当前节点" min-width="120" />
      <el-table-column label="发起时间" width="140">
        <template #default="{ row }">{{ fmt(row.startedAt) }}</template>
      </el-table-column>
      <el-table-column label="" width="150">
        <template #default="{ row }">
          <el-button v-if="row.currentTaskId" link type="primary" @click="openTask(row.currentTaskId)">查看</el-button>
          <el-button v-if="row.currentTaskId" link @click="urge(row.currentTaskId)">催办</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 委托记录 -->
    <el-table v-if="tab === 'delegations'" v-loading="loading" :data="delegations" size="small" data-testid="table-delegations">
      <el-table-column label="方向" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.direction === 'OUT' ? 'primary' : 'success'">{{ row.direction === 'OUT' ? '转出' : '转入' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="nodeName" label="事项" min-width="120" />
      <el-table-column prop="fromName" label="转办人" width="110" />
      <el-table-column prop="toName" label="接收人" width="110" />
      <el-table-column prop="reason" label="原因" min-width="160" show-overflow-tooltip />
      <el-table-column label="时间" width="140">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
    </el-table>

    <!-- 消息：与待办分离，已读不代表完成 -->
    <template v-if="tab === 'messages'">
      <div class="filters">
        <el-button size="small" :disabled="!counts.messagesUnread" data-testid="btn-read-all" @click="readAll">全部标为已读</el-button>
        <span class="sub">消息只是提醒；已读不会完成待办，待办需在「我的待办」中办理。</span>
      </div>
      <el-table v-loading="loading" :data="messages" size="small" data-testid="table-messages">
        <el-table-column label="" width="70">
          <template #default="{ row }"
            ><el-tag size="small" :type="row.readAt ? 'info' : 'warning'">{{ row.readAt ? '已读' : '未读' }}</el-tag></template
          >
        </el-table-column>
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ NOTICE[row.kind] ?? row.kind }}</template>
        </el-table-column>
        <el-table-column prop="message" label="内容" min-width="240" />
        <el-table-column prop="fromName" label="来自" width="110" />
        <el-table-column label="时间" width="140">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="" width="140">
          <template #default="{ row }">
            <el-button v-if="!row.readAt" link :data-testid="`read-${row.id}`" @click.stop="readOne(row.id)">标为已读</el-button>
            <el-button v-if="row.taskId" link type="primary" @click.stop="openTask(row.taskId)">去处理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <TaskPanel ref="panel" :api="api" :actions="actions" :queue="todoQueue" @done="load" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { WorkbenchApi, WorkFilter, WorkItem } from '@/api/lsmom/workbench';
import TaskPanel, { type TaskActionsApi } from './TaskPanel.vue';

/** 协同工作中心：我的待办（超时、即将到期在前）、已办、我发起的、抄送、委托记录与消息；消息与待办分离。 */
const props = defineProps<{ api: WorkbenchApi; actions: TaskActionsApi & { readCc?: (id: number) => Promise<any> } }>();

const OUTCOME: Record<string, string> = {
  APPROVED: '通过',
  REJECTED: '驳回',
  RESUBMITTED: '再提交',
  RETURNED: '退回',
  SUBMITTED: '已提交',
  TRANSFERRED: '已转办',
  WITHDRAWN: '已撤回'
};
const STATUS: Record<string, string> = { RUNNING: '进行中', APPROVED: '已通过', REJECTED: '已驳回', WITHDRAWN: '已撤回', CANCELED: '已取消' };
const STATUS_TYPE: Record<string, any> = { RUNNING: 'primary', APPROVED: 'success', REJECTED: 'danger', WITHDRAWN: 'info', CANCELED: 'info' };
const NOTICE: Record<string, string> = {
  REMIND: '提醒',
  URGE: '催办',
  CC: '抄送',
  ESCALATE: '升级',
  EXCEPTION: '异常',
  TRANSFER: '转办',
  PACK_REVIEW: 'Pack 复审'
};

const tab = ref('todo');
const loading = ref(false);
const filter = reactive<WorkFilter>({ bizType: undefined, urgency: '', keyword: '' });
const counts = ref({ todo: 0, overdue: 0, ccUnread: 0, messagesUnread: 0 });
const items = ref<WorkItem[]>([]);
const started = ref<Record<string, any>[]>([]);
const delegations = ref<Record<string, any>[]>([]);
const messages = ref<Record<string, any>[]>([]);
const knownBiz = ref(new Set<string>());
const bizTypes = computed(() => [...knownBiz.value].sort());
const panel = ref<InstanceType<typeof TaskPanel>>();
/** 连续办理：按待办列表顺序（超时、即将到期在前） */
const todoQueue = async () => (await props.api.todo({})).filter((i) => i.task.kind !== 'CC').map((i) => i.task.taskId);

const fmt = (s?: string) => (s ? s.replace('T', ' ').substring(0, 16) : '');
const rowClass = ({ row }: { row: WorkItem }) => (row.urgency === 'OVERDUE' ? 'row-overdue' : row.urgency === 'DUE_SOON' ? 'row-soon' : '');
const clean = (): WorkFilter => ({
  bizType: filter.bizType || undefined,
  urgency: filter.urgency || undefined,
  keyword: filter.keyword || undefined
});

async function load() {
  loading.value = true;
  try {
    counts.value = await props.api.counts();
    switch (tab.value) {
      case 'todo':
        items.value = await props.api.todo(clean());
        break;
      case 'done':
        items.value = await props.api.done(clean());
        break;
      case 'cc':
        items.value = await props.api.cc(clean());
        break;
      case 'started':
        started.value = await props.api.started(clean());
        started.value.forEach((r) => knownBiz.value.add(r.bizType));
        break;
      case 'delegations':
        delegations.value = await props.api.delegations();
        break;
      case 'messages':
        messages.value = await props.api.messages();
        break;
    }
    if (['todo', 'done', 'cc'].includes(tab.value)) items.value.forEach((i) => knownBiz.value.add(i.task.bizType));
  } finally {
    loading.value = false;
  }
}

const route = useRoute();

async function openTask(taskId: string, row?: WorkItem) {
  if (row?.task.kind === 'CC' && row.task.status === 'TODO' && props.actions.readCc) {
    await props.actions.readCc(row.task.id);
    load();
  }
  panel.value?.open(taskId);
}
async function urge(taskId: string) {
  await props.actions.urge(taskId);
  ElMessage.success('已催办');
}
async function readOne(id: number) {
  await props.api.readMessages([id]);
  await load();
}
async function readAll() {
  await props.api.readMessages();
  await load();
}

onMounted(async () => {
  await load();
  // ?task=<待办 id>：首页卡片、通知等入口直接打开该待办
  const task = route?.query?.task;
  if (typeof task === 'string' && task) panel.value?.open(task);
});
defineExpose({ load, tab, items, counts, panel });
</script>

<style scoped>
.filters {
  display: flex;
  gap: var(--ls-space-2);
  align-items: center;
  margin-bottom: var(--ls-space-2);
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.wc :deep(.row-overdue) {
  --el-table-tr-bg-color: var(--el-color-danger-light-9);
}
.wc :deep(.row-soon) {
  --el-table-tr-bg-color: var(--el-color-warning-light-9);
}
.wc :deep(.el-table__row) {
  cursor: pointer;
}
</style>
