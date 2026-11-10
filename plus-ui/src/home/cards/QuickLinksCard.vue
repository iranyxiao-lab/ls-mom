<template>
  <HomeCard
    code="ls.home.quickLinks"
    title="快捷入口"
    link="/user/prefs"
    link-text="管理收藏"
    :empty="!links.length"
    empty-text="收藏常用页面后会显示在这里（命令面板 Ctrl+D）"
  >
    <ul class="ql">
      <li v-for="l in links" :key="l.path">
        <router-link :to="l.path" class="ql-item" data-testid="home-row">
          <svg-icon :icon-class="l.icon || 'link'" />
          <span>{{ l.title }}</span>
        </router-link>
      </li>
    </ul>
  </HomeCard>
</template>

<script setup lang="ts">
/** 快捷入口：收藏优先，不足时补最近访问的页面。 */
import { computed } from 'vue';
import { useNavHistoryStore } from '@/store/modules/navHistory';
import HomeCard from '../HomeCard.vue';

const history = useNavHistoryStore();
const links = computed(() => {
  const fav = history.favorites;
  const seen = new Set(fav.map((f) => f.path));
  const recent = history.recent.filter((r) => r.kind === 'page' && !seen.has(r.path) && r.path !== '/index');
  return [...fav, ...recent].slice(0, 8);
});
</script>

<style scoped>
.ql {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: var(--ls-space-2);
}
.ql-item {
  display: flex;
  align-items: center;
  gap: var(--ls-space-2);
  min-height: 40px;
  padding: 0 var(--ls-space-2);
  border-radius: var(--ls-radius-md, 6px);
  background: var(--el-fill-color-light);
  color: inherit;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.ql-item:hover {
  color: var(--el-color-primary);
}
</style>
