<template>
  <div class="ls-designer" data-testid="designer">
    <div class="ls-designer__bar">
      <div class="ls-designer__title">
        <strong>{{ meta.title || meta.formCode }}</strong>
        <el-tag size="small">v{{ meta.version }}</el-tag>
        <el-tag size="small" :type="status === 'PUBLISHED' ? 'success' : 'info'">{{ statusText }}</el-tag>
        <span class="ls-designer__muted">{{ meta.conceptCode }}</span>
      </div>
      <div class="ls-designer__ops">
        <el-button :disabled="!canUndo" data-testid="undo" @click="undo">撤销</el-button>
        <el-button :disabled="!canRedo" data-testid="redo" @click="redo">重做</el-button>
        <el-button :loading="busy" data-testid="save" @click="save">保存草稿</el-button>
        <el-button :loading="busy" data-testid="check" @click="check">校验</el-button>
        <el-button type="primary" :loading="busy" data-testid="publish" @click="publish">发布</el-button>
      </div>
    </div>

    <el-alert v-if="adapterError" type="error" :title="adapterError" :closable="false" class="ls-designer__alert" data-testid="adapter-error" />
    <div v-if="issues.length" class="ls-designer__issues" data-testid="issues">
      <div class="ls-designer__issues-title">校验问题（{{ issues.length }}）</div>
      <div v-for="(i, n) in issues" :key="n" class="ls-designer__issue" :data-testid="'issue-' + n" @click="locate(i.path)">
        <code>{{ i.path }}</code> {{ i.message }} <span class="ls-designer__muted">{{ i.code }}</span>
      </div>
    </div>
    <el-alert v-if="locateHint" type="info" :title="locateHint" class="ls-designer__alert" data-testid="locate-hint" @close="locateHint = ''" />
    <el-alert
      v-if="publishResult"
      :type="publishResult.status === 'APPLIED' ? 'success' : 'warning'"
      :closable="false"
      class="ls-designer__alert"
      data-testid="publish-result"
      :title="
        publishResult.status === 'APPLIED'
          ? `已发布 v${meta.version}`
          : `含破坏性变更（移除 ${publishResult.removedFields.join('、')}），已提交变更集 #${publishResult.changeSetId} 待他人评审`
      "
    />

    <el-tabs v-model="tab" class="ls-designer__tabs">
      <el-tab-pane label="设计" name="design">
        <div class="ls-designer__main">
          <div class="ls-designer__canvas">
            <FcDesigner
              ref="designer"
              :height="height"
              :config="fcConfig"
              @active="onActive"
              @drag="snap"
              @create="snap"
              @copy="snap"
              @delete="onDelete"
              @sort-up="snap"
              @sort-down="snap"
              @paste-rule="snap"
            />
          </div>
          <aside class="ls-designer__panel" data-testid="ext-panel">
            <el-tabs v-model="panel" stretch>
              <el-tab-pane label="字段" name="field">
                <el-form v-if="activeTable" label-position="top" size="small" data-testid="table-panel">
                  <el-form-item label="子表绑定子 Concept">
                    <el-select :model-value="activeTable.field" data-testid="bind-table" @update:model-value="bindTable">
                      <el-option v-for="c in childConcepts" :key="c.key" :label="`${c.displayName}（${c.concept}）`" :value="c.key" />
                    </el-select>
                  </el-form-item>
                  <div class="ls-designer__muted">从左侧拖入控件作为子表列，再选中各列绑定子 Concept 字段</div>
                </el-form>
                <div v-else-if="!active?.field" class="ls-designer__muted">在画布中选择一个字段</div>
                <el-form v-else label-position="top" size="small" data-testid="field-panel">
                  <el-form-item label="绑定 Concept 字段">
                    <el-select :model-value="active.field" filterable data-testid="bind-field" @update:model-value="bindField">
                      <el-option v-for="f in bindable" :key="f.code" :label="`${f.label}（${f.code}）`" :value="f.code" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="控件">
                    <el-select v-model="ext.widget" data-testid="ext-widget" @change="touch">
                      <el-option v-for="w in WIDGETS" :key="w" :label="w" :value="w" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="只读 / 隐藏">
                    <el-checkbox v-model="ext.readonly" @change="touch">只读</el-checkbox>
                    <el-checkbox v-model="ext.hidden" @change="touch">隐藏</el-checkbox>
                  </el-form-item>
                  <el-form-item label="长度 / 范围">
                    <div class="ls-designer__grid">
                      <el-input-number v-model="ext.minLength" :min="0" placeholder="最短" :controls="false" @change="touch" />
                      <el-input-number v-model="ext.maxLength" :min="1" placeholder="最长" :controls="false" @change="touch" />
                      <el-input-number v-model="ext.min" placeholder="最小值" :controls="false" @change="touch" />
                      <el-input-number v-model="ext.max" placeholder="最大值" :controls="false" @change="touch" />
                    </div>
                  </el-form-item>
                  <el-form-item label="正则 / 提示">
                    <el-input v-model="ext.pattern" placeholder="^L-[0-9]+$" data-testid="ext-pattern" @change="touch" />
                    <el-input v-model="ext.patternMessage" placeholder="格式提示" @change="touch" />
                  </el-form-item>
                  <el-form-item label="字典类型"><el-input v-model="ext.dictType" placeholder="sys_yes_no" @change="touch" /></el-form-item>
                  <el-form-item v-if="isRef" label="引用 Concept / 显示字段 / 过滤">
                    <el-input v-model="refConcept" placeholder="acme.asset.Equipment" data-testid="ext-ref-concept" @change="touch" />
                    <el-input v-model="refLabel" placeholder="name" @change="touch" />
                    <el-input
                      v-model="refFilter"
                      type="textarea"
                      :rows="2"
                      placeholder='[{"field":"orgId","op":"eq","value":"$context.orgId"}]'
                      data-testid="ext-ref-filter"
                      @change="touch"
                    />
                  </el-form-item>
                  <el-form-item v-if="ext.widget === 'computed' || ext.widget === 'expression'" label="计算表达式（受限 CEL）" :error="exprError">
                    <el-input v-model="computedExpr" type="textarea" :rows="2" data-testid="ext-computed" @change="touch" />
                  </el-form-item>
                  <el-form-item v-if="ext.widget === 'registered'" label="注册组件">
                    <el-select v-model="componentRef" placeholder="从组件注册表选择" @change="touch">
                      <el-option
                        v-for="c in components"
                        :key="c.code + '@' + c.version"
                        :label="`${c.displayName}（${c.code}@${c.version}）`"
                        :value="c.code + '@' + c.version"
                      />
                    </el-select>
                  </el-form-item>
                </el-form>
              </el-tab-pane>
              <el-tab-pane label="规则" name="rules">
                <div v-for="(r, i) in meta.rules" :key="i" class="ls-designer__card" :data-testid="'rule-' + i">
                  <el-select v-model="r.level" size="small" style="width: 80px" @change="touch"
                    ><el-option v-for="l in ['L0', 'L1', 'L2']" :key="l" :value="l"
                  /></el-select>
                  <el-input v-model="r.when" size="small" placeholder="条件（可选）" @change="touch" />
                  <el-input v-model="r.assert" size="small" placeholder="断言" @change="touch" />
                  <el-input v-model="r.field" size="small" placeholder="提示字段" @change="touch" />
                  <el-input v-model="r.message" size="small" placeholder="提示信息" @change="touch" />
                  <div v-if="ruleError(r)" class="ls-designer__err">{{ ruleError(r) }}</div>
                  <el-button
                    link
                    type="danger"
                    size="small"
                    @click="
                      meta.rules!.splice(i, 1);
                      touch();
                    "
                    >删除</el-button
                  >
                </div>
                <el-button
                  size="small"
                  data-testid="add-rule"
                  @click="
                    (meta.rules ??= []).push({ level: 'L1', assert: '', message: '' });
                    touch();
                  "
                  >添加规则</el-button
                >
              </el-tab-pane>
              <el-tab-pane label="动作" name="actions">
                <div v-for="(a, i) in meta.actions" :key="i" class="ls-designer__card" :data-testid="'action-' + i">
                  <el-input v-model="a.label" size="small" placeholder="按钮名称" @change="touch" />
                  <el-select v-model="a.operation" size="small" filterable placeholder="绑定已发布 Operation" @change="touch">
                    <el-option v-for="o in operations" :key="o.operation" :label="`${o.name}（${o.operation}）`" :value="o.operation" />
                  </el-select>
                  <el-input v-model="a.confirm" size="small" placeholder="确认提示（可选）" @change="touch" />
                  <el-button
                    link
                    type="danger"
                    size="small"
                    @click="
                      meta.actions!.splice(i, 1);
                      touch();
                    "
                    >删除</el-button
                  >
                </div>
                <el-button size="small" data-testid="add-action" @click="addAction">添加动作</el-button>
              </el-tab-pane>
              <el-tab-pane label="节点策略" name="policies">
                <div v-for="(p, node) in meta.nodePolicies" :key="node" class="ls-designer__card" :data-testid="'policy-' + node">
                  <strong>{{ node }}</strong>
                  <template v-for="kind in POLICY_KINDS" :key="kind">
                    <el-select v-model="p[kind]" multiple size="small" filterable allow-create :placeholder="kind" @change="touch">
                      <el-option value="*" label="全部（*）" />
                      <el-option v-for="k in fieldKeys" :key="k" :value="k" />
                    </el-select>
                  </template>
                  <el-button
                    link
                    type="danger"
                    size="small"
                    @click="
                      delete meta.nodePolicies![node];
                      touch();
                    "
                    >删除节点</el-button
                  >
                </div>
                <el-input v-model="newNode" size="small" placeholder="流程节点键，如 deptAudit" data-testid="new-node"
                  ><template #append><el-button data-testid="add-node" @click="addNode">添加</el-button></template></el-input
                >
              </el-tab-pane>
              <el-tab-pane label="表单" name="form">
                <el-form label-position="top" size="small">
                  <el-form-item label="标题"><el-input v-model="meta.title" @change="touch" /></el-form-item>
                  <el-form-item label="旧草稿迁移策略">
                    <el-select v-model="migration" @change="touch">
                      <el-option value="PROMPT" label="提示用户选择" /><el-option value="KEEP" label="保持原版本" /><el-option
                        value="UPGRADE"
                        label="切换到新版本"
                      />
                    </el-select>
                  </el-form-item>
                </el-form>
              </el-tab-pane>
            </el-tabs>
          </aside>
        </div>
      </el-tab-pane>

      <el-tab-pane label="Schema" name="schema">
        <div class="ls-designer__schema">
          <div>
            <div class="ls-designer__issues-title">LS FormSchema（可手工编辑，发布时同样校验）</div>
            <el-input v-model="schemaText" type="textarea" :rows="28" class="ls-designer__code" data-testid="schema-text" />
            <el-button size="small" data-testid="apply-schema" @click="applySchemaText">应用到设计器</el-button>
            <span v-if="schemaError" class="ls-designer__err">{{ schemaError }}</span>
          </div>
          <div data-testid="schema-diff">
            <div class="ls-designer__issues-title">与已发布版本的差异（+{{ diffStat.add }} / -{{ diffStat.del }}）</div>
            <pre
              class="ls-designer__diff"
            ><span v-for="(l, i) in diff" :key="i" :class="'d' + (l.op === '+' ? 'a' : l.op === '-' ? 'd' : 'n')">{{ l.op }} {{ l.text }}
