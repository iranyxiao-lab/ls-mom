<template>
  <div class="m" data-testid="mobile-report">
    <template v-if="!cur">
      <div class="m-head">我的生产工作 <el-button link size="small" @click="load">刷新</el-button></div>
      <el-empty v-if="!loading && !works.length" description="暂无分派给你的工作" />
      <div v-for="w in works" :key="w.id" class="m-card" :data-testid="`m-work-${w.id}`" @click="open(w)">
        <div class="m-title">{{ w.title }}</div>
        <div class="sub">{{ w.code }} · {{ w.teamName ?? '' }} {{ w.shift ? shiftLabel(w.shift) : '' }}</div>
        <div class="row">
          <el-tag size="small">{{ WORK_STATUS[w.status] ?? w.status }}</el-tag>
          <span class="sub">{{ w.progress != null ? Number(w.progress) + '%' : '' }}</span>
          <span v-if="hasDraft(w.id)" class="draft">有草稿</span>
        </div>
      </div>
    </template>

    <template v-else>
      <div class="m-head">
        <el-button link @click="cur = undefined">‹ 返回</el-button>
        <span class="grow">{{ cur.title }}</span>
      </div>
      <div class="sub">{{ cur.code }} · {{ WORK_STATUS[cur.status] }}</div>
      <el-button v-if="cur.status === 'ASSIGNED'" type="primary" class="block" data-testid="m-start" @click="start">开始工作</el-button>
      <template v-if="cur.status === 'IN_PROGRESS'">
        <label class="lbl">进度 {{ form.progress }}%</label>
        <el-slider v-model="form.progress" :step="5" data-testid="m-progress" />
        <label class="lbl">人工（分钟）</label>
        <el-input-number v-model="form.laborMinutes" :min="0" :step="30" class="block" data-testid="m-labor" />
        <label class="lbl">产出</label>
        <div class="row">
          <el-input-number v-model="form.outputQty" :min="0" class="grow" data-testid="m-output" />
          <el-input v-model="form.outputUnit" placeholder="单位" style="width: 80px" data-testid="m-unit" />
        </div>
        <label class="lbl">说明</label>
        <el-input v-model="form.remark" type="textarea" :rows="2" data-testid="m-remark" />
        <label class="lbl">现场照片（作为证据附件）</label>
        <input type="file" accept="image/*" capture="environment" multiple data-testid="m-photo" @change="onPhoto" />
        <div class="thumbs">
          <div v-for="(p, i) in photos" :key="i" class="thumb">
            <img :src="p.preview" alt="" />
            <span class="x" @click="photos.splice(i, 1)">×</span>
          </div>
        </div>
        <div class="row mt">
          <el-button class="grow" :loading="busy" data-testid="m-save-draft" @click="save(false)">保存草稿</el-button>
          <el-button class="grow" type="primary" :loading="busy" data-testid="m-submit" @click="save(true)">提交填报</el-button>
        </div>
        <el-button v-if="submittedOnce && form.progress === 100" class="block mt" type="success" data-testid="m-submit-confirm" @click="submitConfirm"
          >完工，提交确认</el-button
        >
      </template>
      <div v-if="evidence.length" class="mt" data-testid="m-evidence">
        <div class="lbl">已上传证据（{{ evidence.length }}）</div>
        <div v-for="e in evidence" :key="e.id" class="sub">📷 {{ e.fileName }}</div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { WORK_STATUS, shiftLabel, type BoardWork } from '@/api/lsmom/productionModel';
import type { ProductionApi } from '@/api/lsmom/production';

/**
 * 13.3 移动填报：手机上看分派给自己的工作，开始、填报进度 / 人工 / 产出，拍照作为证据附件关联到该次填报；
 * 草稿保存到服务器（填报草稿）并在本机暂存未提交的表单。
 */
const props = defineProps<{ api: ProductionApi }>();

const works = ref<BoardWork[]>([]);
const cur = ref<BoardWork>();
const loading = ref(false);
const busy = ref(false);
const submittedOnce = ref(false);
const reportId = ref<number>();
const form = reactive({ progress: 0, laborMinutes: 0, outputQty: undefined as number | undefined, outputUnit: '', remark: '' });
const photos = ref<{ file: File; preview: string }[]>([]);
const evidence = ref<Record<string, any>[]>([]);

