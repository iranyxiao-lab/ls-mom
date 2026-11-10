<template>
  <div class="ledger" data-testid="ledger-workspace">
    <SchemaTable
      ref="table"
      :concept="concept"
      :api="crud"
      @create="openCreate"
      @edit="(row) => openEdit(row.id)"
      @row-click="(row) => openDetail(row.id)"
      @import="openImport"
      @exported="watchExport"
      @action-done="onDone"
    />

    <div v-if="exportJob" class="ledger__export" data-testid="export-job">
      导出任务 #{{ exportJob.id }}：{{ EXPORT_STATUS[exportJob.status] ?? exportJob.status }}
      <template v-if="exportJob.status === 'DONE'">
        （{{ exportJob.rowCount ?? 0 }} 行）
        <el-button link type="primary" data-testid="export-download" @click="downloadExport">下载</el-button>
      </template>
      <span v-if="exportJob.error" class="ledger__error">{{ exportJob.error }}</span>
    </div>

    <!-- 录入 / 编辑：Concept 默认表单（编辑时按冻结版本与迁移策略） -->
    <el-drawer v-model="editor.visible" :title="editor.id ? '编辑' : '新增'" size="60%" append-to-body data-testid="ledger-editor">
      <el-alert v-if="editor.hint" type="info" :closable="false" :title="editor.hint" class="ledger__hint" data-testid="edit-version-hint" />
      <SchemaForm
        v-if="editor.schema"
        ref="formRef"
        v-model="editor.model"
        :schema="editor.schema"
        :api="forms"
        :show-actions="false"
        :draft-key="`${concept}:${editor.id ?? 'new'}`"
        hotkeys
        autofocus
        @save="save()"
        @submit="save(submitAction)"
      />
      <el-empty v-else-if="!editor.loading" description="该台账没有已发布的表单" />
      <template #footer>
        <el-button @click="editor.visible = false">取消</el-button>
        <el-button :loading="busy" :disabled="!editor.schema" data-testid="btn-save-draft" @click="save()">保存草稿</el-button>
        <el-button
          v-for="a in editor.schema?.actions ?? []"
          :key="a.key"
          type="primary"
          :loading="busy"
          :data-testid="'btn-form-' + a.key"
          @click="save(a)"
        >
          {{ a.label }}
        </el-button>
      </template>
    </el-drawer>

    <!-- 详情：按记录冻结的表单版本只读渲染 -->
    <el-drawer v-model="detail.visible" title="详情" size="60%" append-to-body data-testid="ledger-detail">
      <div v-if="detail.record" class="ledger__meta">
        <el-tag size="small" data-testid="detail-status">{{ detail.record.status }}</el-tag>
        <span data-testid="detail-form-version">表单 v{{ detail.view?.version ?? '—' }}</span>
      </div>
      <SchemaDetail
        v-if="detail.view && detail.record"
        :schema="detail.view.schema"
        :record="detail.record"
        :frozen-version="detail.view.version"
        :latest-version="detail.latest"
        :api="forms"
      />
    </el-drawer>

    <!-- 导入：下载模板 → 上传预检 → 确认入库 -->
    <el-dialog v-model="imp.visible" title="导入" width="560px" append-to-body data-testid="ledger-import">
      <div class="ledger__import">
        <el-button link type="primary" data-testid="import-template" @click="downloadTemplate">下载导入模板</el-button>
        <input :key="imp.inputKey" type="file" accept=".xlsx" data-testid="import-file" @change="precheck" />
      </div>
      <template v-if="imp.report">
        <p data-testid="import-summary">共 {{ imp.report.total }} 行：可导入 {{ imp.report.valid }} 行，错误 {{ imp.report.invalid }} 行</p>
        <el-table v-if="imp.report.errors.length" :data="imp.report.errors" size="small" max-height="240" data-testid="import-errors">
          <el-table-column prop="row" label="行" width="60" />
          <el-table-column prop="field" label="字段" width="120" />
          <el-table-column prop="message" label="问题" />
        </el-table>
      </template>
      <template #footer>
        <el-button @click="imp.visible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!imp.report || imp.report.invalid > 0 || !imp.report.valid"
          data-testid="import-confirm"
          @click="confirmImport"
        >
          确认导入
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import SchemaTable from '@/components/SchemaTable/index.vue';
import SchemaForm from '@/components/SchemaForm/index.vue';
import SchemaDetail from '@/components/SchemaDetail/index.vue';
import type { CrudApi } from '@/api/lsmom/crud';
import type { FormApi } from '@/api/lsmom/form';
import type { ExportJob, ImportReport } from '@/api/lsmom/types';
import type { FormAction, FormSchema, ViewVersion } from '@/types/lsView';
import { EXPORT_STATUS, idempotencyKey, saveBlob } from './ledgerModel';
import { useUnsavedGuard } from '@/utils/pageState';

