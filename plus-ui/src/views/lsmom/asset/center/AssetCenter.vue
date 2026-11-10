<template>
  <div data-testid="asset-center">
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="设备编码或名称" clearable style="width: 220px" data-testid="asset-keyword" @change="load" />
      <el-select v-model="status" placeholder="全部状态" clearable style="width: 140px" data-testid="asset-status-filter" @change="load">
        <el-option v-for="(v, k) in ASSET_STATUS" :key="k" :label="v" :value="k" />
      </el-select>
      <span class="grow" />
      <el-button type="primary" data-testid="btn-new-asset" @click="openNew">新增设备</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" size="small" data-testid="asset-table" @row-click="(r: any) => openDetail(r.id)">
      <el-table-column label="设备编码" width="140">
        <template #default="{ row }">
          <span :data-testid="`asset-row-${row.id}`">{{ row.assetCode }}</span>
        </template>
      </el-table-column>
      <el-table-column label="名称" prop="name" min-width="160" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ typeLabel(row.assetType) }}</template>
      </el-table-column>
      <el-table-column label="位置" prop="location" min-width="120" />
      <el-table-column label="负责人" width="110">
        <template #default="{ row }">{{ refLabel(row.owner) || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="ASSET_STATUS_TYPE[row.status]" :data-testid="`asset-status-${row.id}`">{{
            ASSET_STATUS[row.status] ?? row.status
          }}</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="newVisible" title="新增设备" width="520px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="设备编码" required><el-input v-model="draft.assetCode" data-testid="asset-code" /></el-form-item>
        <el-form-item label="名称" required><el-input v-model="draft.name" data-testid="asset-name" /></el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="draft.assetType" data-testid="asset-type">
            <el-option v-for="t in ASSET_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属车间" required>
          <el-select v-model="draft.orgId" data-testid="asset-org">
            <el-option v-for="o in orgs" :key="o.id" :label="o.parent_name ? `${o.parent_name} / ${o.name}` : o.name" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="位置"><el-input v-model="draft.location" data-testid="asset-location" /></el-form-item>
        <el-form-item label="型号"><el-input v-model="draft.model" /></el-form-item>
        <el-form-item label="负责人">
          <el-select v-model="draft.owner" filterable remote :remote-method="searchParties" clearable data-testid="asset-owner">
            <el-option v-for="p in parties" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newVisible = false">取消</el-button>
        <el-button type="primary" data-testid="btn-create-asset" @click="createNew">保存并投用</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" size="720px" :title="cur ? `${cur.assetCode} ${cur.name}` : ''" append-to-body>
      <template v-if="cur">
        <div class="sub">
          {{ typeLabel(cur.assetType) }} · {{ cur.location || '—' }} · 负责人 {{ refLabel(cur.owner) || '—' }} ·
          <el-tag size="small" :type="ASSET_STATUS_TYPE[cur.status]" data-testid="asset-detail-status">{{ ASSET_STATUS[cur.status] }}</el-tag>
          <span v-if="cur.statusReason"> · {{ cur.statusReason }}</span>
        </div>
        <div class="ops">
          <el-button
            v-for="o in availableOps"
            :key="o.op"
            size="small"
            :type="o.needReason ? 'warning' : 'primary'"
            plain
            :data-testid="`btn-asset-${o.op}`"
            @click="startOp(o)"
            >{{ o.label }}</el-button
          >
          <el-button size="small" type="danger" plain data-testid="btn-asset-defect" @click="openDefect">登记缺陷</el-button>
        </div>
        <template v-if="points.length">
          <h4>测点</h4>
          <el-table :data="points" size="small" data-testid="asset-points">
            <el-table-column label="测点" min-width="160">
              <template #default="{ row }"
                ><span :data-testid="`asset-point-${row.code}`">{{ row.name }}</span>
                <div class="sub">{{ row.code }}</div></template
              >
            </el-table-column>
            <el-table-column label="最近快照" min-width="140">
              <template #default="{ row }">
                <span v-if="row.last_bucket"
                  >{{ fmtNum(row.last_avg) }} {{ row.unit }}<span class="sub"> · {{ fmt(row.last_bucket) }}</span></span
                >
                <span v-else class="sub">暂无</span>
              </template>
            </el-table-column>
            <el-table-column label="最大值" width="90">
              <template #default="{ row }">{{ fmtNum(row.last_max) }}</template>
            </el-table-column>
            <el-table-column label="待处置报警" prop="open_alarms" width="96" />
          </el-table>
        </template>
        <h4>设备时间轴</h4>
        <el-empty v-if="!timeline.length" description="暂无记录" :image-size="60" />
        <el-timeline v-else data-testid="asset-timeline">
          <el-timeline-item v-for="(t, i) in timeline" :key="i" :timestamp="fmt(t.at)" placement="top">
            <div :data-testid="`tl-${t.kind}`">
              <el-tag size="small" :type="TIMELINE_KIND[t.kind]?.type">{{ TIMELINE_KIND[t.kind]?.label ?? t.kind }}</el-tag>
              <span class="tl-title">{{ t.title }}</span>
              <div v-if="t.detail" class="sub">{{ t.detail }}</div>
            </div>
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>

    <el-dialog v-model="reasonVisible" :title="pendingOp?.label" width="420px" append-to-body>
      <el-input
        v-model="reason"
        type="textarea"
        :rows="3"
        :placeholder="pendingOp?.needReason ? '原因（必填）' : '说明（可选）'"
        data-testid="asset-reason"
      />
      <template #footer>
        <el-button @click="reasonVisible = false">取消</el-button>
        <el-button type="primary" data-testid="btn-asset-op-confirm" @click="doOp">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="defectVisible" title="登记设备缺陷" width="600px" append-to-body destroy-on-close data-testid="defect-dialog">
      <LsSkeleton v-if="!defectSchema" variant="form" :rows="5" />
      <SchemaForm
        v-else
        ref="defectRef"
        v-model="defect"
        :schema="defectSchema"
        :source="cur"
        :org-id="cur?.orgId"
        :show-actions="false"
        :draft-key="`ls.asset.Defect:new:asset-${cur?.id}`"
      />
      <template #footer>
        <el-button @click="defectVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!defectSchema" :loading="defectBusy" data-testid="btn-defect-report" @click="reportDefect">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { AssetApi } from '@/api/lsmom/asset';
