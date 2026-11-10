<template>
  <div data-testid="knowledge-center">
    <el-tabs v-model="tab">
      <el-tab-pane label="问答" name="ask">
        <div class="ask">
          <el-input
            v-model="question"
            data-testid="in-question"
            placeholder="例如：本周一车间甲班异常数；检修前怎么挂牌上锁"
            clearable
            @keyup.enter="ask"
          />
          <el-button type="primary" :loading="asking" data-testid="btn-ask" @click="ask">提问</el-button>
        </div>
        <p class="sub">业务数字只来自已发布的指标，可钻取到记录；文档问答只引用你有权查看的已发布版本。</p>
        <el-card v-if="answer" shadow="never" class="answer" data-testid="answer">
          <template v-if="answer.type === 'METRIC'">
            <div class="value" data-testid="answer-value">
              {{ answer.value }}<small>{{ answer.unit }}</small>
            </div>
            <p data-testid="answer-text">{{ answer.answer }}</p>
            <div class="meta">
              <el-tag size="small" type="success" data-testid="answer-metric">指标 {{ answer.metric?.code }} v{{ answer.metric?.version }}</el-tag>
              <span class="sub">共 {{ answer.drill?.total ?? 0 }} 条记录{{ (answer.drill?.total ?? 0) > (answer.refs?.length ?? 0) ? `，显示前 ${answer.refs?.length}` : '' }}</span>
            </div>
            <div class="refs">
              <el-tag v-for="r in answer.refs" :key="r.id" size="small" effect="plain" :data-testid="`answer-ref-${r.id}`">{{ r.label }}</el-tag>
            </div>
          </template>
          <template v-else>
            <p class="text" data-testid="answer-text">{{ answer.answer }}</p>
            <ol v-if="answer.citations.length" class="cites">
              <li v-for="c in answer.citations" :key="c.n" :data-testid="`cite-${c.n}`">
                <b>[{{ c.n }}] {{ c.title }}</b>
                <span class="sub">{{ c.documentCode }} v{{ c.version }}{{ c.heading ? ` · ${c.heading}` : '' }}</span>
                <div class="excerpt">{{ c.excerpt }}</div>
              </li>
            </ol>
          </template>
        </el-card>
      </el-tab-pane>

      <el-tab-pane v-if="canManage" label="文档" name="docs">
        <div class="bar">
          <el-button data-testid="btn-new-doc" @click="openNew">新建文档</el-button>
          <el-button @click="loadDocs">刷新</el-button>
        </div>
        <el-table :data="docs" data-testid="table-docs" @row-click="openDoc">
          <el-table-column prop="code" label="编码" width="180" />
          <el-table-column prop="title" label="标题" min-width="220" />
          <el-table-column label="组织" width="140"><template #default="{ row }">{{ row.orgName ?? '全公司' }}</template></el-table-column>
          <el-table-column prop="classification" label="密级" width="110" />
          <el-table-column label="发布版本" width="100"><template #default="{ row }">{{ row.publishedVersion ? `v${row.publishedVersion}` : '未发布' }}</template></el-table-column>
          <el-table-column label="修订稿" width="100">
            <template #default="{ row }"
              ><el-tag v-if="row.draftVersion" size="small" type="warning">v{{ row.draftVersion }} 待发布</el-tag></template
            >
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="drawer" size="60%" :title="doc ? `${doc.code} ${doc.title}` : ''" data-testid="doc-drawer">
      <template v-if="doc">
        <el-alert v-if="published" type="success" :closable="false" :title="`当前发布版本 v${published.version}（问答引用此版本）`" />
        <pre v-if="published" class="content" data-testid="published-content">{{ published.content }}</pre>
        <h4>修订稿{{ draft ? ` v${draft.version}` : '' }}</h4>
        <el-input v-model="draftText" type="textarea" :rows="10" data-testid="in-draft" />
        <el-input v-model="draftNote" placeholder="修订说明" class="mt" data-testid="in-draft-note" />
        <div class="actions">
          <el-button data-testid="btn-save-draft" @click="saveDraft">保存修订稿</el-button>
          <el-button v-if="canPublish" type="primary" :disabled="!draft" data-testid="btn-publish-doc" @click="publish">发布修订稿</el-button>
        </div>
        <p class="sub">修订稿在发布前不会被检索或引用。</p>
      </template>
    </el-drawer>

    <el-dialog v-model="creating" title="新建知识库文档" width="640px">
      <el-form label-width="80px">
        <el-form-item label="编码"><el-input v-model="form.code" data-testid="in-doc-code" /></el-form-item>
        <el-form-item label="标题"><el-input v-model="form.title" data-testid="in-doc-title" /></el-form-item>
        <el-form-item label="组织 ID"><el-input-number v-model="form.orgUnitId" :controls="false" data-testid="in-doc-org" /></el-form-item>
        <el-form-item label="密级">
          <el-select v-model="form.classification" data-testid="in-doc-class">
            <el-option v-for="c in ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SECRET']" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="正文"><el-input v-model="form.content" type="textarea" :rows="8" data-testid="in-doc-content" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="creating = false">取消</el-button>
        <el-button type="primary" data-testid="btn-create-doc" @click="create">保存为修订稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { Answer, KnowledgeApi, KnowledgeDoc } from '@/api/lsmom/knowledge';

