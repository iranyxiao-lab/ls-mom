<template>
  <div data-testid="quality-records">
    <div class="toolbar">
      <el-select v-model="result" placeholder="全部结论" clearable style="width: 140px" @change="load">
        <el-option v-for="(v, k) in RESULTS" :key="k" :label="v" :value="k" />
      </el-select>
      <span class="grow" />
      <el-button type="primary" data-testid="btn-new-record" @click="openNew">登记质量记录</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" size="small" data-testid="record-table">
      <el-table-column label="标题" min-width="200">
        <template #default="{ row }"
          ><span :data-testid="`qr-row-${row.id}`">{{ row.title }}</span></template
        >
      </el-table-column>
      <el-table-column label="类型" width="80"
        ><template #default="{ row }">{{ RECORD_TYPES[row.recordType] }}</template></el-table-column
      >
      <el-table-column label="结论" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.result === 'FAIL' ? 'danger' : row.result === 'PASS' ? 'success' : 'warning'">{{
            RESULTS[row.result]
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="记录时间" width="150"
        ><template #default="{ row }">{{ fmt(row.recordedAt) }}</template></el-table-column
      >
      <el-table-column label="状态" width="90">
        <template #default="{ row }"
          ><span :data-testid="`qr-status-${row.id}`">{{ row.status === 'SUBMITTED' ? '已登记' : '草稿' }}</span></template
        >
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" title="登记质量记录" width="680px" append-to-body>
      <el-form label-width="80px">
        <el-form-item label="标题" required><el-input v-model="draft.title" data-testid="qr-title" /></el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="draft.recordType">
            <el-radio-button v-for="(v, k) in RECORD_TYPES" :key="k" :value="k">{{ v }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="结论">
          <el-radio-group v-model="draft.result">
            <el-radio-button v-for="(v, k) in RESULTS" :key="k" :value="k" :data-testid="`qr-result-${k}`">{{ v }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="draft.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="关联对象">
          <div class="links">
            <div class="sub">可不关联；也可同时关联工作、设备、产出、物料或外部对象</div>
            <div v-for="(l, i) in links" :key="i" class="link-row">
              <el-select v-model="l.objectType" size="small" style="width: 110px" :data-testid="`qr-link-type-${i}`" @change="onTypeChange(l)">
                <el-option v-for="(v, k) in LINK_TYPES" :key="k" :label="v" :value="k" />
              </el-select>
              <el-select
                v-if="l.objectType === 'WORK' || l.objectType === 'ASSET'"
                v-model="l.objectId"
                size="small"
                filterable
                remote
                :remote-method="(q: string) => search(l.objectType, q)"
                style="width: 300px"
                :data-testid="`qr-link-obj-${i}`"
                @change="(v: string) => (l.label = options.find((o) => String(o.id) === String(v))?.label)"
              >
                <el-option v-for="o in options" :key="o.id" :label="o.label" :value="String(o.id)" />
              </el-select>
              <el-input v-else v-model="l.objectId" size="small" placeholder="编号" style="width: 300px" :data-testid="`qr-link-id-${i}`" />
              <el-button link type="danger" @click="links.splice(i, 1)">删</el-button>
            </div>
            <el-button size="small" data-testid="btn-add-link" @click="addLink">添加关联</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" data-testid="btn-save-record" @click="save">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { QualityApi } from '@/api/lsmom/quality';
import { LINK_TYPES, QUALITY_RECORD, RECORD_TYPES, RESULTS, type LinkRow, type ObjectOption } from '@/api/lsmom/qualityModel';

/** 质量记录：可独立登记，也可同时关联多个对象（都不必填）；登记后出现在各关联对象的时间轴上。 */
const props = defineProps<{ api: QualityApi }>();

const rows = ref<Record<string, any>[]>([]);
const loading = ref(false);
const result = ref<string>();
const visible = ref(false);
const draft = reactive({ title: '', recordType: 'INSPECTION', result: 'PASS', description: '' });
const links = ref<LinkRow[]>([]);
const options = ref<ObjectOption[]>([]);

const fmt = (s?: string) => (s ? String(s).replace('T', ' ').slice(0, 16) : '');

async function load() {
  loading.value = true;
  try {
    const filters: any[] = result.value ? [{ field: 'result', op: 'eq', value: result.value }] : [];
    const page = await props.api.query(QUALITY_RECORD, { filters, sort: [{ field: 'id', dir: 'desc' }], page: 1, size: 200 });
    rows.value = page.rows as Record<string, any>[];
  } finally {
    loading.value = false;
  }
}

function openNew() {
  Object.assign(draft, { title: '', recordType: 'INSPECTION', result: 'PASS', description: '' });
  links.value = [];
  visible.value = true;
}

function addLink() {
  links.value.push({ objectType: 'WORK', objectId: '' });
  search('WORK', '');
}

function onTypeChange(l: LinkRow) {
  l.objectId = '';
  l.label = undefined;
  if (l.objectType === 'WORK' || l.objectType === 'ASSET') search(l.objectType, '');
}

async function search(type: string, q: string) {
  options.value = await props.api.objects(type, q).catch(() => []);
}

async function save() {
  if (!draft.title.trim()) return ElMessage.warning('请填写标题');
  const filled = links.value.filter((l) => l.objectId);
  const r = await props.api.create(QUALITY_RECORD, {
    ...draft,
    title: draft.title.trim(),
    qualityRecordLink: filled.map((l) => ({ objectType: l.objectType, objectId: String(l.objectId), label: l.label || undefined }))
  });
  await props.api.op(`${QUALITY_RECORD}.submit`, r.id, r.version);
  ElMessage.success(filled.length ? `质量记录已登记，已关联 ${filled.length} 个对象` : '质量记录已登记');
  visible.value = false;
  await load();
}

onMounted(load);
defineExpose({ load, openNew, addLink });
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
}
.links {
  width: 100%;
}
.link-row {
  display: flex;
  gap: var(--ls-space-2);
  align-items: center;
  margin: var(--ls-space-1) 0;
}
</style>
