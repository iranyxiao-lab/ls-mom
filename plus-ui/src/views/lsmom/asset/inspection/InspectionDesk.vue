<template>
  <div data-testid="inspection-desk">
    <el-tabs v-model="tab" @tab-change="onTab">
      <el-tab-pane label="我的点检" name="mine">
        <div class="toolbar">
          <el-radio-group v-model="mineStatus" size="small" @change="loadMine">
            <el-radio-button value="DRAFT" data-testid="ins-filter-draft">待点检</el-radio-button>
            <el-radio-button value="SUBMITTED" data-testid="ins-filter-done">已提交</el-radio-button>
          </el-radio-group>
        </div>
        <el-empty v-if="!mine.length" description="没有分给你的点检" :image-size="60" />
        <div v-for="m in mine" :key="m.id" class="ins-card" :data-testid="`ins-${m.id}`" @click="openRecord(m.id)">
          <div class="title">{{ m.asset_code }} {{ m.asset_name }}</div>
          <div class="sub">
            应检 {{ m.due_date }} · {{ m.location || '—' }}
            <el-tag v-if="m.result" size="small" :type="m.result === 'ABNORMAL' ? 'danger' : 'success'">{{
              m.result === 'ABNORMAL' ? `异常 ${m.abnormal_count} 项` : '正常'
            }}</el-tag>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane label="点检计划" name="plans">
        <div class="toolbar">
          <span class="grow" />
          <el-button type="primary" data-testid="btn-new-plan" @click="openPlan">新建点检计划</el-button>
        </div>
        <el-table :data="plans" size="small" data-testid="plan-table">
          <el-table-column label="计划" min-width="160">
            <template #default="{ row }"
              ><span :data-testid="`plan-row-${row.id}`">{{ row.name }}</span></template
            >
          </el-table-column>
          <el-table-column label="设备" min-width="140"
            ><template #default="{ row }">{{ refLabel(row.asset) }}</template></el-table-column
          >
          <el-table-column label="周期" width="70"
            ><template #default="{ row }">{{ CYCLES[row.cycle] }}</template></el-table-column
          >
          <el-table-column label="责任人" width="100"
            ><template #default="{ row }">{{ refLabel(row.responsible) }}</template></el-table-column
          >
          <el-table-column label="下次点检" prop="nextDue" width="110" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }"
              ><el-tag size="small" :data-testid="`plan-status-${row.id}`">{{ PLAN_STATUS[row.status] }}</el-tag></template
            >
          </el-table-column>
          <el-table-column label="操作" width="200">
            <template #default="{ row }">
              <el-button v-if="row.status === 'ACTIVE'" link type="primary" :data-testid="`btn-generate-${row.id}`" @click="planOp(row, 'generate')"
                >立即生成</el-button
              >
              <el-button v-if="row.status === 'ACTIVE'" link @click="planOp(row, 'pause')">暂停</el-button>
              <el-button v-else link type="primary" :data-testid="`btn-activate-${row.id}`" @click="planOp(row, 'activate')">启用</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="recordVisible" size="640px" :title="record ? `点检：${refLabel(record.asset)}` : ''" append-to-body>
      <template v-if="record">
        <div class="sub">应检 {{ record.dueDate }} · {{ record.status === 'DRAFT' ? '待点检' : '已提交' }}</div>
        <el-table :data="items" size="small" data-testid="ins-items">
          <el-table-column label="检查项" min-width="140">
            <template #default="{ row }">
              {{ row.name }}
              <div class="sub">{{ row.standard }}</div>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="220">
            <template #default="{ row }">
              <template v-if="editable">
                <el-input-number
                  v-if="row.valueType === 'NUMBER'"
                  v-model="row.valueNum"
                  size="small"
                  :controls="false"
                  style="width: 110px"
                  :class="{ bad: outOfRange(row.valueNum, row.lowerLimit, row.upperLimit) }"
                  :data-testid="`ins-value-${row.itemCode}`"
                />
                <span v-if="row.valueType === 'NUMBER'" class="sub"> {{ row.unit }}</span>
                <el-radio-group v-else v-model="row.outcome" size="small">
                  <el-radio-button value="OK" :data-testid="`ins-ok-${row.itemCode}`">正常</el-radio-button>
                  <el-radio-button value="ABNORMAL" :data-testid="`ins-bad-${row.itemCode}`">异常</el-radio-button>
                </el-radio-group>
              </template>
              <template v-else>
                <span v-if="row.valueNum != null">{{ row.valueNum }} {{ row.unit }} </span>
                <el-tag size="small" :type="row.outcome === 'ABNORMAL' ? 'danger' : 'success'" :data-testid="`ins-outcome-${row.itemCode}`">{{
                  row.outcome === 'ABNORMAL' ? '异常' : '正常'
                }}</el-tag>
                <el-tag v-if="row.defect" size="small" type="warning">已生成缺陷</el-tag>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="140">
            <template #default="{ row }">
              <el-input v-if="editable" v-model="row.note" size="small" :data-testid="`ins-note-${row.itemCode}`" />
              <span v-else>{{ row.note }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-alert v-if="abnormalHint" type="warning" :closable="false" show-icon class="hint" data-testid="ins-abnormal-hint">{{
          abnormalHint
        }}</el-alert>
        <div class="footer">
          <el-button v-if="editable" type="primary" data-testid="btn-ins-submit" @click="submit">提交点检</el-button>
          <el-button v-if="canRaise" type="danger" data-testid="btn-raise-defects" @click="raiseDefects">一键生成缺陷草稿</el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="planVisible" title="新建点检计划" width="760px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="计划名称" required><el-input v-model="plan.name" data-testid="plan-name" /></el-form-item>
        <el-form-item label="设备" required>
          <el-select v-model="plan.asset" filterable data-testid="plan-asset">
            <el-option v-for="a in assets" :key="a.id" :label="`${a.assetCode} ${a.name}`" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="周期">
          <el-radio-group v-model="plan.cycle">
            <el-radio-button v-for="(v, k) in CYCLES" :key="k" :value="k">{{ v }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="责任人" required>
          <el-select v-model="plan.responsible" filterable remote :remote-method="searchParties" data-testid="plan-responsible">
            <el-option v-for="p in parties" :key="p.id" :label="p.orgName ? `${p.name}（${p.orgName}）` : p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="首次点检"
          ><el-date-picker v-model="plan.nextDue" type="date" value-format="YYYY-MM-DD" data-testid="plan-due"
        /></el-form-item>
        <el-form-item label="检查项">
          <el-table :data="plan.items" size="small" data-testid="plan-items">
            <el-table-column label="编码" width="90"
              ><template #default="{ row, $index }"><el-input v-model="row.itemCode" size="small" :data-testid="`pi-code-${$index}`" /></template
            ></el-table-column>
            <el-table-column label="检查项" min-width="110"
              ><template #default="{ row, $index }"><el-input v-model="row.name" size="small" :data-testid="`pi-name-${$index}`" /></template
            ></el-table-column>
            <el-table-column label="类型" width="100">
              <template #default="{ row, $index }">
                <el-select v-model="row.valueType" size="small" :data-testid="`pi-type-${$index}`">
                  <el-option label="判定" value="CHECK" /><el-option label="读数" value="NUMBER" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="单位" width="64"
              ><template #default="{ row }"><el-input v-model="row.unit" size="small" /></template
            ></el-table-column>
            <el-table-column label="上限" width="80">
              <template #default="{ row, $index }"
                ><el-input-number v-model="row.upperLimit" size="small" :controls="false" style="width: 64px" :data-testid="`pi-upper-${$index}`"
              /></template>
            </el-table-column>
            <el-table-column label="标准" min-width="100"
              ><template #default="{ row }"><el-input v-model="row.standard" size="small" /></template
            ></el-table-column>
            <el-table-column width="44"
              ><template #default="{ $index }"
                ><el-button link type="danger" @click="plan.items.splice($index, 1)">删</el-button></template
              ></el-table-column
            >
          </el-table>
          <el-button size="small" data-testid="btn-add-item" @click="addItem">添加检查项</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planVisible = false">取消</el-button>
        <el-button type="primary" data-testid="btn-save-plan" @click="savePlan">保存并启用</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { AssetApi } from '@/api/lsmom/asset';
import { ASSET, CYCLES, PLAN, PLAN_STATUS, RECORD, outOfRange, refLabel, type MyInspection } from '@/api/lsmom/assetModel';

/** 点检：我的点检（逐项填写、超限自动判异常、异常项一键生成缺陷草稿）与点检计划（周期、检查项、责任人）。 */
const props = defineProps<{ api: AssetApi; initialTab?: string }>();

const tab = ref(props.initialTab ?? 'mine');
const mineStatus = ref('DRAFT');
const mine = ref<MyInspection[]>([]);
const plans = ref<Record<string, any>[]>([]);
const assets = ref<Record<string, any>[]>([]);
const parties = ref<{ id: number; name: string; orgName?: string }[]>([]);

const recordVisible = ref(false);
const record = ref<Record<string, any>>();
const items = ref<Record<string, any>[]>([]);
const abnormalHint = ref('');
const editable = computed(() => record.value?.status === 'DRAFT');
const canRaise = computed(() => record.value?.status === 'SUBMITTED' && items.value.some((i) => i.outcome === 'ABNORMAL' && !i.defect));

const planVisible = ref(false);
const blankItem = () => ({ itemCode: '', name: '', valueType: 'CHECK', unit: '', upperLimit: undefined as number | undefined, standard: '' });
const plan = reactive<Record<string, any>>({ name: '', asset: undefined, cycle: 'DAILY', responsible: undefined, nextDue: '', items: [blankItem()] });

async function loadMine() {
  mine.value = await props.api.myInspections(mineStatus.value).catch(() => []);
}

async function loadPlans() {
  const page = await props.api.query(PLAN, {
    filters: [],
    sort: [{ field: 'nextDue', dir: 'asc' }],
    page: 1,
    size: 200,
    expand: ['asset', 'responsible']
  });
  plans.value = page.rows as Record<string, any>[];
}

function onTab(name: string | number) {
  if (name === 'plans') loadPlans();
  else loadMine();
}

async function openRecord(id: number) {
  const r = await props.api.get(RECORD, id);
  record.value = r;
  items.value = ((r.inspectionRecordItem ?? []) as Record<string, any>[]).map((i) => ({
    ...i,
    valueNum: i.valueNum == null ? undefined : Number(i.valueNum)
  }));
  abnormalHint.value = '';
  recordVisible.value = true;
}

async function submit() {
  const r = record.value;
  if (!r) return;
  const payload = items.value.map((i) => ({
    itemCode: i.itemCode,
    valueNum: i.valueType === 'NUMBER' ? (i.valueNum ?? null) : null,
    outcome: i.valueType === 'NUMBER' ? null : (i.outcome ?? null),
    note: i.note || null
  }));
  const res = await props.api.op(`${RECORD}.submit`, r.id, r.version, { items: payload });
  await openRecord(r.id);
  if (res.warnings?.length) {
    abnormalHint.value = res.warnings.join('；');
  } else {
    ElMessage.success('点检已提交，全部正常');
  }
  await loadMine();
}

async function raiseDefects() {
  const r = record.value;
  if (!r) return;
  const res = await props.api.op(`${RECORD}.raiseDefects`, r.id, r.version);
  const n = (res.data?.defects ?? []).length;
  ElMessage.success(`已生成 ${n} 条缺陷草稿，请到「缺陷与维修」补充后登记`);
  abnormalHint.value = '';
  await openRecord(r.id);
}

async function searchParties(q: string) {
  parties.value = await props.api.parties(q).catch(() => []);
}

async function openPlan() {
  const page = await props.api.query(ASSET, {
    filters: [{ field: 'status', op: 'ne', value: 'SCRAPPED' }],
    sort: [{ field: 'assetCode', dir: 'asc' }],
    page: 1,
    size: 500
  });
  assets.value = page.rows as Record<string, any>[];
  if (!parties.value.length) await searchParties('');
  if (!plan.nextDue) plan.nextDue = new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10);
  planVisible.value = true;
}

function addItem() {
  plan.items.push(blankItem());
}

async function savePlan() {
  const lines = (plan.items as Record<string, any>[]).filter((i) => i.itemCode && i.name);
  if (!plan.name || !plan.asset || !plan.responsible || !lines.length) return ElMessage.warning('请填写计划名称、设备、责任人和至少一个检查项');
  const asset = assets.value.find((a) => a.id === plan.asset);
  const r = await props.api.create(PLAN, {
    name: plan.name,
    asset: plan.asset,
    cycle: plan.cycle,
    responsible: plan.responsible,
    nextDue: plan.nextDue,
    orgId: asset?.orgId,
    inspectionPlanItem: lines.map((i) => {
      const l: Record<string, unknown> = { itemCode: i.itemCode, name: i.name, valueType: i.valueType, standard: i.standard || undefined };
      if (i.valueType === 'NUMBER') Object.assign(l, { unit: i.unit || undefined, upperLimit: i.upperLimit ?? undefined });
      return l;
    })
  });
  await props.api.op(`${PLAN}.activate`, r.id, r.version);
  ElMessage.success('点检计划已启用');
  planVisible.value = false;
  Object.assign(plan, { name: '', asset: undefined, responsible: undefined, items: [blankItem()] });
  await loadPlans();
}

async function planOp(row: Record<string, any>, action: string) {
  const res = await props.api.op(`${PLAN}.${action}`, row.id, row.version);
  if (res.warnings?.length) ElMessage.warning(res.warnings.join('；'));
  else ElMessage.success(action === 'generate' ? `已生成点检工作 ${res.data?.work ?? ''}` : '已更新');
  await loadPlans();
}

onMounted(() => (tab.value === 'plans' ? loadPlans() : loadMine()));
defineExpose({ openRecord, loadMine, loadPlans });
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
.ins-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: var(--ls-space-3) var(--ls-space-3);
  margin-bottom: var(--ls-space-2);
  cursor: pointer;
}
.ins-card .title {
  font-weight: 600;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.hint {
  margin-top: var(--ls-space-3);
}
.footer {
  margin-top: var(--ls-space-3);
  display: flex;
  gap: var(--ls-space-2);
}
:deep(.bad .el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}
</style>