/** 知识问答（指标问数＋带引用的文档问答）与知识库维护（修订稿 → 真人发布）。 */
const props = withDefaults(defineProps<{ api: KnowledgeApi; canManage?: boolean; canPublish?: boolean }>(), { canManage: false, canPublish: false });

const tab = ref('ask');
const question = ref('');
const asking = ref(false);
const answer = ref<Answer>();
const docs = ref<KnowledgeDoc[]>([]);
const drawer = ref(false);
const doc = ref<KnowledgeDoc>();
const draftText = ref('');
const draftNote = ref('');
const creating = ref(false);
const form = reactive<{ code: string; title: string; orgUnitId?: number; classification: string; content: string }>({
  code: '',
  title: '',
  orgUnitId: undefined,
  classification: 'INTERNAL',
  content: ''
});

const published = computed(() => doc.value?.versions?.find((v) => v.status === 'PUBLISHED'));
const draft = computed(() => doc.value?.versions?.find((v) => v.status === 'DRAFT'));

async function ask() {
  if (!question.value.trim()) return;
  asking.value = true;
  try {
    answer.value = await props.api.ask(question.value.trim());
  } finally {
    asking.value = false;
  }
}

async function loadDocs() {
  if (props.canManage) docs.value = await props.api.list();
}

function show(d: KnowledgeDoc) {
  doc.value = d;
  draftText.value = (d.versions?.find((v) => v.status === 'DRAFT') ?? d.versions?.find((v) => v.status === 'PUBLISHED'))?.content ?? '';
  draftNote.value = d.versions?.find((v) => v.status === 'DRAFT')?.changeNote ?? '';
}

async function openDoc(row: KnowledgeDoc) {
  show(await props.api.get(row.id));
  drawer.value = true;
}

async function saveDraft() {
  if (!doc.value) return;
  show(await props.api.saveDraft(doc.value.id, draftText.value, draftNote.value));
  ElMessage.success('修订稿已保存');
  await loadDocs();
}

async function publish() {
  if (!doc.value) return;
  show(await props.api.publish(doc.value.id));
  ElMessage.success(`已发布 v${doc.value.publishedVersion}`);
  await loadDocs();
}

function openNew() {
  Object.assign(form, { code: '', title: '', orgUnitId: undefined, classification: 'INTERNAL', content: '' });
  creating.value = true;
}

async function create() {
  const d = await props.api.create({ ...form, category: 'SOP' });
  creating.value = false;
  ElMessage.success('已创建修订稿');
  await loadDocs();
  show(d);
  drawer.value = true;
}

onMounted(loadDocs);
defineExpose({ ask, loadDocs });
</script>

<style scoped>
.ask {
  display: flex;
  gap: 8px;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.answer {
  margin-top: 8px;
}
.value {
  font-size: 32px;
  font-weight: 600;
}
.value small {
  font-size: 14px;
  margin-left: 4px;
}
.meta,
.refs,
.bar,
.actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  margin: 8px 0;
}
.text,
.content {
  white-space: pre-wrap;
}
.cites li {
  margin-bottom: 8px;
}
.excerpt {
  color: var(--el-text-color-regular);
  font-size: 13px;
  white-space: pre-wrap;
}
.mt {
  margin-top: 8px;
}
</style>
