<template>
  <div class="flow-ws" data-testid="flow-designer">
    <div class="toolbar">
      <span class="title">{{ def.name || '未命名流程' }}</span>
      <el-tag size="small" type="info">{{ def.code }}</el-tag>
      <el-tag size="small" :type="status === 'PUBLISHED' ? 'success' : 'warning'" data-testid="flow-status">
        {{ status === 'PUBLISHED' ? `已发布 v${version}` : `草稿${version ? ' v' + version : ''}` }}
      </el-tag>
      <span class="spacer" />
      <el-button size="small" :loading="busy === 'validate'" data-testid="btn-validate" @click="validate">校验</el-button>
      <el-button size="small" :loading="busy === 'save'" data-testid="btn-save" @click="save">保存草稿</el-button>
      <el-button size="small" type="primary" :loading="busy === 'publish'" data-testid="btn-publish" @click="publish">发布</el-button>
    </div>

    <el-alert
      v-if="checked && !errorCount"
      type="success"
      :closable="false"
      :title="warnCount ? `校验通过（${warnCount} 个警告，可以发布）` : '校验通过'"
      data-testid="issues-ok"
      class="mb-2"
    />
    <div v-if="issues.length" class="issues" data-testid="issues">
      <div class="issues-head" data-testid="issues-summary">
        <el-tag v-if="errorCount" size="small" type="danger" effect="dark">{{ errorCount }} 个错误</el-tag>
        <el-tag v-if="warnCount" size="small" type="warning" effect="dark">{{ warnCount }} 个警告</el-tag>
        <span class="path">{{ errorCount ? '有错误时不能发布；' : '' }}点击问题定位到对应的节点或设置</span>
      </div>
      <div v-for="(i, n) in sortedIssues" :key="n" class="issue" :data-testid="'issue-' + n" :data-level="i.level ?? 'ERROR'" @click="locate(i.path)">
        <el-tag size="small" :type="i.level === 'WARNING' ? 'warning' : 'danger'">{{ i.level === 'WARNING' ? '警告' : '错误' }}</el-tag>
        <span>{{ i.message }}</span>
        <span class="path">{{ where(i.path) }}</span>
      </div>
    </div>

    <el-tabs v-model="tab">
      <el-tab-pane label="配置" name="config">
        <div class="config">
          <el-form class="settings" label-width="92px" size="small" data-testid="flow-settings" @submit.prevent>
            <el-form-item label="流程编码">
              <el-input v-model="def.code" :disabled="codeLocked" data-testid="flow-code" />
            </el-form-item>
            <el-form-item label="名称"><el-input v-model="def.name" data-testid="flow-name" /></el-form-item>
            <el-form-item label="业务对象"><el-input :model-value="def.concept" disabled /></el-form-item>
            <el-form-item label="发起动作">
              <el-select v-model="def.startOn" clearable filterable placeholder="哪个 Operation 成功后自动发起" data-testid="flow-start-on">
                <el-option v-for="o in operations" :key="o.operation" :label="opLabel(o)" :value="o.operation" />
              </el-select>
            </el-form-item>
            <el-form-item label="表单">
              <el-select :model-value="formKey" clearable placeholder="节点字段策略基于该表单" data-testid="flow-form" @update:model-value="setForm">
                <el-option
                  v-for="f in forms"
                  :key="f.code + '@' + f.version"
                  :label="`${f.title || f.code} v${f.version}`"
                  :value="f.code + '@' + f.version"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="所属组织">
              <el-select v-model="def.orgUnitId" clearable filterable data-testid="flow-org">
                <el-option v-for="o in orgs" :key="o.id" :label="o.name" :value="o.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="流程管理员">
              <el-select v-model="def.adminRole" clearable filterable placeholder="异常待办的处理角色">
                <el-option v-for="r in roles" :key="r.roleKey" :label="r.roleName" :value="r.roleKey" />
              </el-select>
            </el-form-item>
            <el-divider content-position="left">回调 Operation</el-divider>
            <el-form-item v-for="c in CALLBACKS" :key="c.key" :label="c.label" :required="c.required">
              <el-select v-model="(def.callbacks as any)[c.key]" clearable filterable :data-testid="`cb-${c.key}`">
                <el-option v-for="o in operations" :key="o.operation" :label="opLabel(o)" :value="o.operation" />
              </el-select>
            </el-form-item>
          </el-form>

          <div class="canvas" data-testid="flow-tree" @click="selected = null">
            <div class="terminal start">发起人提交</div>
            <NodeList :nodes="def.nodes" :ctx="ctx" path="root" />
            <div class="terminal end">结束</div>
          </div>

          <div class="panel">
            <NodePanel
              v-if="current"
              :key="current.key"
              :def="def"
              :node="current"
              :operations="operations"
              :roles="roles"
              :orgs="orgs"
              :concept-fields="conceptFields"
              :form-fields="formFields"
              :party-cache="partyCache"
              :search-parties-api="api.parties"
              :focus="focusSection"
              :focus-tick="focusTick"
              @rekey="(_o, n) => (selected = n)"
              @add-branch="addBranch"
            />
            <el-empty v-else description="选择左侧节点编辑属性；点「＋」插入节点" :image-size="60" />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="预览" name="preview" lazy>
        <div class="mb-2">
          <el-button size="small" data-testid="btn-preview" @click="refreshPreview">刷新预览</el-button>
          <span class="hint">由当前配置生成的 BPMN（只读，不可导入或编辑 XML）</span>
        </div>
        <FlowPreview v-if="previewXml" :xml="previewXml" />
      </el-tab-pane>

      <el-tab-pane label="版本差异" name="diff" lazy>
        <div class="diff-bar">
          <span>基准</span>
          <el-select v-model="diffBase" size="small" style="width: 140px" data-testid="diff-base">
            <el-option v-for="h in history" :key="h.version" :label="`v${h.version}（${statusText(h.status)}）`" :value="h.version" />
          </el-select>
          <span>对比</span>
          <el-select v-model="diffTarget" size="small" style="width: 160px" data-testid="diff-target">
            <el-option label="当前配置" :value="0" />
            <el-option v-for="h in history" :key="h.version" :label="`v${h.version}（${statusText(h.status)}）`" :value="h.version" />
          </el-select>
          <el-button size="small" type="primary" :disabled="!diffBase" data-testid="btn-diff" @click="runDiff">比较</el-button>
        </div>
        <template v-if="diff">
          <div class="legend"><span class="added">新增</span><span class="changed">变化</span><span class="removed">删除</span></div>
          <ul class="diff-list" data-testid="diff-list">
            <li v-if="!diff.changes.length">两个版本的节点没有差异</li>
            <li v-for="c in diff.changes" :key="c.kind + c.key" :class="c.kind.toLowerCase()" :data-testid="`diff-${c.kind}-${c.key}`">
              {{ KIND_TEXT[c.kind] }} · {{ c.name }}（{{ c.key }}）<span v-if="c.detail"> — {{ c.detail }}</span>
            </li>
          </ul>
          <FlowPreview
            :xml="diff.xml"
            :keys="diff.keys"
            :added="diff.changes.filter((c) => c.kind === 'ADDED').map((c) => c.key)"
            :changed="diff.changes.filter((c) => c.kind === 'CHANGED').map((c) => c.key)"
          />
        </template>
      </el-tab-pane>

      <el-tab-pane label="配置 JSON" name="json" lazy>
        <pre class="json" data-testid="flow-json">{{ JSON.stringify(clean(def), null, 2) }}</pre>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import NodeList from './NodeList.vue';
