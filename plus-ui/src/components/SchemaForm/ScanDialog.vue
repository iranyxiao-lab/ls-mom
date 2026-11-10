<template>
  <el-dialog
    :model-value="modelValue"
    :title="title ?? '扫码'"
    width="420px"
    append-to-body
    destroy-on-close
    data-testid="scan-dialog"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @opened="start"
    @closed="stop"
  >
    <div class="ls-scan">
      <video ref="videoEl" class="ls-scan__video" muted playsinline aria-label="摄像头画面" data-testid="scan-video" />
      <div v-if="state === 'starting'" class="ls-scan__tip">正在打开摄像头…</div>
      <div v-else-if="state === 'scanning'" class="ls-scan__tip">把条码或二维码对准取景框</div>
      <el-alert v-else-if="state === 'error'" type="warning" :closable="false" :title="error" data-testid="scan-error" />
    </div>
    <el-form class="ls-scan__manual" @submit.prevent="submitManual">
      <el-input
        v-model="manual"
        placeholder="也可以用扫码枪扫描或手动输入编码后按 Enter"
        aria-label="编码"
        data-testid="scan-manual"
        @keyup.enter="submitManual"
      />
    </el-form>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 摄像头扫码（@zxing/browser，按需加载）：识别到条码或二维码后关闭摄像头并返回内容；没有摄像头或被拒绝时可用扫码枪或手动输入。
 */
import { onBeforeUnmount, ref } from 'vue';

defineProps<{ modelValue: boolean; title?: string }>();
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'scanned', text: string): void }>();

const videoEl = ref<HTMLVideoElement>();
const state = ref<'starting' | 'scanning' | 'error'>('starting');
const error = ref('');
const manual = ref('');
let controls: { stop: () => void } | null = null;
let done = false;

async function start() {
  done = false;
  state.value = 'starting';
  manual.value = '';
  try {
    if (!navigator.mediaDevices?.getUserMedia) throw new Error('当前浏览器不支持摄像头');
    const { BrowserMultiFormatReader } = await import('@zxing/browser');
    const reader = new BrowserMultiFormatReader();
    controls = await reader.decodeFromConstraints({ video: { facingMode: 'environment' } }, videoEl.value, (result) => {
      if (result && !done) finish(result.getText());
    });
    if (done) controls.stop();
    else state.value = 'scanning';
  } catch (e: any) {
    state.value = 'error';
    error.value = `无法打开摄像头（${e?.name === 'NotAllowedError' ? '未授权' : (e?.message ?? '未知原因')}），可以用扫码枪或手动输入编码`;
  }
}

function finish(text: string) {
  done = true;
  stop();
  emit('scanned', text);
  emit('update:modelValue', false);
}

function submitManual() {
  if (manual.value.trim()) finish(manual.value.trim());
}

function stop() {
  controls?.stop();
  controls = null;
}

onBeforeUnmount(stop);
</script>

<style scoped>
.ls-scan {
  position: relative;
  aspect-ratio: 4 / 3;
  background: #000;
  border-radius: 4px;
  overflow: hidden;
}
.ls-scan__video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.ls-scan__tip {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 8px;
  text-align: center;
  color: #fff;
  font-size: 13px;
  text-shadow: 0 1px 2px #000;
}
.ls-scan .el-alert {
  position: absolute;
  inset: auto 8px 8px;
  width: auto;
}
.ls-scan__manual {
  margin-top: 12px;
}
</style>
