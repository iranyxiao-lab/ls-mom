<template>
  <div class="m-page--with-actions" data-testid="m-report">
    <div v-if="!work && !loading" class="m-empty" data-testid="m-report-missing">找不到这条任务（可能已完成或不再分派给你）</div>
    <template v-if="work">
      <div class="m-card">
        <div class="m-report__title">{{ work.title }}</div>
        <div class="m-sub">
          {{ work.code }} · {{ shiftLabel(work.shift) }}{{ work.plannedQty ? ` · 计划 ${Number(work.plannedQty)}${work.unit ?? ''}` : '' }}
        </div>
        <div v-if="pending.length" class="m-tag m-tag--warn" data-testid="m-report-pending">本任务有 {{ pending.length }} 条报工待同步</div>
      </div>

      <div v-if="work.status !== 'IN_PROGRESS'" class="m-card">
        <p>当前状态：{{ WORK_STATUS[work.status] ?? work.status }}，{{ work.status === 'ASSIGNED' ? '开工后才能报工' : '不能报工' }}</p>
        <button v-if="work.status === 'ASSIGNED'" type="button" class="m-btn m-btn--primary" data-testid="m-report-start" @click="start">开工</button>
      </div>

      <form v-else class="m-card" data-testid="m-report-form" @submit.prevent="submit">
        <template v-for="f in fields" :key="f.key">
          <label class="m-label" :for="'mr-' + f.key">{{ f.label }}{{ f.unit ? `（${f.unit}）` : '' }}</label>
          <MobileFieldWidget
            :id="'mr-' + f.key"
            :field="f"
            :model-value="form[f.key]"
            :editable="true"
            :api="lazyFormApi"
            @update:model-value="(v: unknown) => (form[f.key] = v)"
          />
        </template>
        <label class="m-label">现场照片（可选，作为证据附件）</label>
        <PhotoPicker v-model="photos" />
      </form>
    </template>

    <div v-if="work?.status === 'IN_PROGRESS'" class="m-actions">
      <button type="button" class="m-btn" data-testid="m-report-draft" :disabled="busy" @click="saveDraft">存草稿</button>
      <button type="button" class="m-btn m-btn--primary" data-testid="m-report-submit" :disabled="busy || !canSubmit" @click="submit">
        {{ busy ? '提交中…' : '提交' }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showSuccessToast, showToast } from 'vant';
import MobileFieldWidget from '@/components/SchemaForm/MobileFieldWidget.vue';
import PhotoPicker, { type PickedPhoto } from '../PhotoPicker.vue';
import { productionApi } from '@/api/lsmom/production';
import { formApi } from '@/api/lsmom/form';
import { lazyFormApi } from '@/api/lsmom/lazyForm';
import { shiftLabel, WORK_STATUS, type BoardWork } from '@/api/lsmom/productionModel';
import type { FormField, MobileSchema } from '@/types/lsView';
import { enqueue, isNetworkError, outbox } from '../outbox';
import { newKey, outboxSender, submitOp } from '../api';
import { useUserStore } from '@/store/modules/user';

/**
 * 报工：只显示 MobileSchema 里的必要字段（默认：产出、进度、说明），数字用数字键盘与 ± 步进，
 * "提交"固定在底部拇指区。断网时排入离线队列显示"待同步"，恢复网络后自动提交（带幂等键，不会重复生成报工）。
 */
const route = useRoute();
const router = useRouter();
const user = useUserStore();
const id = computed(() => Number(route.params.id));
const work = ref<BoardWork>();
const loading = ref(true);
const busy = ref(false);
const photos = ref<PickedPhoto[]>([]);

