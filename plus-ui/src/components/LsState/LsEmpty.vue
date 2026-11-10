<template>
  <div class="ls-empty" role="status" data-testid="ls-empty">
    <svg class="ls-empty__art" viewBox="0 0 160 120" aria-hidden="true">
      <rect x="28" y="22" width="104" height="76" rx="8" class="ls-empty__card" />
      <rect x="42" y="38" width="52" height="8" rx="4" class="ls-empty__line" />
      <rect x="42" y="54" width="76" height="8" rx="4" class="ls-empty__line ls-empty__line--soft" />
      <rect x="42" y="70" width="40" height="8" rx="4" class="ls-empty__line ls-empty__line--soft" />
      <circle cx="120" cy="88" r="18" class="ls-empty__dot" />
      <path d="M112 88l6 6 10-12" class="ls-empty__check" />
    </svg>
    <p class="ls-empty__title">{{ title }}</p>
    <p v-if="description" class="ls-empty__desc">{{ description }}</p>
    <div v-if="actionText || $slots.default" class="ls-empty__actions">
      <slot>
        <el-button type="primary" data-testid="ls-empty-action" @click="$emit('action')">{{ actionText }}</el-button>
      </slot>
    </div>
  </div>
</template>

<script setup lang="ts">
/** 空态：插图＋说明＋一个相关的主操作，而不是空表格。 */
defineProps<{ title: string; description?: string; actionText?: string }>();
defineEmits<{ action: [] }>();
</script>

<style scoped>
.ls-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: var(--ls-space-10) var(--ls-space-4);
  color: var(--ls-color-text-secondary);
}
.ls-empty__art {
  width: 160px;
  height: 120px;
  margin-bottom: var(--ls-space-4);
}
.ls-empty__card {
  fill: var(--ls-color-bg);
  stroke: var(--ls-color-border);
  stroke-width: 1.5;
}
.ls-empty__line {
  fill: var(--ls-color-fill-dark);
}
.ls-empty__line--soft {
  fill: var(--ls-color-fill);
}
.ls-empty__dot {
  fill: color-mix(in srgb, var(--ls-color-primary) 14%, var(--ls-color-bg));
  stroke: var(--ls-color-primary);
  stroke-width: 1.5;
}
.ls-empty__check {
  fill: none;
  stroke: var(--ls-color-primary);
  stroke-width: 2.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.ls-empty__title {
  margin: 0;
  font-size: var(--ls-font-size-lg);
  font-weight: var(--ls-font-weight-medium);
  color: var(--ls-color-text);
}
.ls-empty__desc {
  margin: var(--ls-space-2) 0 0;
  max-width: 420px;
  font-size: var(--ls-font-size-md);
}
.ls-empty__actions {
  margin-top: var(--ls-space-5);
  display: flex;
  gap: var(--ls-space-2);
}
</style>
