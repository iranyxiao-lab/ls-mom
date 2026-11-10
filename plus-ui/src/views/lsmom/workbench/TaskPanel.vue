<template>
  <el-drawer
    v-model="visible"
    :title="ctx ? `${ctx.node.name} · ${ctx.instance.flowName}` : '办理'"
    size="62%"
    append-to-body
    data-testid="task-panel"
  >
    <div v-if="loading" v-loading="true" style="height: 200px" />
    <el-result v-else-if="error" icon="error" :title="error" />
    <template v-else-if="ctx">
      <div class="meta">
        <span v-if="queue && remaining !== null" class="remaining" data-testid="task-remaining">{{
          remaining ? `本条之后还剩 ${remaining} 条` : '这是最后一条待办'
        }}</span>
        <el-tag v-if="ctx.urgency === 'OVERDUE'" type="danger" size="small">已超时</el-tag>
        <el-tag v-else-if="ctx.urgency === 'DUE_SOON'" type="warning" size="small">即将到期</el-tag>
        <span>发起人：{{ ctx.instance.initiatorName }}</span>
        <span v-if="ctx.task.dueAt">期限：{{ fmt(ctx.task.dueAt) }}</span>
        <span>状态：{{ ctx.task.status === 'TODO' ? '待办' : '已办' }}</span>
      </div>
      <el-descriptions :column="4" size="small" border class="mb-3" data-testid="snapshot" title="版本快照">
        <el-descriptions-item label="流程">{{ ctx.snapshot.flowCode }} v{{ ctx.snapshot.flowVersion }}</el-descriptions-item>
        <el-descriptions-item label="表单">{{
          ctx.snapshot.formCode ? `${ctx.snapshot.formCode} v${ctx.snapshot.formVersion}` : '—'
        }}</el-descriptions-item>
        <el-descriptions-item label="单据状态">{{ ctx.snapshot.recordStatus ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="单据版本">{{ ctx.snapshot.recordVersion ?? '—' }}</el-descriptions-item>
      </el-descriptions>

      <el-tabs v-model="tab">
        <el-tab-pane label="业务表单" name="form">
          <SchemaForm
            v-if="formSchema"
            v-model="model"
            :schema="formSchema"
            :node="ctx.node.key"
            :readonly="!canAct"
            :show-actions="false"
            data-testid="task-form"
          />
          <el-descriptions v-else :column="2" size="small" border data-testid="task-record">
            <el-descriptions-item v-for="(v, k) in ctx.record" :key="k" :label="String(k)">{{ v ?? '' }}</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>
        <el-tab-pane label="时间轴" name="timeline">
          <el-timeline data-testid="task-timeline">
            <el-timeline-item v-for="(e, i) in ctx.timeline" :key="i" :timestamp="fmt(e.at)" placement="top">
              <b>{{ e.payload?.label ?? e.action }}</b>
              <span v-if="e.payload?.nodeName"> · {{ e.payload.nodeName }}</span>
              <span> · {{ e.actorName ?? '系统' }}</span>
              <span v-if="e.payload?.targetName"> → {{ e.payload.targetName }}</span>
              <span v-if="e.fromStatus || e.toStatus" class="sub"> （{{ e.fromStatus }} → {{ e.toStatus }}）</span>
              <div v-if="e.payload?.comment" class="sub">意见：{{ e.payload.comment }}</div>
            </el-timeline-item>
          </el-timeline>
        </el-tab-pane>
      </el-tabs>

      <div v-if="canAct || ctx.actions.urge" class="act" data-testid="task-actions">
        <el-input v-if="canAct" v-model="comment" type="textarea" :rows="2" placeholder="办理意见" data-testid="task-comment" />
        <div class="btns">
          <el-button v-if="ctx.actions.approve" type="primary" :loading="busy === 'approve'" data-testid="btn-approve" @click="complete('APPROVE')"
            >通过</el-button
          >
          <el-button
            v-if="ctx.actions.resubmit"
            type="primary"
            :loading="busy === 'resubmit'"
            data-testid="btn-resubmit"
            @click="complete('RESUBMIT')"
            >再提交</el-button
          >
          <el-button v-if="ctx.actions.reject" type="danger" plain :loading="busy === 'reject'" data-testid="btn-reject" @click="complete('REJECT')"
            >驳回</el-button
          >
          <template v-if="ctx.actions.return">
            <el-select v-model="returnTarget" size="default" style="width: 140px" data-testid="return-target">
              <el-option v-for="t in ctx.returnTargets" :key="t.key" :label="t.name" :value="t.key" />
            </el-select>
            <el-button :loading="busy === 'return'" data-testid="btn-return" @click="doReturn">退回</el-button>
          </template>
          <template v-if="ctx.actions.transfer">
            <el-select
              v-model="transferTo"
              filterable
              remote
              :remote-method="searchParty"
              placeholder="转办给"
              style="width: 160px"
              data-testid="transfer-to"
            >
              <el-option v-for="p in parties" :key="p.id" :label="p.name + (p.orgName ? `（${p.orgName}）` : '')" :value="p.id" />
            </el-select>
            <el-button :disabled="!transferTo" :loading="busy === 'transfer'" data-testid="btn-transfer" @click="doTransfer">转办</el-button>
          </template>
          <el-button v-if="ctx.actions.urge" :loading="busy === 'urge'" data-testid="btn-urge" @click="doUrge">催办</el-button>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import SchemaForm from '@/components/SchemaForm/index.vue';
import type { FormSchema } from '@/types/lsView';
import type { TaskContext, WorkbenchApi } from '@/api/lsmom/workbench';

export interface TaskActionsApi {
  complete: (
    taskId: string,
    body: { action: 'APPROVE' | 'REJECT' | 'RESUBMIT'; comment?: string; input?: Record<string, unknown> | null }
  ) => Promise<any>;
  returnTask: (taskId: string, target: string, reason?: string) => Promise<any>;
  transfer: (taskId: string, toParty: number, comment?: string) => Promise<any>;
  urge: (taskId: string) => Promise<any>;
  parties: (keyword?: string) => Promise<{ id: number; name: string; orgName?: string }[]>;
}

/** 在待办中办理：按冻结版本表单与节点策略渲染，提交意见与可编辑字段；转办、退回、催办都经流程接口并写入时间轴。 */
const props = defineProps<{
  api: Pick<WorkbenchApi, 'context'>;
  actions: TaskActionsApi;
  /** 连续办理：返回本人当前待办（按待办列表顺序）的 taskId；办理完自动打开下一条 */
  queue?: () => Promise<string[]>;
}>();
const emit = defineEmits<{ (e: 'done', kind: string): void }>();

const visible = ref(false);
const loading = ref(false);
const error = ref('');
const ctx = ref<TaskContext>();
const tab = ref('form');
const model = ref<Record<string, any>>({});
const comment = ref('');
const busy = ref('');
const returnTarget = ref('initiator');
const transferTo = ref<number>();
const parties = ref<{ id: number; name: string; orgName?: string }[]>([]);

const canAct = computed(() => !!ctx.value && (ctx.value.actions.approve || ctx.value.actions.resubmit || ctx.value.actions.reject));

/** 用节点生效的字段策略覆盖表单 nodePolicies，SchemaForm 与服务端同一算法计算可编辑字段。 */
const formSchema = computed<FormSchema | null>(() => {
  const c = ctx.value;
  if (!c?.form) return null;
  const s = JSON.parse(JSON.stringify(c.form)) as FormSchema;
  if (c.fieldPolicy) s.nodePolicies = { ...(s.nodePolicies ?? {}), [c.node.key]: c.fieldPolicy as any };
  return s;
});

const fmt = (s?: string) => (s ? s.replace('T', ' ').substring(0, 16) : '');

async function open(taskId: string) {
  visible.value = true;
  loading.value = true;
  error.value = '';
  comment.value = '';
  transferTo.value = undefined;
  tab.value = 'form';
  try {
    ctx.value = await props.api.context(taskId);
    model.value = { ...ctx.value.record };
    returnTarget.value = ctx.value.returnTargets[0]?.key ?? 'initiator';
    if (props.queue) void others(taskId).then((r) => (remaining.value = r.length));
  } catch (e: any) {
    error.value = e?.message ?? '加载失败';
  } finally {
    loading.value = false;
  }
}

/** 只提交策略允许编辑的字段（服务端仍会再次校验）。 */
function editableInput(): Record<string, unknown> | null {
  const p = ctx.value?.fieldPolicy;
  if (!p || !ctx.value?.node.operation) return null;
  const keys = new Set([...(p.editable ?? []), ...(p.required ?? [])].filter((k) => k !== '*'));
  if (!keys.size) return null;
  const out: Record<string, unknown> = {};
  keys.forEach((k) => {
    if (model.value[k] !== undefined) out[k] = model.value[k];
  });
  return Object.keys(out).length ? out : null;
}

const remaining = ref<number | null>(null);

/** 本条之外还剩的待办 */
async function others(current: string): Promise<string[]> {
  if (!props.queue) return [];
  try {
    return (await props.queue()).filter((id) => id !== current);
  } catch {
    return [];
  }
}

async function run(kind: string, fn: () => Promise<any>, ok: string) {
  busy.value = kind;
  const current = ctx.value!.task.taskId;
  try {
    await fn();
    emit('done', kind);
    // 原地连续办理：自动加载下一条待办
    const rest = props.queue ? await others(current) : [];
    if (rest.length) {
      ElMessage.success(`${ok}，还剩 ${rest.length} 条`);
      await open(rest[0]);
      return;
    }
    ElMessage.success(props.queue ? `${ok}，待办已全部处理` : ok);
    visible.value = false;
  } finally {
    busy.value = '';
  }
}

const complete = (action: 'APPROVE' | 'REJECT' | 'RESUBMIT') =>
  run(
    action.toLowerCase(),
    () => props.actions.complete(ctx.value!.task.taskId, { action, comment: comment.value, input: action === 'REJECT' ? null : editableInput() }),
    { APPROVE: '已通过', REJECT: '已驳回', RESUBMIT: '已再提交' }[action]
  );
const doReturn = () => run('return', () => props.actions.returnTask(ctx.value!.task.taskId, returnTarget.value, comment.value), '已退回');
const doTransfer = () => run('transfer', () => props.actions.transfer(ctx.value!.task.taskId, transferTo.value!, comment.value), '已转办');
async function doUrge() {
  busy.value = 'urge';
  try {
    await props.actions.urge(ctx.value!.task.taskId);
    ElMessage.success('已催办');
  } finally {
    busy.value = '';
  }
}
async function searchParty(k: string) {
  parties.value = await props.actions.parties(k);
}

defineExpose({ open, ctx, model });
</script>

<style scoped>
.meta {
  display: flex;
  gap: var(--ls-space-4);
  align-items: center;
  margin-bottom: var(--ls-space-3);
  color: var(--el-text-color-regular);
  font-size: 13px;
}
.remaining {
  color: var(--el-color-primary);
  font-weight: 600;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.act {
  border-top: 1px solid var(--el-border-color-lighter);
  margin-top: var(--ls-space-3);
  padding-top: var(--ls-space-3);
}
.btns {
  display: flex;
  gap: var(--ls-space-2);
  margin-top: var(--ls-space-2);
  flex-wrap: wrap;
}
</style>