const ALL: Record<string, FormField> = {
  outputQty: { key: 'outputQty', label: '产出', widget: 'number', min: 0 } as FormField,
  progress: { key: 'progress', label: '进度（%）', widget: 'number', min: 0, max: 100, step: 10 } as any,
  laborMinutes: { key: 'laborMinutes', label: '人工（分钟）', widget: 'number', min: 0, step: 30 } as any,
  remark: { key: 'remark', label: '说明', widget: 'textarea', maxLength: 500 } as FormField
};
const DEFAULT_FIELDS = ['outputQty', 'progress', 'remark'];
const mobileFields = ref<string[]>(DEFAULT_FIELDS);
const fields = computed(() =>
  mobileFields.value
    .filter((k) => ALL[k])
    .map((k) => (k === 'outputQty' && work.value?.unit ? { ...ALL[k], unit: work.value.unit } : ALL[k]) as FormField & { unit?: string })
);
const form = reactive<Record<string, any>>({ outputQty: null, progress: 0, laborMinutes: null, remark: '' });
const pending = computed(() => outbox.items.filter((i) => i.targetId === String(id.value)));
const canSubmit = computed(() => fields.value.some((f) => form[f.key] !== null && form[f.key] !== '' && form[f.key] !== undefined));

const cacheKey = () => `ls-m-today-${user.userId ?? ''}`;
async function load() {
  loading.value = true;
  try {
    const list = await productionApi.board({ mine: true });
    work.value = list.find((w) => w.id === id.value);
  } catch {
    try {
      work.value = (JSON.parse(localStorage.getItem(cacheKey()) ?? 'null')?.works ?? []).find((w: BoardWork) => w.id === id.value);
    } catch {
      /* 无缓存 */
    }
  } finally {
    loading.value = false;
  }
  if (work.value) form.progress = Number(work.value.progress ?? 0);
  // 已发布的移动视图（MobileSchema）决定显示哪些字段
  const v = await formApi.defaultFor('ls.core.WorkReport', 'MOBILE').catch(() => null);
  const schema = v?.schema as unknown as MobileSchema | undefined;
  if (schema?.fields?.length) mobileFields.value = schema.fields;
}

async function start() {
  const w = work.value!;
  await submitOp('ls.core.Work.start', String(w.id), w.version, {}, newKey());
  await load();
}

function input() {
  const o: Record<string, unknown> = {};
  for (const f of fields.value) {
    const v = form[f.key];
    if (v !== null && v !== undefined && v !== '') o[f.key] = v;
  }
  if (o.outputQty !== undefined && work.value?.unit) o.outputUnit = work.value.unit;
  return o;
}

async function send(operation: string, done: string) {
  const w = work.value!;
  const key = newKey();
  const body = input();
  busy.value = true;
  try {
    if (!navigator.onLine) throw new Error('Network Error');
    const r = await submitOp(operation, String(w.id), undefined, body, key);
    const reportId = r.data?.reportId;
    for (const p of photos.value) {
      const up = await outboxSender.upload(new File([p.blob], p.name, { type: p.blob.type || 'image/jpeg' }));
      if (reportId) await outboxSender.attach(up.ossId, 'ls.core.WorkReport', reportId);
    }
    showSuccessToast(done);
    void router.replace('/m');
  } catch (e: any) {
    if (!isNetworkError(e)) return;
    // 断网：排入离线队列，恢复网络后按顺序提交
    await enqueue({
      title: `${done.replace('已', '')}：${w.title}`,
      operation,
      targetId: String(w.id),
      input: body,
      idempotencyKey: key,
      photos: photos.value.map((p) => ({ name: p.name, blob: p.blob })),
      attachTo: { objectType: 'ls.core.WorkReport', objectIdFrom: 'reportId' }
    });
    showToast('网络不可用：已保存，待同步');
    void router.replace('/m');
  } finally {
    busy.value = false;
  }
}

const submit = () => send('ls.core.Work.submitReport', '已提交报工');
const saveDraft = () => send('ls.core.Work.saveReport', '已存草稿');

onMounted(load);
</script>

<style scoped>
.m-report__title {
  font-size: 17px;
  font-weight: 600;
  margin-bottom: 4px;
}
</style>
