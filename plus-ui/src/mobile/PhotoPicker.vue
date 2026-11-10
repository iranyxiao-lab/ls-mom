<template>
  <div class="m-photos" data-testid="m-photos">
    <div v-for="(p, i) in modelValue" :key="p.preview" class="m-photo" :data-testid="`m-photo-${i}`" :data-size="p.blob.size">
      <img :src="p.preview" :alt="`照片 ${i + 1}`" />
      <button
        type="button"
        class="m-photo__btn m-photo__edit"
        :aria-label="`标注照片 ${i + 1}`"
        :data-testid="`m-photo-mark-${i}`"
        @click="openMark(i)"
      >
        标注
      </button>
      <button type="button" class="m-photo__btn m-photo__del" :aria-label="`删除照片 ${i + 1}`" @click="remove(i)">×</button>
    </div>
    <label v-if="modelValue.length < max" class="m-photo m-photo--add" data-testid="m-photo-add">
      <input
        type="file"
        accept="image/*"
        capture="environment"
        :multiple="max - modelValue.length > 1"
        data-testid="m-photo-input"
        @change="onPick"
      />
      <span aria-hidden="true">＋</span>
      <span class="m-sub">{{ busy ? '处理中…' : '拍照' }}</span>
    </label>

    <!-- 标注：手指在照片上画圈或箭头 -->
    <div v-if="marking !== null" class="m-mark" role="dialog" aria-label="标注照片" data-testid="m-mark">
      <canvas
        ref="canvas"
        class="m-mark__canvas"
        data-testid="m-mark-canvas"
        @pointerdown="down"
        @pointermove="move"
        @pointerup="up"
        @pointercancel="up"
      ></canvas>
      <div class="m-mark__bar">
        <button type="button" class="m-btn" data-testid="m-mark-cancel" @click="marking = null">取消</button>
        <button type="button" class="m-btn" data-testid="m-mark-undo" :disabled="!strokes.length" @click="undo">撤销一笔</button>
        <button type="button" class="m-btn m-btn--primary" data-testid="m-mark-done" @click="saveMark">完成</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue';
import { compressImage, jpgName, loadImage } from './photo';

/** 现场照片：拍照后自动压缩，可在照片上标注；提交时作为证据附件关联到当前单据。 */
export interface PickedPhoto {
  name: string;
  blob: Blob;
  preview: string;
}
const props = withDefaults(defineProps<{ modelValue: PickedPhoto[]; max?: number }>(), { max: 6 });
const emit = defineEmits<{ (e: 'update:modelValue', v: PickedPhoto[]): void }>();
const busy = ref(false);

async function onPick(e: Event) {
  const input = e.target as HTMLInputElement;
  const files = Array.from(input.files ?? []).slice(0, props.max - props.modelValue.length);
  input.value = '';
  busy.value = true;
  try {
    const out = [...props.modelValue];
    for (const f of files) {
      const blob = await compressImage(f).catch(() => f);
      out.push({ name: jpgName(f.name || 'photo.jpg'), blob, preview: URL.createObjectURL(blob) });
    }
    emit('update:modelValue', out);
  } finally {
    busy.value = false;
  }
}

function remove(i: number) {
  const out = [...props.modelValue];
  URL.revokeObjectURL(out[i].preview);
  out.splice(i, 1);
  emit('update:modelValue', out);
}

const marking = ref<number | null>(null);
const canvas = ref<HTMLCanvasElement>();
let base: HTMLImageElement | null = null;
const strokes = ref<{ x: number; y: number }[][]>([]);
let drawing: { x: number; y: number }[] | null = null;

async function openMark(i: number) {
  marking.value = i;
  strokes.value = [];
  base = await loadImage(props.modelValue[i].blob);
  await nextTick();
  const c = canvas.value!;
  c.width = base.width;
  c.height = base.height;
  redraw();
}

function redraw() {
  const c = canvas.value;
  if (!c || !base) return;
  const g = c.getContext('2d')!;
  g.drawImage(base, 0, 0);
  g.strokeStyle = '#f53f3f';
  g.lineWidth = Math.max(4, Math.round(c.width / 150));
  g.lineCap = 'round';
  g.lineJoin = 'round';
  for (const s of strokes.value) {
    g.beginPath();
    s.forEach((p, k) => (k ? g.lineTo(p.x, p.y) : g.moveTo(p.x, p.y)));
    g.stroke();
  }
}

function point(e: PointerEvent) {
  const c = canvas.value!;
  const r = c.getBoundingClientRect();
  return { x: ((e.clientX - r.left) / r.width) * c.width, y: ((e.clientY - r.top) / r.height) * c.height };
}
function down(e: PointerEvent) {
  (e.target as Element).setPointerCapture?.(e.pointerId);
  drawing = [point(e)];
  strokes.value.push(drawing);
}
function move(e: PointerEvent) {
  if (!drawing) return;
  drawing.push(point(e));
  redraw();
}
function up() {
  drawing = null;
}
function undo() {
  strokes.value.pop();
  redraw();
}

async function saveMark() {
  const i = marking.value!;
  const c = canvas.value!;
  const blob = await new Promise<Blob | null>((r) => c.toBlob(r, 'image/jpeg', 0.85));
  marking.value = null;
  if (!blob) return;
  const out = [...props.modelValue];
  URL.revokeObjectURL(out[i].preview);
  out[i] = { name: out[i].name, blob, preview: URL.createObjectURL(blob) };
  emit('update:modelValue', out);
}
</script>

<style scoped>
.m-photos {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.m-photo {
  position: relative;
  aspect-ratio: 1;
  border-radius: 8px;
  overflow: hidden;
  background: var(--ls-color-fill, #f2f3f5);
}
.m-photo img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.m-photo--add {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 1px dashed var(--ls-color-border, #c9cdd4);
  font-size: 28px;
  color: var(--ls-color-primary-text);
}
.m-photo--add input {
  position: absolute;
  inset: 0;
  opacity: 0;
}
.m-photo__btn {
  position: absolute;
  min-width: 44px;
  border: 0;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 13px;
}
.m-photo__edit {
  left: 0;
  bottom: 0;
}
.m-photo__del {
  right: 0;
  top: 0;
  font-size: 20px;
}
.m-mark {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: #000;
  display: flex;
  flex-direction: column;
}
.m-mark__canvas {
  display: block;
  max-width: 100%;
  max-height: calc(100dvh - 80px);
  margin: auto;
  touch-action: none;
}
.m-mark__bar {
  display: flex;
  gap: 8px;
  padding: 10px 12px calc(10px + env(safe-area-inset-bottom));
  background: #111;
}
.m-mark__bar .m-btn {
  flex: 1;
}
</style>
