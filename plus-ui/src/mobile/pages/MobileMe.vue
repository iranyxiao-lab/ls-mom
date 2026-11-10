<template>
  <div data-testid="m-me">
    <div class="m-card m-me__user">
      <div class="m-me__name">{{ user.nickname || user.name }}</div>
      <div class="m-sub">{{ outbox.online ? '在线' : '当前离线' }}</div>
    </div>

    <div class="m-section">离线待同步（{{ outbox.items.length }}）</div>
    <div v-if="!outbox.items.length" class="m-empty" data-testid="m-outbox-empty">没有待同步的提交</div>
    <div v-for="i in outbox.items" :key="i.id" class="m-card m-outbox" :data-testid="`m-outbox-${i.id}`" :data-status="i.status">
      <div class="m-outbox__title">{{ i.title }}</div>
      <div class="m-sub">{{ when(i.createdAt) }}{{ i.photos?.length ? ` · 照片 ${i.photos.length} 张` : '' }}</div>
      <div v-if="i.status === 'FAILED'" class="m-outbox__err" data-testid="m-outbox-error">同步失败：{{ i.error }}</div>
      <div v-else class="m-tag m-tag--warn">{{ i.status === 'SYNCING' ? '同步中…' : '待同步' }}</div>
      <button v-if="i.status === 'FAILED'" type="button" class="m-btn m-outbox__drop" data-testid="m-outbox-discard" @click="drop(i.id)">丢弃</button>
    </div>
    <button
      v-if="outbox.items.some((i) => i.status === 'PENDING')"
      type="button"
      class="m-btn m-btn--primary m-me__wide"
      data-testid="m-outbox-sync"
      :disabled="!outbox.online || outbox.syncing"
      @click="sync"
    >
      {{ outbox.syncing ? '同步中…' : '立即同步' }}
    </button>

    <div class="m-section">界面</div>
    <div class="m-card m-me__modes" role="radiogroup" aria-label="界面模式">
      <button
        v-for="m in MODES"
        :key="m.value"
        type="button"
        role="radio"
        :aria-checked="uiMode === m.value"
        class="m-btn"
        :class="{ 'm-btn--primary': uiMode === m.value }"
        :data-testid="`m-mode-${m.value}`"
        @click="choose(m.value)"
      >
        {{ m.label }}
      </button>
    </div>
    <button type="button" class="m-btn m-me__wide" data-testid="m-to-desktop" @click="toDesktop">切换到电脑版</button>

    <button type="button" class="m-btn m-btn--danger-text m-me__wide m-me__logout" data-testid="m-logout" @click="logout">退出登录</button>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router';
import { showConfirmDialog, showToast } from 'vant';
import { useUserStore } from '@/store/modules/user';
import { discard, flush, outbox } from '../outbox';
import { outboxSender } from '../api';
import { setUiMode, uiMode, type UiMode } from '../device';

/** 我的：离线待同步（失败的可查看原因并丢弃）、界面模式（自动／电脑版／手机版）、退出。 */
const MODES: { value: UiMode; label: string }[] = [
  { value: 'auto', label: '自动' },
  { value: 'mobile', label: '手机版' },
  { value: 'desktop', label: '电脑版' }
];
const user = useUserStore();
const router = useRouter();
const when = (s: string) =>
  new Date(s).toLocaleString('zh-CN', { hour12: false, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });

async function sync() {
  await flush(outboxSender);
  const failed = outbox.items.filter((i) => i.status === 'FAILED').length;
  showToast(failed ? `有 ${failed} 条同步失败，请查看` : '已同步');
}

async function drop(id: string) {
  try {
    await showConfirmDialog({
      title: '丢弃这条提交',
      message: '丢弃后不会再提交，此操作不能撤销。',
      confirmButtonText: '丢弃',
      confirmButtonColor: '#f53f3f'
    });
  } catch {
    return;
  }
  await discard(id);
}

async function choose(m: UiMode) {
  await setUiMode(m);
  if (m === 'desktop') void router.push('/index');
}

async function toDesktop() {
  await setUiMode('desktop');
  void router.push('/index');
}

async function logout() {
  await user.logout();
  void router.replace({ path: '/login', query: { redirect: encodeURIComponent('/m') } });
}
</script>

<style scoped>
.m-me__name {
  font-size: 18px;
  font-weight: 600;
}
.m-outbox__title {
  font-weight: 500;
}
.m-outbox__err {
  margin: 6px 0;
  color: #cb2634;
}
.m-outbox__drop {
  margin-top: 6px;
}
.m-me__wide {
  width: 100%;
  margin-bottom: 12px;
}
.m-me__modes {
  display: flex;
  gap: 8px;
}
.m-me__modes .m-btn {
  flex: 1;
}
.m-me__logout {
  margin-top: 24px;
}
</style>