</span></pre>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="预览" name="preview">
        <el-radio-group v-model="device" size="small" class="ls-designer__alert" data-testid="preview-device">
          <el-radio-button value="desktop">桌面</el-radio-button><el-radio-button value="mobile">移动</el-radio-button>
          <el-radio-button value="detail">只读详情</el-radio-button><el-radio-button value="print">打印</el-radio-button>
        </el-radio-group>
        <div v-if="tab === 'preview'" class="ls-designer__preview" data-testid="preview">
          <SchemaForm
            v-if="device === 'desktop'"
            v-model="previewModel"
            :schema="current"
            :api="rendererApi"
            :context="{ orgId: previewOrg }"
            :org-id="previewOrg"
          />
          <SchemaMobile
            v-else-if="device === 'mobile'"
            v-model="previewModel"
            :schema="mobileSchema"
            :form="current"
            :api="rendererApi"
            :org-id="previewOrg"
          />
          <SchemaDetail v-else-if="device === 'detail'" :schema="current" :record="previewModel" :api="rendererApi" />
          <SchemaPrint v-else :schema="printSchema" :record="previewModel" :labels="labels" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="权限模拟" name="simulate">
        <div class="ls-designer__sim">
          <el-select v-model="simNode" clearable placeholder="流程节点（不选为录入）" size="small" data-testid="sim-node">
            <el-option v-for="(_, n) in meta.nodePolicies" :key="n" :value="n" :label="String(n)" />
          </el-select>
          <el-checkbox-group v-model="simPerms" size="small">
            <el-checkbox v-for="p in fieldPermissions" :key="p" :value="p">{{ p }}</el-checkbox>
          </el-checkbox-group>
        </div>
        <div v-if="tab === 'simulate'" data-testid="simulate">
          <SchemaForm v-model="previewModel" :schema="simulated" :node="simNode || undefined" :api="rendererApi" :show-actions="false" />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { FcDesigner } from '@/vendor/form-create';
