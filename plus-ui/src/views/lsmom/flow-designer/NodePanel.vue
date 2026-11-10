<template>
  <el-form ref="root" label-width="96px" size="small" data-testid="node-panel" @submit.prevent>
    <el-form-item label="节点键" :error="keyErr">
      <el-input :model-value="cur.key" data-testid="node-key" @update:model-value="setKey" />
    </el-form-item>
    <el-form-item label="名称">
      <el-input v-model="cur.name" data-testid="node-name" />
    </el-form-item>
    <el-form-item label="类型">
      <el-tag :color="typeInfo(cur.type).color" effect="dark" style="border: none">{{ typeInfo(cur.type).label }}</el-tag>
    </el-form-item>

    <!-- 办理人 -->
    <div
      v-if="human || cur.type === 'CC'"
      class="np-sec"
      :class="{ 'is-focus': flash === 'assignee' }"
      data-section="assignee"
      data-testid="section-assignee"
    >
      <el-divider content-position="left">{{ cur.type === 'CC' ? '抄送对象' : '办理人' }}</el-divider>
      <el-form-item label="策略">
        <el-select v-model="assignee.strategy" data-testid="assignee-strategy" @change="onStrategy">
          <el-option v-for="s in STRATEGIES" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="assignee.strategy === 'USER'" label="人员">
        <el-select
          v-model="userValue"
          multiple
          filterable
          remote
          :remote-method="searchParties"
          :loading="searching"
          placeholder="输入姓名搜索"
          data-testid="assignee-users"
        >
          <el-option v-for="p in partyOptions" :key="p.id" :label="p.name + (p.orgName ? `（${p.orgName}）` : '')" :value="p.id" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="assignee.strategy === 'ROLE' || assignee.strategy === 'DATA_SCOPE'" label="角色">
        <el-select v-model="assignee.value" filterable data-testid="assignee-role">
          <el-option v-for="r in roles" :key="r.roleKey" :label="`${r.roleName}（${r.roleKey}）`" :value="r.roleKey" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="assignee.strategy === 'ROLE'" label="范围">
        <el-checkbox :model-value="assignee.of === 'BIZ_ORG'" @update:model-value="(v: any) => (assignee.of = v ? 'BIZ_ORG' : undefined)">
          只取业务组织内成员
        </el-checkbox>
      </el-form-item>
      <el-form-item v-if="assignee.strategy === 'DEPT' || assignee.strategy === 'TEAM'" label="组织">
        <el-select v-model="assignee.value" clearable filterable placeholder="留空 = 单据所属组织" data-testid="assignee-org">
          <el-option v-for="o in orgs" :key="o.id" :label="o.name" :value="o.id" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="assignee.strategy === 'FIELD'" label="字段">
        <el-select v-model="assignee.value" filterable data-testid="assignee-field">
          <el-option v-for="f in conceptFields" :key="f.code" :label="`${f.label}（${f.code}）`" :value="f.code" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="leader" label="基准">
        <el-radio-group :model-value="assignee.of ?? 'INITIATOR'" @update:model-value="(v: any) => (assignee.of = v)">
          <el-radio value="INITIATOR">发起人所在组织</el-radio>
          <el-radio value="BIZ_ORG">单据所属组织</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="human" label="多人处理">
        <el-radio-group v-model="cur.mode" data-testid="node-mode">
          <el-radio-button v-for="m in MODES" :key="m.value" :value="m.value" :data-testid="`mode-${m.value}`">{{ m.label }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="human" label="节点权限">
        <el-input v-model="cur.permission" placeholder="可选，如 lsmom:loan:audit" clearable />
      </el-form-item>
    </div>

    <!-- Operation -->
    <div
      v-if="human || cur.type === 'SERVICE'"
      class="np-sec"
      :class="{ 'is-focus': flash === 'operation' }"
      data-section="operation"
      data-testid="section-operation"
    >
      <el-divider content-position="left">{{ cur.type === 'SERVICE' ? '执行' : '提交时执行' }}</el-divider>
      <el-form-item :label="cur.type === 'SERVICE' ? 'Operation' : '节点 Operation'">
        <el-select v-model="cur.operation" clearable filterable placeholder="只能选择已发布的 Operation" data-testid="node-operation">
          <el-option v-for="o in operations" :key="o.operation" :label="`${o.name}（${o.operation}）`" :value="o.operation" />
        </el-select>
      </el-form-item>
    </div>

    <!-- 超时 -->
    <div v-if="human" class="np-sec" :class="{ 'is-focus': flash === 'timeout' }" data-section="timeout" data-testid="section-timeout">
      <el-divider content-position="left">超时</el-divider>
      <el-form-item label="时限（小时）">
        <el-input-number v-model="timeout.hours" :min="0" :max="720" controls-position="right" data-testid="timeout-hours" />
      </el-form-item>
      <el-form-item v-if="timeout.hours" label="超时动作">
        <el-select v-model="timeout.action">
          <el-option v-for="a in TIMEOUT_ACTIONS" :key="a.value" :label="a.label" :value="a.value" />
        </el-select>
      </el-form-item>
    </div>

    <!-- 字段策略 -->
    <div v-if="human" class="np-sec" :class="{ 'is-focus': flash === 'policy' }" data-section="policy" data-testid="section-policy">
      <el-divider content-position="left">表单字段策略</el-divider>
      <div v-if="!formFields.length" class="hint">流程未选择表单，或表单没有字段</div>
      <template v-else>
        <el-form-item label="其余字段">
          <el-checkbox :model-value="restReadonly" data-testid="policy-rest-readonly" @update:model-value="setRest">只读</el-checkbox>
        </el-form-item>
        <el-table :data="formFields" size="small" max-height="260" data-testid="policy-table">
          <el-table-column prop="label" label="字段" min-width="100" />
          <el-table-column label="策略" width="130">
            <template #default="{ row }">
              <el-select
                :model-value="fieldMode(row.key)"
                size="small"
                :data-testid="`policy-${row.key}`"
                @update:model-value="(v: any) => setFieldMode(row.key, v)"
              >
                <el-option label="默认" value="" />
                <el-option label="只读" value="readonly" />
                <el-option label="隐藏" value="hidden" />
                <el-option label="可编辑" value="editable" />
                <el-option label="必填" value="required" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>

    <!-- 分支 -->
    <div v-if="cur.branches" class="np-sec" :class="{ 'is-focus': flash === 'branches' }" data-section="branches" data-testid="section-branches">
      <el-divider content-position="left">分支</el-divider>
      <div v-for="(b, i) in cur.branches" :key="i" class="branch-row" :data-testid="`branch-edit-${i}`">
        <el-input v-model="b.name" placeholder="分支名称" style="width: 110px" />
        <el-input
          v-if="cur.type === 'CONDITION'"
          v-model="b.when"
          placeholder="CEL，如 $model.amount > 10000；留空为默认"
          :data-testid="`branch-when-${i}`"
        />
        <el-button link type="danger" :icon="Delete" :disabled="cur.branches.length <= 2" @click="cur.branches.splice(i, 1)" />
      </div>
      <el-button size="small" @click="addBranch">＋ 分支</el-button>
    </div>
  </el-form>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { Delete } from '@element-plus/icons-vue';
import { keyError, MODES, STRATEGIES, TIMEOUT_ACTIONS, typeInfo, isHuman, type FlowDef, type FlowNode } from './model';
import type { FormField, OperationOption } from './types';

const props = defineProps<{
  def: FlowDef;
  node: FlowNode;
  operations: OperationOption[];
  roles: { roleKey: string; roleName: string }[];
  orgs: { id: number; name: string }[];
  conceptFields: { code: string; label: string }[];
  /** 问题定位：要打开并高亮的区块（assignee / operation / timeout / policy / branches），tick 变化时重新定位 */
  focus?: string;
  focusTick?: number;
  formFields: FormField[];
  partyCache: Map<number, { id: number; name: string; orgName?: string }>;
  searchPartiesApi: (k?: string) => Promise<{ id: number; name: string; orgName?: string }[]>;
}>();

const root = ref<{ $el: HTMLElement } | null>(null);
const flash = ref('');
let flashTimer: number | undefined;
watch(
  () => [props.focus, props.focusTick],
  async () => {
    if (!props.focus) return;
    await nextTick();
    const el = root.value?.$el?.querySelector<HTMLElement>(`[data-section="${props.focus}"]`);
    if (!el) return;
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    flash.value = props.focus;
    window.clearTimeout(flashTimer);
    flashTimer = window.setTimeout(() => (flash.value = ''), 2500);
  },
  { immediate: true }
);
const emit = defineEmits<{ (e: 'rekey', oldKey: string, newKey: string): void; (e: 'addBranch', n: FlowNode): void }>();

/** 当前编辑的节点：面板直接修改父组件持有的流程配置树中的这个节点对象。 */
const cur = computed(() => props.node);
const human = computed(() => isHuman(cur.value.type));
const leader = computed(() => ['ORG_LEADER', 'DEPT_LEADER', 'WORKSHOP_LEADER', 'SUPERIOR_LEADER'].includes(assignee.value.strategy));

// 选中节点时补齐可编辑的子对象（提交前由 clean() 去掉空值）
watch(
  () => props.node,
  (n) => {
    if ((isHuman(n.type) || n.type === 'CC') && !n.assignee) n.assignee = { strategy: 'INITIATOR' };
    if (isHuman(n.type)) {
      if (!n.timeout) n.timeout = { hours: 0, action: 'REMIND' };
      if (!n.timeout.action) n.timeout.action = 'REMIND';
      if (!n.fieldPolicy) n.fieldPolicy = {};
      if (!n.mode) n.mode = 'SINGLE';
    }
  },
  { immediate: true }
);
const assignee = computed(() => cur.value.assignee ?? { strategy: '' });
const timeout = computed(() => cur.value.timeout ?? { hours: 0 });

const keyErr = ref('');
function setKey(v: string) {
  const err = keyError(props.def, cur.value, v);
  keyErr.value = err;
  if (!err) {
    const old = cur.value.key;
    cur.value.key = v;
    emit('rekey', old, v);
  }
}
watch(
  () => cur.value,
  () => (keyErr.value = '')
);

function onStrategy() {
  const a = assignee.value;
  a.value = a.strategy === 'USER' ? [] : undefined;
  a.of = leader.value ? 'INITIATOR' : undefined;
}

const userValue = computed({
  get: () => (Array.isArray(assignee.value.value) ? (assignee.value.value as number[]) : []),
  set: (v: number[]) => (assignee.value.value = v)
});
const searching = ref(false);
const found = ref<{ id: number; name: string; orgName?: string }[]>([]);
const partyOptions = computed(() => {
  const m = new Map(found.value.map((p) => [p.id, p]));
  for (const id of userValue.value) if (!m.has(id)) m.set(id, props.partyCache.get(id) ?? { id, name: `#${id}` });
  return [...m.values()];
});
async function searchParties(k: string) {
  searching.value = true;
  try {
    found.value = await props.searchPartiesApi(k);
    found.value.forEach((p) => props.partyCache.set(p.id, p));
  } finally {
    searching.value = false;
  }
}
searchParties('');

// 字段策略：每个字段一种模式；「其余字段只读」写为 readonly: ['*']
const policy = computed(() => cur.value.fieldPolicy ?? {});
const restReadonly = computed(() => (policy.value.readonly ?? []).includes('*'));
function setRest(v: any) {
  const ro = (policy.value.readonly ?? []).filter((x) => x !== '*');
  policy.value.readonly = v ? ['*', ...ro] : ro;
}
function fieldMode(key: string) {
  const p = policy.value;
  if (p.required?.includes(key)) return 'required';
  if (p.editable?.includes(key)) return 'editable';
  if (p.hidden?.includes(key)) return 'hidden';
  if (p.readonly?.includes(key)) return 'readonly';
  return '';
}
function setFieldMode(key: string, mode: string) {
  const p = policy.value;
  for (const k of ['readonly', 'hidden', 'editable', 'required'] as const) p[k] = (p[k] ?? []).filter((x) => x !== key);
  if (mode === 'required') {
    p.editable!.push(key);
    p.required!.push(key);
  } else if (mode) (p as any)[mode].push(key);
}

function addBranch() {
  emit('addBranch', cur.value);
}
</script>

<style scoped>
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  padding: 0 0 var(--ls-space-2) var(--ls-space-4);
}
.branch-row {
  display: flex;
  gap: var(--ls-space-2);
  margin-bottom: var(--ls-space-2);
}
.np-sec {
  border-radius: 6px;
  transition:
    box-shadow 0.3s,
    background-color 0.3s;
}
.np-sec.is-focus {
  background: var(--el-color-warning-light-9);
  box-shadow: 0 0 0 2px var(--el-color-warning-light-5);
}
</style>