const draftKey = (id: number) => `ls-production-report-${id}`;
function readDraft(id: number): any {
  try {
    return JSON.parse(localStorage.getItem(draftKey(id)) ?? 'null');
  } catch {
    return null;
  }
}
function writeDraft(id: number, v: unknown) {
  try {
    if (v === null) localStorage.removeItem(draftKey(id));
    else localStorage.setItem(draftKey(id), JSON.stringify(v));
  } catch {
    /* 本机存储不可用时只保留服务器草稿 */
  }
}
const hasDraft = (id: number) => !!readDraft(id);

async function load() {
  loading.value = true;
  try {
    works.value = await props.api.board({ mine: true });
  } finally {
    loading.value = false;
  }
}

function open(w: BoardWork) {
  cur.value = w;
  const d = readDraft(w.id);
  Object.assign(
    form,
    { progress: Number(w.progress ?? 0), laborMinutes: 0, outputQty: undefined, outputUnit: w.unit ?? '', remark: '' },
    d?.form ?? {}
  );
  reportId.value = d?.reportId;
  photos.value = [];
  submittedOnce.value = false;
  evidence.value = [];
}

async function refresh() {
  await load();
  if (cur.value) cur.value = works.value.find((w) => w.id === cur.value!.id) ?? cur.value;
}

async function start() {
  await props.api.op('ls.core.Work.start', cur.value!.id, cur.value!.version);
  await refresh();
}

function onPhoto(e: Event) {
  const files = Array.from((e.target as HTMLInputElement).files ?? []);
  for (const f of files) photos.value.push({ file: f, preview: URL.createObjectURL(f) });
  (e.target as HTMLInputElement).value = '';
}

function input() {
  const o: Record<string, unknown> = { progress: form.progress, laborMinutes: form.laborMinutes };
  if (form.outputQty !== undefined && form.outputQty !== null) o.outputQty = form.outputQty;
  if (form.outputUnit) o.outputUnit = form.outputUnit;
  if (form.remark) o.remark = form.remark;
  if (reportId.value) o.reportId = reportId.value;
  return o;
}

async function save(submit: boolean) {
  const w = cur.value!;
  busy.value = true;
  try {
    const r = await props.api.op(submit ? 'ls.core.Work.submitReport' : 'ls.core.Work.saveReport', w.id, undefined, input());
    const id = Number(r.data?.reportId);
    reportId.value = id;
    // 照片作为该次填报的证据附件
    for (const p of photos.value) {
      const up = await props.api.upload(p.file);
      await props.api.attach(up.ossId, 'ls.core.WorkReport', id, 'EVIDENCE');
    }
    photos.value = [];
    evidence.value = await props.api.docs('ls.core.WorkReport', id);
    if (submit) {
      writeDraft(w.id, null);
      reportId.value = undefined;
      submittedOnce.value = true;
      ElMessage.success('填报已提交');
    } else {
      writeDraft(w.id, { reportId: id, form: { ...form } });
      ElMessage.success('草稿已保存');
    }
    await refresh();
  } finally {
    busy.value = false;
  }
}

async function submitConfirm() {
  await props.api.op('ls.core.Work.submitConfirm', cur.value!.id, cur.value!.version);
  ElMessage.success('已提交确认');
  cur.value = undefined;
  await load();
}

onMounted(load);
</script>

<style scoped>
.m {
  max-width: 520px;
  margin: 0 auto;
}
.m-head {
  display: flex;
  align-items: center;
  font-weight: 600;
  font-size: 16px;
  margin-bottom: 8px;
  gap: 4px;
}
.m-card {
  padding: 12px;
  border-radius: 8px;
  background: var(--el-bg-color);
  box-shadow: var(--el-box-shadow-lighter);
  margin-bottom: 10px;
}
.m-title {
  font-weight: 600;
  font-size: 15px;
}
.row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.grow {
  flex: 1;
}
.block {
  width: 100%;
  margin-top: 8px;
}
.lbl {
  display: block;
  margin: 12px 0 4px;
  font-size: 13px;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.draft {
  color: var(--el-color-warning);
  font-size: 12px;
}
.mt {
  margin-top: 10px;
}
.thumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}
.thumb {
  position: relative;
  width: 72px;
  height: 72px;
}
.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 4px;
}
.thumb .x {
  position: absolute;
  top: -6px;
  right: -6px;
  background: var(--el-color-danger);
  color: #fff;
  border-radius: 50%;
  width: 18px;
  height: 18px;
  text-align: center;
  line-height: 18px;
  cursor: pointer;
}
</style>