import SchemaForm from '@/components/SchemaForm/index.vue';
import SchemaMobile from '@/components/SchemaMobile/index.vue';
import SchemaDetail from '@/components/SchemaDetail/index.vue';
import SchemaPrint from '@/components/SchemaPrint/index.vue';
import type { FormAction, FormSchema, MobileSchema, NodePolicy, PrintSchema, ViewIssue } from '@/types/lsView';
import { WIDGETS } from '@/types/lsView';
import type { CheckResult, PublishResult, RendererApi } from '@/api/lsmom/form';
import { FcAdapterError, fromFcRule, toFcRule, type FcRule } from '@/utils/lsform/fcAdapter';
import { diffLines } from '@/utils/lsform/diffLines';
import { History } from '@/utils/lsform/history';
import { compileRule, CelRuleError } from '@/utils/cel';
import { lsDragRules, LS_MENUS } from './lsDragRules';
import type { ConceptFieldInfo, DesignerApi } from './types';

const props = withDefaults(
  defineProps<{
    schema: FormSchema;
    published?: FormSchema | null;
    status?: string;
    conceptFields?: ConceptFieldInfo[];
    /** 可作为子表的子 Concept（键与台账接口一致） */
    childConcepts?: { key: string; concept: string; displayName: string }[];
    operations?: { operation: string; name: string }[];
    components?: { code: string; version: string; displayName: string }[];
    api: DesignerApi;
    rendererApi: RendererApi;
    height?: string;
    /** 轮询间隔（毫秒）：捕获属性面板中的修改，记入撤销历史 */
    pollMs?: number;
  }>(),
  {
    status: 'DRAFT',
    conceptFields: () => [],
    childConcepts: () => [],
    operations: () => [],
    components: () => [],
    height: 'calc(100vh - 260px)',
    pollMs: 600
  }
);
const emit = defineEmits<{ (e: 'published', r: PublishResult): void; (e: 'change', s: FormSchema): void }>();

