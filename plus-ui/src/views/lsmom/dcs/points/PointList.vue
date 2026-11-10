<template>
  <div class="dcs-points">
    <div class="bar">
      <el-input
        v-model="keyword"
        size="small"
        placeholder="测点编码 / 名称"
        clearable
        style="width: 220px"
        data-testid="point-keyword"
        @change="load"
      />
      <el-button size="small" @click="load">查询</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" size="small" data-testid="point-table" @row-click="(r: DcsPoint) => open(r)">
      <el-table-column label="编码" width="140">
        <template #default="{ row }"
          ><span :data-testid="`point-row-${row.code}`">{{ row.code }}</span></template
        >
      </el-table-column>
      <el-table-column label="名称" prop="name" min-width="140" />
      <el-table-column label="设备" min-width="150">
        <template #default="{ row }">{{ assetLabel(row.asset_id) }}</template>
      </el-table-column>
      <el-table-column label="组织 / 工艺" min-width="140">
        <template #default="{ row }"
          >{{ row.org_name || '—' }}<span v-if="row.process_name"> · {{ row.process_name }}</span></template
        >
      </el-table-column>
      <el-table-column label="处置策略" width="130">
        <template #default="{ row }">{{ POLICY[row.alarm_policy] }}</template>
      </el-table-column>
      <el-table-column label="最近快照" min-width="160">
        <template #default="{ row }">
          <span v-if="row.last_bucket"
            >{{ num(row.last_avg) }} {{ row.unit }}<span class="sub"> · {{ fmt(row.last_bucket) }}</span></span
          >
          <span v-else class="sub">暂无</span>
        </template>
      </el-table-column>
      <el-table-column label="待处置报警" prop="open_alarms" width="96" />
    </el-table>

    <el-drawer v-model="visible" size="720px" :title="cur ? `${cur.code} ${cur.name}` : ''" append-to-body>
      <template v-if="cur">
        <div class="sub">
          来源 {{ cur.source_system }} · 量程 {{ num(cur.range_low) }} ~ {{ num(cur.range_high) }} {{ cur.unit }} · 最近 6 小时
          {{ snaps.length }} 个快照
        </div>
        <TrendChart :snapshots="snaps" :label="`${cur.code} 最近 6 小时`" />
        <template v-if="canManage">
          <h4>映射与处置策略</h4>
          <el-form label-width="90px" size="small">
            <el-form-item label="设备">
              <el-select
                v-model="form.asset_id"
                filterable
                remote
                clearable
                :remote-method="searchAssets"
                placeholder="输入设备编码搜索"
                data-testid="point-asset"
                style="width: 100%"
              >
                <el-option v-for="a in assetOptions" :key="a.id" :value="a.id" :label="`${a.assetCode} ${a.name}`" />
              </el-select>
            </el-form-item>
            <el-form-item label="工艺"><el-input v-model="form.process_name" data-testid="point-process" /></el-form-item>
            <el-form-item label="处置策略">
              <el-radio-group v-model="form.alarm_policy">
                <el-radio-button v-for="(v, k) in POLICY" :key="k" :value="k" :data-testid="`point-policy-${k}`">{{ v }}</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
          </el-form>
          <el-button type="primary" size="small" data-testid="btn-point-save" @click="save">保存</el-button>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { DcsApi } from '@/api/lsmom/dcs';
import { POLICY, num, type DcsPoint, type Snapshot } from '@/api/lsmom/dcsModel';
import TrendChart from '../TrendChart.vue';

const props = withDefaults(defineProps<{ api: Pick<DcsApi, 'points' | 'savePoint' | 'snapshots' | 'assets'>; canManage?: boolean }>(), {
  canManage: false
});

const rows = ref<DcsPoint[]>([]);
const loading = ref(false);
const keyword = ref('');
const visible = ref(false);
const cur = ref<DcsPoint>();
const snaps = ref<Snapshot[]>([]);
const assetOptions = ref<Record<string, any>[]>([]);
const assetNames = ref<Record<number, string>>({});
const form = reactive<Partial<DcsPoint>>({});

async function load() {
  loading.value = true;
  try {
    rows.value = await props.api.points({ keyword: keyword.value || undefined });
    if (rows.value.some((r) => r.asset_id)) {
      const list = await props.api.assets().catch(() => []);
      list.forEach((a) => (assetNames.value[a.id] = `${a.assetCode} ${a.name}`));
    }
  } finally {
    loading.value = false;
  }
}

async function open(p: DcsPoint) {
  cur.value = p;
  Object.assign(form, { ...p });
  snaps.value = await props.api.snapshots(p.code).catch(() => []);
  assetOptions.value = await props.api.assets().catch(() => []);
  visible.value = true;
}

async function searchAssets(q: string) {
  assetOptions.value = await props.api.assets(q || undefined).catch(() => []);
}

async function save() {
  const saved = await props.api.savePoint({ ...cur.value, ...form });
  ElMessage.success('测点已保存');
  visible.value = false;
  await load();
  return saved;
}

const assetLabel = (id?: number | null) => (id ? (assetNames.value[id] ?? `#${id}`) : '—');
const fmt = (v?: string) => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '—');

onMounted(load);
</script>

<style scoped>
.bar {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
