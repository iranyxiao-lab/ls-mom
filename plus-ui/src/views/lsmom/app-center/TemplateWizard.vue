<template>
  <el-dialog
    :model-value="modelValue"
    title="从模板新建应用"
    width="880px"
    top="6vh"
    data-testid="tpl-wizard"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <el-steps :active="step" finish-status="success" simple class="tw-steps">
      <el-step title="选择模板" />
      <el-step title="基本信息" />
      <el-step title="预览并生成" />
    </el-steps>

    <!-- 1. 选择模板 -->
    <div v-if="step === 0" class="tw-grid" data-testid="tpl-list">
      <button
        v-for="t in templates"
        :key="t.code"
        type="button"
        class="tw-card"
        :class="{ 'is-on': picked === t.code }"
        :data-testid="`tpl-${t.code}`"
        @click="pick(t.code)"
      >
        <span class="tw-card__cat">{{ t.category }}</span>
        <strong>{{ t.name }}</strong>
        <span class="tw-card__desc">{{ t.description }}</span>
        <span class="tw-card__meta">{{ t.fields }} 个字段{{ t.children ? ` · ${t.children} 张明细表` : '' }} · {{ t.nodes }} 个审批节点</span>
      </button>
      <slot name="extra-sources" />
    </div>

    <!-- 2. 基本信息与角色 -->
    <el-form v-else-if="step === 1 && fragment" ref="formRef" :model="info" :rules="rules" label-width="110px" data-testid="tpl-info">
      <el-form-item label="应用名称" prop="title">
        <el-input v-model="info.title" maxlength="40" data-testid="tpl-title" />
      </el-form-item>
      <el-form-item label="编码" prop="ns">
        <el-input v-model="info.ns" placeholder="如 acme.loan" data-testid="tpl-ns" />
        <div class="tw-help">小写字母、数字和点；同一编码再次生成会作为升级提交</div>
      </el-form-item>
      <el-form-item label="业务负责人" prop="owner">
        <el-input v-model="info.owner" data-testid="tpl-owner" />
      </el-form-item>
      <el-form-item label="需求提出人" prop="requester">
        <el-input v-model="info.requester" data-testid="tpl-requester" />
      </el-form-item>
      <el-form-item label="登记角色">
        <el-select v-model="info.initiatorRoles" multiple filterable placeholder="谁可以新增和提交" style="width: 100%" data-testid="tpl-starters">
          <el-option v-for="r in roles" :key="r.roleKey" :label="r.roleName" :value="r.roleKey" />
        </el-select>
      </el-form-item>
      <el-form-item v-for="n in fragment.flow?.nodes ?? []" :key="n.key" :label="`「${n.name}」办理`">
        <el-select v-model="n.role" filterable style="width: 100%" :data-testid="`tpl-node-${n.key}`">
          <el-option v-for="r in roles" :key="r.roleKey" :label="r.roleName" :value="r.roleKey" />
        </el-select>
        <div class="tw-help">按角色分派，且只分给数据范围覆盖该单据组织的人</div>
      </el-form-item>
    </el-form>

    <!-- 3. 预览生成的文件（可直接修改）与授权 -->
    <div v-else-if="step === 2" class="tw-preview" data-testid="tpl-preview">
      <ul class="tw-files">
        <li v-for="p in Object.keys(files)" :key="p">
          <button type="button" :class="{ 'is-on': p === file }" :data-testid="`tpl-file-${p}`" @click="file = p">
            {{ p }}<span v-if="edited.has(p)" class="tw-dot" title="已修改">●</span>
          </button>
        </li>
      </ul>
      <div class="tw-editor">
        <el-input
          v-model="files[file]"
          type="textarea"
          :rows="18"
          spellcheck="false"
          class="tw-code"
          data-testid="tpl-file-editor"
          @input="edited.add(file)"
        />
        <div class="tw-grants" data-testid="tpl-grants">
          <el-checkbox v-model="doGrant" data-testid="tpl-grant">同时建按钮权限并授予角色（审批节点角色须先有办理权限，流程才能发布）</el-checkbox>
          <ul v-if="doGrant">
            <li v-for="g in grants" :key="g.perms">
              <code>{{ g.perms }}</code> → {{ g.roles.length ? g.roles.map(roleName).join('、') : '（无角色）' }}
            </li>
          </ul>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button v-if="step > 0" data-testid="tpl-back" @click="step--">上一步</el-button>
      <el-button v-if="step < 2" type="primary" :disabled="step === 0 && !fragment" :loading="busy" data-testid="tpl-next" @click="next"
        >下一步</el-button
      >
      <el-button v-else type="primary" :loading="busy" data-testid="tpl-create" @click="create">生成草稿</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import { templateApi, type DraftBody, type Grant, type IrFragment, type TemplateSummary } from '@/api/lsmom/templates';
import { flowApi } from '@/api/lsmom/flow';
import type { ChangeSet } from '@/api/lsmom/pack';
import { useUserStore } from '@/store/modules/user';

