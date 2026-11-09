<template>
  <div data-testid="nc-board">
    <div class="toolbar">
      <el-radio-group v-model="status" size="small" @change="load">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button v-for="(v, k) in NC_STATUS" :key="k" :value="k" :data-testid="`nc-filter-${k}`">{{ v }}</el-radio-button>
      </el-radio-group>
      <span class="grow" />
      <el-button type="primary" data-testid="btn-new-nc" @click="openNew">登记不符合项</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" size="small" data-testid="nc-table" @row-click="(r: any) => openDetail(r.id)">
      <el-table-column label="问题" min-width="220">
        <template #default="{ row }">
          <span :data-testid="`nc-row-${row.id}`">{{ row.title }}</span>
          <div class="sub">{{ refLabel(row.asset) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="严重程度" width="90">
        <template #default="{ row }"
          ><el-tag size="small" :type="row.severity === 'HIGH' ? 'danger' : 'info'">{{ SEVERITIES[row.severity] }}</el-tag></template
        >
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="NC_STATUS_TYPE[row.status]" :data-testid="`nc-status-${row.id}`">{{ NC_STATUS[row.status] }}</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="newVisible" title="登记不符合项" width="560px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="问题" required><el-input v-model="draft.title" data-testid="nc-title" /></el-form-item>
        <el-form-item label="问题描述" required
          ><el-input v-model="draft.description" type="textarea" :rows="2" data-testid="nc-description"
        /></el-form-item>
        <el-form-item label="责任车间" required>
          <el-select v-model="draft.orgId" data-testid="nc-org">
            <el-option v-for="o in orgs" :key="o.id" :label="o.parent_name ? `${o.parent_name} / ${o.name}` : o.name" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="严重程度" required>
          <el-radio-group v-model="draft.severity">
            <el-radio-button v-for="(v, k) in SEVERITIES" :key="k" :value="k" :data-testid="`nc-sev-${k}`">{{ v }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="相关设备" :required="draft.severity === 'HIGH'" :error="errors.asset">
          <el-select
            v-model="draft.asset"
            filterable
            remote
            clearable
            :remote-method="(q: string) => search('ASSET', q)"
            data-testid="nc-asset"
            @visible-change="(v: boolean) => v && search('ASSET', '')"
          >
            <el-option v-for="o in assets" :key="o.id" :label="o.label" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="立即措施" :required="draft.severity === 'HIGH'" :error="errors.immediateAction">
          <el-input v-model="draft.immediateAction" type="textarea" :rows="2" data-testid="nc-action" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newVisible = false">取消</el-button>
        <el-button type="primary" data-testid="btn-save-nc" @click="saveNew">登记并提交</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" size="680px" :title="cur?.title ?? ''" append-to-body>
      <template v-if="cur">
        <div class="sub">
          严重程度 {{ SEVERITIES[cur.severity] }} · {{ refLabel(cur.asset) || '无相关设备' }} ·
          <el-tag size="small" :type="NC_STATUS_TYPE[cur.status]" data-testid="nc-detail-status">{{ NC_STATUS[cur.status] }}</el-tag>
        </div>
        <h4>问题描述</h4>
        <el-input :model-value="cur.description" type="textarea" :rows="2" readonly data-testid="nc-detail-description" />

        <template v-if="cur.status === 'DRAFT'">
          <div class="footer"><el-button type="primary" data-testid="btn-nc-submit" @click="act('submit')">提交</el-button></div>
        </template>

        <template v-if="cur.status === 'PENDING_AUDIT'">
          <h4>车间审核 <span class="sub">（本节点只可填写审核意见与立即措施）</span></h4>
          <el-form label-width="90px">
            <el-form-item label="审核意见" required
              ><el-input v-model="audit.auditOpinion" type="textarea" :rows="2" data-testid="nc-audit-opinion"
            /></el-form-item>
            <el-form-item label="立即措施"
              ><el-input v-model="audit.immediateAction" type="textarea" :rows="2" data-testid="nc-audit-action"
            /></el-form-item>
            <el-form-item label="整改负责人" required>
              <el-select v-model="audit.owner" filterable remote :remote-method="searchParties" data-testid="nc-owner">
                <el-option v-for="p in parties" :key="p.id" :label="p.orgName ? `${p.name}（${p.orgName}）` : p.name" :value="p.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="整改工作"
              ><el-input v-model="audit.title" :placeholder="`整改：${cur.title}`" data-testid="nc-rect-title"
            /></el-form-item>
          </el-form>
          <div class="footer">
            <el-button type="primary" data-testid="btn-nc-audit" @click="doAudit">审核通过，派整改</el-button>
            <el-button data-testid="btn-nc-reject" @click="doReject">驳回</el-button>
          </div>
        </template>

        <template v-if="cur.auditOpinion">
          <h4>审核意见</h4>
          <p class="pre">{{ cur.auditOpinion }}</p>
        </template>
        <template v-if="cur.immediateAction && cur.status !== 'PENDING_AUDIT'">
          <h4>立即措施</h4>
          <p class="pre">{{ cur.immediateAction }}</p>
        </template>

        <template v-if="works.length">
          <h4>整改工作</h4>
          <el-table :data="works" size="small" data-testid="nc-works">
            <el-table-column label="工作" min-width="200">
              <template #default="{ row }"
                >{{ row.code }} {{ row.title }}
                <div class="sub">{{ row.assignees || '未指派' }}</div></template
              >
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }"
                ><span :data-testid="`nc-work-status-${row.id}`">{{ WORK_STATUS[row.status] ?? row.status }}</span></template
              >
            </el-table-column>
            <el-table-column label="证据" width="90">
              <template #default="{ row }">
                <span :data-testid="`nc-work-evidence-${row.id}`">{{ row.evidence }} 个</span>
                <label v-if="cur.status === 'IN_RECTIFICATION'" class="upload">
                  上传<input type="file" accept="image/*" hidden :data-testid="`nc-evidence-${row.id}`" @change="(e: Event) => onEvidence(row, e)" />
                </label>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'ASSIGNED'"
                  link
                  type="primary"
                  :data-testid="`btn-nc-work-start-${row.id}`"
                  @click="workOp(row, 'start')"
                  >开始</el-button
                >
                <el-button
                  v-if="row.status === 'IN_PROGRESS'"
                  link
                  type="primary"
                  :data-testid="`btn-nc-work-done-${row.id}`"
                  @click="workOp(row, 'submitConfirm')"
                  >完成</el-button
                >
                <el-button
                  v-if="row.status === 'PENDING_CONFIRM'"
                  link
                  type="success"
                  :data-testid="`btn-nc-work-confirm-${row.id}`"
                  @click="workOp(row, 'confirm')"
                  >确认</el-button
                >
              </template>
            </el-table-column>
          </el-table>
          <div v-if="cur.status === 'IN_RECTIFICATION'" class="footer">
            <el-button type="primary" data-testid="btn-nc-submit-verify" @click="act('submitVerify')">整改完成，提交验证</el-button>
          </div>
        </template>

        <template v-if="cur.status === 'PENDING_VERIFY'">
          <h4>验证与终判 <span class="sub">（只能由具备权限的真人执行）</span></h4>
          <el-input v-model="conclusion" type="textarea" :rows="2" placeholder="验证结论（必填）" data-testid="nc-conclusion" />
          <div class="footer">
            <el-button type="success" data-testid="btn-nc-close" @click="verify('close')">验证通过并关闭</el-button>
            <el-button type="warning" data-testid="btn-nc-fail" @click="verify('verifyFail')">验证不通过</el-button>
          </div>
        </template>
        <p v-if="cur.verifyConclusion" class="sub">验证结论：{{ cur.verifyConclusion }}</p>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { QualityApi } from '@/api/lsmom/quality';
import { NC, NC_STATUS, NC_STATUS_TYPE, SEVERITIES, ncMissing, type ObjectOption } from '@/api/lsmom/qualityModel';
import { refLabel } from '@/api/lsmom/assetModel';
import { WORK_STATUS } from '@/api/lsmom/productionModel';

/** 不符合项：登记（高严重度设备与立即措施必填）→ 车间审核（只可填审核意见与立即措施）→ 整改工作（附证据）→ 提交验证 → 真人终判关闭。 */
const props = defineProps<{ api: QualityApi }>();

const status = ref('');
const rows = ref<Record<string, any>[]>([]);
const loading = ref(false);
const newVisible = ref(false);
const draft = reactive<Record<string, any>>({
  title: '',
  description: '',
  severity: 'MEDIUM',
  asset: undefined,
  immediateAction: '',
  orgId: undefined
});
const orgs = ref<{ id: number; name: string; unit_type: string; parent_name?: string }[]>([]);
const errors = reactive<Record<string, string>>({});
const assets = ref<ObjectOption[]>([]);

const detailVisible = ref(false);
const cur = ref<Record<string, any>>();
const works = ref<Record<string, any>[]>([]);
const parties = ref<{ id: number; name: string; orgName?: string }[]>([]);
const audit = reactive<{ auditOpinion: string; immediateAction: string; owner?: number; title: string }>({
  auditOpinion: '',
  immediateAction: '',
  owner: undefined,
  title: ''
});
const conclusion = ref('');

const MESSAGES: Record<string, string> = { asset: '严重程度为高时必须填写相关设备', immediateAction: '严重程度为高时必须填写立即措施' };

async function load() {
  loading.value = true;
  try {
    const filters: any[] = status.value ? [{ field: 'status', op: 'eq', value: status.value }] : [];
    const page = await props.api.query(NC, { filters, sort: [{ field: 'id', dir: 'desc' }], page: 1, size: 200, expand: ['asset'] });
    rows.value = page.rows as Record<string, any>[];
  } finally {
    loading.value = false;
  }
}

async function search(type: string, q: string) {
  assets.value = await props.api.objects(type, q).catch(() => []);
}

async function searchParties(q: string) {
  parties.value = await props.api.parties(q).catch(() => []);
}

async function openNew() {
  if (!orgs.value.length) orgs.value = await props.api.orgs().catch(() => []);
  Object.assign(draft, {
    title: '',
    description: '',
    severity: 'MEDIUM',
    asset: undefined,
    immediateAction: '',
    orgId: orgs.value.find((o) => o.unit_type === 'WORKSHOP')?.id ?? orgs.value[0]?.id
  });
  Object.keys(errors).forEach((k) => delete errors[k]);
  newVisible.value = true;
}

async function saveNew() {
  Object.keys(errors).forEach((k) => delete errors[k]);
  if (!draft.title.trim() || !draft.description.trim()) return ElMessage.warning('请填写问题与问题描述');
  const missing = ncMissing(draft);
  if (missing.length) {
    missing.forEach((k) => (errors[k] = MESSAGES[k]));
    return ElMessage.warning(MESSAGES[missing[0]]);
  }
  const body: Record<string, unknown> = {
    title: draft.title.trim(),
    description: draft.description.trim(),
    severity: draft.severity,
    orgId: draft.orgId
  };
  if (draft.asset) body.asset = draft.asset;
  if (draft.immediateAction.trim()) body.immediateAction = draft.immediateAction.trim();
  const r = await props.api.create(NC, body);
  await props.api.op(`${NC}.submit`, r.id, r.version);
  ElMessage.success('不符合项已提交，等待车间审核');
  newVisible.value = false;
  await load();
}

async function openDetail(id: number) {
  cur.value = await props.api.get(NC, id);
  works.value = await props.api.rectifications(id).catch(() => []);
  Object.assign(audit, { auditOpinion: '', immediateAction: cur.value?.immediateAction ?? '', owner: undefined, title: '' });
  conclusion.value = '';
  if (!parties.value.length) await searchParties('');
  detailVisible.value = true;
}

async function refresh() {
  if (cur.value) await openDetail(cur.value.id);
  await load();
}

async function act(action: string, input: Record<string, unknown> = {}) {
  const c = cur.value;
  if (!c) return;
  const r = await props.api.op(`${NC}.${action}`, c.id, c.version, input);
  if (r.warnings?.length) ElMessage.warning(r.warnings.join('；'));
  else ElMessage.success(`已更新为${NC_STATUS[r.status] ?? r.status}`);
  await refresh();
}

async function doAudit() {
  if (!audit.auditOpinion.trim() || !audit.owner) return ElMessage.warning('请填写审核意见并选择整改负责人');
  const input: Record<string, unknown> = { auditOpinion: audit.auditOpinion.trim(), owner: audit.owner };
  if (audit.immediateAction.trim()) input.immediateAction = audit.immediateAction.trim();
  if (audit.title.trim()) input.titles = [audit.title.trim()];
  await act('audit', input);
}

async function doReject() {
  if (!audit.auditOpinion.trim()) return ElMessage.warning('请填写驳回意见');
  await act('reject', { auditOpinion: audit.auditOpinion.trim() });
}

async function onEvidence(row: Record<string, any>, e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  const up = await props.api.upload(file);
  await props.api.attach(up.ossId, 'ls.core.Work', row.id, 'EVIDENCE');
  ElMessage.success('整改证据已上传');
  works.value = await props.api.rectifications(cur.value!.id);
}

async function workOp(w: Record<string, any>, action: string) {
  await props.api.op(`ls.core.Work.${action}`, w.id, w.version);
  works.value = await props.api.rectifications(cur.value!.id);
}

async function verify(action: 'close' | 'verifyFail') {
  if (!conclusion.value.trim()) return ElMessage.warning('请填写验证结论');
  await act(action, { conclusion: conclusion.value.trim() });
}

onMounted(load);
defineExpose({ load, openDetail, openNew });
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}
.grow {
  flex: 1;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: normal;
}
.pre {
  white-space: pre-wrap;
}
.footer {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}
.upload {
  margin-left: 6px;
  color: var(--el-color-primary);
  cursor: pointer;
  font-size: 12px;
}
</style>
