<template>
  <div data-testid="m-home">
    <div class="m-hello">
      <div>
        <div class="m-hello__name">{{ greeting }}，{{ userName }}</div>
        <div class="m-sub" data-testid="m-shift">今天 {{ today }} · 当前{{ shiftLabel(currentShift) }}</div>
      </div>
      <button type="button" class="m-btn" data-testid="m-refresh" :disabled="loading" @click="load">刷新</button>
    </div>

    <div class="m-quick" role="group" aria-label="快捷动作">
      <button type="button" class="m-quick__btn" data-testid="m-quick-report" @click="quickReport"><span aria-hidden="true">✎</span>报工</button>
      <router-link to="/m/inspect" class="m-quick__btn" data-testid="m-quick-inspect"><span aria-hidden="true">☑</span>点检</router-link>
      <router-link to="/m/defect" class="m-quick__btn" data-testid="m-quick-defect"><span aria-hidden="true">⚠</span>报缺陷</router-link>
      <button type="button" class="m-quick__btn" data-testid="m-quick-scan" @click="emit('scan')"><span aria-hidden="true">⌗</span>扫一扫</button>
    </div>

    <p v-if="fromCache" class="m-sub" data-testid="m-cached">离线：显示的是 {{ cachedAt }} 缓存的今日任务</p>

    <template v-if="current.length">
      <div class="m-section" data-testid="m-section-current">本班次 · {{ shiftLabel(currentShift) }}（{{ current.length }}）</div>
      <TaskCard v-for="w in current" :key="w.id" :work="w" :pending="pendingFor(w.id)" @start="start(w)" @report="report(w)" />
    </template>
    <template v-if="others.length">
      <div class="m-section" data-testid="m-section-others">今日其他（{{ others.length }}）</div>
      <TaskCard v-for="w in others" :key="w.id" :work="w" :pending="pendingFor(w.id)" @start="start(w)" @report="report(w)" />
    </template>
    <div v-if="!loading && !current.length && !others.length" class="m-empty" data-testid="m-no-task">今天没有分派给你的任务</div>

    <div class="m-section">待我确认（{{ todos.length }}）</div>
    <div v-if="!todos.length" class="m-empty m-empty--small">没有待确认的事项</div>
    <router-link
      v-for="t in todos.slice(0, 5)"
      :key="t.task.id"
      :to="{ path: '/lsmom/workbench', query: { task: t.task.taskId } }"
      class="m-card m-todo"
      :data-testid="`m-todo-${t.task.id}`"
    >
      <span class="m-todo__title">{{ t.bizTitle || t.task.nodeName }}</span>
      <span class="m-sub">{{ t.task.nodeName }}{{ t.initiatorName ? ' · ' + t.initiatorName : '' }}</span>
    </router-link>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { showToast } from 'vant';
import { productionApi } from '@/api/lsmom/production';
import { workbenchApi, type WorkItem } from '@/api/lsmom/workbench';
import { shiftLabel, WORK_STATUS, type BoardWork, type Shift } from '@/api/lsmom/productionModel';
import { useUserStore } from '@/store/modules/user';
import { outbox } from '../outbox';
import { newKey, submitOp } from '../api';
import { currentShiftOf, sortToday, todayStr } from '../today';

/**
 * 移动工作台：今日任务（本班次在前，按紧急程度排序），每张卡片一个主按钮（开工／报工），
 * 待我确认与快捷动作（报工、点检、报缺陷、扫一扫）。今日任务缓存在本机，离线时也能看到。
 */
const emit = defineEmits<{ (e: 'scan', cb?: (code: string) => void): void }>();
const router = useRouter();
const user = useUserStore();
const userName = computed(() => user.nickname || user.name || '');
const now = new Date();
const today = todayStr(now);
const currentShift = ref<Shift>(currentShiftOf(now));
const greeting = computed(() => {
  const hh = now.getHours();
  return hh < 11 ? '早上好' : hh < 14 ? '中午好' : hh < 18 ? '下午好' : '晚上好';
});

const works = ref<BoardWork[]>([]);
const todos = ref<WorkItem[]>([]);
const loading = ref(false);
const fromCache = ref(false);
const cachedAt = ref('');

const cacheKey = () => `ls-m-today-${user.userId ?? ''}`;
const sorted = computed(() => sortToday(works.value, today, currentShift.value));
const current = computed(() => sorted.value.filter((w) => w.shift === currentShift.value));
const others = computed(() => sorted.value.filter((w) => w.shift !== currentShift.value));
const pendingFor = (id: number) => outbox.items.filter((i) => i.targetId === String(id));

