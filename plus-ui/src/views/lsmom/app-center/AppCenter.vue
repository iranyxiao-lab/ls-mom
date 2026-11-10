<template>
  <div class="ac" data-testid="app-center">
    <el-tabs v-model="tab" @tab-change="load">
      <el-tab-pane label="已安装 Pack" name="packs" />
      <el-tab-pane name="changes">
        <template #label> 变更发布 <el-badge v-if="pendingCount" :value="pendingCount" type="warning" /> </template>
      </el-tab-pane>
    </el-tabs>

    <template v-if="tab === 'packs'">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="搜索名称或编码" clearable style="width: 220px" />
        <el-select v-model="flag" placeholder="状态" clearable style="width: 150px" data-testid="filter-flag">
          <el-option label="待复审" value="REVIEW_DUE" />
          <el-option label="建议下线" value="SUGGEST_RETIRE" />
          <el-option label="配置漂移" value="DRIFT" />
          <el-option label="可升级" value="UPGRADE" />
          <el-option label="已停用" value="DISABLED" />
        </el-select>
        <span class="grow" />
        <el-button data-testid="btn-review" @click="runReview">复审检查</el-button>
      </div>
      <el-table
        v-loading="loading"
        :data="shownPacks"
        size="small"
        data-testid="table-packs"
        row-key="code"
        @row-click="(r: PackSummary) => open(r.code)"
      >
        <el-table-column label="Pack" min-width="210">
          <template #default="{ row }">
            <b>{{ row.name }}</b>
            <el-tag size="small" :type="row.standard ? 'primary' : 'success'" class="ml">{{ row.standard ? '标准' : '客户' }}</el-tag>
            <div class="sub">{{ row.code }}</div>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="140">
          <template #default="{ row }">
            <span data-testid="pack-version">{{ row.version }}</span>
            <el-tag v-if="row.upgradeAvailable" size="small" type="warning" class="ml">可升级 {{ row.latestVersion }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="STATUS_TYPE[row.status]">{{ STATUS[row.status] ?? row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="依赖" min-width="150">
          <template #default="{ row }">
            <span v-if="!row.dependsOn.length" class="sub">—</span>
            <div v-for="d in row.dependsOn" :key="d.code" class="sub">{{ d.code }} {{ d.version }}</div>
          </template>
        </el-table-column>
        <el-table-column label="负责人 / 提出人" width="130">
          <template #default="{ row }"
            >{{ row.owner }}<span class="sub"> / {{ row.requester }}</span></template
          >
        </el-table-column>
        <el-table-column label="复审" width="150">
          <template #default="{ row }">
            <span data-testid="pack-review-due">{{ row.reviewDue }}</span>
            <el-tag
              v-if="row.reviewStatus !== 'OK'"
              size="small"
              :type="row.reviewStatus === 'SUGGEST_RETIRE' ? 'danger' : 'warning'"
              class="ml"
              data-testid="pack-review-status"
            >
              {{ REVIEW[row.reviewStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="近 90 天使用" width="110" align="right">
          <template #default="{ row }"
            ><span data-testid="pack-uses">{{ row.uses90d }}</span></template
          >
        </el-table-column>
        <el-table-column label="最近使用" width="140">
          <template #default="{ row }">{{ fmt(row.lastUsedAt) || '—' }}</template>
        </el-table-column>
        <el-table-column label="失败任务" width="90" align="right">
          <template #default="{ row }">
            <span :class="{ bad: row.failedTasks > 0 }">{{ row.failedTasks }}</span>
          </template>
        </el-table-column>
        <el-table-column label="漂移" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.drifted" size="small" type="danger" data-testid="pack-drift">{{ row.driftCount }} 项</el-tag>
            <span v-else class="sub">无</span>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <template v-else>
      <div class="toolbar">
        <el-upload :show-file-list="false" accept=".zip" :auto-upload="false" :on-change="onFile" data-testid="upload-pack">
          <el-button type="primary">上传 Pack（zip）</el-button>
        </el-upload>
        <span class="sub">提交后自动执行校验与依赖分析；沙箱、回归、越权与升级测试由升级演练脚本在临时环境完成并回写。</span>
      </div>
      <el-table v-loading="loading" :data="changes" size="small" data-testid="table-changes" row-key="id">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="stages">
              <div v-for="s in STAGES" :key="s" class="stage">
                <b>{{ STAGE[s] }}</b>
                <template v-if="stageOf(row, s)">
                  <el-tag size="small" :type="stageOf(row, s)!.status === 'PASSED' ? 'success' : 'danger'">{{
                    stageOf(row, s)!.status === 'PASSED' ? '通过' : '失败'
                  }}</el-tag>
                  <span class="sub">{{ fmt(stageOf(row, s)!.runAt) }} {{ stageText(stageOf(row, s)!) }}</span>
                </template>
                <span v-else class="sub">未执行</span>
              </div>
              <div v-for="(it, i) in row.items.filter((x: any) => x.type === 'DRIFT')" :key="i" class="sub">
                回灌 {{ it.kind }} {{ it.code }}：{{ it.diffs.map((d: any) => d.path).join('、') }}
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="#" width="60" />
        <el-table-column label="变更" min-width="240">
          <template #default="{ row }">
            {{ row.title }}
            <el-tag v-if="row.breaking" size="small" type="danger" class="ml">破坏性</el-tag>
            <el-tag v-if="row.source === 'DRIFT'" size="small" type="warning" class="ml">漂移回灌</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="测试" width="190">
          <template #default="{ row }">
            <span v-for="s in STAGES" :key="s" class="dot" :class="dotClass(row, s)" :title="STAGE[s]" :data-testid="`stage-${row.id}-${s}`" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="CS_TYPE[row.status]" :data-testid="`cs-status-${row.id}`">{{ CS[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="140">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="" width="200">
          <template #default="{ row }">
            <template v-if="row.status === 'REVIEWING'">
              <el-button size="small" type="primary" :data-testid="`btn-approve-${row.id}`" @click="approve(row, true)">批准</el-button>
              <el-button size="small" @click="approve(row, false)">驳回</el-button>
            </template>
            <el-button
              v-if="row.status !== 'APPLIED' && row.status !== 'REJECTED'"
              size="small"
              :type="row.status === 'APPROVED' ? 'success' : 'default'"
              :data-testid="`btn-release-${row.id}`"
              @click="release(row)"
              >发布</el-button
            >
          </template>
        </el-table-column>
      </el-table>
    </template>

    <el-drawer v-model="drawer" size="62%" :title="cur ? `${cur.name} ${cur.version}` : ''" data-testid="pack-drawer" destroy-on-close>
      <template v-if="cur">
        <el-alert v-if="cur.error" type="error" :closable="false" :title="cur.error" class="mb" />
        <el-alert
          v-if="cur.reviewStatus === 'SUGGEST_RETIRE'"
          type="error"
          :closable="false"
          class="mb"
          :title="`已过复审日期 ${cur.reviewDue}，且 90 天无使用：建议下线`"
          data-testid="retire-hint"
        />
        <el-descriptions :column="3" size="small" border data-testid="pack-info">
          <el-descriptions-item label="编码">{{ cur.code }}</el-descriptions-item>
          <el-descriptions-item label="版本"
            >{{ cur.version }}<span v-if="cur.previousVersion" class="sub">（上一版本 {{ cur.previousVersion }}）</span></el-descriptions-item
          >
          <el-descriptions-item label="状态">{{ STATUS[cur.status] }}</el-descriptions-item>
          <el-descriptions-item label="业务负责人">{{ cur.owner }}</el-descriptions-item>
          <el-descriptions-item label="需求提出人">{{ cur.requester }}</el-descriptions-item>
          <el-descriptions-item label="下次复审"
            >{{ cur.reviewDue }} {{ cur.reviewStatus !== 'OK' ? REVIEW[cur.reviewStatus] : '' }}</el-descriptions-item
          >
          <el-descriptions-item label="依赖">{{ cur.dependsOn.map((d) => d.code + ' ' + d.version).join('；') || '—' }}</el-descriptions-item>
          <el-descriptions-item label="被依赖">{{ cur.dependents.join('、') || '—' }}</el-descriptions-item>
          <el-descriptions-item label="安装时间">{{ fmt(cur.installedAt) }}</el-descriptions-item>
          <el-descriptions-item label="近 90 天使用">{{ cur.uses90d }}</el-descriptions-item>
          <el-descriptions-item label="最近使用">{{ fmt(cur.lastUsedAt) || '—' }}</el-descriptions-item>
          <el-descriptions-item label="失败任务">{{ cur.failedTasks }}</el-descriptions-item>
        </el-descriptions>
        <p v-if="cur.description" class="sub">{{ cur.description }}</p>
        <div class="toolbar">
          <el-button v-for="e in cur.entries" :key="e.path" size="small" :disabled="cur.status !== 'ENABLED'" @click="emit('navigate', e.path)">{{
            e.name
          }}</el-button>
          <span class="grow" />
          <el-button size="small" data-testid="btn-test" :disabled="cur.status !== 'ENABLED'" :loading="busy === 'test'" @click="runTest"
            >运行测试</el-button
          >
          <el-button size="small" data-testid="btn-export" @click="exportData">导出数据</el-button>
          <el-button v-if="cur.status === 'ENABLED'" size="small" type="danger" plain data-testid="btn-disable" @click="toggle(false)"
            >停用</el-button
          >
          <el-button v-else-if="cur.status === 'DISABLED'" size="small" type="success" plain data-testid="btn-enable" @click="toggle(true)"
            >启用</el-button
          >
        </div>

        <el-tabs v-model="detailTab">
          <el-tab-pane label="配置漂移" name="drift">
            <div v-if="drift && !drift.drifted" class="sub" data-testid="drift-none">运行环境与安装清单一致</div>
            <template v-else-if="drift">
              <el-table :data="drift.items.filter((i) => i.state !== 'SAME')" size="small" data-testid="drift-table">
                <el-table-column label="定义" min-width="200">
                  <template #default="{ row }"
                    >{{ KIND[row.kind] ?? row.kind }} · {{ row.code }}
                    <div class="sub">{{ row.path }}</div></template
                  >
                </el-table-column>
                <el-table-column label="版本" width="110">
                  <template #default="{ row }">{{ row.expectedVersion ?? '' }} → {{ row.actualVersion ?? '缺失' }}</template>
                </el-table-column>
                <el-table-column label="差异" min-width="320">
                  <template #default="{ row }">
                    <div v-for="d in row.diffs" :key="d.path" class="diff">
                      <code>{{ d.path }}</code
                      >：<del>{{ show(d.expected) }}</del> → <ins>{{ show(d.actual) }}</ins>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
              <el-button
                type="warning"
                size="small"
                class="mt"
                data-testid="btn-drift-changeset"
                :loading="busy === 'drift'"
                @click="driftToChangeSet"
              >
                将差异回灌为 ChangeSet
              </el-button>
            </template>
          </el-tab-pane>
          <el-tab-pane label="安装内容" name="items">
            <el-table :data="cur.items ?? []" size="small" data-testid="items-table">
              <el-table-column label="类型" width="100">
                <template #default="{ row }">{{ KIND[row.kind] ?? row.kind }}</template>
              </el-table-column>
              <el-table-column prop="code" label="编码" min-width="220" />
              <el-table-column prop="version" label="版本" width="70" />
              <el-table-column prop="path" label="文件" min-width="180" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="来源" name="provenance">
            <div v-if="!prov || prov.provenance === null" class="sub" data-testid="prov-none">手写 Pack，没有生成或转换来源记录。</div>
            <template v-else>
              <el-descriptions :column="2" size="small" border data-testid="prov-info">
                <el-descriptions-item label="来源类型">{{ SOURCE_TYPE[prov.sourceType ?? ''] ?? prov.sourceType }}</el-descriptions-item>
                <el-descriptions-item label="来源应用">{{ prov.source?.app }} {{ prov.source?.version }}</el-descriptions-item>
                <el-descriptions-item label="许可证">{{ prov.license }}</el-descriptions-item>
                <el-descriptions-item label="转换器">{{ prov.converter?.name }} {{ prov.converter?.version }}</el-descriptions-item>
                <el-descriptions-item label="模型">{{ prov.model ? `${prov.model.name} ${prov.model.version}` : '（未使用）' }}</el-descriptions-item>
                <el-descriptions-item label="生成时间">{{ fmt(prov.generatedAt) }}</el-descriptions-item>
              </el-descriptions>
              <el-alert
                v-if="openManual.length"
                type="warning"
                :closable="false"
                class="mb"
                :title="`还有 ${openManual.length} 项需人工处理（其中禁止自动转换 ${openManual.filter((m) => m.kind === 'BLOCKED').length} 项），处理前 ChangeSet 不能发布`"
                data-testid="prov-open"
              />
              <h4>对象来源</h4>
              <el-table :data="elementRows" size="small" data-testid="prov-elements">
                <el-table-column prop="code" label="编码" min-width="200" />
                <el-table-column prop="kind" label="类型" width="110" />
                <el-table-column label="来源" min-width="220">
                  <template #default="{ row }">{{ [row.source?.type, row.source?.name, row.source?.path].filter(Boolean).join(' · ') }}</template>
                </el-table-column>
              </el-table>
              <h4>需人工处理</h4>
              <el-table :data="prov.manualRequired ?? []" size="small" data-testid="prov-manual">
                <el-table-column label="状态" width="90">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.status === 'RESOLVED' ? 'success' : row.kind === 'BLOCKED' ? 'danger' : 'warning'">
                      {{ row.status === 'RESOLVED' ? '已处理' : row.kind === 'BLOCKED' ? '禁止自动转换' : '需人工' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="path" label="位置" min-width="180" />
                <el-table-column prop="reason" label="原因" min-width="220" />
                <el-table-column label="处理" min-width="160">
                  <template #default="{ row }">{{ row.resolution ? `${row.resolvedBy}：${row.resolution}` : '' }}</template>
                </el-table-column>
              </el-table>
              <h4>人工修改历史</h4>
              <el-table :data="prov.manualEdits ?? []" size="small" data-testid="prov-edits">
                <el-table-column label="时间" width="140">
                  <template #default="{ row }">{{ fmt(row.at) }}</template>
                </el-table-column>
                <el-table-column prop="by" label="修改人" width="100" />
                <el-table-column prop="path" label="位置" min-width="160" />
                <el-table-column prop="reason" label="原因" min-width="200" />
                <el-table-column prop="lines" label="行数" width="60" />
              </el-table>
            </template>
          </el-tab-pane>
          <el-tab-pane label="测试结果" name="test">
            <div v-if="!report" class="sub">点击"运行测试"执行黄金场景与越权检查（在事务中执行并回滚）。</div>
            <template v-else>
              <el-alert
                :type="report.passed ? 'success' : 'error'"
                :closable="false"
                :title="report.passed ? '全部通过' : '存在失败'"
                data-testid="test-result"
              />
              <div v-for="c in [...report.regression, ...report.privilege]" :key="c.name" class="case">
                <el-tag size="small" :type="c.passed ? 'success' : 'danger'">{{ c.passed ? '通过' : '失败' }}</el-tag>
                {{ c.name }} <span v-if="c.message" class="sub">{{ c.message }}</span>
              </div>
            </template>
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { UploadFile } from 'element-plus';
import type { ChangeSet, DriftReport, PackApi, PackSummary, Provenance, Stage, StageResult, TestReport } from '@/api/lsmom/pack';

/** 协同应用中心：已安装 Pack 的版本、依赖、负责人、复审、使用量、失败任务、漂移与下线状态，以及 ChangeSet 发布。 */
const props = defineProps<{ api: PackApi }>();
const emit = defineEmits<{ navigate: [path: string] }>();

const STATUS: Record<string, string> = { INSTALLING: '安装中', ENABLED: '启用', DISABLED: '停用', FAILED: '失败', UNINSTALLED: '已卸载' };
const STATUS_TYPE: Record<string, 'success' | 'info' | 'danger' | 'warning'> = {
  ENABLED: 'success',
  DISABLED: 'info',
  FAILED: 'danger',
  INSTALLING: 'warning'
};
const REVIEW: Record<string, string> = { OK: '', REVIEW_DUE: '待复审', SUGGEST_RETIRE: '建议下线' };
const KIND: Record<string, string> = {
  CONCEPT: '台账',
  STATE_MACHINE: '状态机',
  RULE: '规则',
  VIEW: '视图',
  METRIC: '指标',
  FLOW: '审批流',
  MIGRATION: '迁移',
  SEED: '种子'
};
const STAGES: Stage[] = ['VALIDATE', 'SANDBOX', 'REGRESSION', 'PRIVILEGE', 'UPGRADE'];
const STAGE: Record<Stage, string> = {
  VALIDATE: '校验与依赖',
  SANDBOX: '沙箱安装',
  REGRESSION: '回归测试',
  PRIVILEGE: '越权测试',
  UPGRADE: '升级演练'
};
const CS: Record<string, string> = { DRAFT: '测试中', REVIEWING: '待审批', APPROVED: '已批准', APPLIED: '已发布', REJECTED: '已驳回' };
const CS_TYPE: Record<string, 'success' | 'info' | 'danger' | 'warning' | 'primary'> = {
  DRAFT: 'info',
  REVIEWING: 'warning',
  APPROVED: 'primary',
  APPLIED: 'success',
  REJECTED: 'danger'
};

const tab = ref<'packs' | 'changes'>('packs');
const loading = ref(false);
const packs = ref<PackSummary[]>([]);
const changes = ref<ChangeSet[]>([]);
const keyword = ref('');
const flag = ref('');
const drawer = ref(false);
const cur = ref<PackSummary>();
const drift = ref<DriftReport>();
const report = ref<TestReport>();
const detailTab = ref('drift');
const prov = ref<Provenance>();
const SOURCE_TYPE: Record<string, string> = { REQUIREMENT: '需求生成', FRAPPE: 'Frappe 转换', LEGACY_SCHEMA: '旧表结构', MANUAL: '人工编写' };
const openManual = computed(() => (prov.value?.manualRequired ?? []).filter((m) => m.status !== 'RESOLVED'));
const elementRows = computed(() => Object.entries(prov.value?.elements ?? {}).map(([code, e]) => ({ code, ...e })));
const busy = ref('');

const pendingCount = computed(() => changes.value.filter((c) => c.status === 'REVIEWING' || c.status === 'APPROVED').length);
const shownPacks = computed(() =>
  packs.value.filter((p) => {
    const k = keyword.value.trim();
    if (k && !p.name.includes(k) && !p.code.includes(k)) return false;
    switch (flag.value) {
      case 'REVIEW_DUE':
      case 'SUGGEST_RETIRE':
        return p.reviewStatus === flag.value;
      case 'DRIFT':
        return p.drifted;
      case 'UPGRADE':
        return p.upgradeAvailable;
      case 'DISABLED':
        return p.status === 'DISABLED';
      default:
        return true;
    }
  })
);

const pad = (n: number) => String(n).padStart(2, '0');
/** 服务端本地时间原样显示；带时区（如生成时间 ...Z）的按浏览器本地时间显示。 */
const fmt = (s?: string) => {
  if (!s) return '';
  if (!/(Z|[+-]\d\d:?\d\d)$/.test(s)) return s.replace('T', ' ').substring(0, 16);
  const d = new Date(s);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};
const show = (v: unknown) => (v === null || v === undefined ? '（无）' : typeof v === 'string' ? v : JSON.stringify(v));
const stageOf = (c: ChangeSet, s: Stage) => c.stages.find((x) => x.stage === s);
const dotClass = (c: ChangeSet, s: Stage) => {
  const r = stageOf(c, s);
  return r ? (r.status === 'PASSED' ? 'ok' : 'fail') : 'none';
};
const stageText = (r: StageResult) => {
  const d = r.detail ?? {};
  if (r.status === 'FAILED') return [d.migration ? `迁移 ${d.migration}` : '', d.message ?? d.error ?? ''].filter(Boolean).join('：');
  if (r.stage === 'VALIDATE' && d.breaking?.length) return '破坏性：' + d.breaking.join('；');
  if (d.passed !== undefined) return `${d.cases ?? ''}`;
  return '';
};

async function load() {
  loading.value = true;
  try {
    const [p, c] = await Promise.all([props.api.list(), props.api.changeSets()]);
    packs.value = p;
    changes.value = c;
  } finally {
    loading.value = false;
  }
}

async function open(code: string) {
  cur.value = await props.api.get(code);
  drift.value = await props.api.drift(code);
  prov.value = await props.api.provenance(code).catch(() => undefined);
  report.value = undefined;
  detailTab.value = drift.value.drifted ? 'drift' : 'items';
  drawer.value = true;
}

async function refreshCurrent() {
  if (cur.value) {
    cur.value = await props.api.get(cur.value.code);
    drift.value = await props.api.drift(cur.value.code);
  }
  await load();
}

async function toggle(on: boolean) {
  if (!cur.value) return;
  if (!on) {
    await ElMessageBox.confirm(`停用后「${cur.value.name}」的页面、Operation 与 Agent 工具不可用，数据保留并可导出。确定停用？`, '停用 Pack', {
      type: 'warning'
    });
  }
  await (on ? props.api.enable(cur.value.code) : props.api.disable(cur.value.code));
  ElMessage.success(on ? '已启用' : '已停用');
  await refreshCurrent();
}

async function runTest() {
  if (!cur.value) return;
  busy.value = 'test';
  try {
    report.value = await props.api.test(cur.value.code);
    detailTab.value = 'test';
  } finally {
    busy.value = '';
  }
}

async function exportData() {
  if (!cur.value) return;
  const d = await props.api.exportData(cur.value.code);
  const blob = new Blob([JSON.stringify(d, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `${cur.value.code}-${cur.value.version}-data.json`;
  a.click();
  URL.revokeObjectURL(a.href);
}

async function driftToChangeSet() {
  if (!cur.value) return;
  busy.value = 'drift';
  try {
    const cs = await props.api.driftToChangeSet(cur.value.code);
    ElMessage.success(`已生成 ChangeSet #${cs.id}（${cs.version}）`);
    drawer.value = false;
    tab.value = 'changes';
    await load();
  } finally {
    busy.value = '';
  }
}

async function runReview() {
  await props.api.runReview();
  ElMessage.success('复审检查完成');
  await load();
}

function toBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).replace(/^data:[^,]*,/, ''));
    r.onerror = () => reject(r.error);
    r.readAsDataURL(file);
  });
}

async function onFile(f: UploadFile) {
  if (!f.raw) return;
  const cs = await props.api.submit(await toBase64(f.raw));
  if (cs.status === 'REJECTED') ElMessage.error(`校验未通过：${cs.stages[0]?.detail?.message ?? ''}`);
  else ElMessage.success(`已提交 ChangeSet #${cs.id}：${cs.title}`);
  tab.value = 'changes';
  await load();
}

async function approve(c: ChangeSet, ok: boolean) {
  const { value } = await ElMessageBox.prompt(ok ? '审批意见（可选）' : '驳回原因', ok ? `批准 #${c.id}` : `驳回 #${c.id}`, {
    inputValue: '',
    inputValidator: (v) => ok || !!v?.trim() || '请填写驳回原因'
  });
  await props.api.approve(c.id, ok, value);
  ElMessage.success(ok ? '已批准' : '已驳回');
  await load();
}

async function release(c: ChangeSet) {
  const r = await props.api.release(c.id);
  ElMessage.success(`已发布 ${r.code} ${r.version}`);
  await load();
}

onMounted(load);
defineExpose({ load, open });
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 8px 0;
  flex-wrap: wrap;
}
.grow {
  flex: 1;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.ml {
  margin-left: 6px;
}
.mb {
  margin-bottom: 8px;
}
.mt {
  margin-top: 8px;
}
.bad {
  color: var(--el-color-danger);
  font-weight: 600;
}
.dot {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  margin-right: 6px;
  background: var(--el-border-color);
}
.dot.ok {
  background: var(--el-color-success);
}
.dot.fail {
  background: var(--el-color-danger);
}
.stages {
  padding: 4px 48px;
}
.stage {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 2px 0;
}
.stage b {
  width: 90px;
  font-weight: 500;
}
.diff code {
  font-size: 12px;
}
.diff del {
  color: var(--el-color-danger);
}
.diff ins {
  color: var(--el-color-success);
  text-decoration: none;
}
.case {
  padding: 4px 0;
}
</style>