import NodePanel from './NodePanel.vue';
import FlowPreview from './FlowPreview.vue';
import {
  allKeys,
  assigneeText,
  clean,
  diffDefs,
  findNode,
  newNode,
  removeNode,
  walk,
  type FlowDef,
  type FlowNode,
  type NodeChange,
  type NodeType
} from './model';
import type { FlowDesignerApi, FormField, OperationOption, TreeContext } from './types';

const props = defineProps<{
  definition: FlowDef;
  status: string;
  version?: number;
  codeLocked?: boolean;
  operations: OperationOption[];
  roles: { roleKey: string; roleName: string }[];
  orgs: { id: number; name: string }[];
  conceptFields: { code: string; label: string }[];
  forms: { code: string; version: number; title?: string }[];
  api: FlowDesignerApi;
}>();
const emit = defineEmits<{ (e: 'published', v: { version: number; status: string }): void }>();

const CALLBACKS = [
  { key: 'approved', label: '通过时', required: true },
  { key: 'rejected', label: '驳回时', required: true },
  { key: 'returned', label: '退回发起人', required: false },
  { key: 'resubmitted', label: '再提交', required: false },
  { key: 'withdrawn', label: '撤回', required: false }
];
const KIND_TEXT: Record<NodeChange['kind'], string> = { ADDED: '新增', CHANGED: '变化', REMOVED: '删除' };
const statusText = (s: string) => ({ PUBLISHED: '已发布', DRAFT: '草稿', ARCHIVED: '已归档', RETIRED: '已退役' })[s] ?? s;

