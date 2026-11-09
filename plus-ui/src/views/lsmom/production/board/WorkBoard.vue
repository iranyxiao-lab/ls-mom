<template>
  <div data-testid="work-board">
    <div class="toolbar">
      <el-date-picker
        v-model="day"
        type="date"
        value-format="YYYY-MM-DD"
        :clearable="false"
        style="width: 150px"
        data-testid="board-day"
        @change="load"
      />
      <el-select v-model="teamId" placeholder="全部班组" clearable style="width: 160px" data-testid="board-team" @change="load">
        <el-option v-for="t in teams" :key="t.id" :label="t.parentName ? `${t.parentName} / ${t.name}` : t.name" :value="t.id" />
      </el-select>
      <el-select v-model="status" placeholder="全部状态" clearable style="width: 120px" data-testid="board-status" @change="load">
        <el-option v-for="(l, v) in WORK_STATUS" :key="v" :label="l" :value="v" />
      </el-select>
      <el-select v-model="risk" placeholder="风险" clearable style="width: 110px" data-testid="board-risk" @change="load">
        <el-option v-for="(l, v) in RISK" :key="v" :label="l" :value="v" />
      </el-select>
      <span class="grow" />
      <el-radio-group v-model="mode" size="small">
        <el-radio-button value="kanban" data-testid="board-mode-kanban">班次看板</el-radio-button>
        <el-radio-button value="list" data-testid="board-mode-list">列表</el-radio-button>
      </el-radio-group>
      <el-button size="small" @click="load">刷新</el-button>
    </div>

    <div v-if="mode === 'kanban'" v-loading="loading" class="kanban">
      <div
        v-for="col in columns"
        :key="col.key"
        class="col"
        :class="{ over: overCol === col.key }"
        :data-testid="`col-${col.key}`"
        @dragover.prevent="overCol = col.key"
        @dragleave="overCol = ''"
        @drop.prevent="onDrop(col.key)"
      >
        <div class="col-head">
          <b>{{ col.label }}</b> <span class="sub">{{ col.time }}</span>
          <el-tag size="small" round class="ml">{{ col.items.length }}</el-tag>
        </div>
        <div
          v-for="w in col.items"
          :key="w.id"
          class="card"
          :class="{ risk: w.risks.includes('OVERDUE') || w.risks.includes('BLOCKED') }"
          draggable="true"
          :data-testid="`card-${w.id}`"
          @dragstart="dragging = w"
          @dragend="dragging = undefined"
        >
          <div class="card-title">{{ w.title }}</div>
          <div class="sub">{{ w.code }} · {{ w.teamName ?? '未排班' }}</div>
          <div class="card-tags">
            <el-tag size="small" :type="STATUS_TYPE[w.status]">{{ WORK_STATUS[w.status] ?? w.status }}</el-tag>
            <el-tag v-for="r in w.risks.filter((x) => x !== 'UNSCHEDULED')" :key="r" size="small" type="danger">{{ RISK[r] }}</el-tag>
            <span v-if="w.progress != null" class="sub">{{ Number(w.progress) }}%</span>
          </div>
          <div v-if="w.assignees.length" class="sub">👤 {{ w.assignees.map((a) => a.name).join('、') }}</div>
        </div>
      </div>
    </div>

    <el-table v-else v-loading="loading" :data="rows" size="small" data-testid="board-table">
      <el-table-column label="工作" min-width="220">
        <template #default="{ row }">
          <b>{{ row.title }}</b>
          <div class="sub">
            {{ row.code }}<template v-if="row.line"> · {{ row.line }}</template>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }"
          ><el-tag size="small" :type="STATUS_TYPE[row.status]">{{ WORK_STATUS[row.status] ?? row.status }}</el-tag></template
        >
      </el-table-column>
      <el-table-column label="班组 / 班次" width="200">
        <template #default="{ row }">{{ row.teamName ?? '—' }} {{ row.shiftDate ?? '' }} {{ row.shift ? shiftLabel(row.shift) : '' }}</template>
      </el-table-column>
      <el-table-column label="负责人" min-width="120">
        <template #default="{ row }">{{ row.assignees.map((a: any) => a.name).join('、') || '—' }}</template>
      </el-table-column>
      <el-table-column label="进度" width="80">
        <template #default="{ row }">{{ row.progress != null ? Number(row.progress) + '%' : '—' }}</template>
      </el-table-column>
      <el-table-column label="计划数量" width="100">
        <template #default="{ row }">{{ row.plannedQty ?? '' }} {{ row.unit ?? '' }}</template>
      </el-table-column>
      <el-table-column label="风险" width="140">
        <template #default="{ row }">
          <el-tag v-for="r in row.risks" :key="r" size="small" :type="r === 'UNSCHEDULED' ? 'info' : 'danger'" class="mr">{{ RISK[r] }}</el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { RISK, SHIFTS, SHIFT_PLAN, WORK_STATUS, shiftLabel, type BoardWork, type Team } from '@/api/lsmom/productionModel';