/**
 * 通用台账工作区：列表（筛选、导出、行动作）＋ 默认表单录入编辑 ＋ 冻结版本只读详情 ＋ 导入预检。
 * 只用 Concept、表单、列表视图与 Operation 配置驱动，不需要为单个台账写页面。
 */
const props = defineProps<{ concept: string; crud: CrudApi; forms: FormApi }>();

const table = ref<InstanceType<typeof SchemaTable>>();
const formRef = ref<InstanceType<typeof SchemaForm>>();
const busy = ref(false);
const editor = reactive({
  visible: false,
  loading: false,
  id: undefined as number | undefined,
  version: 0,
  schema: null as FormSchema | null,
  model: {} as Record<string, any>,
  hint: ''
});
const detail = reactive({ visible: false, record: null as Record<string, any> | null, view: null as ViewVersion<FormSchema> | null, latest: 0 });
const imp = reactive({ visible: false, report: null as ImportReport | null, inputKey: 0 });
const exportJob = ref<ExportJob>();

// 打开编辑后表单内容与打开时不同即视为未保存（关闭页签时提示）
let snapshot = '';
watch(
  () => editor.loading,
  async (loading) => {
    if (loading || !editor.visible) return;
    await nextTick();
    await nextTick();
    snapshot = JSON.stringify(editor.model);
  }
);
useUnsavedGuard(() => editor.visible && !editor.loading && JSON.stringify(editor.model) !== snapshot);

async function openCreate() {
  Object.assign(editor, { visible: true, loading: true, id: undefined, version: 0, schema: null, model: {}, hint: '' });
  try {
    editor.schema = (await props.forms.defaultFor(props.concept))?.schema ?? null;
  } finally {
    editor.loading = false;
  }
}

/** 编辑草稿：记录按旧版本保存时，按表单迁移策略提示切换到最新版本或保持旧版本。 */
async function openEdit(id: number) {
  Object.assign(editor, { visible: true, loading: true, id, schema: null, model: {}, hint: '' });
  try {
    const [rec, latest] = await Promise.all([props.crud.get(props.concept, id), props.forms.defaultFor(props.concept)]);
    editor.version = Number(rec.version);
    editor.model = { ...rec };
    if (!latest) return;
    const frozen = Number(rec.schemaVersion ?? latest.version);
    let use = latest.version;
    if (frozen < latest.version) {
      const r = await props.forms.editVersion(latest.code, frozen);
      use = r.useVersion;
      if (r.prompt) {
        const upgrade = await ElMessageBox.confirm(
          r.note ?? `该记录按表单 v${frozen} 保存，最新为 v${r.latestVersion}，是否切换到新版本编辑？`,
          '表单已更新',
          { confirmButtonText: `切换到 v${r.latestVersion}`, cancelButtonText: `保持 v${frozen}`, distinguishCancelAndClose: true }
        )
          .then(() => true)
          .catch(() => false);
        use = upgrade ? r.latestVersion : frozen;
      }
      editor.hint = use === frozen ? `按表单 v${frozen} 编辑（最新 v${r.latestVersion}）` : `已切换到表单 v${use} 编辑`;
    }
    editor.schema = use === latest.version ? latest.schema : (await props.forms.get<FormSchema>(latest.code, use)).schema;
  } finally {
    editor.loading = false;
  }
}

/** Ctrl/⌘+Enter 执行的表单动作：优先 submit，没有时取第一个动作（都没有则只保存）。 */
const submitAction = computed(() => editor.schema?.actions?.find((a) => a.key === 'submit') ?? editor.schema?.actions?.[0]);

