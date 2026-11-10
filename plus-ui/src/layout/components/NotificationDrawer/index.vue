<template>
  <div class="ls-notice-entry">
    <!-- 整块（含未读数角标）都是按钮，角标不挡点击 -->
    <button
      type="button"
      class="right-menu-item hover-effect ls-notice-btn"
      :aria-label="`通知，${total} 条未读`"
      data-testid="open-notices"
      @click="open"
    >
      <el-badge :value="total > 0 ? total : ''" :max="99" data-testid="notice-badge">
        <svg-icon icon-class="message" />
      </el-badge>
    </button>
    <el-drawer v-model="visible" title="通知" size="420px" append-to-body class="ls-notice-drawer" data-testid="notice-drawer" @open="load">
      <el-tabs v-model="tab">
        <el-tab-pane v-for="g in GROUPS" :key="g.key" :name="g.key">
          <template #label>
            <span :data-testid="`notice-tab-${g.key}`">{{ g.title }}</span>
            <el-badge v-if="unread[g.key]" :value="unread[g.key]" :max="99" class="tab-badge" />
          </template>
          <div class="notice-tools">
            <el-button link :disabled="!unread[g.key]" :data-testid="`notice-read-all-${g.key}`" @click="readAll(g.key)">全部已读</el-button>
          </div>
          <LsSkeleton v-if="loading && !lists[g.key].length" variant="card" :rows="3" />
          <LsEmpty v-else-if="!lists[g.key].length" :title="g.empty" />
          <ul v-else class="notice-list">
            <li
              v-for="n in lists[g.key]"
              :key="n.key"
              :class="['notice-item', { unread: !n.read }]"
              :data-testid="`notice-item-${g.key}`"
              :data-notice-id="n.id"
            >
              <button type="button" class="notice-main" @click="openItem(n)">
                <span v-if="!n.read" class="dot" aria-label="未读" />
                <span class="notice-title">{{ n.title }}</span>
                <span class="notice-msg">{{ n.message }}</span>
                <span class="notice-meta">{{ n.from ? n.from + ' · ' : '' }}{{ n.time }}</span>
              </button>
              <div v-if="n.action" class="notice-act">
                <LsStatusTag v-if="n.done" status="DONE" label="已处理" />
                <el-button v-else type="primary" size="small" :loading="busy === n.key" data-testid="notice-action" @click="act(n)">
                  {{ n.action.label }}
                </el-button>
              </div>
            </li>
          </ul>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/**
 * 通知抽屉：顶栏显示未读数，按"待办提醒 / 系统消息 / @我"分组；点通知跳到对象，带动作的通知（如确认接班）在抽屉内直接完成。
 * 系统消息同时包含服务端推送（SSE）的公告。未读数每分钟刷新一次。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { LsEmpty, LsSkeleton, LsStatusTag } from '@/components/LsState';
import { useNoticeStore } from '@/store/modules/notice';
import { notificationApi, type Notice, type NoticeAction, type NoticeGroup } from '@/api/lsmom/notifications';

interface Row {
  key: string;
  id?: number;
  title: string;
  message: string;
  time: string;
  from?: string | null;
  link?: string | null;
  action?: NoticeAction | null;
  read: boolean;
  done: boolean;
  /** SSE 推送的公告（只在本地记已读） */
  local?: { index: number };
}

const GROUPS: { key: NoticeGroup; title: string; empty: string }[] = [
  { key: 'todo', title: '待办提醒', empty: '没有待处理的提醒' },
  { key: 'system', title: '系统消息', empty: '没有系统消息' },
  { key: 'mention', title: '@我', empty: '没有提到你的消息' }
];
const KIND_TITLE: Record<string, string> = {
  REMIND: '待办提醒',
  URGE: '催办',
  ESCALATE: '超时升级',
  EXCEPTION: '流程异常',
  TRANSFER: '转办',
  PACK_REVIEW: '应用待评审',
  DCS_ALARM: 'DCS 报警',
  ACTION: '待确认',
  SYSTEM: '系统消息',
  MENTION: '@我',
  CC: '抄送'
};

const router = useRouter();
const sse = useNoticeStore();
const visible = ref(false);
const tab = ref<NoticeGroup>('todo');
const loading = ref(false);
const busy = ref<string>();
const server = reactive<Record<NoticeGroup, Notice[]>>({ todo: [], system: [], mention: [] });
const serverUnread = reactive<Record<NoticeGroup, number>>({ todo: 0, system: 0, mention: 0 });

