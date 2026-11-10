<template>
  <div class="desk" data-testid="dispatch-desk">
    <div class="left">
      <div class="toolbar">
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" style="width: 240px" @change="load" />
        <el-checkbox v-model="pendingOnly" @change="load">只看待分派</el-checkbox>
      </div>
      <el-table v-loading="loading" :data="shown" size="small" highlight-current-row data-testid="dispatch-table" @row-click="select">
        <el-table-column label="工作" min-width="200">
          <template #default="{ row }">
            <span :data-testid="`dispatch-row-${row.id}`">{{ row.title }}</span>
            <div class="sub">{{ row.code }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">{{ WORK_STATUS[row.status] ?? row.status }}</template>
        </el-table-column>
        <el-table-column label="班次" width="150">
          <template #default="{ row }">{{ row.teamName ?? '未排班' }} {{ row.shift ? shiftLabel(row.shift) : '' }}</template>
        </el-table-column>
        <el-table-column label="负责人" width="110">
          <template #default="{ row }">{{ row.assignees.map((a: any) => a.name).join('、') || '—' }}</template>
        </el-table-column>
      </el-table>
    </div>

    <el-card v-if="cur" shadow="never" class="right" data-testid="dispatch-panel">
      <template #header>
        <b>{{ cur.title }}</b> <span class="sub">{{ cur.code }} · {{ WORK_STATUS[cur.status] }}</span>
      </template>
      <h4>班次安排</h4>
      <el-form label-width="60px" size="small">
        <el-form-item label="班组">
          <el-select v-model="plan.teamId" placeholder="班组" data-testid="dispatch-team">
            <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期"
          ><el-date-picker v-model="plan.shiftDate" type="date" value-format="YYYY-MM-DD" data-testid="dispatch-date"
        /></el-form-item>
        <el-form-item label="班次">
          <el-radio-group v-model="plan.shift" data-testid="dispatch-shift">
            <el-radio-button v-for="s in SHIFTS" :key="s.value" :value="s.value" :data-testid="`dispatch-shift-${s.value}`">{{
              s.label
            }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-button
          type="primary"
          size="small"
          :disabled="!plan.teamId || !plan.shiftDate || !plan.shift"
          data-testid="btn-save-plan"
          @click="savePlan"
          >{{ cur.planId ? '调整班次' : '排班' }}</el-button
        >
      </el-form>
      <el-divider />
      <h4>分派</h4>
      <el-select
        v-model="parties"
        multiple
        filterable
        remote
        :remote-method="searchParty"
        ref="partySelect"
        placeholder="搜索人员"
        style="width: 100%"
        data-testid="dispatch-party"
      >
        <el-option v-for="p in partyOptions" :key="p.id" :label="p.orgName ? `${p.name}（${p.orgName}）` : p.name" :value="p.id" />
      </el-select>
      <el-button class="mt" type="primary" size="small" :disabled="!parties.length || !canAssign" data-testid="btn-assign" @click="assign"
        >分派</el-button
      >
      <div v-if="!canAssign" class="sub mt">当前状态不能分派</div>
      <el-alert v-for="(w, i) in warnings" :key="i" :title="w" type="warning" :closable="false" class="mt" data-testid="dispatch-warning" />
    </el-card>
    <el-empty v-else class="right" description="选择左侧一条工作进行排班与分派" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { registerFixAction } from '@/utils/actionableError';
import { ElMessage } from 'element-plus';
import { SHIFTS, SHIFT_PLAN, WORK_STATUS, shiftLabel, type BoardWork, type Team } from '@/api/lsmom/productionModel';
import type { ProductionApi } from '@/api/lsmom/production';
import { useUndoable } from '@/utils/undoable';

/** 13.3 分派台：给生产工作排班（班组、日期、班次）并分派到人；资源冲突按约束 Profile 提示或阻断。 */
const props = defineProps<{ api: ProductionApi }>();
const undoable = useUndoable();

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const now = new Date();
const range = ref<[string, string] | null>([iso(new Date(now.getTime() - 7 * 864e5)), iso(new Date(now.getTime() + 14 * 864e5))]);
const pendingOnly = ref(true);
const rows = ref<BoardWork[]>([]);
const teams = ref<Team[]>([]);
const loading = ref(false);
const cur = ref<BoardWork>();
const plan = reactive({ teamId: undefined as number | undefined, shiftDate: '', shift: '' });
const parties = ref<number[]>([]);
const partyOptions = ref<{ id: number; name: string; orgName?: string }[]>([]);
const warnings = ref<string[]>([]);

const shown = computed(() =>
  pendingOnly.value ? rows.value.filter((w) => ['DRAFT', 'READY', 'ASSIGNED'].includes(w.status) || w.risks.includes('UNSCHEDULED')) : rows.value
);
const canAssign = computed(() => !!cur.value && ['DRAFT', 'READY', 'ASSIGNED'].includes(cur.value.status));

async function load() {
  loading.value = true;
  try {
    rows.value = await props.api.board({ from: range.value?.[0], to: range.value?.[1] });
    if (cur.value) cur.value = rows.value.find((w) => w.id === cur.value!.id) ?? cur.value;
  } finally {
    loading.value = false;
  }
}

function select(w: BoardWork) {
  cur.value = w;
  warnings.value = [];
  plan.teamId = w.teamId;
  plan.shiftDate = w.shiftDate ?? '';
  plan.shift = w.shift ?? '';
  parties.value = w.assignees.filter((a) => a.type === 'PARTY').map((a) => Number(a.ref));
  partyOptions.value = w.assignees.filter((a) => a.type === 'PARTY').map((a) => ({ id: Number(a.ref), name: a.name }));
}

async function searchParty(q: string) {
  partyOptions.value = await props.api.parties(q);
}

async function savePlan() {
  const w = cur.value!;
  if (w.planId) {
    // 调整班次可撤销：5 秒内点撤销，服务端以补偿 Operation 恢复原班组、日期、班次
    const r = await undoable.run({
      done: '班次已调整',
      exec: () => props.api.op(`${SHIFT_PLAN}.move`, w.planId!, w.planVersion, { teamId: plan.teamId, shiftDate: plan.shiftDate, shift: plan.shift }),
      compensate: (op, id, version, input) => props.api.op(op, id, version, input),
      after: async () => {
        await load();
        if (cur.value) select(cur.value);
      }
    });
    warnings.value = r?.warnings ?? [];
    await load();
    return;
  }
  const r = await props.api
    .create(SHIFT_PLAN, { workItem: w.id, team: plan.teamId, shiftDate: plan.shiftDate, shift: plan.shift })
    .then((c) => props.api.op(`${SHIFT_PLAN}.activate`, c.id, c.version));
  warnings.value = r.warnings ?? [];
  if (!warnings.value.length) ElMessage.success('班次已安排');
  await load();
}

async function assign() {
  let w = cur.value!;
  if (w.status === 'DRAFT') {
    await props.api.op('ls.core.Work.ready', w.id, w.version);
    await load();
    w = cur.value!;
  }
  const r = await props.api.op('ls.core.Work.assign', w.id, w.version, {
    targets: parties.value.map((p) => ({ resourceType: 'PARTY', resourceRef: String(p) }))
  });
  warnings.value = r.warnings ?? [];
  if (!warnings.value.length) ElMessage.success('已分派');
  await load();
}

// 分派被拒（如资质过期）时错误提示里的「改派他人」：去掉该人员，光标回到人员选择
const partySelect = ref<{ focus: () => void }>();
const offFix = registerFixAction('reassign', async (fix) => {
  const id = Number(fix.partyId);
  parties.value = parties.value.filter((p) => p !== id);
  await nextTick();
  partySelect.value?.focus();
});
onBeforeUnmount(offFix);

onMounted(async () => {
  teams.value = (await props.api.teams().catch(() => [] as Team[])).filter((t) => t.inScope !== false);
  await load();
});
</script>

<style scoped>
.desk {
  display: flex;
  gap: var(--ls-space-3);
  align-items: flex-start;
}
.left {
  flex: 3;
  min-width: 0;
}
.right {
  flex: 2;
  min-width: 320px;
}
.toolbar {
  display: flex;
  gap: var(--ls-space-3);
  margin-bottom: var(--ls-space-3);
  align-items: center;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.mt {
  margin-top: var(--ls-space-2);
}
h4 {
  margin: var(--ls-space-1) 0 var(--ls-space-2);
}
</style>
