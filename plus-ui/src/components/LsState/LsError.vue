<template>
  <div class="ls-error" role="alert" data-testid="ls-error">
    <span class="ls-error__icon i-lucide-circle-alert" aria-hidden="true" />
    <p class="ls-error__title">{{ title }}</p>
    <p v-if="reason" class="ls-error__reason" data-testid="ls-error-reason">{{ reason }}</p>
    <p v-if="nextStep" class="ls-error__next">{{ nextStep }}</p>
    <div class="ls-error__actions">
      <slot />
      <el-button v-if="retryable" type="primary" data-testid="ls-error-retry" @click="$emit('retry')">重试</el-button>
    </div>
    <p v-if="traceId" class="ls-error__trace">
      追踪编号 <code data-testid="ls-error-trace">{{ traceId }}</code>
      <el-button link size="small" aria-label="复制追踪编号" @click="copy">
        <span class="i-lucide-copy" aria-hidden="true" />
      </el-button>
    </p>
  </div>
</template>

<script setup lang="ts">
/** 错误态：说明原因与下一步，带重试与追踪编号（trace_id），方便反馈给管理员。 */
import { ElMessage } from 'element-plus';

const props = defineProps<{ title: string; reason?: string; nextStep?: string; traceId?: string; retryable?: boolean }>();
defineEmits<{ retry: [] }>();

async function copy() {
  try {
    await navigator.clipboard.writeText(props.traceId ?? '');
    ElMessage.success('已复制追踪编号');
  } catch {
    ElMessage.info('请手动复制追踪编号');
  }
}
</script>

<style scoped>
.ls-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: var(--ls-space-10) var(--ls-space-4);
  color: var(--ls-color-text-secondary);
}
.ls-error__icon {
  width: 48px;
  height: 48px;
  color: var(--ls-color-danger);
}
.ls-error__title {
  margin: var(--ls-space-3) 0 0;
  font-size: var(--ls-font-size-lg);
  font-weight: var(--ls-font-weight-medium);
  color: var(--ls-color-text);
}
.ls-error__reason,
.ls-error__next {
  margin: var(--ls-space-2) 0 0;
  max-width: 480px;
}
.ls-error__actions {
  margin-top: var(--ls-space-5);
  display: flex;
  gap: var(--ls-space-2);
}
.ls-error__trace {
  margin-top: var(--ls-space-4);
  font-size: var(--ls-font-size-xs);
  display: flex;
  align-items: center;
  gap: var(--ls-space-1);
}
</style>