/** 保存草稿；带表单动作时保存后经 Operation 执行（如提交审核）。 */
async function save(action?: FormAction) {
  const form = formRef.value;
  if (!form || !editor.schema) return;
  if (Object.keys(form.validate().errors).length) return;
  busy.value = true;
  try {
    const body = form.payload();
    for (const k of ['id', 'version', 'status', 'schemaVersion', 'createdBy', 'createdAt', 'updatedBy', 'updatedAt']) delete body[k];
    for (const t of editor.schema.subTables ?? []) {
      if (Array.isArray(body[t.key])) body[t.key] = body[t.key].map(({ id, lineNo, parentId, ...line }: Record<string, any>) => line);
    }
    const saved = editor.id
      ? await props.crud.update(props.concept, editor.id, editor.version, body, idempotencyKey())
      : await props.crud.create(props.concept, body, idempotencyKey());
    editor.id = saved.id;
    editor.version = saved.version;
    if (action) {
      await props.crud.execute(action.operation, { targetId: String(saved.id), version: saved.version, input: {} }, idempotencyKey());
      ElMessage.success(`已${action.label}`);
    } else {
      ElMessage.success('已保存草稿');
    }
    snapshot = JSON.stringify(editor.model);
    await form.clearDraft();
    editor.visible = false;
    await table.value?.load();
  } catch (e) {
    // 字段校验失败：错误显示到对应字段与子表单元格，并定位到第一个错误
    if (!form.setServerErrors(e)) throw e;
  } finally {
    busy.value = false;
  }
}

async function openDetail(id: number) {
  Object.assign(detail, { visible: true, record: null, view: null, latest: 0 });
  const [rec, latest] = await Promise.all([props.crud.get(props.concept, id), props.forms.defaultFor(props.concept)]);
  detail.record = rec;
  if (!latest) return;
  detail.latest = latest.version;
  const frozen = Number(rec.schemaVersion ?? latest.version);
  detail.view = frozen === latest.version ? latest : await props.forms.get<FormSchema>(latest.code, frozen);
}

function openImport() {
  imp.visible = true;
  imp.report = null;
}

async function downloadTemplate() {
  saveBlob(await props.crud.importTemplate(props.concept), `${props.concept}-导入模板.xlsx`);
}

async function precheck(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  imp.report = null;
  try {
    imp.report = await props.crud.importPrecheck(props.concept, file);
  } finally {
    input.value = '';
    imp.inputKey++;
  }
}

async function confirmImport() {
  if (!imp.report) return;
  const n = await props.crud.importConfirm(props.concept, imp.report.batchId);
  ElMessage.success(`已导入 ${n} 条`);
  imp.visible = false;
  await table.value?.load();
}

async function watchExport(job: ExportJob) {
  exportJob.value = job;
  for (let i = 0; i < 60 && ['PENDING', 'RUNNING'].includes(exportJob.value.status); i++) {
    await new Promise((r) => setTimeout(r, 500));
    exportJob.value = await props.crud.exportStatus(job.id);
  }
}

async function downloadExport() {
  if (exportJob.value) saveBlob(await props.crud.exportFile(exportJob.value.id), `${props.concept}-${exportJob.value.id}.xlsx`);
}

function onDone(p: { operation: string; ok: number; failed: { id: unknown; message: string }[] }) {
  if (p.failed.length) ElMessage.error(p.failed.map((f) => `#${f.id}：${f.message}`).join('；'));
}

defineExpose({ openCreate, openEdit, openDetail, openImport });
</script>

<style scoped>
.ledger__hint,
.ledger__meta {
  margin-bottom: var(--ls-space-3);
}
.ledger__meta {
  display: flex;
  gap: var(--ls-space-3);
  align-items: center;
  color: var(--el-text-color-secondary);
}
.ledger__export {
  margin-top: var(--ls-space-2);
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.ledger__error {
  color: var(--el-color-danger);
  margin-left: var(--ls-space-2);
}
.ledger__import {
  display: flex;
  gap: var(--ls-space-4);
  align-items: center;
  margin-bottom: var(--ls-space-3);
}
</style>
