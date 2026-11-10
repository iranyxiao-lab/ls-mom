<template>
  <el-dialog
    :model-value="modelValue"
    title="从模板新建应用"
    width="880px"
    top="6vh"
    data-testid="tpl-wizard"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <el-steps :active="stages.indexOf(stage)" finish-status="success" simple class="tw-steps">
      <el-step v-for="st in stages" :key="st" :title="STAGE_LABEL[st]" />
    </el-steps>

    <!-- 1. 选择模板 -->
    <div v-if="stage === 'pick'" class="tw-grid" data-testid="tpl-list">
      <button
        v-for="t in templates"
        :key="t.code"
        type="button"
        class="tw-card"
        :class="{ 'is-on': picked === t.code }"
        :data-testid="`tpl-${t.code}`"
        @click="pick(t.code)"
      >
        <span class="tw-card__cat">{{ t.category }}</span>
        <strong>{{ t.name }}</strong>
        <span class="tw-card__desc">{{ t.description }}</span>
        <span class="tw-card__meta">{{ t.fields }} 个字段{{ t.children ? ` · ${t.children} 张明细表` : '' }} · {{ t.nodes }} 个审批节点</span>
      </button>
      <label class="tw-card tw-card--excel" data-testid="tpl-excel">
        <span class="tw-card__cat">导入</span>
        <strong>从 Excel 生成</strong>
        <span class="tw-card__desc">读取第一个工作表的表头与前 50 行，推断字段类型、字典、设备 / 人员引用与必填，逐项核对后生成</span>
        <span class="tw-card__meta">{{ busy && excelName ? `正在读取 ${excelName}…` : '.xlsx / .xls / .csv' }}</span>
        <input type="file" accept=".xlsx,.xls,.csv" class="tw-file" data-testid="tpl-excel-input" @change="onExcel" />
      </label>
    </div>

    <!-- Excel：逐项核对推断的字段 -->
    <div v-else-if="stage === 'fields' && fragment" data-testid="tpl-fields">
      <div class="tw-row">
        <span>对象名（英文）</span>
        <el-input v-model="fragment.entity" style="width: 200px" data-testid="tpl-entity" />
        <span>显示名</span>
        <el-input v-model="fragment.entityName" style="width: 200px" data-testid="tpl-entity-name" />
        <span class="tw-help"
          >来源：{{ fragment.provenance?.file }} · {{ fragment.provenance?.sheet }}（{{ fragment.provenance?.sampleRows }} 行样例）</span
        >
      </div>
      <el-table :data="fragment.fields" size="small" max-height="430" data-testid="tpl-field-table">
        <el-table-column label="Excel 列" width="120">
          <template #default="{ row }">
            <el-input v-model="row.displayName" size="small" :data-testid="`fld-label-${row.code}`" />
          </template>
        </el-table-column>
        <el-table-column label="编码" width="130">
          <template #default="{ row }">
            <el-input v-model="row.code" size="small" :data-testid="`fld-code-${row.code}`" />
          </template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-select v-model="row.dataType" size="small" :data-testid="`fld-type-${row.code}`" @change="onType(row)">
              <el-option v-for="(l, t) in TYPE_LABEL" :key="t" :label="l" :value="t" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="引用 / 选项" min-width="170">
          <template #default="{ row }">
            <el-select v-if="row.dataType === 'REF'" v-model="row.refConcept" size="small" :data-testid="`fld-ref-${row.code}`">
              <el-option v-for="(l, c) in REF_LABEL" :key="c" :label="l" :value="c" />
            </el-select>
            <el-input
              v-else-if="row.dataType === 'ENUM'"
              :model-value="optionsText(row)"
              size="small"
              placeholder="显示名=编码，逗号分隔"
              :data-testid="`fld-opts-${row.code}`"
              @update:model-value="(v: string) => setOptions(row, v)"
            />
            <span v-else class="tw-help">—</span>
          </template>
        </el-table-column>
        <el-table-column label="必填" width="60" align="center">
          <template #default="{ row }">
            <el-checkbox v-model="row.required" :data-testid="`fld-req-${row.code}`" />
          </template>
        </el-table-column>
        <el-table-column label="推断依据" min-width="200">
          <template #default="{ row }">
            <span class="tw-help" :data-testid="`fld-why-${row.code}`">{{ row.evidence }}</span>
          </template>
        </el-table-column>
        <el-table-column width="50">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" :data-testid="`fld-del-${$index}`" @click="fragment!.fields.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 2. 基本信息与角色 -->
    <el-form v-else-if="stage === 'info' && fragment" ref="formRef" :model="info" :rules="rules" label-width="110px" data-testid="tpl-info">
      <el-form-item label="应用名称" prop="title">
        <el-input v-model="info.title" maxlength="40" data-testid="tpl-title" />
      </el-form-item>
      <el-form-item label="编码" prop="ns">
        <el-input v-model="info.ns" placeholder="如 acme.loan" data-testid="tpl-ns" />
        <div class="tw-help">小写字母、数字和点；同一编码再次生成会作为升级提交</div>
      </el-form-item>
      <el-form-item label="业务负责人" prop="owner">
        <el-input v-model="info.owner" data-testid="tpl-owner" />
      </el-form-item>
      <el-form-item label="需求提出人" prop="requester">
        <el-input v-model="info.requester" data-testid="tpl-requester" />
      </el-form-item>
      <el-form-item label="登记角色">
        <el-select v-model="info.initiatorRoles" multiple filterable placeholder="谁可以新增和提交" style="width: 100%" data-testid="tpl-starters">
          <el-option v-for="r in roles" :key="r.roleKey" :label="r.roleName" :value="r.roleKey" />
        </el-select>
      </el-form-item>
      <el-form-item v-for="n in fragment.flow?.nodes ?? []" :key="n.key" :label="`「${n.name}」办理`">
        <el-select v-model="n.role" filterable style="width: 100%" :data-testid="`tpl-node-${n.key}`">
          <el-option v-for="r in roles" :key="r.roleKey" :label="r.roleName" :value="r.roleKey" />
        </el-select>
        <div class="tw-help">按角色分派，且只分给数据范围覆盖该单据组织的人</div>
      </el-form-item>
    </el-form>

    <!-- 3. 预览生成的文件（可直接修改）与授权 -->
    <div v-else-if="stage === 'preview'" class="tw-preview" data-testid="tpl-preview">
      <ul class="tw-files">
        <li v-for="p in Object.keys(files)" :key="p">
          <button type="button" :class="{ 'is-on': p === file }" :data-testid="`tpl-file-${p}`" @click="file = p">
            {{ p }}<span v-if="edited.has(p)" class="tw-dot" title="已修改">●</span>
          </button>
        </li>
      </ul>
      <div class="tw-editor">
        <el-input
          v-model="files[file]"
          type="textarea"
          :rows="18"
          spellcheck="false"
          class="tw-code"
          data-testid="tpl-file-editor"
          @input="edited.add(file)"
        />
        <div class="tw-grants" data-testid="tpl-grants">
          <el-checkbox v-model="doGrant" data-testid="tpl-grant">同时建按钮权限并授予角色（审批节点角色须先有办理权限，流程才能发布）</el-checkbox>
          <ul v-if="doGrant">
            <li v-for="g in grants" :key="g.perms">
              <code>{{ g.perms }}</code> → {{ g.roles.length ? g.roles.map(roleName).join('、') : '（无角色）' }}
            </li>
          </ul>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button v-if="stage !== 'pick'" data-testid="tpl-back" @click="back">上一步</el-button>
      <el-button
        v-if="stage !== 'preview'"
        type="primary"
        :disabled="stage === 'pick' && !fragment"
        :loading="busy"
        data-testid="tpl-next"
        @click="next"
        >下一步</el-button
      >
      <el-button v-else type="primary" :loading="busy" data-testid="tpl-create" @click="create">生成草稿</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import { templateApi, type DraftBody, type Grant, type IrField, type IrFragment, type TemplateSummary } from '@/api/lsmom/templates';
