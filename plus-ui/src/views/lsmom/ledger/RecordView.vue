<template>
  <div class="record" data-testid="record-view">
    <el-skeleton v-if="!record" :rows="6" animated />
    <template v-else>
      <div class="record__meta">
        <el-tag size="small" data-testid="detail-status">{{ stateLabel(record.status) }}</el-tag>
        <span data-testid="detail-form-version">表单 v{{ view?.version ?? '—' }}</span>
        <span class="record__sub">#{{ record.id }} · 版本 {{ record.version }}</span>
      </div>

      <!-- 状态与下一步、流程进度（有流程的单据） -->
      <FlowHeader
        v-if="flow"
        ref="flowHead"
        :concept="concept"
        :id="id"
        :api="flow"
        :status-label="stateLabel(record.status)"
        @changed="onFlowChanged"
        @open-task="openTask"
      />
      <TaskPanel v-if="flow" ref="taskPanel" :api="flow" :actions="flow" @done="onFlowChanged" />

      <!-- 动作区：只显示当前状态下本人可执行的动作 -->
      <div v-if="actions.length" class="record__actions" data-testid="detail-actions">
        <el-button
          v-for="op in actions"
          :key="op.operation"
          :type="pending?.operation === op.operation ? 'primary' : 'default'"
          :loading="busy === op.operation"
          :data-testid="'detail-op-' + op.operation"
          @click="start(op)"
        >
          {{ op.name }}
        </el-button>
        <div v-if="pending" class="record__input" data-testid="detail-op-input">
          <el-form label-position="top" @submit.prevent>
            <el-form-item v-for="p in inputProps(pending).filter(fillable)" :key="p.code" :label="p.label" :required="p.required">
              <el-input-number v-if="p.type === 'number'" v-model="input[p.code]" :data-testid="'detail-in-' + p.code" />
              <el-date-picker
                v-else-if="p.format === 'date'"
                v-model="input[p.code]"
                type="date"
                value-format="YYYY-MM-DD"
                :data-testid="'detail-in-' + p.code"
              />
              <el-switch v-else-if="p.type === 'boolean'" v-model="input[p.code]" :data-testid="'detail-in-' + p.code" />
              <el-input v-else v-model="input[p.code]" type="textarea" :rows="2" :maxlength="p.maxLength" :data-testid="'detail-in-' + p.code" />
            </el-form-item>
          </el-form>
          <el-button @click="pending = null">取消</el-button>
          <el-button type="primary" :loading="!!busy" data-testid="detail-op-confirm" @click="run(pending)">确认{{ pending.name }}</el-button>
        </div>
      </div>

      <el-tabs v-model="tab">
        <el-tab-pane label="详情" name="detail">
          <SchemaDetail v-if="view" :schema="view.schema" :record="record" :frozen-version="view.version" :latest-version="latest" :api="forms" />
          <!-- 没有发布表单时按台账字段显示 -->
          <el-descriptions v-else-if="listSchema" :column="2" border size="small" data-testid="detail-fields">
            <el-descriptions-item v-for="f in listSchema.fields.filter((x) => x.type !== 'JSON')" :key="f.code" :label="f.label">{{
              cellText(f, record)
            }}</el-descriptions-item>
          </el-descriptions>
          <el-empty v-else description="该台账没有已发布的表单" />
        </el-tab-pane>
        <el-tab-pane :label="`时间轴${timeline.length ? `（${timeline.length}）` : ''}`" name="timeline">
          <el-timeline v-if="timeline.length" data-testid="detail-timeline">
            <el-timeline-item v-for="(e, i) in timeline" :key="i" :timestamp="fmt(e.at)" placement="top">
              <b>{{ e.payload?.label ?? opName(e.action) }}</b>
              <span v-if="e.payload?.nodeName"> · {{ e.payload.nodeName }}</span>
              <span> · {{ e.actorName ?? '系统' }}</span>
              <span v-if="e.fromStatus || e.toStatus" class="record__sub"> （{{ stateLabel(e.fromStatus) }} → {{ stateLabel(e.toStatus) }}） </span>
              <div v-if="e.payload?.comment" class="record__sub">意见：{{ e.payload.comment }}</div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无记录" />
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
</template>

