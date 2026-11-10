<template>
  <div :class="{ 'm-page--with-actions': record }" data-testid="m-inspect">
    <!-- 没有选中记录：待点检清单 + 扫码 -->
    <template v-if="!record">
      <button type="button" class="m-btn m-btn--primary m-inspect__scan" data-testid="m-inspect-scan" @click="emit('scan', onCode)">
        扫设备码开始点检
      </button>
      <p v-if="hint" class="m-card m-inspect__hint" data-testid="m-inspect-hint">{{ hint }}</p>
      <div class="m-section">我的待点检（{{ mine.length }}）</div>
      <div v-if="!mine.length" class="m-empty">没有待点检的设备</div>
      <button v-for="m in mine" :key="m.id" type="button" class="m-card m-inspect__item" :data-testid="`m-ins-${m.id}`" @click="open(m.id, m)">
        <span class="m-inspect__name">{{ m.asset_code }} {{ m.asset_name }}</span>
        <span class="m-sub">到期 {{ m.due_date }}{{ m.location ? ' · ' + m.location : '' }}</span>
      </button>
    </template>

    <template v-else>
      <div class="m-card">
        <div class="m-inspect__name">{{ title }}</div>
        <div class="m-sub">{{ record.status === 'DRAFT' ? '待点检' : '已提交' }}{{ record.dueDate ? ' · 到期 ' + record.dueDate : '' }}</div>
        <router-link
          :to="{ path: '/m/defect', query: { asset: String(record.asset), name: title } }"
          class="m-btn m-inspect__defect"
          data-testid="m-inspect-defect"
          >报缺陷</router-link
        >
      </div>
      <div v-for="it in items" :key="it.itemCode" class="m-card" :data-testid="`m-item-${it.itemCode}`">
        <div class="m-inspect__item-name">
          {{ it.name }}
          <span v-if="it.standard" class="m-sub">（{{ it.standard }}）</span>
          <span v-if="it.outcome === 'ABNORMAL' && record.status !== 'DRAFT'" class="m-tag m-tag--danger">异常</span>
          <span v-if="it.defect" class="m-tag m-tag--warn">已生成缺陷</span>
        </div>
        <template v-if="record.status === 'DRAFT'">
          <div v-if="it.valueType === 'NUMBER'" class="m-inspect__num">
            <MobileFieldWidget
              :field="numField(it)"
              :model-value="it.valueNum"
              :editable="true"
              :api="lazyFormApi"
              @update:model-value="(v: unknown) => (it.valueNum = v)"
            />
            <span class="m-sub">{{ it.unit }}{{ it.upperLimit != null ? ` · 上限 ${Number(it.upperLimit)}` : '' }}</span>
          </div>
          <div v-else class="m-inspect__ok" role="radiogroup" :aria-label="it.name">
            <button
              type="button"
              role="radio"
              :aria-checked="it.outcome === 'OK'"
              class="m-chip-lg"
              :class="{ 'is-ok': it.outcome === 'OK' }"
              :data-testid="`m-item-${it.itemCode}-ok`"
              @click="it.outcome = 'OK'"
            >
              正常
            </button>
            <button
              type="button"
              role="radio"
              :aria-checked="it.outcome === 'ABNORMAL'"
              class="m-chip-lg"
              :class="{ 'is-bad': it.outcome === 'ABNORMAL' }"
              :data-testid="`m-item-${it.itemCode}-bad`"
              @click="it.outcome = 'ABNORMAL'"
            >
              异常
            </button>
          </div>
        </template>
        <div v-else class="m-sub">
          {{
            it.valueType === 'NUMBER'
              ? `${it.valueNum ?? '—'} ${it.unit ?? ''}`
              : it.outcome === 'OK'
                ? '正常'
                : it.outcome === 'ABNORMAL'
                  ? '异常'
                  : '—'
          }}
        </div>
      </div>
      <p v-if="abnormal" class="m-card m-inspect__hint" data-testid="m-inspect-abnormal">{{ abnormal }}</p>
    </template>

    <div v-if="record" class="m-actions">
      <button type="button" class="m-btn" data-testid="m-inspect-back" @click="close">返回清单</button>
      <button
        v-if="record.status === 'DRAFT'"
        type="button"
        class="m-btn m-btn--primary"
        data-testid="m-inspect-submit"
        :disabled="busy"
        @click="submit"
      >
        提交点检
      </button>
      <button v-else-if="canRaise" type="button" class="m-btn m-btn--primary" data-testid="m-inspect-raise" :disabled="busy" @click="raise">
        异常项生成缺陷草稿
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showSuccessToast, showToast } from 'vant';
import MobileFieldWidget from '@/components/SchemaForm/MobileFieldWidget.vue';
import { assetApi } from '@/api/lsmom/asset';
import { lazyFormApi } from '@/api/lsmom/lazyForm';
import type { MyInspection } from '@/api/lsmom/assetModel';
import type { FormField } from '@/types/lsView';
import { normalizeCode } from '@/utils/scan/scanCode';

/**
 * 点检（手机）：扫设备码直接打开分给自己的待点检清单，逐项点"正常／异常"或填数值（超限自动判异常），
 * 提交后异常项一键生成缺陷草稿；也可以从设备直接报缺陷。
 */
