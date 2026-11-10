<template>
  <div data-testid="request-board">
    <div class="toolbar">
      <el-select v-model="status" placeholder="状态" clearable style="width: 140px" @change="load">
        <el-option v-for="(l, v) in STATUS" :key="v" :label="l" :value="v" />
      </el-select>
      <span class="grow" />
      <el-button type="primary" data-testid="btn-new-request" @click="openNew">登记需求</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" size="small" row-key="id" data-testid="request-table" @expand-change="onExpand">
      <el-table-column type="expand">
        <template #default="{ row }">
          <el-table :data="works[row.id] ?? []" size="small" :data-testid="`works-${row.id}`">
            <el-table-column label="工作" min-width="200">
              <template #default="{ row: w }">{{ w.code }} {{ w.title }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row: w }">{{ WORK_STATUS[w.status] ?? w.status }}</template>
            </el-table-column>
            <el-table-column label="班组 / 班次" width="200">
              <template #default="{ row: w }">{{ w.teamName ?? '—' }} {{ w.shiftDate ?? '' }} {{ w.shift ? shiftLabel(w.shift) : '' }}</template>
            </el-table-column>
            <el-table-column label="计划数量" width="110">
              <template #default="{ row: w }">{{ w.plannedQty ?? '' }} {{ w.unit ?? '' }}</template>
            </el-table-column>
          </el-table>
        </template>
      </el-table-column>
      <el-table-column label="需求" min-width="260">
        <template #default="{ row }">
          <b>{{ row.title }}</b>
          <div class="sub">{{ PERIOD[row.period] ?? row.period }} · {{ row.quantity ?? '' }} {{ row.unit ?? '' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="期望完成" prop="dueDate" width="120" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="STATUS_TYPE[row.status]" :data-testid="`req-status-${row.id}`">{{ STATUS[row.status] ?? row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="已转工作" prop="workCount" width="90" />
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT'" link type="primary" :data-testid="`btn-submit-${row.id}`" @click="act(row, 'submit')"
            >发布</el-button
          >
          <el-button
            v-if="['OPEN', 'CONVERTED'].includes(row.status)"
            link
            type="primary"
            :data-testid="`btn-convert-${row.id}`"
            @click="openConvert(row)"
            >转为工作</el-button
          >
          <el-button v-if="row.status === 'CONVERTED'" link :data-testid="`btn-close-${row.id}`" @click="act(row, 'close')">关闭</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="newVisible" title="登记生产需求" width="560px" append-to-body>
      <el-form label-width="100px">
        <el-form-item label="需求描述" required>
          <el-input v-model="form.title" type="textarea" :rows="2" placeholder="如：周三前完成 A 线清洗与 200 件组装" data-testid="req-title" />
        </el-form-item>
        <el-form-item label="需求周期">
          <el-radio-group v-model="form.period">
            <el-radio-button v-for="(l, v) in PERIOD" :key="v" :value="v">{{ l }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="数量 / 单位">
          <el-input-number v-model="form.quantity" :min="0" controls-position="right" style="width: 160px" />
          <el-input v-model="form.unit" placeholder="单位" style="width: 100px; margin-left: var(--ls-space-2)" />
        </el-form-item>
        <el-form-item label="期望完成">
          <el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" data-testid="req-due" />
        </el-form-item>
        <el-form-item label="补充说明"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="btn-save-request" @click="saveNew">保存并发布</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="convertVisible" :title="`转为工作：${current?.title ?? ''}`" width="900px" append-to-body>
      <p class="sub">已按描述拆出建议条目，可增删修改；指定班组与班次的工作会直接进入工作板对应班次。</p>
      <el-table :data="items" size="small" data-testid="convert-table">
        <el-table-column label="工作" min-width="200">
          <template #default="{ row, $index }"><el-input v-model="row.title" :data-testid="`convert-title-${$index}`" /></template>
        </el-table-column>
        <el-table-column label="数量" width="110">
          <template #default="{ row }"><el-input-number v-model="row.quantity" :min="0" :controls="false" style="width: 90px" /></template>
        </el-table-column>
        <el-table-column label="单位" width="80">
          <template #default="{ row }"><el-input v-model="row.unit" /></template>
        </el-table-column>
        <el-table-column label="班组" width="150">
          <template #default="{ row, $index }">
            <el-select v-model="row.teamId" clearable placeholder="班组" :data-testid="`convert-team-${$index}`">
              <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="日期" width="150">
          <template #default="{ row }"
            ><el-date-picker v-model="row.shiftDate" type="date" value-format="YYYY-MM-DD" style="width: 130px"
          /></template>
        </el-table-column>
        <el-table-column label="班次" width="110">
          <template #default="{ row }">
            <el-select v-model="row.shift" clearable placeholder="班次">
              <el-option v-for="s in SHIFTS" :key="s.value" :label="s.label" :value="s.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column width="60">
          <template #default="{ $index }"><el-button link type="danger" @click="items.splice($index, 1)">删除</el-button></template>
        </el-table-column>
      </el-table>
      <el-button class="mt" data-testid="btn-add-work" @click="items.push(blank())">添加一条</el-button>
      <template #footer>
        <el-button @click="convertVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!items.length" data-testid="btn-do-convert" @click="doConvert"
          >生成 {{ items.length }} 条工作</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { REQUEST, SHIFTS, WORK_STATUS, shiftLabel, suggestWorks, type BoardWork, type Team, type WorkDraft } from '@/api/lsmom/productionModel';
import type { ProductionApi } from '@/api/lsmom/production';

/** 13.3 生产需求板：登记文字需求，拆成一条或多条生产工作（可直接排到班组班次）。 */
const props = defineProps<{ api: ProductionApi }>();

const PERIOD: Record<string, string> = { MONTH: '月', WEEK: '周', DAY: '日', TEMP: '临时' };
const STATUS: Record<string, string> = { DRAFT: '草稿', OPEN: '待转工作', CONVERTED: '已转工作', CLOSED: '已关闭', CANCELLED: '已取消' };
const STATUS_TYPE: Record<string, any> = { DRAFT: 'info', OPEN: 'warning', CONVERTED: 'primary', CLOSED: 'success', CANCELLED: 'info' };

const rows = ref<Record<string, any>[]>([]);
const works = reactive<Record<number, BoardWork[]>>({});
const teams = ref<Team[]>([]);
const status = ref<string>();
const loading = ref(false);
const saving = ref(false);
const newVisible = ref(false);
const convertVisible = ref(false);
const current = ref<Record<string, any>>();
const items = ref<WorkDraft[]>([]);
const form = reactive({ title: '', period: 'WEEK', quantity: undefined as number | undefined, unit: '', dueDate: '', description: '' });

const blank = (): WorkDraft => ({ title: '' });

async function load() {
  loading.value = true;
  try {
    const page = await props.api.query(REQUEST, {
      filters: status.value ? [{ field: 'status', op: 'eq', value: status.value }] : [],
      sort: [{ field: 'id', dir: 'desc' }],
      page: 1,
      size: 100
    });
    rows.value = page.rows as Record<string, any>[];
  } finally {
    loading.value = false;
  }
}

async function onExpand(row: Record<string, any>) {
  works[row.id] = await props.api.board({ requestId: row.id });
}

function openNew() {
  Object.assign(form, { title: '', period: 'WEEK', quantity: undefined, unit: '', dueDate: '', description: '' });
  newVisible.value = true;
}

async function saveNew() {
  if (!form.title.trim()) return ElMessage.warning('请填写需求描述');
  saving.value = true;
  try {
    const body: Record<string, unknown> = { title: form.title.trim(), period: form.period };
    if (form.quantity !== undefined && form.quantity !== null) body.quantity = form.quantity;
    if (form.unit) body.unit = form.unit;
    if (form.dueDate) body.dueDate = form.dueDate;
    if (form.description) body.description = form.description;
    const r = await props.api.create(REQUEST, body);
    await props.api.op(`${REQUEST}.submit`, r.id, r.version);
    ElMessage.success('需求已发布');
    newVisible.value = false;
    await load();
  } finally {
    saving.value = false;
  }
}

async function act(row: Record<string, any>, action: string) {
  await props.api.op(`${REQUEST}.${action}`, row.id, row.version);
  await load();
}

function openConvert(row: Record<string, any>) {
  current.value = row;
  items.value = suggestWorks(String(row.title ?? ''));
  if (!items.value.length) items.value = [blank()];
  convertVisible.value = true;
}

async function doConvert() {
  const list = items.value.filter((i) => i.title.trim());
  if (!list.length) return ElMessage.warning('至少一条工作');
  saving.value = true;
  try {
    const payload = list.map((i) => {
      const o: Record<string, unknown> = { title: i.title.trim() };
      if (i.quantity) o.quantity = i.quantity;
      if (i.unit) o.unit = i.unit;
      if (i.teamId && i.shiftDate && i.shift) Object.assign(o, { teamId: i.teamId, shiftDate: i.shiftDate, shift: i.shift });
      return o;
    });
    const cur = current.value!;
    await props.api.op(`${REQUEST}.convert`, cur.id, cur.version, { works: payload });
    ElMessage.success(`已生成 ${payload.length} 条工作`);
    convertVisible.value = false;
    await load();
    works[cur.id] = await props.api.board({ requestId: cur.id });
  } finally {
    saving.value = false;
  }
}

onMounted(async () => {
  await load();
  teams.value = (await props.api.teams().catch(() => [] as Team[])).filter((t) => t.inScope !== false);
});
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
.mt {
  margin-top: var(--ls-space-2);
}
</style>