const POLICY_KINDS = ['readonly', 'editable', 'hidden', 'required'] as const;
const designer = ref<any>();
const tab = ref('design');
const panel = ref('field');
const busy = ref(false);
const issues = ref<ViewIssue[]>([]);
const adapterError = ref('');
const publishResult = ref<PublishResult | null>(null);
const status = ref(props.status);
const device = ref<'desktop' | 'mobile' | 'detail' | 'print'>('desktop');
const previewModel = ref<Record<string, any>>({});
const previewOrg = ref<number | undefined>(undefined);
const simNode = ref<string>('');
const simPerms = ref<string[]>([]);
const newNode = ref('');
const schemaText = ref('');
const schemaError = ref('');

/** 表单级属性（编码、版本、规则、动作、节点策略……）由工作区持有；布局与字段在设计器内。 */
const meta = reactive<FormSchema>(JSON.parse(JSON.stringify({ ...props.schema, layout: [], fields: [], subTables: undefined })));
const current = ref<FormSchema>(JSON.parse(JSON.stringify(props.schema)));
const history = new History<FormSchema>(100);
const historyTick = ref(0);
const canUndo = computed(() => (historyTick.value, history.canUndo));
const canRedo = computed(() => (historyTick.value, history.canRedo));
const statusText = computed(() => ({ DRAFT: '草稿', PUBLISHED: '已发布', RETIRED: '已退役' })[status.value] ?? status.value);