const RECORD = 'ls.asset.InspectionRecord';
const emit = defineEmits<{ (e: 'scan', cb?: (code: string) => void): void }>();
const route = useRoute();
const router = useRouter();
const mine = ref<MyInspection[]>([]);
const record = ref<Record<string, any>>();
const items = ref<Record<string, any>[]>([]);
const title = ref('');
const hint = ref('');
const abnormal = ref('');
const busy = ref(false);
const canRaise = computed(() => record.value?.status === 'SUBMITTED' && items.value.some((i) => i.outcome === 'ABNORMAL' && !i.defect));

const numField = (it: Record<string, any>) => ({ key: it.itemCode, label: it.name, widget: 'number', min: 0, precision: 2 }) as unknown as FormField;

async function loadMine() {
  mine.value = await assetApi.myInspections('DRAFT').catch(() => []);
}

async function open(id: number, m?: MyInspection) {
  const from = m ?? mine.value.find((x) => x.id === id);
  title.value = from ? `${from.asset_code} ${from.asset_name}` : '';
  let r: Record<string, any>;
  try {
    r = await assetApi.get(RECORD, id);
  } catch (e: any) {
    hint.value = e?.message ?? '打不开这条点检记录';
    return;
  }
  record.value = r;
  items.value = ((r.inspectionRecordItem ?? []) as Record<string, any>[]).map((i) => ({
    ...i,
    valueNum: i.valueNum == null ? null : Number(i.valueNum)
  }));
  abnormal.value = '';
  hint.value = '';
}

function close() {
  record.value = undefined;
  void router.replace({ path: '/m/inspect' });
  void loadMine();
}

/** 扫到设备码：打开分给自己的该设备待点检；没有时说明原因 */
async function onCode(text: string) {
  const code = normalizeCode(text);
  if (!code) return;
  await loadMine();
  const todo = mine.value.filter((m) => m.asset_code === code).sort((a, b) => String(a.due_date).localeCompare(String(b.due_date)));
  if (todo.length) return open(todo[0].id, todo[0]);
  record.value = undefined;
  try {
    const a = await assetApi.scanAsset(code);
    hint.value = `「${a.label}」没有分给你的待点检。`;
  } catch (e: any) {
    hint.value = e?.message ?? `没有找到编码为「${code}」的设备`;
  }
}

async function submit() {
  const r = record.value!;
  const missing = items.value.filter((i) => (i.valueType === 'NUMBER' ? i.valueNum == null : !i.outcome));
  if (missing.length) return showToast(`还有 ${missing.length} 项没填：${missing[0].name}`);
  busy.value = true;
  try {
    const payload = items.value.map((i) => ({
      itemCode: i.itemCode,
      valueNum: i.valueType === 'NUMBER' ? i.valueNum : null,
      outcome: i.valueType === 'NUMBER' ? null : i.outcome,
      note: i.note || null
    }));
    const res = await assetApi.op(`${RECORD}.submit`, r.id, r.version, { items: payload });
    await open(r.id);
    if (res.warnings?.length) abnormal.value = res.warnings.join('；');
    else showSuccessToast('点检已提交，全部正常');
  } finally {
    busy.value = false;
  }
}

async function raise() {
  const r = record.value!;
  busy.value = true;
  try {
    const res = await assetApi.op(`${RECORD}.raiseDefects`, r.id, r.version);
    const defects = (res.data?.defects ?? []) as (number | string)[];
    showSuccessToast(`已生成 ${defects.length} 条缺陷草稿`);
    // 只有一条时直接打开补充照片并登记
    if (defects.length === 1) void router.push({ path: '/m/defect', query: { id: String(defects[0]) } });
    else await open(r.id);
  } finally {
    busy.value = false;
  }
}

watch(
  () => route.query.code,
  (c) => c && void onCode(String(c)),
  { immediate: true }
);
onMounted(loadMine);
</script>

<style scoped>
.m-inspect__scan {
  width: 100%;
}
.m-inspect__hint {
  color: #ad4e00;
  background: #fff7e6;
}
.m-inspect__item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  width: 100%;
  border: 0;
  text-align: left;
  color: inherit;
}
.m-inspect__name {
  font-weight: 600;
  font-size: 16px;
}
.m-inspect__defect {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-top: 8px;
  color: var(--ls-color-danger, #f53f3f);
  text-decoration: none;
}
.m-inspect__item-name {
  margin-bottom: 8px;
  font-weight: 500;
}
.m-inspect__ok {
  display: flex;
  gap: 12px;
}
.m-inspect__num {
  display: flex;
  align-items: center;
  gap: 8px;
}
.m-chip-lg {
  flex: 1;
  min-height: 48px;
  border-radius: 10px;
  border: 1px solid var(--ls-color-border, #dcdfe6);
  background: var(--ls-color-surface, #fff);
  color: inherit;
  font-size: 16px;
}
.m-chip-lg.is-ok {
  background: #e8f7ee;
  border-color: #00b42a;
  color: #00802a;
}
.m-chip-lg.is-bad {
  background: #ffece8;
  border-color: #f53f3f;
  color: #cb2634;
}
</style>