import { readSheetSample } from '@/utils/excelSample';
import { flowApi } from '@/api/lsmom/flow';
import type { ChangeSet } from '@/api/lsmom/pack';
import { useUserStore } from '@/store/modules/user';

/**
 * 从模板（或 Excel 推断的片段）新建台账应用：选模板 / 上传 Excel（逐项核对推断的字段）→ 名称、编码、负责人、角色 →
 * 预览生成的 Pack 文件（可改）→
 * 授权并提交为 ChangeSet 草稿（之后与手写 Pack 一样走校验、沙箱、回归、越权、升级演练与审批发布）。
 */
const props = defineProps<{ modelValue: boolean; initial?: IrFragment | null }>();
const emit = defineEmits<{ 'update:modelValue': [boolean]; created: [ChangeSet] }>();

type Stage = 'pick' | 'fields' | 'info' | 'preview';
const STAGE_LABEL: Record<Stage, string> = { pick: '选择模板', fields: '核对字段', info: '基本信息', preview: '预览并生成' };
const TYPE_LABEL: Record<string, string> = {
  STRING: '文本',
  TEXT: '长文本',
  INTEGER: '整数',
  DECIMAL: '小数',
  DATE: '日期',
  DATETIME: '日期时间',
  BOOLEAN: '是否',
  ENUM: '选项',
  REF: '引用',
  FILE: '附件'
};
const REF_LABEL: Record<string, string> = { 'ls.asset.Asset': '设备', 'ls.core.Party': '人员', 'ls.core.OrgUnit': '组织' };
const stage = ref<Stage>('pick');
const fromExcel = ref(false);
const excelName = ref('');
const stages = computed<Stage[]>(() => (fromExcel.value ? ['pick', 'fields', 'info', 'preview'] : ['pick', 'info', 'preview']));
function back() {
  stage.value = stages.value[Math.max(0, stages.value.indexOf(stage.value) - 1)];
}
const busy = ref(false);
const templates = ref<TemplateSummary[]>([]);
const roles = ref<{ roleKey: string; roleName: string }[]>([]);
const picked = ref('');
const fragment = ref<IrFragment | null>(null);
const user = useUserStore();
const info = reactive({ title: '', ns: '', owner: user.nickname, requester: user.nickname, initiatorRoles: [] as string[] });
const formRef = ref<FormInstance>();
const rules: FormRules = {
  title: [{ required: true, message: '请填写应用名称', trigger: 'blur' }],
  ns: [
    { required: true, message: '请填写编码', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$/, message: '小写字母、数字和点组成，如 acme.loan', trigger: 'blur' }
  ],
  owner: [{ required: true, message: '请填写业务负责人', trigger: 'blur' }],
  requester: [{ required: true, message: '请填写需求提出人', trigger: 'blur' }]
};
const files = ref<Record<string, string>>({});
const file = ref('pack.yaml');
const edited = reactive(new Set<string>());
const doGrant = ref(true);
const grants = ref<Grant[]>([]);
const roleName = (k: string) => roles.value.find((r) => r.roleKey === k)?.roleName ?? k;