const fmt = (t?: string) => (t ? String(t).replace('T', ' ').slice(5, 16) : '');
const toRow = (n: Notice): Row => ({
  key: `s${n.id}`,
  id: n.id,
  title: n.title || KIND_TITLE[n.kind] || '通知',
  message: n.message,
  time: fmt(n.createdAt),
  from: n.fromName,
  link: n.link,
  action: n.action,
  read: !!n.readAt,
  done: !!n.doneAt
});
const sseRows = computed<Row[]>(() =>
  sse.state.notices
    .map((n: any, index: number) => ({
      key: `l${index}`,
      title: n.title || '系统公告',
      message: String(n.message ?? ''),
      time: n.time,
      read: !!n.read,
      done: false,
      local: { index }
    }))
    .reverse()
);
const lists = computed<Record<NoticeGroup, Row[]>>(() => ({
  todo: server.todo.map(toRow),
  system: [...sseRows.value, ...server.system.map(toRow)],
  mention: server.mention.map(toRow)
}));
const unread = computed<Record<NoticeGroup, number>>(() => ({
  todo: serverUnread.todo,
  system: serverUnread.system + sseRows.value.filter((r) => !r.read).length,
  mention: serverUnread.mention
}));
const total = computed(() => unread.value.todo + unread.value.system + unread.value.mention);

async function load() {
  loading.value = true;
  try {
    const r = await notificationApi.list();
    for (const g of GROUPS) {
      server[g.key] = r[g.key] ?? [];
      serverUnread[g.key] = Number(r.unread?.[g.key] ?? 0);
    }
  } catch {
    /* 拉取失败时保留上次结果 */
  } finally {
    loading.value = false;
  }
}

function open() {
  visible.value = true;
  // 有待办提醒时默认打开待办页，否则打开有未读的分组
  tab.value = GROUPS.find((g) => unread.value[g.key])?.key ?? tab.value;
}

async function markRead(r: Row) {
  if (r.read) return;
  if (r.local) {
    sse.state.notices[r.local.index].read = true;
    return;
  }
  await notificationApi.read([r.id!]).catch(() => undefined);
  await load();
}

async function openItem(r: Row) {
  await markRead(r);
  if (r.link) {
    visible.value = false;
    await router.push(r.link);
  }
}

async function act(r: Row) {
  if (!r.action) return;
  busy.value = r.key;
  try {
    await notificationApi.act(r.action);
    ElMessage.success(`已${r.action.label}`);
    await notificationApi.read([r.id!]).catch(() => undefined);
  } catch {
    /* 失败原因（如已被他人处理、无权限）由请求拦截器提示 */
  } finally {
    busy.value = undefined;
    await load();
  }
}

async function readAll(g: NoticeGroup) {
  if (g === 'system') sse.readAll();
  const ids = server[g].filter((n) => !n.readAt).map((n) => n.id);
  if (ids.length) await notificationApi.read(ids).catch(() => undefined);
  await load();
}

let timer: ReturnType<typeof setInterval> | undefined;
onMounted(() => {
  void load();
  timer = setInterval(() => document.visibilityState === 'visible' && load(), 60_000);
});
onBeforeUnmount(() => clearInterval(timer));

defineExpose({ open, load });
</script>

<style lang="scss">
.ls-notice-drawer {
  max-width: 100vw;
  .tab-badge {
    margin-left: 4px;
    vertical-align: middle;
  }
}
</style>

<style scoped lang="scss">
.ls-notice-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 32px;
  height: 32px;
  padding: 0 8px;
  font-size: 18px;
  border-radius: var(--app-radius-md);
  &:hover {
    background: var(--el-fill-color-light);
  }
  &:focus-visible {
    outline: 2px solid var(--el-color-primary);
  }
  border: 0;
  background: none;
  color: var(--el-text-color-regular);
  cursor: pointer;
  :deep(.el-badge__content) {
    pointer-events: none;
  }
}
.notice-tools {
  display: flex;
  justify-content: flex-end;
}
.notice-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.notice-item {
  display: flex;
  align-items: flex-start;
  gap: var(--ls-space-2);
  padding: var(--ls-space-3) 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.notice-main {
  flex: 1;
  min-width: 0;
  display: grid;
  grid-template-columns: auto 1fr;
  column-gap: var(--ls-space-2);
  text-align: left;
  background: none;
  border: 0;
  padding: 0;
  color: inherit;
  font: inherit;
  cursor: pointer;
  &:focus-visible {
    outline: 2px solid var(--el-color-primary);
    outline-offset: 2px;
  }
}
.dot {
  grid-row: 1;
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--el-color-danger);
}
.notice-title {
  grid-column: 2;
  font-weight: 600;
}
.notice-msg,
.notice-meta {
  grid-column: 2;
  overflow-wrap: anywhere;
}
.notice-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.notice-item:not(.unread) .notice-title {
  font-weight: 400;
}
.notice-act {
  flex: none;
}
</style>