const fcConfig = computed(() => ({
  showSaveBtn: false,
  showPreviewBtn: false,
  showEventForm: false,
  showStyleForm: false,
  showValidateForm: false,
  showJsonPreview: false,
  showCustomProps: false,
  showAi: false,
  showLanguage: false,
  showInputData: false,
  exitConfirm: false,
  hiddenMenu: ['main'],
  hiddenItem: ['elAlert', 'elButton', 'fcTitle', 'elTag', 'html', 'elImage', 'subForm', 'group', 'space', 'fcTable'],
  fieldReadonly: false,
  fieldList: props.conceptFields.filter((f) => !f.table).map((f) => ({ field: f.code, label: f.label }))
}));

// ---------- 设计器 ⇄ LS ----------

function readDesigner(): FormSchema | null {
  if (!designer.value) return null;
  try {
    const s = fromFcRule(designer.value.getRule(), { form: designer.value.getOption()?.form, lsMeta: meta as any });
    adapterError.value = '';
    return s;
  } catch (e) {
    adapterError.value = e instanceof FcAdapterError ? e.message : String(e);
    return null;
  }
}

/** 载入期间设计器内部异步刷新，暂停读取，避免把旧内容记入历史。 */
let loading = 0;

function load(s: FormSchema) {
  loading++;
  const { rule, options } = toFcRule(s);
  Object.assign(meta, JSON.parse(JSON.stringify({ ...s, layout: [], fields: [], subTables: undefined })));
  designer.value.setRule(rule);
  const { lsMeta: _meta, ...designerOptions } = options;
  void _meta;
  designer.value.setOption(designerOptions);
  current.value = JSON.parse(JSON.stringify(s));
  active.value = null;
  settle().then(() => loading--);
}

async function settle() {
  for (let i = 0; i < 3; i++) await nextTick();
  await new Promise((r) => setTimeout(r, 0));
}

/** 读取当前设计并记入历史。 */
function snap() {
  nextTick(() => {
    if (loading) return;
    const s = readDesigner();
    if (!s) return;
    current.value = s;
    if (history.push(s)) {
      historyTick.value++;
      emit('change', s);
    }
  });
}
const touch = () => snap();

function undo() {
  const s = history.undo();
  if (s) {
    load(s);
    historyTick.value++;
  }
}

function redo() {
  const s = history.redo();
  if (s) {
    load(s);
    historyTick.value++;
  }
}

function onKey(e: KeyboardEvent) {
  const t = e.target as HTMLElement;
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') {
    e.preventDefault();
    if (e.shiftKey) redo();
    else undo();
  }
}

let timer: number | undefined;
onMounted(async () => {
  await nextTick();
  for (const m of LS_MENUS) designer.value.addMenu({ name: m.name, title: m.title, list: [], before: true });
  designer.value.addComponent(lsDragRules());
  load(props.schema);
  history.push(current.value);
  historyTick.value++;
  window.addEventListener('keydown', onKey);
  if (props.pollMs > 0) timer = window.setInterval(snap, props.pollMs);
});
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey);
  if (timer) window.clearInterval(timer);
});

// ---------- 扩展面板：当前字段的 LS 属性 ----------