function useFragment(f: IrFragment, code?: string) {
  fragment.value = JSON.parse(JSON.stringify(f));
  info.title = f.name ?? f.entityName ?? '';
  info.ns = `cust.${(code ?? f.entity).toLowerCase().replace(/[^a-z0-9]/g, '')}${Date.now().toString(36).slice(-3)}`;
}

async function pick(code: string) {
  picked.value = code;
  fromExcel.value = false;
  useFragment(await templateApi.get(code), code);
  stage.value = 'info';
}

async function onExcel(e: Event) {
  const input = e.target as HTMLInputElement;
  const f = input.files?.[0];
  input.value = '';
  if (!f) return;
  busy.value = true;
  excelName.value = f.name;
  try {
    let sample;
    try {
      sample = await readSheetSample(f);
    } catch (err: any) {
      ElMessage.error(`读取 Excel 失败：${err?.message ?? err}`);
      return;
    }
    const frag = await templateApi.infer(sample);
    picked.value = '';
    fromExcel.value = true;
    useFragment(frag, 'xl');
    stage.value = 'fields';
  } finally {
    busy.value = false;
  }
}

/** 选项：显示名=编码，逗号分隔 */
const optionsText = (f: IrField) =>
  (f.options ?? []).map((o) => (typeof o === 'string' ? o : o.label && o.label !== o.value ? `${o.label}=${o.value}` : o.value)).join(', ');