<script setup lang="ts">
import { notifyDone } from '@/utils/opResult';
import { confirmOperation } from '@/utils/opConfirm';
import { useUndoable } from '@/utils/undoable';
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import SchemaDetail from '@/components/SchemaDetail/index.vue';
import FlowHeader from '@/components/LsFlow/FlowHeader.vue';
import TaskPanel from '@/views/lsmom/workbench/TaskPanel.vue';
import type { FlowProgressApi } from '@/api/lsmom/flowProgress';
import type { CrudApi, TimelineEntry } from '@/api/lsmom/crud';
import type { FormApi } from '@/api/lsmom/form';
import type { ListSchema, SchemaOperation } from '@/api/lsmom/types';
import type { FormSchema, ViewVersion } from '@/types/lsView';
import { statesOf } from '@/components/SchemaTable/conditions';
import { cellText, rowOperations } from '@/components/SchemaTable/querySpec';
import { idempotencyKey, recordLabel } from './ledgerModel';

/** 单条记录：状态、可执行动作、只读详情（按记录冻结的表单版本）与时间轴。抽屉和"在新页签打开"共用。 */
const props = defineProps<{
  concept: string;
  id: number;
  crud: CrudApi;
  forms: FormApi;
  schema?: ListSchema | null;
  /** 打开后直接进入该动作的输入（列表行内点了需要填写意见的动作） */
  startOp?: string | null;
  /** 提供时显示流程头部（状态与下一步、步骤条）并可原地办理 */
  flow?: FlowProgressApi | null;
}>();
const emit = defineEmits<{ (e: 'changed', record: Record<string, any>): void; (e: 'loaded', record: Record<string, any>): void }>();

const undoable = useUndoable();
const record = ref<Record<string, any> | null>(null);
const view = ref<ViewVersion<FormSchema> | null>(null);
const latest = ref(0);
const timeline = ref<TimelineEntry[]>([]);
const ownSchema = ref<ListSchema | null>(null);
const tab = ref('detail');
const busy = ref<string | null>(null);
const pending = ref<SchemaOperation | null>(null);
const input = reactive<Record<string, any>>({});

const listSchema = computed(() => props.schema ?? ownSchema.value);
const states = computed(() => (listSchema.value ? statesOf(listSchema.value) : []));
const actions = computed(() => {
  const s = listSchema.value;
  const r = record.value;
  if (!s || !r) return [];
  return rowOperations(s).filter((o) => (!o.from || o.from.includes(r.status)) && supported(o));
});

function stateLabel(code?: string) {
  return (code && states.value.find((s) => s.code === code)?.label) || code || '';
}
function opName(action: string) {
  return listSchema.value?.operations.find((o) => o.operation === action)?.name ?? action;
}
function fmt(at: string) {
  return at ? at.replace('T', ' ').slice(0, 19) : '';
}

interface InputProp {
  code: string;
  label: string;
  type: string;
  required: boolean;
  maxLength?: number;
  format?: string;
}
function inputProps(op: SchemaOperation): InputProp[] {
  const sch = (op.inputSchema ?? {}) as { properties?: Record<string, any>; required?: string[] };
  const req = sch.required ?? [];
  return Object.entries(sch.properties ?? {}).map(([code, p]) => ({
    code,
    label: p.title ?? listSchema.value?.fields.find((f) => f.code === code)?.label ?? code,
    type: String(p.type ?? 'string'),
    required: req.includes(code),
    maxLength: p.maxLength,
    format: p.format
  }));
}
/** 抽屉里能直接填写的输入：文本、日期、小数、开关。整数多为对象编号（如整改负责人），需要选择器，在对应工作台办理。 */
function fillable(p: InputProp) {
  return ['string', 'number', 'boolean'].includes(p.type);
}
function supported(op: SchemaOperation) {
  return inputProps(op).every((p) => !p.required || fillable(p));
}