const active = ref<FcRule | null>(null);
/** 选中字段的 lsExt（选中时已确保存在）。 */
const ext = computed<Record<string, any>>(() => active.value?.lsExt ?? {});
const activeTable = ref<FcRule | null>(null);
/** 选中控件所在子表的键（子表列只能绑定该子 Concept 的字段）；不在子表内为 undefined。 */
const activeIn = ref<string | undefined>();
function tableOf(rule: any): string | undefined {
  let ctx = rule?.__fc__?.parent;
  for (let i = 0; ctx && i < 16; i++, ctx = ctx.parent) {
    const r = ctx.rule ?? ctx;
    if (r?.type === 'tableForm' || r?._fc_drag_tag === 'tableForm') return r.field ?? '';
  }
  return undefined;
}
function onActive(rule: FcRule) {
  activeTable.value = rule?.type === 'tableForm' ? rule : null;
  active.value = rule && rule.field !== undefined && rule.type !== 'tableForm' ? rule : null;
  if (active.value) active.value.lsExt ??= { widget: 'input' };
  activeIn.value = active.value ? tableOf(active.value) : undefined;
  if (active.value || activeTable.value) panel.value = 'field';
  snap();
}
/** 子表绑定子 Concept：键与台账接口一致，列再绑定子 Concept 的字段。 */
function bindTable(key: string) {
  const c = props.childConcepts.find((x) => x.key === key);
  if (!activeTable.value || !c) return;
  activeTable.value.field = c.key;
  activeTable.value.title = c.displayName;
  activeTable.value.lsExt = { ...(activeTable.value.lsExt ?? {}), node: 'subTable', conceptCode: c.concept };
  touch();
}
function onDelete() {
  active.value = null;
  snap();
}

const bindable = computed(() => props.conceptFields.filter((f) => (activeIn.value === undefined ? !f.table : f.table === activeIn.value)));
const fieldKeys = computed(() => {
  const keys = current.value.fields.map((f) => f.key);
  for (const t of current.value.subTables ?? []) {
    keys.push(t.key);
    t.fields.forEach((f) => keys.push(`${t.key}.${f.key}`));
  }
  return keys;
});

const WIDGET_BY_TYPE: Record<string, string> = {
  TEXT: 'textarea',
  INTEGER: 'number',
  DECIMAL: 'number',
  BOOLEAN: 'switch',
  DATE: 'date',
  DATETIME: 'datetime',
  ENUM: 'select',
  REF: 'conceptRef',
  FILE: 'attachment'
};
function bindField(code: string) {
  const f = bindable.value.find((x) => x.code === code);
  if (!active.value || !f) return;
  active.value.field = code;
  active.value.title = f.label;
  if (f.refConcept) ext.value.ref = { ...(ext.value.ref ?? {}), concept: f.refConcept };
  if (f.dictType) ext.value.dictType = f.dictType;
  if (!ext.value.widget || ext.value.widget === 'input') ext.value.widget = WIDGET_BY_TYPE[f.type] ?? 'input';
  touch();
}

const isRef = computed(() => /Ref$/.test(ext.value.widget ?? '') || ext.value.widget === 'remoteSelect');
const refConcept = computed({
  get: () => ext.value.ref?.concept ?? '',
  set: (v: string) => (ext.value.ref = { ...(ext.value.ref ?? {}), concept: v })
});
const refLabel = computed({
  get: () => ext.value.ref?.labelField ?? '',
  set: (v: string) => (ext.value.ref = { ...(ext.value.ref ?? {}), labelField: v || undefined })
});
const refFilter = computed({
  get: () => (ext.value.ref?.filter ? JSON.stringify(ext.value.ref.filter) : ''),
  set: (v: string) => {
    try {
      ext.value.ref = { ...(ext.value.ref ?? {}), filter: v.trim() ? JSON.parse(v) : undefined };
    } catch {
      ElMessage.error('过滤条件不是合法 JSON');
    }
  }
});
const computedExpr = computed({ get: () => ext.value.computed?.expression ?? '', set: (v: string) => (ext.value.computed = { expression: v }) });
const exprError = computed(() => (computedExpr.value ? celError(computedExpr.value) : ''));
const componentRef = computed({
  get: () => (ext.value.component ? `${ext.value.component.code}@${ext.value.component.version}` : ''),
  set: (v: string) => {
    const [code, version] = v.split('@');
    ext.value.component = { code, version };
  }
});

