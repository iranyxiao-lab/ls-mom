<template>
  <div data-testid="handover-page">
    <div class="toolbar">
      <el-radio-group v-model="status" size="small" @change="load">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="DRAFT">草稿</el-radio-button>
        <el-radio-button value="PUBLISHED" data-testid="ho-filter-published">待接班</el-radio-button>
        <el-radio-button value="COMPLETED">已完成</el-radio-button>
      </el-radio-group>
      <span class="grow" />
      <el-button type="primary" data-testid="btn-new-handover" @click="openNew">发起交接班</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" size="small" data-testid="handover-table" @row-click="(r: any) => openDetail(r.id)">
      <el-table-column label="交接" min-width="240">
        <template #default="{ row }">
          <span :data-testid="`ho-row-${row.id}`">{{ label(row.fromTeam) }} → {{ label(row.toTeam) }}</span>
          <div class="sub">{{ row.shiftDate }} {{ shiftLabel(row.shift) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="STATUS_TYPE[row.status]" :data-testid="`ho-status-${row.id}`">{{ STATUS[row.status] ?? row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="交班人" width="120">
        <template #default="{ row }">{{ label(row.publishedBy) || '—' }}</template>
      </el-table-column>
      <el-table-column label="接班人" width="120">
        <template #default="{ row }">{{ label(row.acceptedBy) || '—' }}</template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="newVisible" title="发起交接班" width="480px" append-to-body>
      <el-form label-width="80px">
        <el-form-item label="交班班组">
          <el-select v-model="draft.fromTeam" data-testid="ho-from">
            <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="接班班组">
          <el-select v-model="draft.toTeam" data-testid="ho-to">
            <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期"
          ><el-date-picker v-model="draft.shiftDate" type="date" value-format="YYYY-MM-DD" data-testid="ho-date"
        /></el-form-item>
        <el-form-item label="交班班次">
          <el-radio-group v-model="draft.shift">
            <el-radio-button v-for="s in SHIFTS" :key="s.value" :value="s.value" :data-testid="`ho-shift-${s.value}`">{{ s.label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newVisible = false">取消</el-button>
        <el-button type="primary" data-testid="btn-create-handover" @click="createNew">生成交接班草稿</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" size="640px" :title="cur ? `${label(cur.fromTeam)} → ${label(cur.toTeam)}` : ''" append-to-body>
      <template v-if="cur">
        <div class="sub">
          {{ cur.shiftDate }} {{ shiftLabel(cur.shift) }} ·
          <el-tag size="small" :type="STATUS_TYPE[cur.status]" data-testid="ho-detail-status">{{ STATUS[cur.status] }}</el-tag>
          <span v-if="cur.status === 'DRAFT'"> · 草稿只有你自己能看到，确认交班后接班班组才能看到</span>
        </div>
        <h4>交班摘要</h4>
        <el-input v-if="cur.status === 'DRAFT'" v-model="summary" type="textarea" :rows="8" data-testid="ho-summary" />
        <pre v-else class="pre" data-testid="ho-summary-view">{{ cur.summary }}</pre>
        <h4>异常</h4>
        <div class="pre">{{ cur.issueSummary || '—' }}</div>
        <h4>DCS 摘要</h4>
        <div class="pre">{{ cur.dcsSummary || '—' }}</div>
        <h4>交接事项</h4>
        <el-table :data="items" size="small" data-testid="ho-items">
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ KIND[row.kind] ?? row.kind }}</template>
          </el-table-column>
          <el-table-column label="事项" prop="title" min-width="220" />
          <el-table-column label="当前状态" width="100">
            <template #default="{ row }">{{ WORK_STATUS[row.workStatus] ?? row.workStatus }}</template>
          </el-table-column>
        </el-table>
        <h4>附件</h4>
        <div v-for="d in docs" :key="d.id" class="sub">📎 {{ d.fileName }}</div>
        <input v-if="cur.status === 'DRAFT'" type="file" multiple data-testid="ho-attach" @change="onAttach" />
        <template v-if="cur.status === 'COMPLETED'">
          <h4>接班意见</h4>
          <div class="pre">{{ cur.acceptNote || '—' }}</div>
        </template>
        <div class="actions">
          <template v-if="cur.status === 'DRAFT'">
            <el-button data-testid="btn-generate" @click="run('generate')">{{ cur.summary ? '重新生成摘要' : '生成摘要草稿' }}</el-button>
            <el-button type="primary" data-testid="btn-publish" @click="run('publish', { summary })">确认交班</el-button>
          </template>
          <template v-if="cur.status === 'PUBLISHED'">
            <el-input v-model="note" placeholder="接班意见（可选）" style="width: 220px" data-testid="ho-note" />
            <el-button type="primary" data-testid="btn-accept" @click="run('accept', note ? { note } : {})">确认接班</el-button>
            <el-button data-testid="btn-return" @click="returnBack">退回</el-button>
          </template>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { HANDOVER, SHIFTS, WORK_STATUS, shiftLabel, type Team } from '@/api/lsmom/productionModel';
import type { ProductionApi } from '@/api/lsmom/production';

/**
 * 13.3 交接班：交班人生成摘要草稿（当班工作、未完成事项、异常、DCS 摘要、附件），确认后发布；
 * 接班班组确认接收后完成，未完成事项进入接班班组的工作板。草稿在确认前只有交班人可见。
 */
const props = defineProps<{ api: ProductionApi }>();

const STATUS: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '待接班', COMPLETED: '已完成' };
const STATUS_TYPE: Record<string, any> = { DRAFT: 'info', PUBLISHED: 'warning', COMPLETED: 'success' };
const KIND: Record<string, string> = { UNFINISHED: '未完成', DONE: '已完成', ISSUE: '异常', NOTE: '备注' };

const rows = ref<Record<string, any>[]>([]);
const teams = ref<Team[]>([]);
const status = ref('');
const loading = ref(false);
const newVisible = ref(false);
const detailVisible = ref(false);
const cur = ref<Record<string, any>>();
const items = ref<Record<string, any>[]>([]);
const docs = ref<Record<string, any>[]>([]);
const summary = ref('');
const note = ref('');
const draft = reactive({ fromTeam: undefined as number | undefined, toTeam: undefined as number | undefined, shiftDate: '', shift: 'DAY' });

const label = (v: any) => (v && typeof v === 'object' ? (v.label ?? v.id) : (v ?? ''));
const today = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

async function load() {
  loading.value = true;
  try {
    const page = await props.api.query(HANDOVER, {
      filters: status.value ? [{ field: 'status', op: 'eq', value: status.value }] : [],
      sort: [{ field: 'id', dir: 'desc' }],
      page: 1,
      size: 100,
      expand: ['fromTeam', 'toTeam', 'publishedBy', 'acceptedBy']
    });
    rows.value = page.rows as Record<string, any>[];
  } finally {
    loading.value = false;
  }
}

async function openNew() {
  const mine = await props.api.myTeams().catch(() => []);
  draft.fromTeam = mine[0]?.id ?? teams.value[0]?.id;
  draft.toTeam = teams.value.find((t) => t.id !== draft.fromTeam)?.id;
  draft.shiftDate = today();
  newVisible.value = true;
}

async function createNew() {
  if (!draft.fromTeam || !draft.toTeam || draft.fromTeam === draft.toTeam) return ElMessage.warning('请选择不同的交班与接班班组');
  const r = await props.api.create(HANDOVER, { ...draft, orgId: draft.fromTeam });
  await props.api.op(`${HANDOVER}.generate`, r.id, r.version);
  newVisible.value = false;
  await load();
  await openDetail(r.id);
}

async function openDetail(id: number) {
  const row = await props.api.get(HANDOVER, id);
  cur.value = row;
  summary.value = String(row.summary ?? '');
  items.value = (row.shiftHandoverItem ?? []) as Record<string, any>[];
  docs.value = await props.api.docs(HANDOVER, id).catch(() => []);
  note.value = '';
  detailVisible.value = true;
}

async function run(action: string, input: Record<string, unknown> = {}) {
  const c = cur.value!;
  const r = await props.api.op(`${HANDOVER}.${action}`, c.id, c.version, input);
  if (r.warnings?.length) ElMessage.warning(r.warnings.join('；'));
  else
    ElMessage.success(
      { generate: '摘要草稿已生成', publish: '已确认交班，接班班组可见', accept: '已确认接班，未完成事项已转入本班组' }[action] ?? '已完成'
    );
  await load();
  await openDetail(c.id);
}

async function returnBack() {
  const { value } = await ElMessageBox.prompt('退回原因', '退回交班人', { inputPattern: /\S/, inputErrorMessage: '请填写原因' });
  await run('returnBack', { reason: value });
}

async function onAttach(e: Event) {
  const c = cur.value!;
  for (const f of Array.from((e.target as HTMLInputElement).files ?? [])) {
    const up = await props.api.upload(f);
    await props.api.attach(up.ossId, HANDOVER, c.id, 'ATTACHMENT');
  }
  (e.target as HTMLInputElement).value = '';
  docs.value = await props.api.docs(HANDOVER, c.id);
}

onMounted(async () => {
  teams.value = await props.api.teams().catch(() => []);
  await load();
});

// ?id=<交接班 id>：通知、收藏等入口直接打开该交接班
const route = useRoute();
const router = useRouter();
watch(
  () => [route?.path, route?.query?.id] as const,
  async ([path, id]) => {
    if (!id || !path) return;
    const { id: _drop, ...rest } = route.query;
    await router.replace({ path, query: rest });
    await openDetail(Number(id));
  },
  { immediate: true }
);
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: var(--ls-space-2);
  margin-bottom: var(--ls-space-3);
}
.grow {
  flex: 1;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.pre {
  white-space: pre-wrap;
  font-family: inherit;
  background: var(--el-fill-color-light);
  padding: var(--ls-space-2);
  border-radius: 4px;
  margin: 0;
}
.actions {
  display: flex;
  gap: var(--ls-space-2);
  margin-top: var(--ls-space-4);
  align-items: center;
}
h4 {
  margin: var(--ls-space-4) 0 var(--ls-space-2);
}
</style>
