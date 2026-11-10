<template>
  <section class="home-card" :aria-labelledby="`hc-${uid}`" :data-testid="`home-card-${code}`">
    <header class="home-card__head">
      <h2 :id="`hc-${uid}`" class="home-card__title">
        {{ title }}
        <span v-if="count !== undefined && count !== null" class="home-card__count" data-testid="home-card-count">{{ count }}</span>
      </h2>
      <router-link v-if="link" :to="link" class="home-card__link" data-testid="home-card-link">{{ linkText || '查看全部' }}</router-link>
    </header>
    <div class="home-card__body">
      <LsSkeleton v-if="loading" variant="card" :rows="3" />
      <LsError v-else-if="error" :title="`${title}加载失败`" :reason="error" retryable @retry="$emit('retry')" />
      <LsEmpty v-else-if="empty" :title="emptyText || '暂无内容'" />
      <slot v-else />
    </div>
  </section>
</template>

<script setup lang="ts">
/** 首页卡片外框：标题、数量、"查看全部"（一键进入对应列表）、加载 / 出错 / 空状态。 */
import { LsEmpty, LsError, LsSkeleton } from '@/components/LsState';

defineProps<{
  code: string;
  title: string;
  count?: number | null;
  link?: string;
  linkText?: string;
  loading?: boolean;
  error?: string;
  empty?: boolean;
  emptyText?: string;
}>();
defineEmits<{ retry: [] }>();
const uid = Math.random().toString(36).slice(2, 8);
</script>

<style scoped lang="scss">
.home-card {
  display: flex;
  flex-direction: column;
  min-height: 220px;
  padding: var(--ls-space-4);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--ls-radius-lg, 10px);
  background: var(--el-bg-color);
}
.home-card__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ls-space-2);
  margin-bottom: var(--ls-space-3);
}
.home-card__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.home-card__count {
  margin-left: var(--ls-space-2);
  font-size: 20px;
  color: var(--ls-color-primary-text);
  font-variant-numeric: tabular-nums;
}
.home-card__link {
  font-size: 13px;
  color: var(--ls-color-primary-text);
  white-space: nowrap;
}
.home-card__body {
  flex: 1;
}
</style>