function celError(src: string) {
  try {
    compileRule(src);
    return '';
  } catch (e) {
    return e instanceof CelRuleError ? e.message : String(e);
  }
}
const ruleError = (r: { when?: string; assert: string }) => (r.assert ? celError(r.assert) : '') || (r.when ? celError(r.when) : '');

function addAction() {
  (meta.actions ??= []).push({
    key: 'action' + ((meta.actions?.length ?? 0) + 1),
    label: '提交',
    operation: props.operations[0]?.operation ?? '',
    style: 'primary'
  } as FormAction);
  touch();
}
function addNode() {
  const k = newNode.value.trim();
  if (!k) return;
  (meta.nodePolicies ??= {})[k] = { readonly: [], editable: [] } as NodePolicy;
  newNode.value = '';
  touch();
}
const migration = computed({
  get: () => meta.migration?.strategy ?? 'PROMPT',
  set: (v: string) => (meta.migration = { ...(meta.migration ?? {}), strategy: v as any })
});

// ---------- Schema 区与差异 ----------

watch(
  () => [tab.value, current.value],
  () => {
    if (tab.value === 'schema' && !schemaError.value) schemaText.value = JSON.stringify(current.value, null, 2);
  },
  { deep: true }
);

function applySchemaText() {
  try {
    const s = JSON.parse(schemaText.value) as FormSchema;
    load(s);
    schemaError.value = '';
    history.push(s);
    historyTick.value++;
  } catch (e) {
    schemaError.value = 'JSON 无法解析：' + (e as Error).message;
  }
}

const diff = computed(() =>
  tab.value === 'schema' ? diffLines(props.published ? JSON.stringify(props.published, null, 2) : '', JSON.stringify(current.value, null, 2)) : []
);
const diffStat = computed(() => ({ add: diff.value.filter((l) => l.op === '+').length, del: diff.value.filter((l) => l.op === '-').length }));

// ---------- 预览与权限模拟 ----------

const labels = computed(() => Object.fromEntries(current.value.fields.map((f) => [f.key, f.label ?? f.key])));
const mobileSchema = computed<MobileSchema>(() => ({
  viewType: 'MOBILE',
  formCode: current.value.formCode + 'Mobile',
  version: current.value.version,
  conceptCode: current.value.conceptCode,
  fields: current.value.fields.filter((f) => !f.hidden).map((f) => f.key),
  offlineDraft: true,
  actions: current.value.actions
}));
const printSchema = computed<PrintSchema>(() => ({
  viewType: 'PRINT',
  formCode: current.value.formCode + 'Print',
  version: current.value.version,
  conceptCode: current.value.conceptCode,
  templateVersion: '1.0.0',
  header: { title: current.value.title },
  sections: [
    { fields: current.value.fields.filter((f) => !f.hidden).map((f) => f.key), columns: 2 },
    ...(current.value.subTables ?? []).map((t) => ({ title: t.label, fields: [], subTable: t.conceptCode }))
  ],
  signatures: [{ label: '填报人' }, { label: '审核人' }]
}));
const fieldPermissions = computed(() => [
  ...new Set(props.conceptFields.flatMap((f) => [f.readPermission, f.writePermission]).filter(Boolean) as string[])
]);
/** 按所选角色权限模拟：缺读权限的字段隐藏，缺写权限的字段只读；节点策略由渲染器按同一算法计算。 */
const simulated = computed<FormSchema>(() => {
  const perms = new Set(simPerms.value);
  const byCode = new Map(props.conceptFields.map((f) => [f.code, f]));
  return {
    ...current.value,
    fields: current.value.fields.map((f) => {
      const c = byCode.get(f.key);
      if (c?.readPermission && !perms.has(c.readPermission)) return { ...f, hidden: true };
      if (c?.writePermission && !perms.has(c.writePermission)) return { ...f, readonly: true };
      return f;
    })
  };
});

// ---------- 保存、校验、发布 ----------

const locateHint = ref('');

