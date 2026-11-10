<template>
  <div data-testid="handle-board">
    <el-table v-loading="loading" :data="topics" size="small" data-testid="handle-topics" @row-click="(r: Topic) => open(r)">
      <el-table-column label="议题" min-width="220">
        <template #default="{ row }">
          <span :data-testid="`handle-topic-${row.id}`">{{ row.title }}</span>
          <el-tag v-if="row.anonymous" size="small" type="info" class="ml">匿名</el-tag>
          <div class="sub">范围 {{ row.scope_name }} · 截止 {{ fmtTime(row.deadline) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90"
        ><template #default="{ row }">{{ TOPIC_STATUS[row.status] }}</template></el-table-column
      >
      <el-table-column label="意见" width="160">
        <template #default="{ row }">共 {{ row.total }} · 待答复 {{ row.pending }} · 转整改 {{ row.converted }}</template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="visible" size="820px" :title="cur?.title ?? ''" append-to-body>
      <template v-if="cur">
        <div class="bar">
          <el-tag size="small" data-testid="handle-topic-status">{{ TOPIC_STATUS[cur.status] }}</el-tag>
          <span v-if="cur.anonymous" class="sub">匿名征求：不显示提交人</span>
          <span class="grow" />
          <el-button v-if="cur.status === 'OPEN'" size="small" data-testid="btn-topic-close" @click="close">截止征求</el-button>
        </div>
        <el-table :data="opinions" size="small" data-testid="handle-opinions">
          <el-table-column label="#" width="60" prop="id" />
          <el-table-column label="意见" min-width="240">
            <template #default="{ row }">
              <div class="pre" :data-testid="`op-content-${row.id}`">{{ row.content }}</div>
              <div class="sub">
                <span :data-testid="`op-author-${row.id}`">{{ authorLabel(row) }}</span> · {{ fmtTime(row.submitted_at) }}
                <span v-if="row.category"> · {{ CATEGORIES[row.category] }}</span>
              </div>
              <div v-if="row.reply" class="reply">答复：{{ row.reply }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="OPINION_STATUS_TYPE[row.status]" :data-testid="`op-status-${row.id}`">{{
                OPINION_STATUS[row.status]
              }}</el-tag>
              <div v-if="row.issue_code" class="sub" :data-testid="`op-issue-${row.id}`">{{ row.issue_code }}</div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <template v-if="row.status !== 'CONVERTED'">
                <el-button link type="primary" :data-testid="`btn-reply-${row.id}`" @click="openReply(row)">答复</el-button>
                <el-button link type="danger" :data-testid="`btn-to-issue-${row.id}`" @click="openIssue(row)">转整改</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>

        <template v-if="cur.status === 'CLOSED'">
          <h4>咨询总结</h4>
          <template v-if="summary">
            <SummaryCard :summary="summary" />
            <div v-if="summary.status === 'DRAFT'" class="footer">
              <el-button data-testid="btn-summary-discard" @click="discard">作废草稿</el-button>
              <el-button type="primary" data-testid="btn-summary-publish" @click="publish">确认发布</el-button>
            </div>
          </template>
          <div v-else data-testid="summary-editor">
            <el-input v-model="sDraft.content" type="textarea" :rows="3" placeholder="总结" data-testid="summary-content" />
            <div v-for="(p, i) in sDraft.points" :key="i" class="point">
              <el-input v-model="p.viewpoint" :placeholder="`观点 ${i + 1}`" :data-testid="`point-text-${i}`" />
              <el-select v-model="p.opinionIds" multiple placeholder="引用来源意见" class="cite" :data-testid="`point-cites-${i}`">
                <el-option v-for="o in opinions" :key="o.id" :label="`#${o.id} ${o.content.slice(0, 20)}`" :value="o.id" />
              </el-select>
              <el-button link type="danger" @click="sDraft.points.splice(i, 1)">删除</el-button>
            </div>
            <div v-for="m in sErrors" :key="m" class="err">{{ m }}</div>
            <div class="footer">
              <el-button data-testid="btn-point-add" @click="sDraft.points.push({ viewpoint: '', opinionIds: [] })">添加观点</el-button>
              <el-button type="primary" data-testid="btn-summary-draft" @click="saveDraft">保存草稿</el-button>
            </div>
          </div>
        </template>
      </template>
    </el-drawer>

    <el-dialog v-model="replyVisible" title="答复意见" width="520px" append-to-body>
      <el-input v-model="reply.text" type="textarea" :rows="3" data-testid="reply-text" />
      <el-select v-model="reply.category" clearable placeholder="归类" class="mt" data-testid="reply-category">
        <el-option v-for="(v, k) in CATEGORIES" :key="k" :label="v" :value="k" />
      </el-select>
      <template #footer>
        <el-button @click="replyVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!reply.text.trim()" data-testid="btn-reply-save" @click="saveReply">答复</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="issueVisible" title="转整改" width="520px" append-to-body>
      <el-form label-width="80px">
        <el-form-item label="问题标题"><el-input v-model="issue.title" placeholder="默认取意见内容" data-testid="issue-title" /></el-form-item>
        <el-form-item label="严重程度">
          <el-radio-group v-model="issue.severity">
            <el-radio-button value="LOW">低</el-radio-button>
            <el-radio-button value="MEDIUM">中</el-radio-button>
            <el-radio-button value="HIGH" data-testid="issue-sev-HIGH">高</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="答复"
          ><el-input v-model="issue.reply" type="textarea" :rows="2" placeholder="可选" data-testid="issue-reply"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueVisible = false">取消</el-button>
        <el-button type="primary" data-testid="btn-issue-save" @click="saveIssue">生成整改问题</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { ConsultationApi, SummaryView } from '@/api/lsmom/consultation';
import {
  CATEGORIES,
  OPINION_STATUS,
  OPINION_STATUS_TYPE,
  TOPIC_STATUS,
  authorLabel,
  fmtTime,
  summaryErrors,
  type DraftPoint,
  type Opinion,
  type Topic
} from '@/api/lsmom/consultationModel';
import SummaryCard from '../SummaryCard.vue';

/** 意见处理：议题 → 意见（匿名议题不显示提交人）→ 答复归类 / 转整改 → 截止后起草总结（观点引用意见）→ 确认发布。 */
const props = defineProps<{ api: ConsultationApi }>();

const topics = ref<Topic[]>([]);
const loading = ref(false);
const visible = ref(false);
const cur = ref<Topic>();
const opinions = ref<Opinion[]>([]);
const summary = ref<SummaryView | null>(null);
const replyVisible = ref(false);
const issueVisible = ref(false);
const target = ref<Opinion>();
const reply = reactive({ text: '', category: '' });
const issue = reactive({ title: '', severity: 'MEDIUM', reply: '' });
const sDraft = reactive<{ content: string; points: DraftPoint[] }>({ content: '', points: [{ viewpoint: '', opinionIds: [] }] });
const sErrors = ref<string[]>([]);

const fail = (e: any) => ElMessage.error(e?.message ?? String(e));

async function load() {
  loading.value = true;
  try {
    topics.value = await props.api.topics();
  } finally {
    loading.value = false;
  }
}

async function open(t: Topic) {
  cur.value = t;
  await refresh();
  visible.value = true;
}

async function refresh() {
  if (!cur.value) return;
  const id = cur.value.id;
  [opinions.value, summary.value] = await Promise.all([
    props.api.opinions(id),
    cur.value.status === 'CLOSED' ? props.api.summary(id) : Promise.resolve(null)
  ]);
}

async function reloadTopic() {
  await load();
  cur.value = topics.value.find((t) => t.id === cur.value?.id) ?? cur.value;
  await refresh();
}

function openReply(o: Opinion) {
  target.value = o;
  reply.text = o.reply ?? '';
  reply.category = o.category ?? '';
  replyVisible.value = true;
}

async function saveReply() {
  if (!target.value) return;
  try {
    await props.api.reply(target.value, reply.text.trim(), reply.category || undefined);
    ElMessage.success('已答复');
    replyVisible.value = false;
    await reloadTopic();
  } catch (e) {
    fail(e);
  }
}

function openIssue(o: Opinion) {
  target.value = o;
  issue.title = '';
  issue.severity = 'MEDIUM';
  issue.reply = '';
  issueVisible.value = true;
}

async function saveIssue() {
  if (!target.value) return;
  try {
    const input: Record<string, string> = { severity: issue.severity };
    if (issue.title.trim()) input.title = issue.title.trim();
    if (issue.reply.trim()) input.reply = issue.reply.trim();
    await props.api.toIssue(target.value, input);
    ElMessage.success('已转整改');
    issueVisible.value = false;
    await reloadTopic();
  } catch (e) {
    fail(e);
  }
}

async function close() {
  if (!cur.value) return;
  try {
    await props.api.closeTopic(cur.value);
    ElMessage.success('已截止');
    await reloadTopic();
  } catch (e) {
    fail(e);
  }
}

async function saveDraft() {
  if (!cur.value) return;
  sErrors.value = summaryErrors(sDraft.content, sDraft.points);
  if (sErrors.value.length) return;
  try {
    await props.api.draftSummary(
      cur.value.id,
      sDraft.content.trim(),
      sDraft.points.map((p) => ({ viewpoint: p.viewpoint.trim(), opinionIds: [...p.opinionIds] }))
    );
    ElMessage.success('总结草稿已保存');
    await refresh();
  } catch (e) {
    fail(e);
  }
}

async function publish() {
  if (!summary.value) return;
  try {
    await props.api.publishSummary(summary.value);
    ElMessage.success('总结已发布');
    await reloadTopic();
  } catch (e) {
    fail(e);
  }
}

async function discard() {
  if (!summary.value) return;
  try {
    await props.api.discardSummary(summary.value);
    await refresh();
  } catch (e) {
    fail(e);
  }
}

onMounted(load);
defineExpose({ load, open, sDraft, saveDraft });
</script>

<style scoped>
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.ml {
  margin-left: 6px;
}
.mt {
  margin-top: 8px;
}
.bar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
}
.grow {
  flex: 1;
}
.pre {
  white-space: pre-wrap;
}
.reply {
  margin-top: 4px;
  padding: 4px 8px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: 12px;
}
.point {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.cite {
  width: 280px;
}
.err {
  color: var(--el-color-danger);
  font-size: 12px;
  margin-top: 4px;
}
.footer {
  margin-top: 10px;
  text-align: right;
}
</style>