async function load() {
  loading.value = true;
  try {
    const from = todayStr(new Date(Date.now() - 7 * 864e5));
    works.value = await productionApi.board({ mine: true, from, to: today });
    fromCache.value = false;
    try {
      localStorage.setItem(cacheKey(), JSON.stringify({ at: new Date().toISOString(), works: works.value }));
    } catch {
      /* 本机存储不可用 */
    }
  } catch {
    // 离线：用缓存的今日任务
    try {
      const c = JSON.parse(localStorage.getItem(cacheKey()) ?? 'null');
      if (c) {
        works.value = c.works;
        fromCache.value = true;
        cachedAt.value = new Date(c.at).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
      }
    } catch {
      /* 无缓存 */
    }
  } finally {
    loading.value = false;
  }
  todos.value = await workbenchApi.todo().catch(() => todos.value);
}

async function start(w: BoardWork) {
  try {
    await submitOp('ls.core.Work.start', String(w.id), w.version, {}, newKey());
    showToast('已开工');
    void router.push(`/m/report/${w.id}`);
  } catch (e: any) {
    if (!e?.code) showToast('网络不可用，开工需要联网');
  }
}
function report(w: BoardWork) {
  void router.push(`/m/report/${w.id}`);
}
/** 快捷"报工"：只有一个进行中的任务时直接打开，否则定位到任务列表 */
function quickReport() {
  const running = sorted.value.filter((w) => w.status === 'IN_PROGRESS');
  if (running.length === 1) return report(running[0]);
  showToast(running.length ? '请选择要报工的任务' : '没有进行中的任务');
  document.querySelector('[data-testid="m-section-current"], [data-testid="m-section-others"]')?.scrollIntoView({ behavior: 'smooth' });
}

const TaskCard = defineComponent({
  props: { work: { type: Object as () => BoardWork, required: true }, pending: { type: Array as () => unknown[], default: () => [] } },
  emits: ['start', 'report'],
  setup(p, { emit: e }) {
    return () => {
      const w = p.work;
      const urgent = w.priority <= 2;
      const action =
        w.status === 'ASSIGNED'
          ? h('button', { type: 'button', class: 'm-btn m-btn--primary', 'data-testid': `m-task-start-${w.id}`, onClick: () => e('start') }, '开工')
          : w.status === 'IN_PROGRESS'
            ? h(
                'button',
                { type: 'button', class: 'm-btn m-btn--primary', 'data-testid': `m-task-report-${w.id}`, onClick: () => e('report') },
                '报工'
              )
            : null;
      return h('div', { class: 'm-card m-task', 'data-testid': `m-task-${w.id}`, 'data-status': w.status }, [
        h('div', { class: 'm-task__main' }, [
          h('div', { class: 'm-task__title' }, [urgent ? h('span', { class: 'm-tag m-tag--danger' }, '急') : null, ' ', w.title]),
          h('div', { class: 'm-sub' }, [
            `${w.code} · ${shiftLabel(w.shift)}`,
            w.plannedQty ? ` · 计划 ${Number(w.plannedQty)}${w.unit ?? ''}` : '',
            w.progress != null ? ` · ${Number(w.progress)}%` : ''
          ]),
          h('div', { class: 'm-task__tags' }, [
            h('span', { class: 'm-tag' }, WORK_STATUS[w.status] ?? w.status),
            p.pending.length ? h('span', { class: 'm-tag m-tag--warn', 'data-testid': `m-task-pending-${w.id}` }, `待同步 ${p.pending.length}`) : null
          ])
        ]),
        action
      ]);
    };
  }
});

onMounted(load);
</script>

<style scoped>
.m-hello {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.m-hello__name {
  font-size: 18px;
  font-weight: 600;
}
.m-quick {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}
.m-quick__btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-height: 72px;
  border-radius: 12px;
  border: 0;
  background: var(--ls-color-surface, #fff);
  color: inherit;
  font-size: 14px;
  text-decoration: none;
}
.m-quick__btn span {
  font-size: 22px;
  color: var(--ls-color-primary, #1677ff);
}
:deep(.m-task) {
  display: flex;
  align-items: center;
  gap: 12px;
}
:deep(.m-task__main) {
  flex: 1;
  min-width: 0;
}
:deep(.m-task__title) {
  font-weight: 600;
  font-size: 16px;
  margin-bottom: 4px;
}
:deep(.m-task__tags) {
  display: flex;
  gap: 6px;
  margin-top: 6px;
}
:deep(.m-task .m-btn) {
  min-width: 76px;
}
.m-todo {
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: inherit;
  text-decoration: none;
}
.m-todo__title {
  font-weight: 500;
}
.m-empty--small {
  padding: 8px 0;
}
</style>
