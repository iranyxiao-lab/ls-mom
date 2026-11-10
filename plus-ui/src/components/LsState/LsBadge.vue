<template>
  <span class="ls-badge-host">
    <slot />
    <span v-if="show" class="ls-badge" :class="`ls-badge--${tone}`" :aria-label="`${count} 条${unit}`" data-testid="ls-badge">{{ text }}</span>
  </span>
</template>

<script setup lang="ts">
/** 徽标：未读数、待办数。读屏读出"N 条未读"。 */
import { computed } from 'vue';

const props = withDefaults(defineProps<{ count: number; max?: number; tone?: 'danger' | 'primary' | 'neutral'; unit?: string; showZero?: boolean }>(), {
  max: 99,
  tone: 'danger',
  unit: '未读',
  showZero: false
});
const show = computed(() => props.count > 0 || props.showZero);
const text = computed(() => (props.count > props.max ? `${props.max}+` : String(props.count)));
</script>

<style scoped>
.ls-badge-host {
  position: relative;
  display: inline-flex;
}
.ls-badge {
  position: absolute;
  top: 0;
  right: 0;
  transform: translate(50%, -40%);
  min-width: 18px;
  height: 18px;
  padding: 0 var(--ls-space-1);
  border-radius: var(--ls-radius-full);
  font-size: var(--ls-font-size-xs);
  line-height: 18px;
  text-align: center;
  color: var(--ls-color-on-solid);
  border: 1px solid var(--ls-color-bg);
}
.ls-badge--danger {
  background: var(--ls-color-danger);
}
.ls-badge--primary {
  background: var(--ls-color-primary);
}
.ls-badge--neutral {
  background: var(--ls-color-info);
}
</style>
