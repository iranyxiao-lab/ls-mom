<template>
  <div v-if="p?.flow" class="flow-head" data-testid="flow-header">
    <!-- 退回意见醒目显示 -->
    <el-alert
      v-if="p.returned"
      type="warning"
      :closable="false"
      show-icon
      class="flow-head__returned"
      data-testid="flow-returned"
      :title="`被 ${who(p.returned.by)} 退回（${p.returned.nodeName}）${p.returned.at ? ' · ' + fmt(p.returned.at) : ''}`"
      :description="p.returned.comment ? `退回意见：${p.returned.comment}` : '没有填写退回意见'"
    />

    <div class="flow-head__line">
      <el-tag v-if="statusLabel" size="small" data-testid="flow-status">{{ statusLabel }}</el-tag>
      <span class="flow-head__flow">{{ p.flow.flowName }}</span>
      <template v-if="p.flow.status !== 'RUNNING'">
        <span data-testid="flow-ended">流程已结束{{ ENDED[p.flow.status] ? '：' + ENDED[p.flow.status] : '' }}</span>
      </template>
      <template v-else-if="mine">
        <span class="flow-head__me" data-testid="flow-my-turn">轮到你{{ verb(mine.nodeKey) }}：{{ mine.nodeName }}</span>
        <span v-if="mine.dueAt" :class="['flow-head__due', 'is-' + mine.urgency]">{{ remaining(mine) }}</span>
      </template>
      <template v-else-if="p.current.length">
        <span class="flow-head__node">当前：{{ currentNodes }}</span>
        <span data-testid="flow-waiting">等待 {{ waitingText }}</span>
        <span v-if="p.current[0].dueAt" :class="['flow-head__due', 'is-' + p.current[0].urgency]">，{{ remaining(p.current[0]) }}</span>
        <el-button v-if="p.canUrge" size="small" :loading="busy === 'urge'" data-testid="flow-urge" @click="urge">催办</el-button>
      </template>
    </div>

    <!-- 轮到我：意见框直接可见，唯一主按钮 -->
    <div v-if="mine && p.flow.status === 'RUNNING'" class="flow-head__act" data-testid="flow-act">
      <el-form v-if="required.length" label-position="top" class="flow-head__fields" @submit.prevent>
        <el-form-item v-for="f in required" :key="f.key" :label="f.label" required>
          <el-input v-model="fields[f.key]" :type="f.widget === 'textarea' ? 'textarea' : 'text'" :rows="2" :data-testid="'flow-field-' + f.key" />
        </el-form-item>
      </el-form>
      <el-input v-model="comment" type="textarea" :rows="2" :placeholder="isInitiator ? '补充说明（可选）' : '办理意见'" data-testid="flow-comment" />
      <div class="flow-head__btns">
        <el-button type="primary" :loading="busy === 'main'" :disabled="!!busy || complex" data-testid="flow-main-action" @click="main">
          {{ mainLabel }}
        </el-button>
        <template v-if="!isInitiator && isAudit">
          <el-button :disabled="!!busy" :loading="busy === 'return'" data-testid="flow-return" @click="doReturn">退回发起人</el-button>
          <el-button type="danger" plain :disabled="!!busy" :loading="busy === 'reject'" data-testid="flow-reject" @click="reject">驳回</el-button>
        </template>
        <el-button link type="primary" data-testid="flow-open-task" @click="emit('open-task', mine.taskId)">
          {{ complex ? '需要填写更多内容，打开办理面板' : '打开办理面板' }}
        </el-button>
      </div>
    </div>

    <!-- 流程进度：点击节点查看办理人、时间、意见 -->
    <el-steps :active="activeIndex" finish-status="success" align-center class="flow-head__steps" data-testid="flow-steps">
      <el-step v-for="s in p.steps" :key="s.key" :status="STEP_STATUS[s.state]" :data-testid="'flow-step-' + s.key">
        <template #title>
          <el-popover trigger="click" placement="bottom" :width="300">
            <template #reference>
              <span class="flow-head__step" role="button" tabindex="0" :data-testid="'flow-step-btn-' + s.key">{{ s.name }}</span>
            </template>
            <div :data-testid="'flow-step-detail-' + s.key">
              <div class="flow-head__stepname">{{ s.name }} · {{ STATE_TEXT[s.state] }}</div>
              <div v-if="s.state === 'current'" class="flow-head__sub">办理人：{{ handlersOf(s.key) || '—' }}</div>
              <div v-for="(r, i) in s.records" :key="i" class="flow-head__rec">
                <b>{{ who(r.by) }}</b> {{ OUTCOME[r.outcome] ?? r.outcome }} <span class="flow-head__sub">{{ fmt(r.at) }}</span>
                <div v-if="r.comment" :class="{ 'flow-head__warn': r.outcome === 'RETURNED' || r.outcome === 'REJECTED' }">意见：{{ r.comment }}</div>
              </div>
              <div v-if="!s.records.length && s.state === 'pending'" class="flow-head__sub">尚未开始</div>
            </div>
          </el-popover>
        </template>
      </el-step>
    </el-steps>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FlowProgress, FlowProgressApi, FlowPerson, FlowCurrent } from '@/api/lsmom/flowProgress';