const def = reactive<FlowDef>(JSON.parse(JSON.stringify(props.definition)));
if (!def.callbacks) def.callbacks = {};
if (!def.nodes) def.nodes = [];
const status = ref(props.status);
const version = ref(props.version);
const tab = ref('config');
const selected = ref<string | null>(null);
const current = computed(() => (selected.value ? findNode(def, selected.value) : undefined));
const issues = ref<{ path: string; code: string; message: string; level?: 'ERROR' | 'WARNING' }[]>([]);
const checked = ref(false);
const busy = ref('');
const partyCache = reactive(new Map<number, { id: number; name: string; orgName?: string }>());

// 已选人员的名字（卡片摘要用）
props.api
  .parties('')
  .then((ps) => ps.forEach((p) => partyCache.set(p.id, p)))
  .catch(() => undefined);

const opLabel = (o: OperationOption) => `${o.name}（${o.operation.substring(o.operation.lastIndexOf('.') + 1)}）`;
const opName = (code?: string) => (code ? (props.operations.find((o) => o.operation === code)?.name ?? code) : '未选择');

const issueKeys = computed(() => {
  const s = new Set<string>();
  for (const i of issues.value) {
    const n = nodeAtPath(i.path);
    if (n) s.add(n.key);
  }
  return s;
});

const ctx: TreeContext = {
  selected,
  issueKeys,
  select: (k) => (selected.value = k),
  insert: (list: FlowNode[], index: number, type: NodeType) => {
    const n = newNode(def, type);
    list.splice(index, 0, n);
    selected.value = n.key;
    checked.value = false;
  },
  remove: (k) => {
    removeNode(def, k);
    if (selected.value && !findNode(def, selected.value)) selected.value = null;
  },
  addBranch: (n) => addBranch(n),
  assignee: (n) =>
    assigneeText(n.assignee, {
      party: (id) => partyCache.get(id)?.name ?? `#${id}`,
      role: (k) => props.roles.find((r) => r.roleKey === k)?.roleName ?? k,
      org: (id) => props.orgs.find((o) => o.id === id)?.name ?? `#${id}`
    }),
  opName
};

function addBranch(n: FlowNode) {
  n.branches = n.branches ?? [];
  const i = n.branches.length + 1;
  n.branches.splice(n.type === 'CONDITION' ? n.branches.length - 1 : n.branches.length, 0, {
    name: n.type === 'CONDITION' ? `条件 ${i - 1}` : `分支 ${i}`,
    when: n.type === 'CONDITION' ? '' : undefined,
    nodes: []
  });
}

