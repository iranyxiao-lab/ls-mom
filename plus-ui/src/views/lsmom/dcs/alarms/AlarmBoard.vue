<template>
  <div class="dcs-alarms">
    <div class="bar">
      <el-radio-group v-model="filter" size="small" data-testid="alarm-filter" @change="load">
        <el-radio-button value="OPEN" data-testid="alarm-filter-open">待处置</el-radio-button>
        <el-radio-button value="ALL" data-testid="alarm-filter-all">全部</el-radio-button>
        <el-radio-button v-for="(v, k) in ALARM_STATUS" :key="k" :value="k">{{ v }}</el-radio-button>
      </el-radio-group>
      <el-button size="small" data-testid="btn-alarm-refresh" @click="load">刷新</el-button>
    </div>
    <el-alert type="info" :closable="false" show-icon class="hint" title="只读集成：报警来自 DCS / Historian，系统不会向 DCS 写入任何值。" />

    <el-table v-loading="loading" :data="rows" size="small" data-testid="alarm-table" @row-click="(r: DcsAlarm) => open(r.id)">
      <el-table-column label="最近发生" width="160">
        <template #default="{ row }">{{ fmt(row.last_occurred_at) }}</template>
      </el-table-column>
      <el-table-column label="测点" min-width="150">
        <template #default="{ row }">
          <span :data-testid="`alarm-row-${row.point_code}`">{{ row.point_name || row.point_code }}</span>
          <div class="sub">{{ row.point_code }}</div>
        </template>
      </el-table-column>
      <el-table-column label="内容" prop="message" min-width="200" />
      <el-table-column label="次数" prop="occurrence_count" width="70" />
      <el-table-column label="严重度" width="80">
        <template #default="{ row }">{{ SEVERITY[row.severity] ?? row.severity ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="ALARM_STATUS_TYPE[row.status]">{{ ALARM_STATUS[row.status] ?? row.status }}</el-tag>
          <el-tag v-if="row.clock_anomaly" size="small" type="danger" effect="plain" class="ml">时钟异常</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="关联问题" width="150">
        <template #default="{ row }">{{ row.issue_code || (row.suggested_issue_code ? `建议：${row.suggested_issue_code}` : '—') }}</template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !rows.length" description="没有报警" :image-size="60" />

    <el-drawer v-model="visible" size="760px" :title="cur ? cur.message || cur.point_code : ''" append-to-body>
      <template v-if="cur">
        <div class="sub" data-testid="alarm-detail">
          {{ cur.point_name || cur.point_code }} · {{ cur.alarm_type }} · 共 {{ cur.occurrence_count }} 次 · 首次 {{ fmt(cur.first_occurred_at) }} ·
          最近 {{ fmt(cur.last_occurred_at) }} · 最近值 {{ num(cur.last_value) }}{{ cur.unit || '' }}
          <el-tag size="small" :type="ALARM_STATUS_TYPE[cur.status]" data-testid="alarm-detail-status">{{ ALARM_STATUS[cur.status] }}</el-tag>
          <el-tag v-if="cur.clock_anomaly" size="small" type="danger" effect="plain" class="ml">时钟异常：源时间超前接收时间</el-tag>
        </div>

        <el-alert v-if="cur.status === 'SUGGESTED'" type="warning" :closable="false" class="block" data-testid="alarm-suggestion">
          <template #title>同一设备已有人工登记的问题 {{ cur.suggested_issue_code }}「{{ cur.suggested_issue_title }}」，建议关联而不新建。</template>
          <div v-if="canHandle" class="ops">
            <el-button size="small" type="primary" data-testid="btn-alarm-link" @click="act('link')">关联到该问题</el-button>
            <el-button size="small" data-testid="btn-alarm-draft" @click="act('draft')">仍生成新问题草稿</el-button>
          </div>
        </el-alert>

        <div v-if="cur.issue_id" class="block" data-testid="alarm-issue">
          关联问题：<b>{{ cur.issue_code }}</b> {{ cur.issue_title }}
          <el-tag size="small" :type="cur.issue_status === 'DRAFT' ? 'warning' : 'success'">{{
            cur.issue_status === 'DRAFT' ? '草稿待确认' : cur.issue_status
          }}</el-tag>
          <el-button
            v-if="canConfirm && cur.issue_status === 'DRAFT'"
            size="small"
            type="primary"
            class="ml"
            data-testid="btn-alarm-confirm"
            @click="confirmIssue"
            >确认为正式问题</el-button
          >
        </div>

        <div v-if="canHandle && ['NEW', 'NOTIFIED'].includes(cur.status)" class="ops block">
          <el-button size="small" type="primary" data-testid="btn-alarm-draft" @click="act('draft')">生成问题草稿</el-button>
          <el-button size="small" data-testid="btn-alarm-ignore" @click="act('ignore')">忽略</el-button>
        </div>

        <h4>报警前后 30 分钟快照</h4>
        <TrendChart v-if="ctx" :snapshots="ctx.snapshots" :mark="cur.last_occurred_at" :label="`${cur.point_code} 快照`" />
        <div v-if="ctx" class="sub" data-testid="alarm-window">
          {{ fmt(ctx.window.from) }} ~ {{ fmt(ctx.window.to) }} · {{ ctx.snapshots.length }} 个 {{ ctx.window.bucketSeconds }} 秒快照 · 原始引用
          {{ ctx.rawRef.sourceSystem }}/{{ ctx.rawRef.pointCode }}
        </div>

        <h4>原始事件引用</h4>
        <el-table :data="ctx?.events ?? cur.events ?? []" size="small" data-testid="alarm-events">
          <el-table-column label="来源事件" prop="source_event_id" min-width="200" />
          <el-table-column label="发生时间" width="150">
            <template #default="{ row }">{{ fmt(row.occurred_at) }}</template>
          </el-table-column>
          <el-table-column label="接收时间" width="150">
            <template #default="{ row }">{{ fmt(row.received_at) }}</template>
          </el-table-column>
          <el-table-column label="质量" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.quality === 'GOOD' ? 'success' : 'danger'">{{ row.quality === 'GOOD' ? '正常' : '坏值' }}</el-tag>
              <el-tag v-if="row.clock_anomaly" size="small" type="danger" effect="plain">时钟</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="追踪号" prop="trace_id" width="160" />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { DcsApi } from '@/api/lsmom/dcs';
