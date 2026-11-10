<template>
  <div class="ls-upload">
    <ul class="ls-upload__list">
      <li v-for="(f, i) in files" :key="f.ossId">
        <img v-if="image && f.url" :src="f.url" :alt="f.name" class="ls-upload__thumb" />
        <a v-else :href="f.url" target="_blank" rel="noopener">{{ f.name }}</a>
        <el-button v-if="!disabled" link type="danger" size="small" @click="remove(i)">移除</el-button>
      </li>
    </ul>
    <label v-if="!disabled && files.length < (maxCount ?? 10)" class="ls-upload__pick">
      <input type="file" :accept="accept ?? (image ? 'image/*' : undefined)" :capture="capture ? 'environment' : undefined" @change="pick" />
      <span>{{ image ? '上传图片' : '上传附件' }}</span>
    </label>
    <span v-if="uploading" class="ls-upload__hint">上传中…</span>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import type { RendererApi } from '@/api/lsmom/form';
import type { FileRef } from './types';

const props = defineProps<{
  modelValue: unknown;
  api: RendererApi;
  image?: boolean;
  disabled?: boolean;
  maxCount?: number;
  accept?: string;
  capture?: boolean;
}>();
const emit = defineEmits<{ (e: 'update:modelValue', v: FileRef[]): void }>();
const uploading = ref(false);
const files = computed<FileRef[]>(() => (Array.isArray(props.modelValue) ? (props.modelValue as FileRef[]) : []));

async function pick(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  uploading.value = true;
  try {
    const r = await props.api.upload(file);
    emit('update:modelValue', [...files.value, { ossId: String(r.ossId), name: r.fileName ?? file.name, url: r.url }]);
  } finally {
    uploading.value = false;
    input.value = '';
  }
}

function remove(i: number) {
  emit(
    'update:modelValue',
    files.value.filter((_, j) => j !== i)
  );
}
</script>

<style scoped>
.ls-upload__list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.ls-upload__list li {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ls-upload__thumb {
  width: 64px;
  height: 64px;
  object-fit: cover;
  border-radius: 4px;
}
.ls-upload__pick input {
  display: none;
}
.ls-upload__pick span {
  cursor: pointer;
  color: var(--el-color-primary);
}
.ls-upload__hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
}
</style>
