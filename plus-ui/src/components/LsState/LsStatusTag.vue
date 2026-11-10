<template>
  <span class="ls-status" :class="`ls-status--${s.tone}`" :data-status="status" :data-tone="s.tone" role="status">
    <span class="ls-status__icon" :class="s.icon" aria-hidden="true" />
    <span class="ls-status__label">{{ s.label }}</span>
  </span>
</template>

<script setup lang="ts">
/** 状态标签：颜色＋图标＋文字，色盲与黑白打印下也可辨认。 */
import { computed } from 'vue';
import { statusStyle, type Tone } from './status';

const props = defineProps<{ status: string; label?: string; tone?: Tone; icon?: string }>();
const s = computed(() => statusStyle(props.status, { label: props.label, tone: props.tone, icon: props.icon }));
</script>

<style scoped>
.ls-status {
  display: inline-flex;
  align-items: center;
  gap: var(--ls-space-1);
  padding: var(--ls-space-half) var(--ls-space-2);
  border-radius: var(--ls-radius-full);
  font-size: var(--ls-font-size-xs);
  line-height: var(--ls-line-height-normal);
  white-space: nowrap;
  border: 1px solid transparent;
}
.ls-status__icon {
  width: 1.1em;
  height: 1.1em;
  flex: none;
}
.ls-status--neutral {
  color: var(--ls-color-text-secondary);
  background: var(--ls-color-fill);
  border-color: var(--ls-color-border-light);
}
.ls-status--info {
  color: var(--ls-color-text-regular);
  background: var(--ls-color-info-soft);
  border-color: var(--ls-color-border-light);
}
.ls-status--primary {
  color: var(--ls-color-primary-text);
  background: color-mix(in srgb, var(--ls-color-primary) 10%, var(--ls-color-bg));
}
.ls-status--success {
  color: var(--ls-color-success);
  background: var(--ls-color-success-soft);
}
.ls-status--warning {
  color: var(--ls-color-warning);
  background: var(--ls-color-warning-soft);
}
.ls-status--danger {
  color: var(--ls-color-danger);
  background: var(--ls-color-danger-soft);
}
</style>
