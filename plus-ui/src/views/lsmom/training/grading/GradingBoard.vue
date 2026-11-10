<template>
  <div data-testid="grading-board">
    <el-table v-loading="loading" :data="rows" size="small" data-testid="grading-table" empty-text="没有待评的简答题">
      <el-table-column label="考试" min-width="180" prop="exam_title" />
      <el-table-column label="考生" width="120">
        <template #default="{ row }"
          ><span :data-testid="`grading-row-${row.id}`">{{ row.candidate_name }}</span></template
        >
      </el-table-column>
      <el-table-column label="交卷时间" width="160">
        <template #default="{ row }">{{
          String(row.submitted_at ?? '')
            .replace('T', ' ')
            .slice(0, 16)
        }}</template>
      </el-table-column>
      <el-table-column label="客观题" width="80"
        ><template #default="{ row }">{{ Number(row.objective_score ?? 0) }}</template></el-table-column
      >
      <el-table-column label="操作" width="80">
        <template #default="{ row }"
          ><el-button link type="primary" :data-testid="`btn-grade-${row.id}`" @click="open(row.id)">评分</el-button></template
        >
      </el-table-column>
    </el-table>

    <el-drawer v-model="visible" size="640px" :title="sheet ? `${sheet.examTitle} · ${sheet.candidateName}` : ''" append-to-body>
      <template v-if="sheet">
        <div v-for="it in essays" :key="it.seq" class="item" :data-testid="`essay-${it.seq}`">
          <div class="stem">{{ it.seq }}. [{{ Number(it.score) }} 分] {{ it.stem }}</div>
          <div class="label">考生作答</div>
          <p class="pre">{{ it.response }}</p>
          <template v-if="it.referenceAnswer">
            <div class="label">参考答案</div>
            <p class="pre sub">{{ it.referenceAnswer }}</p>
          </template>
          <div class="score">
            得分 <el-input-number v-model="scores[it.seq]" :min="0" :max="Number(it.score)" :step="1" :data-testid="`essay-score-${it.seq}`" />
            <span v-if="errors[it.seq]" class="err" :data-testid="`essay-error-${it.seq}`">{{ errors[it.seq] }}</span>
          </div>
        </div>
        <div class="footer"><el-button type="primary" data-testid="btn-grade-save" :loading="busy" @click="save">提交评分</el-button></div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { AnswerSheet, TrainingApi } from '@/api/lsmom/training';
import { essayErrors } from '@/api/lsmom/trainingModel';

/** 阅卷：待评简答的答卷（数据域内）→ 对照参考答案逐题评分 → 全部评完出总分，及格生成培训记录。不能给自己的答卷评分。 */
const props = defineProps<{ api: TrainingApi }>();

const rows = ref<Record<string, any>[]>([]);
const loading = ref(false);
const visible = ref(false);
const busy = ref(false);
const sheet = ref<AnswerSheet>();
const scores = reactive<Record<string, number | undefined>>({});
const errors = ref<Record<string, string>>({});
const essays = computed(() => (sheet.value?.items ?? []).filter((i) => i.qtype === 'ESSAY' && i.earned == null));

async function load() {
  loading.value = true;
  try {
    rows.value = await props.api.grading();
  } finally {
    loading.value = false;
  }
}

async function open(id: number) {
  sheet.value = await props.api.answer(id);
  for (const k of Object.keys(scores)) delete scores[k];
  errors.value = {};
  visible.value = true;
}

async function save() {
  if (!sheet.value) return;
  errors.value = essayErrors(sheet.value.items, scores);
  if (Object.keys(errors.value).length) return;
  const body: Record<string, number> = {};
  for (const it of essays.value) body[String(it.seq)] = Number(scores[it.seq]);
  busy.value = true;
  try {
    await props.api.gradeEssay(sheet.value.id, sheet.value.version, body);
    ElMessage.success('已评分');
    visible.value = false;
    await load();
  } catch (e: any) {
    ElMessage.error(e?.message ?? String(e));
  } finally {
    busy.value = false;
  }
}

onMounted(load);
defineExpose({ load, open });
</script>

<style scoped>
.item {
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.stem {
  font-weight: 500;
  white-space: pre-wrap;
}
.label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 6px;
}
.pre {
  white-space: pre-wrap;
  margin: 4px 0;
}
.sub {
  color: var(--el-text-color-secondary);
}
.score {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 6px;
}
.err {
  color: var(--el-color-danger);
  font-size: 12px;
}
.footer {
  margin-top: 12px;
  text-align: right;
}
</style>