import type { FormSchema } from '@/types/lsView';
import SchemaForm from '@/components/SchemaForm/index.vue';
import { LsSkeleton } from '@/components/LsState';
import {
  ASSET,
  ASSET_OPS,
  ASSET_STATUS,
  ASSET_STATUS_TYPE,
  ASSET_TYPES,
  DEFECT,
  TIMELINE_KIND,
  refLabel,
  type OrgOption,
  type TimelineItem
} from '@/api/lsmom/assetModel';

/** 设备台账：列表、新增并投用、状态变更（带原因留痕）、登记缺陷、设备时间轴。 */
const props = defineProps<{ api: AssetApi }>();

const rows = ref<Record<string, any>[]>([]);
const loading = ref(false);
const keyword = ref('');
const status = ref<string>();
const orgs = ref<OrgOption[]>([]);
const parties = ref<{ id: number; name: string }[]>([]);

const newVisible = ref(false);
const draft = reactive<Record<string, any>>({
  assetCode: '',
  name: '',
  assetType: 'PUMP',
  orgId: undefined,
  location: '',
  model: '',
  owner: undefined
});

const detailVisible = ref(false);
const cur = ref<Record<string, any>>();
const timeline = ref<TimelineItem[]>([]);
const points = ref<Record<string, any>[]>([]);
const fmtNum = (v?: number | null) => (v === null || v === undefined ? '—' : String(Math.round(v * 1000) / 1000));
const availableOps = computed(() => ASSET_OPS.filter((o) => cur.value && o.from.includes(cur.value.status)));

const reasonVisible = ref(false);
const pendingOp = ref<(typeof ASSET_OPS)[number]>();
const reason = ref('');

const defectVisible = ref(false);
const defectSchema = ref<FormSchema>();
const defectRef = ref<InstanceType<typeof SchemaForm>>();
const defect = ref<Record<string, any>>({});
const defectBusy = ref(false);

/** 打开缺陷表单：设备、位置从当前设备带入，登记人、时间取当前上下文，严重度记住上次的选择。 */
async function openDefect() {
  defect.value = {};
  defectVisible.value = true;
  if (!defectSchema.value) defectSchema.value = await props.api.defectForm();
}

const typeLabel = (v: string) => ASSET_TYPES.find((t) => t.value === v)?.label ?? v;
const fmt = (s: string) => (s ? String(s).replace('T', ' ').slice(0, 16) : '');

