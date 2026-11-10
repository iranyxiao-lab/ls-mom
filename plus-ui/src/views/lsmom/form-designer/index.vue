<template>
  <div class="p-2">
    <el-card v-if="error" shadow="never"><el-result icon="error" :title="error" /></el-card>
    <el-card v-else-if="!ready" v-loading="true" shadow="never" style="height: 300px" />
    <el-card v-else shadow="never">
      <Workspace
        :key="schema!.formCode + '@' + schema!.version"
        :schema="schema!"
        :published="published"
        :status="status"
        :concept-fields="conceptFields"
        :child-concepts="childConcepts"
        :operations="operations"
        :components="components"
        :node-names="nodeNames"
        :api="designerApi"
        :renderer-api="formApi"
        @published="onPublished"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="FormDesigner">
import { getCurrentInstance, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { formApi } from '@/api/lsmom/form';
import { crudApi } from '@/api/lsmom/crud';
import { flowApi } from '@/api/lsmom/flow';
import type { FormSchema } from '@/types/lsView';
import type { PublishResult } from '@/api/lsmom/form';
import Workspace from './Workspace.vue';
import type { ConceptFieldInfo, DesignerApi } from './types';
import { installDesigner } from './install';

/**
 * 表单设计器页面：/lsmom/form-designer/<视图编码>?concept=<Concept>。
 * 已发布表单自动派生下一版本草稿；新表单按 Concept 字段生成初稿。只能保存草稿与提交发布（经服务端校验与变更集）。
 */
installDesigner(getCurrentInstance()!.appContext.app);
const route = useRoute();
const router = useRouter();
const ready = ref(false);
const error = ref('');
const schema = ref<FormSchema>();
const published = ref<FormSchema | null>(null);
const status = ref('DRAFT');
const conceptFields = ref<ConceptFieldInfo[]>([]);
const childConcepts = ref<{ key: string; concept: string; displayName: string }[]>([]);
const operations = ref<{ operation: string; name: string }[]>([]);
const components = ref<{ code: string; version: string; displayName: string }[]>([]);
/** 绑定该表单的流程节点名（节点视角预览、节点策略显示"车间审核"而不是键） */
const nodeNames = ref<Record<string, string>>({});

const designerApi: DesignerApi = { saveDraft: (s) => formApi.saveDraft(s), check: formApi.check, publish: formApi.publish };

const lowerFirst = (s: string) => s.charAt(0).toLowerCase() + s.slice(1);

async function loadConcept(concept: string) {
  const ls = await crudApi.schema(concept);
  conceptFields.value = [
    ...ls.fields.map((f: any) => ({
      code: f.code,
      label: f.label,
      type: f.type,
      refConcept: f.refConcept,
      dictType: f.dictType,
      readPermission: f.readPermission,
      writePermission: f.writePermission
    })),
    ...ls.children.flatMap((c) => c.fields.map((f: any) => ({ code: f.code, label: `${c.displayName} · ${f.label}`, type: f.type, table: c.key })))
  ];
  operations.value = ls.operations.map((o) => ({ operation: o.operation, name: o.name }));
  childConcepts.value = ls.children.map((c) => ({ key: c.key, concept: c.concept, displayName: c.displayName }));
  return ls;
}

/** 新表单初稿：按 Concept 主表字段生成（状态字段除外）；子表由实施人员从控件面板拖入并绑定子 Concept。 */
function draftFrom(code: string, concept: string, ls: Awaited<ReturnType<typeof crudApi.schema>>): FormSchema {
  const widget = (t: string, ref?: string) =>
    ref
      ? 'conceptRef'
      : ((
          {
            TEXT: 'textarea',
            INTEGER: 'number',
            DECIMAL: 'number',
            BOOLEAN: 'switch',
            DATE: 'date',
            DATETIME: 'datetime',
            ENUM: 'select',
            FILE: 'attachment'
          } as any
        )[t] ?? 'input');
  const field = (f: any) => ({
    key: f.code,
    label: f.label,
    widget: widget(f.type, f.refConcept),
    required: f.required || undefined,
    ...(f.refConcept ? { ref: { concept: f.refConcept } } : {}),
    ...(f.options ? { options: f.options.map((o: string) => ({ value: o, label: f.optionLabels?.[o] ?? o })) } : {}),
    ...(f.dictType ? { widget: 'dict', dictType: f.dictType } : {})
  });
  const fields = ls.fields.filter((f: any) => f.writable !== false).map(field);
  return {
    viewType: 'FORM',
    formCode: code,
    version: 1,
    conceptCode: concept,
    title: ls.displayName,
    layout: fields.map((f: any) => ({ type: 'field' as const, key: f.key })),
    fields: fields as any,
    actions: []
  };
}

onMounted(async () => {
  try {
    const code = String(route.params.code || '');
    const concept = String(route.query.concept || '');
    components.value = await formApi.components().catch(() => []);
    const views = code ? await formApi.history(code) : [];
    if (views.length) {
      const pub = views.find((v: any) => v.status === 'PUBLISHED');
      if (pub) published.value = (await formApi.get<FormSchema>(code, pub.version)).schema;
      const draft = views.find((v: any) => v.status === 'DRAFT');
      const v = draft ? await formApi.get<FormSchema>(code, draft.version) : await formApi.derive(code);
      schema.value = v.schema as FormSchema;
      status.value = 'DRAFT';
      await loadConcept(schema.value.conceptCode);
    } else {
      if (!concept) throw new Error('新建表单需要 ?concept=<Concept 编码>');
      const ls = await loadConcept(concept);
      schema.value = draftFrom(
        code || `${concept.substring(0, concept.lastIndexOf('.'))}.${lowerFirst(concept.substring(concept.lastIndexOf('.') + 1))}Form`,
        concept,
        ls
      );
    }
    const nodes = await flowApi.formNodes(schema.value!.formCode).catch(() => []);
    nodeNames.value = Object.fromEntries(nodes.map((n) => [n.key, n.name]));
    ready.value = true;
  } catch (e: any) {
    error.value = e?.message ?? String(e);
  }
});

function onPublished(r: PublishResult) {
  if (r.status === 'APPLIED') router.replace({ path: `/lsmom/form-designer/${encodeURIComponent(schema.value!.formCode)}` });
}
</script>
