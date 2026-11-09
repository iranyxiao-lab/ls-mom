<template>
  <div class="p-2">
    <el-card v-if="error" shadow="never"><el-result icon="error" :title="error" /></el-card>
    <el-card v-else-if="!ready" v-loading="true" shadow="never" style="height: 300px" />
    <el-card v-else shadow="never">
      <Workspace
        :key="definition!.code + '@' + (version ?? 0)"
        :definition="definition!"
        :status="status"
        :version="version"
        :code-locked="codeLocked"
        :operations="operations"
        :roles="roles"
        :orgs="orgs"
        :concept-fields="conceptFields"
        :forms="forms"
        :api="designerApi"
        @published="onPublished"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="FlowDesigner">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import request from '@/utils/request';
import { flowApi } from '@/api/lsmom/flow';
import { formApi } from '@/api/lsmom/form';
import type { FormSchema } from '@/types/lsView';
import Workspace from './Workspace.vue';
import type { FlowDef } from './model';
import type { FlowDesignerApi, OperationOption } from './types';

/**
 * 审批流配置器：/lsmom/flow-designer/<流程编码>?concept=<Concept>。
 * 树形编辑节点，只提交 JSON 配置（不提供 BPMN XML 导入）；服务节点与回调只能选择已发布 Operation。
 */
const route = useRoute();
const router = useRouter();
const ready = ref(false);
const error = ref('');
const definition = ref<FlowDef>();
const status = ref('DRAFT');
const version = ref<number>();
const codeLocked = ref(false);
const operations = ref<OperationOption[]>([]);
const roles = ref<{ roleKey: string; roleName: string }[]>([]);
const orgs = ref<{ id: number; name: string }[]>([]);
const conceptFields = ref<{ code: string; label: string }[]>([]);
const forms = ref<{ code: string; version: number; title?: string }[]>([]);

const designerApi: FlowDesignerApi = {
  validate: flowApi.validate,
  preview: flowApi.preview,
  saveDraft: flowApi.saveDraft,
  publish: flowApi.publish,
  history: flowApi.history,
  get: flowApi.get,
  parties: flowApi.parties,
  formFields: async (code, v) => {
    const s = (await formApi.get<FormSchema>(code, v)).schema as FormSchema;
    return (s.fields ?? []).map((f) => ({ key: f.key, label: f.label ?? f.key }));
  }
};

async function loadConcept(concept: string) {
  const catalog = await request({ url: '/api/op/catalog', method: 'get' }).then((r: any) => r.data as any[]);
  operations.value = catalog.filter((o) => o.concept === concept).map((o) => ({ operation: o.code, name: o.name }));
  conceptFields.value = await flowApi.conceptFields(concept).catch(() => []);
  const views = await formApi.list({ concept, type: 'FORM' }).catch(() => []);
  forms.value = views.filter((v: any) => v.publishedVersion).map((v: any) => ({ code: v.code, version: v.publishedVersion, title: v.displayName }));
}

onMounted(async () => {
  try {
    const code = String(route.params.code || '');
    const concept = String(route.query.concept || '');
    [roles.value, orgs.value] = await Promise.all([flowApi.roles().catch(() => []), flowApi.orgs().catch(() => [])]);
    const hist = code ? await flowApi.history(code).catch(() => []) : [];
    if (hist.length) {
      const pick = hist.find((h) => h.status === 'DRAFT') ?? hist.find((h) => h.status === 'PUBLISHED') ?? hist[0];
      const v = await flowApi.get(code, pick.version);
      definition.value = { ...v.definition, version: undefined };
      status.value = v.status;
      version.value = v.version;
      codeLocked.value = true;
      await loadConcept(v.definition.concept);
    } else {
      if (!concept) throw new Error('新建流程需要 ?concept=<Concept 编码>');
      await loadConcept(concept);
      const me = await flowApi.me().catch(() => null);
      const ns = concept.substring(0, concept.lastIndexOf('.'));
      const simple = concept.substring(concept.lastIndexOf('.') + 1);
      definition.value = {
        code: code || `${ns}.${simple.charAt(0).toLowerCase()}${simple.slice(1)}Approval`,
        name: `${simple}审批`,
        concept,
        startOn: operations.value.find((o) => o.operation.endsWith('.submit'))?.operation,
        form: forms.value[0] ? { code: forms.value[0].code, version: forms.value[0].version } : null,
        callbacks: {},
        orgUnitId: me?.orgUnitId ?? null,
        nodes: []
      };
    }
    ready.value = true;
  } catch (e: any) {
    error.value = e?.message ?? String(e);
  }
});

function onPublished() {
  const code = definition.value!.code;
  if (!route.params.code) router.replace({ path: `/lsmom/flow-designer/${encodeURIComponent(code)}` });
}
</script>