async function load() {
  loading.value = true;
  try {
    const base: any[] = status.value ? [{ field: 'status', op: 'eq', value: status.value }] : [];
    const q = (filters: any[]) =>
      props.api.query(ASSET, { filters, sort: [{ field: 'assetCode', dir: 'asc' }], page: 1, size: 200, expand: ['owner'] });
    const kw = keyword.value.trim();
    if (!kw) {
      rows.value = (await q(base)).rows as Record<string, any>[];
      return;
    }
    // 输入框按「设备编码或名称」搜索：两个条件分别查询后合并（查询接口的条件之间是“且”）
    const [byCode, byName] = await Promise.all([
      q([...base, { field: 'assetCode', op: 'like', value: kw }]),
      q([...base, { field: 'name', op: 'like', value: kw }])
    ]);
    const seen = new Set<unknown>();
    rows.value = ([...byCode.rows, ...byName.rows] as Record<string, any>[])
      .filter((r) => !seen.has(r.id) && seen.add(r.id))
      .sort((a, b) => String(a.assetCode).localeCompare(String(b.assetCode)));
  } finally {
    loading.value = false;
  }
}

async function searchParties(q: string) {
  parties.value = await props.api.parties(q).catch(() => []);
}

async function openNew() {
  if (!orgs.value.length) orgs.value = await props.api.orgs().catch(() => []);
  if (!draft.orgId) draft.orgId = orgs.value.find((o) => o.unit_type === 'WORKSHOP')?.id ?? orgs.value[0]?.id;
  if (!parties.value.length) await searchParties('');
  newVisible.value = true;
}

async function createNew() {
  if (!draft.assetCode || !draft.name || !draft.orgId) return ElMessage.warning('请填写设备编码、名称和所属车间');
  const body: Record<string, unknown> = {};
  Object.entries(draft).forEach(([k, v]) => {
    if (v !== '' && v !== undefined && v !== null) body[k] = v;
  });
  const r = await props.api.create(ASSET, body);
  await props.api.op(`${ASSET}.commission`, r.id, r.version);
  ElMessage.success('设备已投用');
  newVisible.value = false;
  Object.assign(draft, { assetCode: '', name: '', location: '', model: '', owner: undefined });
  await load();
}

async function openDetail(id: number) {
  cur.value = await props.api.get(ASSET, id);
  timeline.value = await props.api.timeline(id).catch(() => []);
  points.value = (await props.api.points?.(id).catch(() => [])) ?? [];
  detailVisible.value = true;
}

function startOp(o: (typeof ASSET_OPS)[number]) {
  pendingOp.value = o;
  reason.value = '';
  reasonVisible.value = true;
}

async function doOp() {
  const o = pendingOp.value;
  const c = cur.value;
  if (!o || !c) return;
  if (o.needReason && !reason.value.trim()) return ElMessage.warning('请填写原因');
  const r = await props.api.op(`${ASSET}.${o.op}`, c.id, c.version, reason.value.trim() ? { reason: reason.value.trim() } : {});
  ElMessage.success(`已${o.label}`);
  reasonVisible.value = false;
  await openDetail(Number(r.targetId ?? c.id));
  await load();
}

async function reportDefect() {
  const c = cur.value;
  const form = defectRef.value;
  if (!c || !form || Object.keys(form.validate().errors).length) return;
  defectBusy.value = true;
  try {
    const r = await props.api.create(DEFECT, { ...form.payload(), orgId: c.orgId });
    await form.commitRemembered();
    await form.clearDraft();
    try {
      await props.api.op(`${DEFECT}.report`, r.id, r.version);
      ElMessage.success('缺陷已登记');
    } catch {
      ElMessage.warning('缺陷已保存为草稿，请到「缺陷与维修」补充证据后登记');
    }
    defectVisible.value = false;
    await openDetail(c.id);
  } finally {
    defectBusy.value = false;
  }
}

onMounted(load);
defineExpose({ load, openDetail });
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: var(--ls-space-2);
  align-items: center;
  margin-bottom: var(--ls-space-3);
}
.grow {
  flex: 1;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: var(--ls-space-half);
}
.ops {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ls-space-2);
  margin: var(--ls-space-3) 0;
}
.tl-title {
  margin-left: var(--ls-space-2);
}
</style>