/**
 * 单据头部"状态与下一步"＋流程进度。发起人看到"等待谁做什么、还剩多久"并可催办；
 * 办理人看到唯一的主按钮和直接可见的意见框；被退回时醒目显示退回节点与意见。
 */
const props = defineProps<{ concept: string; id: number | string; api: FlowProgressApi; statusLabel?: string }>();
const emit = defineEmits<{
  (e: 'loaded', p: FlowProgress): void;
  (e: 'changed'): void;
  (e: 'open-task', taskId: string): void;
}>();

const ENDED: Record<string, string> = { APPROVED: '审批通过', REJECTED: '已驳回', WITHDRAWN: '已撤回', CANCELED: '已取消' };
const STEP_STATUS: Record<string, 'success' | 'process' | 'error' | 'wait'> = {
  done: 'success',
  current: 'process',
  returned: 'error',
  rejected: 'error',
  pending: 'wait'
};
const STATE_TEXT: Record<string, string> = { done: '已完成', current: '进行中', returned: '已退回', rejected: '已驳回', pending: '未开始' };
const OUTCOME: Record<string, string> = { SUBMITTED: '发起', APPROVED: '通过', REJECTED: '驳回', RETURNED: '退回', RESUBMITTED: '再提交' };

const p = ref<FlowProgress | null>(null);
const busy = ref('');
const comment = ref('');
const fields = reactive<Record<string, string>>({});
const required = ref<{ key: string; label: string; widget?: string }[]>([]);
const complex = ref(false);
const nodeType = ref<string | undefined>();

const mine = computed<FlowCurrent | null>(() => p.value?.current?.find((c) => c.mine) ?? null);
const isInitiator = computed(() => mine.value?.nodeKey === 'initiator');
const isAudit = computed(() => nodeType.value === 'AUDIT');
const mainLabel = computed(() =>
  isInitiator.value ? '再提交' : ({ AUDIT: '审核通过', CONFIRM: '确认', FILL: '提交' }[nodeType.value ?? ''] ?? '办理')
);
const waitingText = computed(() => {
  const c = p.value?.current ?? [];
  const people = c.flatMap((x) => x.handlers).map(who);
  const uniq = [...new Set(people)];
  const shown = uniq.slice(0, 3).join('、') + (uniq.length > 3 ? ` 等 ${uniq.length} 人` : '');
  return `${shown || '待分配'}${verbOf(c[0]?.nodeKey)}`;
});
const currentNodes = computed(() => [...new Set((p.value?.current ?? []).map((x) => x.nodeName))].join('、'));
const activeIndex = computed(() => {
  const s = p.value?.steps ?? [];
  const i = s.findIndex((x) => x.state === 'current');
  return i >= 0 ? i : s.filter((x) => x.state === 'done').length;
});

function who(x?: FlowPerson | null) {
  if (!x?.name) return '—';
  return x.role ? `${x.name}（${x.role}）` : x.name;
}
function handlersOf(key: string) {
  return (p.value?.current ?? [])
    .filter((c) => c.nodeKey === key)
    .flatMap((c) => c.handlers)
    .map(who)
    .join('、');
}
function verbOf(nodeKey?: string) {
  const s = p.value?.steps.find((x) => x.key === nodeKey);
  if (nodeKey === 'initiator') return '修改后再提交';
  return { AUDIT: '审核', CONFIRM: '确认', FILL: '填写' }[s?.type ?? ''] ?? '办理';
}
const verb = (k: string) => verbOf(k);
function remaining(c: FlowCurrent) {
  const m = c.remainingMinutes;
  if (m == null) return '';
  const txt = (n: number) => (n >= 60 * 24 ? `${Math.floor(n / 60 / 24)} 天` : n >= 60 ? `${Math.floor(n / 60)} 小时` : `${Math.max(n, 1)} 分钟`);
  return m < 0 ? `已超时 ${txt(-m)}` : `剩余 ${txt(m)}`;
}
const fmt = (s?: string | null) => (s ? s.replace('T', ' ').substring(0, 16) : '');

