<template>
  <div class="ls-trial" data-testid="trial">
    <el-alert type="info" :closable="false" show-icon class="ls-trial__note">
      用当前草稿填写一张单据，以各节点候选办理人的身份走完流程。试运行在沙箱事务中执行，结束后全部回滚，正式库不留任何记录；
      这里只保留试运行轨迹，可一键清除。
    </el-alert>
    <el-empty v-if="error" :description="error" data-testid="trial-unavailable" />
    <template v-else-if="setup">
      <div class="ls-trial__flow" data-testid="trial-flow">
        流程：<strong>{{ setup.flowName }}</strong> v{{ setup.flowVersion }}　节点：
        <span v-for="(n, i) in setup.nodes" :key="n.key">{{ i ? ' → ' : '' }}{{ n.name }}</span>
      </div>
      <el-form label-width="96px" size="small" class="ls-trial__form">
        <el-form-item label="发起人">
          <el-select v-model="initiator" placeholder="有提交权限的人" data-testid="trial-initiator" style="width: 280px">
            <el-option
              v-for="p in setup.initiators"
              :key="p.party"
              :value="p.party"
              :label="p.name + (p.orgName ? '（' + p.orgName + '）' : '')"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="ls-trial__section">发起人填写</div>
      <SchemaForm v-model="model" :schema="schema" :api="api" :show-actions="false" data-testid="trial-form" />
      <div v-for="n in setup.nodes" :key="n.key" class="ls-trial__node" :data-testid="'trial-node-' + n.key">
        <div class="ls-trial__section">{{ n.name }}</div>
        <el-radio-group v-model="steps[n.key].decision" size="small" :data-testid="'trial-decision-' + n.key">
          <el-radio-button value="APPROVE">通过</el-radio-button>
          <el-radio-button value="REJECT">驳回</el-radio-button>
        </el-radio-group>
        <div v-for="f in editableOf(n.policyKey)" :key="f.key" class="ls-trial__field">
          <span>{{ f.label }}<em v-if="f.required">*</em></span>
          <el-input v-model="inputs[n.key][f.key]" size="small" :data-testid="'trial-input-' + n.key + '-' + f.key" />
        </div>
      </div>
      <div class="ls-trial__bar">
        <el-button type="primary" :loading="busy" data-testid="trial-run" @click="run">开始试运行</el-button>
        <el-button :disabled="!history.length" data-testid="trial-clear" @click="clear">清除试运行数据（{{ history.length }}）</el-button>
      </div>
      <template v-if="result">
        <el-alert
          :type="result.status === 'PASSED' ? 'success' : 'error'"
          :title="(result.status === 'PASSED' ? '试运行通过：' : '试运行失败：') + result.summary"
          :closable="false"
          data-testid="trial-result"
        />
        <el-timeline class="ls-trial__trace">
          <el-timeline-item
            v-for="(s, i) in result.trace"
            :key="i"
            :type="s.ok === false ? 'danger' : 'primary'"
            :data-testid="'trial-step-' + i"
            :data-kind="s.kind"
          >
            <strong>{{ s.label }}</strong>
            <span v-if="s.actor" class="ls-trial__actor">{{ s.actor }}</span>
            <span v-if="s.decision">{{ s.decision === 'APPROVE' ? '通过' : '驳回' }}</span>
            <span v-if="s.status" class="ls-trial__status">单据状态 {{ s.status }}</span>
            <span v-if="s.flowStatus">流程 {{ s.flowStatus }}</span>
            <div v-if="s.editable?.length" class="ls-trial__sub">可编辑：{{ s.editable.join('、') }}</div>
            <div v-if="s.callbacks?.length" class="ls-trial__sub">
              回调：{{ s.callbacks.map((c) => c.event + ' → ' + c.operation + '（' + c.status + '）').join('；') }}
            </div>
            <div v-if="s.error" class="ls-trial__err">{{ s.error }}</div>
          </el-timeline-item>
        </el-timeline>
      </template>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import SchemaForm from '@/components/SchemaForm/index.vue';
import type { FormSchema } from '@/types/lsView';
import type { RendererApi } from '@/api/lsmom/form';
import { trialApi, type TrialNodeStep, type TrialResult, type TrialSetup } from '@/api/lsmom/trialRun';

const props = defineProps<{ schema: FormSchema; api: RendererApi }>();

const setup = ref<TrialSetup>();
const error = ref('');
const initiator = ref<number>();
const model = ref<Record<string, any>>({});
const steps = reactive<Record<string, TrialNodeStep>>({});
const inputs = reactive<Record<string, Record<string, string>>>({});
const busy = ref(false);
const result = ref<TrialResult>();
const history = ref<any[]>([]);

function editableOf(policyKey: string) {
  const p = props.schema.nodePolicies?.[policyKey];
  const req = new Set(p?.required ?? []);
  return (p?.editable ?? [])
    .map((k) => props.schema.fields.find((f) => f.key === k))
    .filter((f): f is NonNullable<typeof f> => !!f)
    .map((f) => ({ key: f.key, label: f.label ?? f.key, required: req.has(f.key) || !!f.required }));
}

async function refresh() {
  history.value = await trialApi.list(props.schema.formCode).catch(() => []);
}

onMounted(async () => {
  try {
    setup.value = await trialApi.setup(props.schema.formCode);
    initiator.value = setup.value.initiators[0]?.party;
    for (const n of setup.value.nodes) {
      steps[n.key] = { decision: 'APPROVE' };
      inputs[n.key] = {};
    }
    await refresh();
  } catch (e: any) {
    error.value = e?.message ?? String(e);
  }
});

async function run() {
  if (!setup.value) return;
  busy.value = true;
  try {
    const values: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(model.value)) if (!k.startsWith('_') && v !== undefined && v !== '') values[k] = v;
    const nodes: Record<string, TrialNodeStep> = {};
    for (const n of setup.value.nodes) {
      const input = Object.fromEntries(Object.entries(inputs[n.key]).filter(([, v]) => v !== ''));
      nodes[n.key] = { ...steps[n.key], input: Object.keys(input).length ? input : undefined };
    }
    result.value = await trialApi.run({ formCode: props.schema.formCode, schema: props.schema, values, initiatorParty: initiator.value, nodes });
    await refresh();
  } finally {
    busy.value = false;
  }
}

async function clear() {
  await ElMessageBox.confirm(`删除你在本表单上的 ${history.value.length} 次试运行轨迹？`, '清除试运行数据', { type: 'warning' });
  const n = await trialApi.clear(props.schema.formCode);
  result.value = undefined;
  await refresh();
  ElMessage.success(`已清除 ${n} 次试运行数据`);
}
</script>

<style scoped>
.ls-trial {
  display: flex;
  flex-direction: column;
  gap: var(--ls-space-3);
  max-width: 960px;
}
.ls-trial__flow,
.ls-trial__sub {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.ls-trial__section {
  font-weight: 600;
  margin-top: var(--ls-space-1);
}
.ls-trial__node {
  display: flex;
  flex-direction: column;
  gap: var(--ls-space-1);
}
.ls-trial__field {
  display: grid;
  grid-template-columns: 96px 1fr;
  align-items: center;
  max-width: 480px;
}
.ls-trial__field em {
  color: var(--el-color-danger);
  font-style: normal;
}
.ls-trial__bar {
  display: flex;
  gap: var(--ls-space-2);
}
.ls-trial__actor,
.ls-trial__status {
  margin: 0 var(--ls-space-2);
  color: var(--el-text-color-secondary);
}
.ls-trial__err {
  color: var(--el-color-danger);
}
</style>