/** 点击问题定位：切到设计画布并选中该字段；控件本身无效（设计器无法选中）时提示到 Schema 区修正。 */
function locate(path: string) {
  const m = /^\/(fields|subTables)\/(\d+)/.exec(path);
  const key = m?.[1] === 'fields' ? current.value.fields[Number(m[2])]?.key : m ? current.value.subTables?.[Number(m[2])]?.key : undefined;
  tab.value = 'design';
  active.value = null;
  if (!key) {
    locateHint.value = `问题位于 ${path}，请在 Schema 区修正`;
    return;
  }
  try {
    designer.value?.triggerActive(key);
  } catch {
    /* 无效控件没有拖拽配置，设计器无法选中 */
  }
  nextTick(() => {
    locateHint.value = active.value ? `已定位到字段 ${key}` : `已定位到字段 ${key}（${path}）：该处内容设计器无法表示，请在 Schema 区修正`;
  });
}

function serverIssues(e: any): ViewIssue[] | null {
  const d = e?.response?.data?.data ?? e?.data ?? e?.details;
  return Array.isArray(d?.issues) ? d.issues : null;
}

async function save(): Promise<boolean> {
  const s = loading ? current.value : readDesigner();
  if (!s) return false;
  busy.value = true;
  try {
    const r = await props.api.saveDraft(s);
    issues.value = r.issues;
    current.value = s;
    ElMessage.success(r.issues.length ? `草稿已保存，还有 ${r.issues.length} 个问题` : '草稿已保存');
    return true;
  } finally {
    busy.value = false;
  }
}

async function check() {
  if (!(await save())) return;
  busy.value = true;
  try {
    issues.value = (await props.api.check(meta.formCode, meta.version)).issues;
  } finally {
    busy.value = false;
  }
}

async function publish() {
  if (!(await save())) return;
  busy.value = true;
  publishResult.value = null;
  try {
    const r = await props.api.publish(meta.formCode, meta.version);
    publishResult.value = r;
    issues.value = [];
    if (r.status === 'APPLIED') status.value = 'PUBLISHED';
    emit('published', r);
  } catch (e) {
    const list = serverIssues(e);
    if (list) issues.value = list;
    else throw e;
  } finally {
    busy.value = false;
  }
}

defineExpose({ designer, current, history, undo, redo, snap, load, save, publish, issues });
</script>

<style scoped>
.ls-designer__bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.ls-designer__title {
  display: flex;
  gap: 8px;
  align-items: center;
}
.ls-designer__muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.ls-designer__alert {
  margin-bottom: 8px;
}
.ls-designer__issues {
  border: 1px solid var(--el-color-danger-light-5);
  background: var(--el-color-danger-light-9);
  border-radius: 4px;
  padding: 8px 12px;
  margin-bottom: 8px;
  max-height: 160px;
  overflow: auto;
}
.ls-designer__issues-title {
  font-weight: 600;
  margin-bottom: 4px;
}
.ls-designer__issue {
  cursor: pointer;
  line-height: 1.6;
}
.ls-designer__main {
  display: flex;
  gap: 8px;
}
.ls-designer__canvas {
  flex: 1;
  min-width: 0;
}
.ls-designer__panel {
  width: 320px;
  flex-shrink: 0;
  overflow: auto;
}
.ls-designer__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
}
.ls-designer__card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 6px;
  margin-bottom: 6px;
  display: grid;
  gap: 4px;
}
.ls-designer__err {
  color: var(--el-color-danger);
  font-size: 12px;
}
.ls-designer__schema {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.ls-designer__code :deep(textarea) {
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
.ls-designer__diff {
  font-size: 12px;
  max-height: 640px;
  overflow: auto;
  background: var(--el-fill-color-light);
  padding: 8px;
}
.ls-designer__diff .da {
  color: var(--el-color-success);
}
.ls-designer__diff .dd {
  color: var(--el-color-danger);
}
.ls-designer__diff .dn {
  color: var(--el-text-color-secondary);
}
.ls-designer__preview {
  max-width: 1000px;
}
.ls-designer__sim {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 8px;
}
</style>