function setOptions(f: IrField, text: string) {
  f.options = text
    .split(/[,，]/)
    .map((x) => x.trim())
    .filter(Boolean)
    .map((x) => {
      const [label, value] = x.includes('=') ? x.split('=').map((y) => y.trim()) : [x, x];
      return { label, value };
    });
}
function onType(f: IrField) {
  if (f.dataType === 'REF' && !f.refConcept) f.refConcept = 'ls.asset.Asset';
  if (f.dataType !== 'REF') delete f.refConcept;
  if (f.dataType === 'ENUM' && !f.options?.length) f.options = [{ value: 'V1', label: '选项1' }];
  if (f.dataType !== 'ENUM') delete f.options;
}

const body = (): DraftBody => ({
  fragment: fragment.value!,
  ns: info.ns.trim(),
  title: info.title.trim(),
  owner: info.owner,
  requester: info.requester,
  initiatorRoles: info.initiatorRoles
});

async function next() {
  if (stage.value === 'pick') {
    if (fragment.value) stage.value = stages.value[1];
    return;
  }
  if (stage.value === 'fields') {
    if (!/^[A-Z][A-Za-z0-9]{1,40}$/.test(fragment.value?.entity ?? '')) return void ElMessage.warning('对象名须为英文大驼峰，如 CheckRecord');
    stage.value = 'info';
    return;
  }
  await formRef.value?.validate();
  busy.value = true;
  try {
    const [f, g] = await Promise.all([templateApi.preview(body()), templateApi.grantPlan(body())]);
    files.value = f;
    grants.value = g;
    edited.clear();
    file.value = 'pack.yaml';
    stage.value = 'preview';
  } finally {
    busy.value = false;
  }
}

async function create() {
  busy.value = true;
  try {
    if (doGrant.value) await templateApi.grant(body());
    const cs = await templateApi.draft({ ...body(), files: edited.size ? files.value : undefined });
    if (cs.status === 'REJECTED') {
      ElMessage.error(`校验未通过：${(cs.stages[0]?.detail as any)?.message ?? ''}`);
      return;
    }
    ElMessage.success(`已生成草稿 ChangeSet #${cs.id}：${cs.title}`);
    emit('created', cs);
    emit('update:modelValue', false);
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  const [t, r] = await Promise.all([templateApi.list(), flowApi.roles().catch(() => [])]);
  templates.value = t;
  roles.value = r;
  const starters = ['team_leader', 'operator'].filter((k) => r.some((x) => x.roleKey === k));
  info.initiatorRoles = starters;
  if (props.initial) {
    useFragment(props.initial);
    stage.value = 'info';
  }
});
</script>

<style scoped>
.tw-steps {
  margin-bottom: var(--ls-space-4);
}
.tw-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: var(--ls-space-3);
}
.tw-card {
  display: flex;
  flex-direction: column;
  gap: var(--ls-space-1);
  padding: var(--ls-space-3);
  text-align: left;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  cursor: pointer;
}
.tw-card:hover,
.tw-card.is-on {
  border-color: var(--el-color-primary);
}
.tw-card__cat {
  font-size: 12px;
  color: var(--el-color-primary);
}
.tw-card__desc {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.tw-card__meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.tw-card--excel {
  position: relative;
  border-style: dashed;
}
.tw-file {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}
.tw-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ls-space-2);
  margin-bottom: var(--ls-space-2);
  font-size: 13px;
}
.tw-help {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.tw-preview {
  display: flex;
  gap: var(--ls-space-3);
}
.tw-files {
  width: 230px;
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 480px;
  overflow: auto;
}
.tw-files button {
  width: 100%;
  padding: var(--ls-space-1) var(--ls-space-2);
  border: 0;
  background: none;
  text-align: left;
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
  color: var(--el-text-color-regular);
  cursor: pointer;
  border-radius: 4px;
}
.tw-files button.is-on {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.tw-dot {
  margin-left: var(--ls-space-1);
  color: var(--el-color-warning);
}
.tw-editor {
  flex: 1;
  min-width: 0;
}
.tw-code :deep(textarea) {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
}
.tw-grants {
  margin-top: var(--ls-space-2);
  font-size: 13px;
}
.tw-grants ul {
  margin: var(--ls-space-1) 0 0;
  padding-left: var(--ls-space-4);
  color: var(--el-text-color-regular);
}
.tw-grants code {
  font-size: 12px;
}
</style>