import type { ProductionApi } from '@/api/lsmom/production';

/** 13.3 工作板：按状态 / 班组 / 日期 / 风险查看生产工作；班次看板中拖动卡片即调整班次（冲突按 Profile 提示或阻断）。 */
const props = defineProps<{ api: ProductionApi; initialDay?: string }>();

const STATUS_TYPE: Record<string, any> = {
  DRAFT: 'info',
  READY: 'info',
  ASSIGNED: 'primary',
  IN_PROGRESS: 'warning',
  BLOCKED: 'danger',
  PENDING_CONFIRM: 'warning',
  DONE: 'success',
  CANCELLED: 'info'
};

const today = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};
const day = ref(props.initialDay ?? today());
const teamId = ref<number>();
const status = ref<string>();
const risk = ref<string>();
const mode = ref<'kanban' | 'list'>('kanban');
const rows = ref<BoardWork[]>([]);
const teams = ref<Team[]>([]);
const loading = ref(false);
const dragging = ref<BoardWork>();
const overCol = ref('');

const columns = computed(() => [
  ...SHIFTS.map((s) => ({ key: s.value as string, label: s.label, time: s.time, items: rows.value.filter((w) => w.shift === s.value) })),
  { key: 'NONE', label: '未排班', time: '计划在当天结束', items: rows.value.filter((w) => !w.shift) }
]);

async function load() {
  loading.value = true;
  try {
    rows.value = await props.api.board({ from: day.value, to: day.value, teamId: teamId.value, status: status.value, risk: risk.value });
  } finally {
    loading.value = false;
  }
}

async function onDrop(target: string) {
  overCol.value = '';
  const w = dragging.value;
  dragging.value = undefined;
  if (!w || target === 'NONE' || w.shift === target) return;
  try {
    let r;
    if (w.planId) {
      r = await props.api.op(`${SHIFT_PLAN}.move`, w.planId, w.planVersion, { shift: target, shiftDate: day.value });
    } else {
      const team = teamId.value ?? teams.value[0]?.id;
      if (!team) return ElMessage.warning('请先选择班组再排班');
      const c = await props.api.create(SHIFT_PLAN, { workItem: w.id, team, shiftDate: day.value, shift: target });
      r = await props.api.op(`${SHIFT_PLAN}.activate`, c.id, c.version);
    }
    if (r.warnings?.length) ElMessage.warning(r.warnings.join('；'));
    else ElMessage.success(`已调整到${shiftLabel(target)}`);
  } finally {
    await load();
  }
}

onMounted(async () => {
  teams.value = (await props.api.teams().catch(() => [] as Team[])).filter((t) => t.inScope !== false);
  await load();
});

defineExpose({ load, onDrop, dragging });
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
  align-items: center;
}
.grow {
  flex: 1;
}
.kanban {
  display: grid;
  grid-template-columns: repeat(4, minmax(220px, 1fr));
  gap: 10px;
  overflow-x: auto;
}
.col {
  background: var(--el-fill-color-light);
  border-radius: 6px;
  padding: 8px;
  min-height: 300px;
  border: 2px dashed transparent;
}
.col.over {
  border-color: var(--el-color-primary);
}
.col-head {
  margin-bottom: 8px;
}
.card {
  background: var(--el-bg-color);
  border-radius: 6px;
  padding: 8px;
  margin-bottom: 8px;
  cursor: grab;
  box-shadow: var(--el-box-shadow-lighter);
  border-left: 3px solid var(--el-color-primary-light-5);
}
.card.risk {
  border-left-color: var(--el-color-danger);
}
.card-title {
  font-weight: 600;
}
.card-tags {
  display: flex;
  gap: 4px;
  align-items: center;
  margin: 4px 0;
  flex-wrap: wrap;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.ml {
  margin-left: 4px;
}
.mr {
  margin-right: 4px;
}
</style>