/** 后端问题路径（如 /nodes/1/branches/0/nodes/0/assignee）定位到节点。 */
function nodeAtPath(path: string): FlowNode | undefined {
  const re = /\/(nodes|branches)\/(\d+)/g;
  let list: any[] = def.nodes;
  let node: FlowNode | undefined;
  let m: RegExpExecArray | null;
  while ((m = re.exec(path))) {
    const i = Number(m[2]);
    if (m[1] === 'nodes') {
      node = list?.[i];
      if (!node) return undefined;
    } else {
      list = node?.branches?.[i]?.nodes ?? [];
    }
  }
  return node;
}
const errorCount = computed(() => issues.value.filter((i) => i.level !== 'WARNING').length);
const warnCount = computed(() => issues.value.length - errorCount.value);
const sortedIssues = computed(() => [...issues.value].sort((a, b) => Number(a.level === 'WARNING') - Number(b.level === 'WARNING')));
const focusSection = ref('');
const focusTick = ref(0);
/** 节点内路径 → 节点属性面板区块 */
const SECTION: Record<string, string> = {
  fieldPolicy: 'policy',
  policy: 'policy',
  assignee: 'assignee',
  mode: 'assignee',
  operation: 'operation',
  timeout: 'timeout',
  branches: 'branches'
};
/** 流程级路径 → 设置项 */
const SETTING: Record<string, string> = { code: 'flow-code', name: 'flow-name', startOn: 'flow-start-on', form: 'flow-form', orgUnitId: 'flow-org' };
function where(path: string) {
  const n = nodeAtPath(path);
  if (n) return `节点「${n.name || n.key}」`;
  const m = /^\/callbacks\/(\w+)/.exec(path);
  if (m) return `回调 ${m[1]}`;
  return path === '/' ? '整体' : path;
}
function locate(path: string) {
  tab.value = 'config';
  const n = nodeAtPath(path);
  if (n) {
    selected.value = n.key;
    const rest =
      path
        .replace(/^.*\/nodes\/\d+/, '')
        .split('/')
        .filter(Boolean)[0] ?? '';
    focusSection.value = SECTION[rest] ?? '';
    focusTick.value++;
    return;
  }
  const cb = /^\/callbacks\/(\w+)/.exec(path);
  const testid = cb ? `cb-${cb[1]}` : SETTING[path.split('/').filter(Boolean)[0] ?? ''];
  if (!testid) return;
  nextTick(() => {
    const el = document.querySelector<HTMLElement>(`[data-testid="${testid}"]`)?.closest<HTMLElement>('.el-form-item');
    if (!el) return;
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    el.classList.add('is-located');
    window.setTimeout(() => el.classList.remove('is-located'), 2500);
  });
}

const payload = () => clean(JSON.parse(JSON.stringify(def))) as FlowDef;

async function validate() {
  busy.value = 'validate';
  try {
    issues.value = await props.api.validate(payload());
    checked.value = true;
    // 只有错误阻止发布；警告只提示
    return issues.value.every((i) => i.level === 'WARNING');
  } finally {
    busy.value = '';
  }
}
async function save() {
  busy.value = 'save';
  try {
    const r = await props.api.saveDraft(payload());
    status.value = r.status;
    version.value = r.version;
    ElMessage.success(`已保存草稿 v${r.version}`);
    await loadHistory();
  } finally {
    busy.value = '';
  }
}
async function publish() {
  if (!(await validate())) {
    ElMessage.error(`有 ${errorCount.value} 个错误，不能发布；点击问题定位修改`);
    return;
  }
  busy.value = 'publish';
  try {
    const r = await props.api.publish(payload());
    status.value = r.status;
    version.value = r.version;
    ElMessage.success(`已发布 v${r.version}`);
    await loadHistory();
    emit('published', r);
  } finally {
    busy.value = '';
  }
}

// 表单与字段
const formKey = computed(() => (def.form ? `${def.form.code}@${def.form.version}` : ''));
function setForm(v: string) {
  if (!v) def.form = null;
  else {
    const at = v.lastIndexOf('@');
    def.form = { code: v.substring(0, at), version: Number(v.substring(at + 1)) };
  }
}
const formFields = ref<FormField[]>([]);
watch(
  () => formKey.value,
  async () => {
    formFields.value = def.form ? await props.api.formFields(def.form.code, def.form.version).catch(() => []) : [];
  },
  { immediate: true }
);

// 预览
const previewXml = ref('');
async function refreshPreview() {
  const r = await props.api.preview(payload());
  previewXml.value = r.bpmnXml;
}
watch(tab, (t) => {
  if (t === 'preview') refreshPreview();
});

