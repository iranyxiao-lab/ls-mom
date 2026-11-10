<template>
  <div data-testid="my-exams">
    <el-tabs v-model="tab">
      <el-tab-pane label="我的考试" name="exams">
        <el-table v-loading="loading" :data="exams" size="small" data-testid="exam-table">
          <el-table-column label="考试" min-width="200">
            <template #default="{ row }">
              <span :data-testid="`exam-row-${row.id}`">{{ row.title }}</span>
              <div class="sub">{{ row.course_title }} · 及格 {{ num(row.pass_score) }} / 满分 {{ num(row.total_score) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="时间" min-width="200">
            <template #default="{ row }"
              >{{ dt(row.start_at) }} ~ {{ dt(row.end_at) }}
              <div class="sub">限时 {{ row.duration_minutes }} 分钟</div></template
            >
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="phaseType[examPhase(row)]" :data-testid="`exam-phase-${row.id}`">{{ PHASE_LABEL[examPhase(row)] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="成绩" width="110">
            <template #default="{ row }">
              <span v-if="examPhase(row) === 'DONE'" :data-testid="`exam-score-${row.id}`">
                {{ num(row.score) }} 分 <el-tag size="small" :type="row.passed ? 'success' : 'danger'">{{ row.passed ? '及格' : '不及格' }}</el-tag>
              </span>
              <span v-else class="sub">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button v-if="examPhase(row) === 'OPEN'" link type="primary" :data-testid="`btn-exam-start-${row.id}`" @click="start(row)"
                >开始考试</el-button
              >
              <el-button
                v-else-if="examPhase(row) === 'IN_PROGRESS'"
                link
                type="primary"
                :data-testid="`btn-exam-resume-${row.id}`"
                @click="start(row)"
                >继续作答</el-button
              >
              <el-button v-else-if="row.answer_id" link :data-testid="`btn-exam-view-${row.id}`" @click="openSheet(row.answer_id)"
                >查看答卷</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="我的培训记录" name="records">
        <el-table :data="records" size="small" data-testid="record-table">
          <el-table-column label="课程" min-width="200">
            <template #default="{ row }">{{ row.course_code }} {{ row.course_title }}</template>
          </el-table-column>
          <el-table-column label="成绩" width="80"
            ><template #default="{ row }">{{ num(row.score) }}</template></el-table-column
          >
          <el-table-column label="通过日期" width="120" prop="passed_on" />
          <el-table-column label="有效期至" width="120">
            <template #default="{ row }">
              <span :class="{ expired: isExpired(row.valid_until) }" :data-testid="`record-valid-${row.id}`">{{ row.valid_until || '长期' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="visible" :title="sheet?.examTitle ?? ''" width="760px" append-to-body :close-on-click-modal="false" @closed="stopTimer">
      <template v-if="sheet">
        <div v-if="sheet.status === 'IN_PROGRESS'" class="bar">
          <span
            >剩余时间 <b :class="{ warn: remain < 60 }" data-testid="exam-countdown">{{ fmtCountdown(remain) }}</b></span
          >
          <span class="sub">到时未交卷将按已保存的作答自动交卷</span>
        </div>
        <div v-else class="bar" data-testid="exam-result">
          <template v-if="sheet.essayPending">客观题 {{ num(sheet.objectiveScore) }} 分，简答题待评</template>
          <template v-else>
            总分 <b data-testid="exam-result-score">{{ num(sheet.totalScore) }}</b> 分 ·
            <el-tag size="small" :type="sheet.passed ? 'success' : 'danger'">{{ sheet.passed ? '及格' : '不及格' }}</el-tag>
          </template>
          <span v-if="sheet.autoSubmitted" class="sub">（到时自动交卷）</span>
        </div>
        <div v-for="it in sheet.items" :key="it.seq" class="item" :data-testid="`q-${it.seq}`">
          <div class="stem">
            {{ it.seq }}. [{{ QTYPES[it.qtype] }}，{{ num(it.score) }} 分] {{ it.stem }}
            <span v-if="it.earned != null" class="sub" :data-testid="`q-earned-${it.seq}`">得 {{ num(it.earned) }} 分</span>
          </div>
          <template v-if="it.qtype === 'ESSAY'">
            <el-input v-model="resp[it.seq]" type="textarea" :rows="3" :readonly="!editable" :data-testid="`q-input-${it.seq}`" />
          </template>
          <el-checkbox-group v-else-if="it.qtype === 'MULTI'" v-model="multi[it.seq]" :disabled="!editable">
            <el-checkbox v-for="c in parseChoices(it.choices)" :key="c.key" :value="c.key" :data-testid="`q-${it.seq}-${c.key}`"
              >{{ c.key }}. {{ c.content }}</el-checkbox
            >
          </el-checkbox-group>
          <el-radio-group v-else v-model="resp[it.seq]" :disabled="!editable">
            <el-radio v-for="c in parseChoices(it.choices)" :key="c.key" :value="c.key" :data-testid="`q-${it.seq}-${c.key}`"
              >{{ c.key }}. {{ c.content }}</el-radio
            >
          </el-radio-group>
        </div>
      </template>
      <template #footer>
        <template v-if="editable">
          <el-button data-testid="btn-exam-save" :loading="busy" @click="save()">保存</el-button>
          <el-button type="primary" data-testid="btn-exam-submit" :loading="busy" @click="submit">交卷</el-button>
        </template>
        <el-button v-else @click="visible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { AnswerSheet, TrainingApi } from '@/api/lsmom/training';
import {
  PHASE_LABEL,
  QTYPES,
  examPhase,
  fmtCountdown,
  joinKeys,
  parseChoices,
  remainingSeconds,
  splitKeys,
  type MyExam
} from '@/api/lsmom/trainingModel';

/** 我的考试：开考（限时，倒计时）→ 作答、保存 → 交卷（客观题即时出分，简答待评）；到时未交由系统按已保存作答自动交卷。 */
const props = defineProps<{ api: TrainingApi }>();

const tab = ref('exams');
const loading = ref(false);
const exams = ref<MyExam[]>([]);
const records = ref<Record<string, any>[]>([]);
const visible = ref(false);
const sheet = ref<AnswerSheet>();
const resp = reactive<Record<string, string>>({});
const multi = reactive<Record<string, string[]>>({});
const remain = ref(0);
const busy = ref(false);
let timer: ReturnType<typeof setInterval> | undefined;
/** 本机时钟下的截止时刻（由服务器剩余秒数换算，避免浏览器与服务器时区或时钟不一致）。 */
let endsAt = 0;

const phaseType: Record<string, any> = {
  NOT_STARTED: 'info',
  OPEN: 'primary',
  IN_PROGRESS: 'warning',
  PENDING: 'warning',
  DONE: 'success',
  MISSED: 'info'
};
const editable = computed(() => sheet.value?.status === 'IN_PROGRESS' && remain.value > 0);
const num = (v: unknown) => (v == null || v === '' ? '—' : String(Number(v)));
const dt = (v?: string) => (v ? String(v).replace('T', ' ').slice(0, 16) : '');
const isExpired = (d?: string) => !!d && d < new Date().toISOString().slice(0, 10);

async function load() {
  loading.value = true;
  try {
    [exams.value, records.value] = await Promise.all([props.api.myExams(), props.api.myRecords()]);
  } finally {
    loading.value = false;
  }
}

async function start(row: MyExam) {
  try {
    const r = await props.api.start(row.id);
    await openSheet(r.data?.answerId ?? row.answer_id);
  } catch (e: any) {
    ElMessage.error(e?.message ?? String(e));
  }
}

async function openSheet(id: number | string) {
  const s = await props.api.answer(id);
  sheet.value = s;
  for (const k of Object.keys(resp)) delete resp[k];
  for (const k of Object.keys(multi)) delete multi[k];
  for (const it of s.items) {
    if (it.qtype === 'MULTI') multi[it.seq] = splitKeys(it.response);
    else resp[it.seq] = it.response ?? '';
  }
  visible.value = true;
  stopTimer();
  if (s.status === 'IN_PROGRESS') {
    endsAt = s.remainingSeconds != null ? Date.now() + s.remainingSeconds * 1000 : Date.now() + remainingSeconds(s.deadline) * 1000;
    remain.value = Math.max(0, Math.floor((endsAt - Date.now()) / 1000));
    timer = setInterval(tick, 1000);
  }
}

function tick() {
  remain.value = Math.max(0, Math.floor((endsAt - Date.now()) / 1000));
  if (remain.value <= 0) {
    stopTimer();
    ElMessage.warning('作答时间已到，系统将按已保存的作答自动交卷');
  }
}

function stopTimer() {
  if (timer) clearInterval(timer);
  timer = undefined;
}

function responses(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const it of sheet.value?.items ?? []) {
    const v = it.qtype === 'MULTI' ? joinKeys(multi[it.seq] ?? []) : (resp[it.seq] ?? '');
    if (v !== '') out[String(it.seq)] = v;
  }
  return out;
}

async function save(quiet = false) {
  if (!sheet.value) return;
  busy.value = true;
  try {
    const r = await props.api.save(sheet.value.id, sheet.value.version, responses());
    sheet.value.version = r.version;
    if (!quiet) ElMessage.success('已保存');
  } catch (e: any) {
    ElMessage.error(e?.message ?? String(e));
  } finally {
    busy.value = false;
  }
}

async function submit() {
  if (!sheet.value) return;
  const blank = sheet.value.items.length - Object.keys(responses()).length;
  try {
    await ElMessageBox.confirm(blank > 0 ? `还有 ${blank} 题未作答，确定交卷？` : '确定交卷？交卷后不能修改。', '交卷', { type: 'warning' });
  } catch {
    return;
  }
  busy.value = true;
  try {
    await props.api.submit(sheet.value.id, sheet.value.version, responses());
    ElMessage.success('已交卷');
    await openSheet(sheet.value.id);
    await load();
  } catch (e: any) {
    ElMessage.error(e?.message ?? String(e));
  } finally {
    busy.value = false;
  }
}

onMounted(load);
onBeforeUnmount(stopTimer);
defineExpose({ load, openSheet, responses });
</script>

<style scoped>
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.bar {
  display: flex;
  gap: var(--ls-space-4);
  align-items: center;
  margin-bottom: var(--ls-space-3);
}
.warn {
  color: var(--el-color-danger);
}
.expired {
  color: var(--el-color-danger);
}
.item {
  padding: var(--ls-space-3) 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.stem {
  margin-bottom: var(--ls-space-2);
  white-space: pre-wrap;
}
</style>
