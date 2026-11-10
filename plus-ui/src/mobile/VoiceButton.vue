<template>
  <button
    v-if="available"
    type="button"
    class="m-voice"
    :class="{ 'is-on': on }"
    :aria-label="on ? '松开结束说话' : '按住说话'"
    :data-testid="testid"
    @pointerdown.prevent="start"
    @pointerup.prevent="stop"
    @pointerleave="stop"
    @pointercancel="stop"
    @contextmenu.prevent
  >
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <path
        fill="currentColor"
        d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11h-2z"
      />
    </svg>
    <span v-if="on" class="m-voice__tip">正在听…</span>
  </button>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { showToast } from 'vant';
import { detectSpeech, listen, speechMode, type Listening } from './speech';

/** 按住说话、松开结束，识别出的文字经 text 事件交给字段（用户可再修改）。不支持语音时不显示。 */
withDefaults(defineProps<{ testid?: string }>(), { testid: 'voice' });
const emit = defineEmits<{ (e: 'text', t: string): void }>();
const available = computed(() => speechMode.value === 'browser' || speechMode.value === 'server');
const on = ref(false);
let cur: Listening | null = null;

onMounted(() => void detectSpeech());

async function start() {
  if (on.value) return;
  on.value = true;
  try {
    cur = await listen(
      (t) => emit('text', t),
      (m) => showToast(m)
    );
  } catch {
    on.value = false;
    showToast('无法使用麦克风');
  }
}
function stop() {
  if (!on.value) return;
  on.value = false;
  cur?.stop();
  cur = null;
}
</script>

<style scoped>
.m-voice {
  min-width: 44px;
  min-height: 44px;
  border: 1px solid var(--ls-color-border, #dcdfe6);
  border-radius: 22px;
  background: var(--ls-color-bg-elevated);
  color: var(--ls-color-primary-text);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 0 10px;
  touch-action: none;
  user-select: none;
}
.m-voice.is-on {
  background: var(--ls-color-primary, #1677ff);
  color: var(--ls-color-on-solid);
}
.m-voice__tip {
  font-size: 13px;
}
</style>
