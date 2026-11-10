<template>
  <div class="m-page--with-actions" data-testid="m-defect">
    <div class="m-defect__head">
      <span class="m-sub">{{ assetName ? '设备：' + assetName : '登记设备缺陷' }}</span>
      <!-- 危险动作放在顶部，不与底部"提交"相邻 -->
      <button v-if="existing" type="button" class="m-btn m-btn--danger-text" data-testid="m-defect-discard" @click="discard">删除草稿</button>
    </div>
    <div v-if="schema" class="m-card">
      <SchemaForm
        ref="formRef"
        v-model="model"
        :schema="schema"
        platform="mobile"
        :only="ONLY"
        :show-actions="false"
        :smart="!existing"
        :source="source"
      />
      <label class="m-label">现场照片（高、严重级别必须拍照）</label>
      <PhotoPicker v-model="photos" />
    </div>
    <div class="m-actions">
      <button type="button" class="m-btn m-btn--primary" data-testid="m-defect-submit" :disabled="busy || !schema" @click="submit">
        {{ busy ? '提交中…' : '提交' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showConfirmDialog, showSuccessToast, showToast } from 'vant';
import SchemaForm from '@/components/SchemaForm/index.vue';
import PhotoPicker, { type PickedPhoto } from '../PhotoPicker.vue';
import { assetApi } from '@/api/lsmom/asset';
import { crudApi } from '@/api/lsmom/crud';
import { newKey } from '../api';
import type { FormSchema } from '@/types/lsView';

/**
 * 报缺陷（手机）：从设备进入时自动带出设备；只填缺陷描述（可语音）、严重度（一次点选）、照片，
 * "提交"即登记（照片先作为现场证据附件关联到缺陷）。点检生成的缺陷草稿也在这里补充后登记。
 */
const DEFECT = 'ls.asset.Defect';
const ONLY = ['asset', 'title', 'severity', 'symptom'];
const route = useRoute();
const router = useRouter();
const schema = ref<FormSchema>();
const model = ref<Record<string, any>>({});
const formRef = ref<InstanceType<typeof SchemaForm>>();
const photos = ref<PickedPhoto[]>([]);
const busy = ref(false);
const existing = ref<Record<string, any> | null>(null);
const assetName = ref(String(route.query.name ?? ''));
const source = computed(() => (route.query.asset ? { id: Number(route.query.asset), name: assetName.value } : null));

async function load() {
  schema.value = await assetApi.defectForm();
  if (route.query.id) {
    const r = await assetApi.get(DEFECT, Number(route.query.id));
    existing.value = r;
    model.value = { ...r };
  } else if (route.query.asset) {
    model.value = { asset: Number(route.query.asset), severity: 'MEDIUM' };
  } else {
    model.value = { severity: 'MEDIUM' };
  }
}

async function submit() {
  const v = formRef.value?.validate();
  if (v && Object.keys(v.errors).length) return showToast(Object.values(v.errors)[0] as string);
  busy.value = true;
  try {
    // 只提交表单字段（编辑草稿时不带状态、版本等系统字段）
    const keys = new Set([...(schema.value?.fields ?? []).map((f) => f.key), 'orgId']);
    const body = Object.fromEntries(Object.entries(formRef.value!.payload()).filter(([k]) => keys.has(k)));
    let id: number;
    let version: number;
    if (existing.value) {
      const r = await crudApi.update(DEFECT, existing.value.id, existing.value.version, body, newKey());
      id = r.id ?? existing.value.id;
      version = r.version;
    } else {
      const r = await assetApi.create(DEFECT, body);
      id = r.id;
      version = r.version;
    }
    for (const p of photos.value) {
      const up = await assetApi.upload(new File([p.blob], p.name, { type: p.blob.type || 'image/jpeg' }));
      await assetApi.attach(up.ossId, DEFECT, id, 'EVIDENCE');
    }
    await assetApi.op(`${DEFECT}.report`, id, version);
    formRef.value?.clearDraft?.();
    showSuccessToast('缺陷已登记');
    void router.replace('/m');
  } catch (e: any) {
    // 业务错误已由统一提示显示；草稿已保存在服务端时转为编辑该草稿
    if (!existing.value && e?.errorCode && model.value.id) existing.value = model.value;
  } finally {
    busy.value = false;
  }
}

async function discard() {
  const d = existing.value;
  if (!d) return;
  try {
    await showConfirmDialog({
      title: '删除草稿',
      message: '取消这条缺陷草稿？此操作不能撤销。',
      confirmButtonText: '删除',
      confirmButtonColor: '#f53f3f'
    });
  } catch {
    return;
  }
  await assetApi.op(`${DEFECT}.cancel`, d.id, d.version, { reason: '手机端删除草稿' });
  showToast('草稿已删除');
  void router.replace('/m');
}

onMounted(load);
</script>

<style scoped>
.m-defect__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
:deep(.el-form-item) {
  margin-bottom: 14px;
}
:deep(.el-form-item__label) {
  font-size: 15px;
}
</style>