async function load() {
  p.value = await props.api.progress(props.concept, props.id);
  comment.value = '';
  Object.keys(fields).forEach((k) => delete fields[k]);
  required.value = [];
  complex.value = false;
  nodeType.value = undefined;
  const m = mine.value;
  if (m) {
    nodeType.value = p.value.steps.find((s) => s.key === m.nodeKey)?.type;
    // 节点要求填写的字段：文本类在头部直接填，其他类型到办理面板
    try {
      const ctx = await props.api.context(m.taskId);
      const req = (ctx.fieldPolicy?.required ?? []).filter((k: string) => k !== '*');
      const defs = (ctx.form?.fields ?? []) as { key: string; label: string; widget?: string }[];
      const simple = req.map((k: string) => defs.find((f) => f.key === k)).filter((f): f is { key: string; label: string; widget?: string } => !!f);
      complex.value = simple.length !== req.length || simple.some((f) => !['input', 'textarea'].includes(f.widget ?? 'input'));
      required.value = complex.value ? [] : simple;
      for (const f of simple) fields[f.key] = String(ctx.record?.[f.key] ?? '');
    } catch {
      /* 办理上下文取不到时只提供意见 */
    }
  }
  emit('loaded', p.value);
}

async function act(kind: string, fn: () => Promise<unknown>, ok: string) {
  busy.value = kind;
  try {
    await fn();
    ElMessage.success(ok);
    await load();
    emit('changed');
  } finally {
    busy.value = '';
  }
}

function main() {
  const m = mine.value!;
  const missing = required.value.filter((f) => !fields[f.key]?.trim());
  if (missing.length) return ElMessage.warning(`请填写${missing.map((f) => f.label).join('、')}`);
  const input = required.value.length ? Object.fromEntries(required.value.map((f) => [f.key, fields[f.key]])) : null;
  const action = isInitiator.value ? 'RESUBMIT' : 'APPROVE';
  return act(
    'main',
    () => props.api.complete(m.taskId, { action, comment: comment.value, input }),
    isInitiator.value ? '已再提交' : `已${mainLabel.value}`
  );
}
function doReturn() {
  if (!comment.value.trim()) return ElMessage.warning('请在意见中写明退回原因');
  return act('return', () => props.api.returnTask(mine.value!.taskId, 'initiator', comment.value), '已退回发起人');
}
async function reject() {
  if (!comment.value.trim()) return ElMessage.warning('请在意见中写明驳回原因');
  try {
    await ElMessageBox.confirm('驳回后流程结束，单据不能再提交。确定驳回？', '驳回', { type: 'warning', confirmButtonText: '驳回' });
  } catch {
    return;
  }
  return act('reject', () => props.api.complete(mine.value!.taskId, { action: 'REJECT', comment: comment.value, input: null }), '已驳回');
}
function urge() {
  const t = p.value!.current[0];
  return act('urge', () => props.api.urge(t.taskId), `已催办 ${t.handlers.map(who).join('、')}`);
}

watch(() => [props.concept, props.id], load, { immediate: true });
defineExpose({ load, progress: p });
</script>

<style scoped>
.flow-head {
  margin-bottom: var(--ls-space-3, 12px);
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-blank);
}
.flow-head__returned {
  margin-bottom: 10px;
}
.flow-head__line {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.flow-head__flow {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.flow-head__node {
  font-weight: 600;
}
.flow-head__me {
  font-weight: 600;
  color: var(--el-color-primary);
}
.flow-head__due {
  font-size: 13px;
}
.flow-head__due.is-OVERDUE {
  color: var(--el-color-danger);
}
.flow-head__due.is-DUE_SOON {
  color: var(--el-color-warning);
}
.flow-head__act {
  margin-top: 10px;
}
.flow-head__btns {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.flow-head__steps {
  margin-top: 14px;
}
.flow-head__step {
  cursor: pointer;
  border-bottom: 1px dashed currentColor;
}
.flow-head__stepname {
  font-weight: 600;
  margin-bottom: 6px;
}
.flow-head__rec {
  margin-top: 6px;
  font-size: 13px;
}
.flow-head__sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.flow-head__warn {
  color: var(--el-color-danger);
}
</style>
