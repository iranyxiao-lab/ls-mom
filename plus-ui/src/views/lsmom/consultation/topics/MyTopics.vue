<template>
  <div data-testid="my-topics">
    <el-table v-loading="loading" :data="topics" size="small" data-testid="topic-table" empty-text="暂无可参与的议题">
      <el-table-column label="议题" min-width="220">
        <template #default="{ row }">
          <span :data-testid="`topic-row-${row.id}`">{{ row.title }}</span>
          <el-tag v-if="row.anonymous" size="small" type="info" class="ml">匿名</el-tag>
          <div class="sub">{{ row.description }}</div>
        </template>
      </el-table-column>
      <el-table-column label="截止时间" width="150"
        ><template #default="{ row }">{{ fmtTime(row.deadline) }}</template></el-table-column
      >
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'OPEN' ? 'primary' : 'info'" :data-testid="`topic-status-${row.id}`">{{
            TOPIC_STATUS[row.status]
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="我的意见" width="90">
        <template #default="{ row }">
          <el-link v-if="row.my_count" type="primary" :data-testid="`topic-mine-${row.id}`" @click="openMine(row)">{{ row.my_count }} 条</el-link>
          <span v-else class="sub">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button v-if="row.status === 'OPEN'" link type="primary" :data-testid="`btn-opine-${row.id}`" @click="openNew(row)">提交意见</el-button>
          <el-button v-if="row.summary_id" link :data-testid="`btn-summary-${row.id}`" @click="openSummary(row)">查看总结</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="newVisible" :title="cur?.title ?? ''" width="560px" append-to-body>
      <p v-if="cur?.anonymous" class="hint" data-testid="anonymous-hint">本议题为匿名征求：答复人看不到你的姓名。</p>
      <el-form label-width="70px">
        <el-form-item label="意见" required :error="error">
          <el-input v-model="draft.content" type="textarea" :rows="4" maxlength="4000" show-word-limit data-testid="opinion-content" />
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="draft.category" clearable data-testid="opinion-category">
            <el-option v-for="(v, k) in CATEGORIES" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" data-testid="btn-opinion-save" @click="save">提交</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="mineVisible" size="560px" :title="`我的意见 · ${cur?.title ?? ''}`" append-to-body>
      <div v-for="o in mine" :key="o.id" class="op" :data-testid="`mine-${o.id}`">
        <div class="pre">{{ o.content }}</div>
        <div class="sub">
          {{ fmtTime(o.submitted_at) }} · <span :data-testid="`mine-status-${o.id}`">{{ OPINION_STATUS[o.status] }}</span>
          <span v-if="o.issue_code"> · 整改问题 {{ o.issue_code }}</span>
        </div>
        <div v-if="o.reply" class="reply" :data-testid="`mine-reply-${o.id}`">答复：{{ o.reply }}</div>
      </div>
    </el-drawer>

    <el-drawer v-model="summaryVisible" size="600px" :title="`咨询总结 · ${cur?.title ?? ''}`" append-to-body>
      <SummaryCard v-if="summary" :summary="summary" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { ConsultationApi, SummaryView } from '@/api/lsmom/consultation';
import { CATEGORIES, OPINION_STATUS, TOPIC_STATUS, fmtTime, type Opinion, type Topic } from '@/api/lsmom/consultationModel';
import SummaryCard from '../SummaryCard.vue';

/** 参与征求：范围内的议题 → 提交意见（匿名议题不显示姓名）→ 查看答复、是否转整改 → 截止后查看总结。 */
const props = defineProps<{ api: ConsultationApi }>();

const topics = ref<Topic[]>([]);
const loading = ref(false);
const cur = ref<Topic>();
const newVisible = ref(false);
const mineVisible = ref(false);
const summaryVisible = ref(false);
const busy = ref(false);
const error = ref('');
const draft = reactive({ content: '', category: '' });
const mine = ref<Opinion[]>([]);
const summary = ref<SummaryView | null>(null);

async function load() {
  loading.value = true;
  try {
    topics.value = await props.api.myTopics();
  } finally {
    loading.value = false;
  }
}

function openNew(t: Topic) {
  cur.value = t;
  draft.content = '';
  draft.category = '';
  error.value = '';
  newVisible.value = true;
}

async function save() {
  if (!cur.value) return;
  if (!draft.content.trim()) {
    error.value = '请填写意见';
    return;
  }
  busy.value = true;
  try {
    await props.api.submit(cur.value.id, draft.content.trim(), draft.category || undefined);
    ElMessage.success('意见已提交');
    newVisible.value = false;
    await load();
  } catch (e: any) {
    ElMessage.error(e?.message ?? String(e));
  } finally {
    busy.value = false;
  }
}

async function openMine(t: Topic) {
  cur.value = t;
  mine.value = await props.api.myOpinions(t.id);
  mineVisible.value = true;
}

async function openSummary(t: Topic) {
  cur.value = t;
  summary.value = await props.api.summary(t.id);
  summaryVisible.value = true;
}

onMounted(load);
defineExpose({ load, openNew, openMine });
</script>

<style scoped>
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.ml {
  margin-left: 6px;
}
.hint {
  color: var(--el-color-info);
  margin: 0 0 8px;
}
.pre {
  white-space: pre-wrap;
}
.op {
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.reply {
  margin-top: 4px;
  padding: 6px 8px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
</style>