import { ALARM_STATUS, ALARM_STATUS_TYPE, OPEN_STATUSES, SEVERITY, num, type AlarmContext, type DcsAlarm } from '@/api/lsmom/dcsModel';
import TrendChart from '../TrendChart.vue';

const props = withDefaults(
  defineProps<{
    api: Pick<DcsApi, 'alarms' | 'alarm' | 'context' | 'link' | 'draft' | 'ignore' | 'confirmIssue'>;
    canHandle?: boolean;
    canConfirm?: boolean;
  }>(),
  {
    canHandle: true,
    canConfirm: true
  }
);

const filter = ref('OPEN');
const rows = ref<DcsAlarm[]>([]);
const loading = ref(false);
const visible = ref(false);
const cur = ref<DcsAlarm>();
const ctx = ref<AlarmContext>();

async function load() {
  loading.value = true;
  try {
    const status = filter.value === 'ALL' ? undefined : filter.value === 'OPEN' ? OPEN_STATUSES.join(',') : filter.value;
    rows.value = await props.api.alarms({ status });
  } finally {
    loading.value = false;
  }
}

async function open(id: number) {
  cur.value = await props.api.alarm(id);
  ctx.value = await props.api.context(id).catch(() => undefined);
  visible.value = true;
}

async function act(kind: 'link' | 'draft' | 'ignore') {
  const id = cur.value!.id;
  const res =
    kind === 'link'
      ? await props.api.link(id, cur.value!.suggested_issue_id ?? undefined)
      : kind === 'draft'
        ? await props.api.draft(id)
        : await props.api.ignore(id);
  ElMessage.success(kind === 'link' ? `已关联到 ${res.issue_code ?? ''}` : kind === 'draft' ? `已生成问题草稿 ${res.issue_code ?? ''}` : '已忽略');
  await open(id);
  await load();
}

async function confirmIssue() {
  const a = cur.value!;
  await props.api.confirmIssue(a.issue_id!, a.issue_version ?? 0);
  ElMessage.success(`问题 ${a.issue_code} 已确认`);
  await open(a.id);
  await load();
}

const fmt = (v?: string) => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '—');

onMounted(load);
defineExpose({ load, open });
</script>

<style scoped>
.bar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.hint,
.block {
  margin: 8px 0;
}
.ops {
  display: flex;
  gap: 8px;
  margin-top: 6px;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.ml {
  margin-left: 6px;
}
</style>
