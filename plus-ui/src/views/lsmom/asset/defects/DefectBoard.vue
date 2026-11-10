<template>
  <div data-testid="defect-board">
    <div class="toolbar">
      <el-radio-group v-model="status" size="small" @change="load">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button v-for="(v, k) in DEFECT_STATUS" :key="k" :value="k" :data-testid="`df-filter-${k}`">{{ v }}</el-radio-button>
      </el-radio-group>
    </div>
    <el-table v-loading="loading" :data="rows" size="small" data-testid="defect-table" @row-click="(r: any) => openDetail(r.id)">
      <el-table-column label="缺陷" min-width="220">
        <template #default="{ row }">
          <span :data-testid="`df-row-${row.id}`">{{ row.title }}</span>
          <div class="sub">{{ refLabel(row.asset) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="严重度" width="80"
        ><template #default="{ row }">{{ SEVERITY[row.severity] }}</template></el-table-column
      >
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="DEFECT_STATUS_TYPE[row.status]" :data-testid="`df-status-${row.id}`">{{ DEFECT_STATUS[row.status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="100"
        ><template #default="{ row }">{{ row.inspectionRecord ? '点检异常' : '手工登记' }}</template></el-table-column
      >
    </el-table>

    <el-drawer v-model="detailVisible" size="680px" :title="cur?.title ?? ''" append-to-body>
      <template v-if="cur">
        <div class="sub">
          {{ refLabel(cur.asset) }} · 严重度 {{ SEVERITY[cur.severity] }} ·
          <el-tag size="small" :type="DEFECT_STATUS_TYPE[cur.status]" data-testid="df-detail-status">{{ DEFECT_STATUS[cur.status] }}</el-tag>
          <span v-if="cur.issue"> · 异常 {{ refLabel(cur.issue) }}</span>
        </div>
        <p class="pre">{{ cur.symptom || '—' }}</p>
        <p v-if="cur.verifyConclusion" class="sub">验证结论：{{ cur.verifyConclusion }}</p>

        <template v-if="cur.status === 'DRAFT'">
          <div class="row">
            <span class="sub">现场证据（高、严重级别登记前必传）：已上传 {{ evidence.length }} 个</span>
            <input type="file" accept="image/*" data-testid="df-evidence" @change="onEvidence" />
          </div>
          <div class="footer">
            <el-button type="primary" data-testid="btn-df-report" @click="act('report')">登记缺陷</el-button>
            <el-button data-testid="btn-df-cancel" @click="cancel">取消缺陷</el-button>
          </div>
        </template>

        <template v-if="cur.status === 'REPORTED' || cur.status === 'REPAIRING'">
          <h4>{{ cur.status === 'REPORTED' ? '生成维修工作' : '再派维修' }}</h4>
          <el-form label-width="90px">
            <el-form-item label="维修人">
              <el-select v-model="repair.assignee" filterable remote :remote-method="searchParties" clearable data-testid="df-assignee">
                <el-option v-for="p in parties" :key="p.id" :label="p.orgName ? `${p.name}（${p.orgName}）` : p.name" :value="p.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="工作标题"
              ><el-input v-model="repair.title" :placeholder="`维修：${cur.title}`" data-testid="df-repair-title"
            /></el-form-item>
            <el-form-item label="检查清单"
              ><el-input v-model="repair.checklist" type="textarea" :rows="3" placeholder="每行一项，可选" data-testid="df-checklist"
            /></el-form-item>
          </el-form>
          <el-button type="primary" data-testid="btn-df-repair" @click="createRepair">生成维修工作</el-button>
        </template>

        <template v-if="works.length">
          <h4>维修工作</h4>
          <el-table :data="works" size="small" data-testid="df-works">
            <el-table-column label="工作" min-width="200">
              <template #default="{ row }"
                >{{ row.code }} {{ row.title }}
                <div class="sub">{{ row.assignees || '未指派' }}</div></template
              >
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }"
                ><span :data-testid="`df-work-status-${row.id}`">{{ WORK_STATUS[row.status] ?? row.status }}</span></template
              >
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button
                  v-if="row.status === 'ASSIGNED'"
                  link
                  type="primary"
                  :data-testid="`btn-work-start-${row.id}`"
                  @click="workOp(row, 'start')"
                  >开始</el-button
                >
                <el-button
                  v-if="row.status === 'IN_PROGRESS'"
                  link
                  type="primary"
                  :data-testid="`btn-work-done-${row.id}`"
                  @click="workOp(row, 'submitConfirm')"
                  >完成</el-button
                >
                <el-button
                  v-if="row.status === 'PENDING_CONFIRM'"
                  link
                  type="success"
                  :data-testid="`btn-work-confirm-${row.id}`"
                  @click="workOp(row, 'confirm')"
                  >确认</el-button
                >
              </template>
            </el-table-column>
          </el-table>
          <div v-if="cur.status === 'REPAIRING'" class="footer">
            <el-button type="primary" data-testid="btn-df-submit-verify" @click="act('submitVerify')">维修完成，提交验证</el-button>
          </div>
        </template>

        <template v-if="cur.status === 'PENDING_VERIFY'">
          <h4>验证</h4>
          <el-input v-model="conclusion" type="textarea" :rows="2" placeholder="验证结论（必填）" data-testid="df-conclusion" />
          <div class="footer">
            <el-button type="success" data-testid="btn-df-pass" @click="verify('verifyPass')">验证通过，关闭缺陷</el-button>
            <el-button type="warning" data-testid="btn-df-fail" @click="verify('verifyFail')">验证不通过</el-button>
          </div>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { notifyDone } from '@/utils/opResult';
import { confirmOperation } from '@/utils/opConfirm';
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { AssetApi } from '@/api/lsmom/asset';
import { DEFECT, DEFECT_STATUS, DEFECT_STATUS_TYPE, SEVERITY, refLabel } from '@/api/lsmom/assetModel';
import { WORK_STATUS } from '@/api/lsmom/productionModel';

/** 缺陷与维修：登记（高严重度先传证据）→ 生成 MAINTENANCE 维修工作（可附检查清单）→ 维修执行 → 提交验证 → 验证通过关闭 / 不通过再派。 */
const props = defineProps<{ api: AssetApi; initialStatus?: string }>();

const status = ref(props.initialStatus ?? '');
const rows = ref<Record<string, any>[]>([]);
const loading = ref(false);
const detailVisible = ref(false);
const cur = ref<Record<string, any>>();
const works = ref<Record<string, any>[]>([]);
const evidence = ref<Record<string, any>[]>([]);
const parties = ref<{ id: number; name: string; orgName?: string }[]>([]);
const repair = reactive<{ assignee?: number; title: string; checklist: string }>({ assignee: undefined, title: '', checklist: '' });
const conclusion = ref('');

async function load() {
  loading.value = true;
  try {
    const filters: any[] = status.value ? [{ field: 'status', op: 'eq', value: status.value }] : [];
    const page = await props.api.query(DEFECT, { filters, sort: [{ field: 'id', dir: 'desc' }], page: 1, size: 200, expand: ['asset'] });
    rows.value = page.rows as Record<string, any>[];
  } finally {
    loading.value = false;
  }
}

async function openDetail(id: number) {
  cur.value = await props.api.get(DEFECT, id);
  works.value = await props.api.repairs(id).catch(() => []);
  evidence.value = [];
  conclusion.value = '';
  Object.assign(repair, { title: '', checklist: '' });
  if (!parties.value.length) await searchParties('');
  detailVisible.value = true;
}

async function searchParties(q: string) {
  parties.value = await props.api.parties(q).catch(() => []);
}

async function refresh() {
  if (cur.value) await openDetail(cur.value.id);
  await load();
}

/** 关闭、取消不可撤销：先确认，列出将结束的缺陷、关联问题与维修工作数量 */
const FINAL: Record<string, string> = { verifyPass: '验证通过，关闭缺陷', cancel: '取消缺陷' };

async function act(action: string, input: Record<string, unknown> = {}) {
  const c = cur.value;
  if (!c) return;
  if (FINAL[action]) {
    const ok = await confirmOperation({
      name: FINAL[action],
      label: c.title,
      impact: () => props.api.impact(`${DEFECT}.${action}`, c.id),
      stateLabel: (s) => (s ? (DEFECT_STATUS[s] ?? s) : '')
    });
    if (!ok) return;
  }
  const r = await props.api.op(`${DEFECT}.${action}`, c.id, c.version, input);
  notifyDone(`已${DEFECT_STATUS[r.status] ? '更新为' + DEFECT_STATUS[r.status] : '完成'}`, r.warnings);
  await refresh();
}

async function onEvidence(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file || !cur.value) return;
  const up = await props.api.upload(file);
  await props.api.attach(up.ossId, DEFECT, cur.value.id, 'EVIDENCE');
  evidence.value.push({ fileName: up.fileName });
  ElMessage.success('证据已上传');
}

async function cancel() {
  await act('cancel', { reason: '取消登记' });
}

async function createRepair() {
  const input: Record<string, unknown> = {};
  if (repair.assignee) input.assignee = repair.assignee;
  if (repair.title.trim()) input.titles = [repair.title.trim()];
  const lines = repair.checklist
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);
  if (lines.length) input.checklist = lines;
  await act('createRepair', input);
}

async function workOp(w: Record<string, any>, action: string) {
  await props.api.op(`ls.core.Work.${action}`, w.id, w.version);
  works.value = await props.api.repairs(cur.value!.id);
}

async function verify(action: 'verifyPass' | 'verifyFail') {
  if (!conclusion.value.trim()) return ElMessage.warning('请填写验证结论');
  await act(action, { conclusion: conclusion.value.trim() });
}

onMounted(load);
defineExpose({ load, openDetail });
</script>

<style scoped>
.toolbar {
  margin-bottom: var(--ls-space-3);
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.pre {
  white-space: pre-wrap;
}
.row {
  display: flex;
  gap: var(--ls-space-3);
  align-items: center;
  margin: var(--ls-space-2) 0;
}
.footer {
  margin-top: var(--ls-space-3);
  display: flex;
  gap: var(--ls-space-2);
}
</style>
