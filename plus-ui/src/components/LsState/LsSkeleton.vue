<template>
  <div class="ls-skeleton" :class="`ls-skeleton--${variant}`" aria-busy="true" aria-live="polite" data-testid="ls-skeleton">
    <span class="ls-sr-only">正在加载…</span>
    <template v-if="variant === 'table'">
      <div class="ls-skeleton__row ls-skeleton__row--head" />
      <div v-for="i in rows" :key="i" class="ls-skeleton__row" />
    </template>
    <template v-else-if="variant === 'form'">
      <div v-for="i in rows" :key="i" class="ls-skeleton__field">
        <div class="ls-skeleton__bar ls-skeleton__bar--label" />
        <div class="ls-skeleton__bar ls-skeleton__bar--input" />
      </div>
    </template>
    <template v-else>
      <div v-for="i in rows" :key="i" class="ls-skeleton__card">
        <div class="ls-skeleton__bar ls-skeleton__bar--title" />
        <div class="ls-skeleton__bar" />
        <div class="ls-skeleton__bar ls-skeleton__bar--short" />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
/** 骨架屏：按内容形状占位；系统开启"减少动态效果"时不闪烁。 */
withDefaults(defineProps<{ variant?: 'table' | 'form' | 'card'; rows?: number }>(), { variant: 'table', rows: 6 });
</script>

<style scoped>
.ls-skeleton {
  display: grid;
  gap: var(--ls-space-2);
}
.ls-skeleton__row,
.ls-skeleton__bar {
  border-radius: var(--ls-radius-md);
  background: linear-gradient(90deg, var(--ls-color-fill) 25%, var(--ls-color-fill-light) 37%, var(--ls-color-fill) 63%);
  background-size: 400% 100%;
  animation: ls-shimmer 1.4s ease infinite;
}
.ls-skeleton__row {
  height: var(--ls-table-row-height);
}
.ls-skeleton__row--head {
  background: var(--ls-color-fill-dark);
  animation: none;
}
.ls-skeleton__field {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: var(--ls-space-3);
  align-items: center;
}
.ls-skeleton__bar {
  height: var(--ls-space-3);
}
.ls-skeleton__bar--label {
  width: 80px;
}
.ls-skeleton__bar--input {
  height: var(--ls-control-height);
}
.ls-skeleton__bar--title {
  width: 40%;
  height: var(--ls-space-4);
}
.ls-skeleton__bar--short {
  width: 60%;
}
.ls-skeleton--card {
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
}
.ls-skeleton__card {
  display: grid;
  gap: var(--ls-space-3);
  padding: var(--ls-card-padding);
  border: 1px solid var(--ls-color-border-light);
  border-radius: var(--ls-radius-lg);
  background: var(--ls-color-bg);
}
@keyframes ls-shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}
@media (prefers-reduced-motion: reduce) {
  .ls-skeleton__row,
  .ls-skeleton__bar {
    animation: none;
  }
}
html[data-motion='reduced'] .ls-skeleton__row,
html[data-motion='reduced'] .ls-skeleton__bar {
  animation: none;
}
.ls-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
</style>