/**
 * 从模板（或 Excel 推断的片段）新建台账应用：选模板 → 名称、编码、负责人、角色 → 预览生成的 Pack 文件（可改）→
 * 授权并提交为 ChangeSet 草稿（之后与手写 Pack 一样走校验、沙箱、回归、越权、升级演练与审批发布）。
 */
const props = defineProps<{ modelValue: boolean; initial?: IrFragment | null }>();
const emit = defineEmits<{ 'update:modelValue': [boolean]; created: [ChangeSet] }>();

const step = ref(0);
const busy = ref(false);
const templates = ref<TemplateSummary[]>([]);
const roles = ref<{ roleKey: string; roleName: string }[]>([]);
const picked = ref('');
const fragment = ref<IrFragment | null>(null);
const user = useUserStore();
const info = reactive({ title: '', ns: '', owner: user.nickname, requester: user.nickname, initiatorRoles: [] as string[] });
const formRef = ref<FormInstance>();
const rules: FormRules = {
  title: [{ required: true, message: '请填写应用名称', trigger: 'blur' }],
  ns: [
    { required: true, message: '请填写编码', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$/, message: '小写字母、数字和点组成，如 acme.loan', trigger: 'blur' }
  ],
  owner: [{ required: true, message: '请填写业务负责人', trigger: 'blur' }],
  requester: [{ required: true, message: '请填写需求提出人', trigger: 'blur' }]
};
const files = ref<Record<string, string>>({});
const file = ref('pack.yaml');
const edited = reactive(new Set<string>());
const doGrant = ref(true);
const grants = ref<Grant[]>([]);
const roleName = (k: string) => roles.value.find((r) => r.roleKey === k)?.roleName ?? k;

function useFragment(f: IrFragment, code?: string) {
  fragment.value = JSON.parse(JSON.stringify(f));
  info.title = f.name ?? f.entityName ?? '';
  info.ns = `cust.${(code ?? f.entity).toLowerCase().replace(/[^a-z0-9]/g, '')}${Date.now().toString(36).slice(-3)}`;
}

async function pick(code: string) {
  picked.value = code;
  useFragment(await templateApi.get(code), code);
  step.value = 1;
}

const body = (): DraftBody => ({
  fragment: fragment.value!,
  ns: info.ns.trim(),
  title: info.title.trim(),
  owner: info.owner,
  requester: info.requester,
  initiatorRoles: info.initiatorRoles
});

async function next() {
  if (step.value === 0) {
    if (fragment.value) step.value = 1;
    return;
  }
  await formRef.value?.validate();
  busy.value = true;
  try {
    const [f, g] = await Promise.all([templateApi.preview(body()), templateApi.grantPlan(body())]);
    files.value = f;
    grants.value = g;
    edited.clear();
    file.value = 'pack.yaml';
    step.value = 2;
  } finally {
    busy.value = false;
  }
}

async function create() {
  busy.value = true;
  try {
    if (doGrant.value) await templateApi.grant(body());
    const cs = await templateApi.draft({ ...body(), files: edited.size ? files.value : undefined });
    if (cs.status === 'REJECTED') {
      ElMessage.error(`校验未通过：${(cs.stages[0]?.detail as any)?.message ?? ''}`);
      return;
    }
    ElMessage.success(`已生成草稿 ChangeSet #${cs.id}：${cs.title}`);
    emit('created', cs);
    emit('update:modelValue', false);
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  const [t, r] = await Promise.all([templateApi.list(), flowApi.roles().catch(() => [])]);
  templates.value = t;
  roles.value = r;
  const starters = ['team_leader', 'operator'].filter((k) => r.some((x) => x.roleKey === k));
  info.initiatorRoles = starters;
  if (props.initial) {
    useFragment(props.initial);
    step.value = 1;
  }
});
</script>

<style scoped>
.tw-steps {
  margin-bottom: 16px;
}
.tw-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 12px;
}
.tw-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px;
  text-align: left;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  cursor: pointer;
}
.tw-card:hover,
.tw-card.is-on {
  border-color: var(--el-color-primary);
}
.tw-card__cat {
  font-size: 12px;
  color: var(--el-color-primary);
}
.tw-card__desc {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.tw-card__meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.tw-help {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.tw-preview {
  display: flex;
  gap: 12px;
}
.tw-files {
  width: 230px;
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 480px;
  overflow: auto;
}
.tw-files button {
  width: 100%;
  padding: 6px 8px;
  border: 0;
  background: none;
  text-align: left;
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
  color: var(--el-text-color-regular);
  cursor: pointer;
  border-radius: 4px;
}
.tw-files button.is-on {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.tw-dot {
  margin-left: 4px;
  color: var(--el-color-warning);
}
.tw-editor {
  flex: 1;
  min-width: 0;
}
.tw-code :deep(textarea) {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12px;
}
.tw-grants {
  margin-top: 10px;
  font-size: 13px;
}
.tw-grants ul {
  margin: 6px 0 0;
  padding-left: 18px;
  color: var(--el-text-color-regular);
}
.tw-grants code {
  font-size: 12px;
}
</style>