async function load() {
  const id = props.id;
  record.value = null;
  view.value = null;
  pending.value = null;
  timeline.value = [];
  if (!props.schema && !ownSchema.value) ownSchema.value = await props.crud.schema(props.concept);
  const [rec, def, tl] = await Promise.all([
    props.crud.get(props.concept, id),
    props.forms.defaultFor(props.concept),
    props.crud.timeline(props.concept, id).catch(() => [] as TimelineEntry[])
  ]);
  if (id !== props.id) return; // 已切到别的记录
  record.value = rec as Record<string, any>;
  timeline.value = [...tl].reverse();
  emit('loaded', record.value);
  const op = props.startOp && actions.value.find((a) => a.operation === props.startOp);
  if (op) start(op);
  if (!def) return;
  latest.value = def.version;
  const frozen = Number(rec.schemaVersion ?? def.version);
  view.value = frozen === def.version ? def : await props.forms.get<FormSchema>(def.code, frozen);
}

function start(op: SchemaOperation) {
  Object.keys(input).forEach((k) => delete input[k]);
  if (inputProps(op).some(fillable)) pending.value = op;
  else void run(op);
}

async function run(op: SchemaOperation) {
  const missing = inputProps(op).filter((p) => p.required && (input[p.code] === undefined || input[p.code] === ''));
  if (missing.length) {
    ElMessage.warning(`请填写${missing.map((p) => p.label).join('、')}`);
    return;
  }
  const r = record.value!;
  if (op.undoable) return runUndoable(op, r);
  const ok = await confirmOperation({
    name: op.name,
    label: recordLabel(r, listSchema.value),
    impact: typeof props.crud.impact === 'function' ? () => props.crud.impact(op.operation, r.id) : undefined,
    stateLabel: (c) => stateLabel(c ?? undefined)
  });
  if (!ok) return;
  busy.value = op.operation;
  try {
    const res = await props.crud.execute(op.operation, { targetId: String(r.id), version: Number(r.version), input: { ...input } }, idempotencyKey());
    notifyDone(`已${op.name}`, res.warnings);
    pending.value = null;
    await load();
    emit('changed', record.value!);
  } finally {
    busy.value = null;
  }
}

/** 可撤销的动作：直接执行，提示里 5 秒内可撤销 */
async function runUndoable(op: SchemaOperation, r: Record<string, any>) {
  busy.value = op.operation;
  try {
    await undoable.run({
      done: `已${op.name}`,
      exec: () => props.crud.execute(op.operation, { targetId: String(r.id), version: Number(r.version), input: { ...input } }, idempotencyKey()),
      compensate: (o, id, version, inp) => props.crud.execute(o, { targetId: id, version, input: inp }, idempotencyKey()),
      after: async () => {
        await load();
        if (record.value) emit('changed', record.value);
      }
    });
    pending.value = null;
    await load();
    emit('changed', record.value!);
  } finally {
    busy.value = null;
  }
}

const flowHead = ref<InstanceType<typeof FlowHeader>>();
const taskPanel = ref<InstanceType<typeof TaskPanel>>();
function openTask(taskId: string) {
  void taskPanel.value?.open(taskId);
}
/** 在流程头部或办理面板办理后：刷新记录、时间轴和头部 */
async function onFlowChanged() {
  await load();
  await flowHead.value?.load();
  if (record.value) emit('changed', record.value);
}

watch(() => [props.concept, props.id], load, { immediate: true });
// 同一条记录再次从列表点需要输入的动作
watch(
  () => props.startOp,
  (code) => {
    const op = code && record.value && actions.value.find((a) => a.operation === code);
    if (op) start(op);
  }
);
defineExpose({ load, record });
</script>

<style scoped>
.record__meta {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}
.record__sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.record__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.record__input {
  flex-basis: 100%;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}
</style>