// 版本差异
const history = ref<{ version: number; status: string }[]>([]);
async function loadHistory() {
  history.value = def.code ? await props.api.history(def.code).catch(() => []) : [];
  if (!diffBase.value) diffBase.value = history.value.find((h) => h.status === 'PUBLISHED')?.version ?? history.value[0]?.version ?? 0;
}
const diffBase = ref(0);
const diffTarget = ref(0);
const diff = ref<{ changes: NodeChange[]; xml: string; keys: string[] } | null>(null);
async function runDiff() {
  const base = (await props.api.get(def.code, diffBase.value)).definition as FlowDef;
  const target = diffTarget.value ? ((await props.api.get(def.code, diffTarget.value)).definition as FlowDef) : payload();
  const xml = (await props.api.preview(target)).bpmnXml;
  diff.value = { changes: diffDefs(base, target), xml, keys: [...allKeys(target)] };
}
loadHistory();

defineExpose({ def, issues, selected, validate, publish, runDiff, walk });
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: var(--ls-space-2);
  margin-bottom: var(--ls-space-2);
}
.toolbar .title {
  font-weight: 600;
  font-size: 15px;
}
.spacer {
  flex: 1;
}
.issues {
  border: 1px solid var(--el-color-danger-light-5);
  background: var(--el-color-danger-light-9);
  border-radius: 6px;
  padding: var(--ls-space-2) var(--ls-space-3);
  margin-bottom: var(--ls-space-2);
}
.issue {
  display: flex;
  gap: var(--ls-space-2);
  align-items: center;
  cursor: pointer;
  padding: var(--ls-space-half) 0;
}
.issue .path {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.config {
  display: grid;
  grid-template-columns: 300px 1fr 340px;
  gap: var(--ls-space-3);
  min-height: 560px;
}
.settings {
  border-right: 1px solid var(--el-border-color-lighter);
  padding-right: var(--ls-space-2);
}
.canvas {
  overflow: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: var(--ls-space-3);
  background: var(--el-fill-color-lighter);
  border-radius: 8px;
  max-height: 720px;
}
.terminal {
  padding: var(--ls-space-2) var(--ls-space-4);
  border-radius: 16px;
  font-size: 12px;
  color: var(--ls-color-on-solid);
}
.terminal.start {
  background: var(--ls-color-success);
}
.terminal.end {
  background: var(--ls-color-text-secondary);
}
.panel {
  border-left: 1px solid var(--el-border-color-lighter);
  padding-left: var(--ls-space-2);
  overflow: auto;
  max-height: 720px;
}
.hint {
  margin-left: var(--ls-space-2);
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.diff-bar {
  display: flex;
  gap: var(--ls-space-2);
  align-items: center;
  margin-bottom: var(--ls-space-2);
}
.legend {
  display: flex;
  gap: var(--ls-space-3);
  font-size: 12px;
  margin-bottom: var(--ls-space-1);
}
.legend span::before {
  content: '';
  display: inline-block;
  width: 10px;
  height: 10px;
  margin-right: var(--ls-space-1);
  border-radius: 2px;
  vertical-align: -1px;
}
.legend .added::before {
  background: var(--ls-color-success);
}
.legend .changed::before {
  background: var(--ls-color-warning);
}
.legend .removed::before {
  background: var(--ls-color-danger);
}
.diff-list {
  margin: 0 0 var(--ls-space-2);
  padding-left: var(--ls-space-5);
  font-size: 13px;
}
.diff-list li.added {
  color: var(--ls-color-success);
}
.diff-list li.changed {
  color: var(--ls-color-warning);
}
.diff-list li.removed {
  color: var(--ls-color-danger);
  text-decoration: line-through;
}
.json {
  max-height: 600px;
  overflow: auto;
  background: var(--el-fill-color-lighter);
  padding: var(--ls-space-2);
  font-size: 12px;
}
.issues-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
:deep(.el-form-item.is-located) {
  border-radius: 6px;
  background: var(--el-color-warning-light-9);
  box-shadow: 0 0 0 2px var(--el-color-warning-light-5);
}
</style>
