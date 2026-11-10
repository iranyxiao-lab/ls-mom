/**
 * 匿名使用埋点：记录关键任务的开始、步骤、完成、放弃与错误次数和耗时，用于发现高成本流程。
 * 只上报任务名、步骤名（小写标识符）、事件类型和耗时；不带用户、不带任何表单值。
 * 管理员在「系统监控 → 体验分析」关闭后，前端不再上报，服务端也拒收。
 */
import { onBeforeUnmount, watch, type Ref } from 'vue';
import { getToken } from '@/utils/auth';

export type TrackKind = 'start' | 'step' | 'complete' | 'abandon' | 'error';
export interface TrackProps {
  task: string;
  step?: string;
  durationMs?: number;
}
interface TrackEvent {
  task: string;
  step?: string;
  kind: TrackKind;
  durationMs?: number;
}

/** 与服务端一致：名称只能是小写标识符，自由文本在格式上就进不来。 */
export const TRACK_NAME = /^[a-z][a-z0-9_.-]{0,63}$/;
const KINDS = new Set<TrackKind>(['start', 'step', 'complete', 'abandon', 'error']);
const FLUSH_MS = 5000;
const CONFIG_TTL_MS = 5 * 60_000;
const MAX_QUEUE = 200;

const queue: TrackEvent[] = [];
let timer: ReturnType<typeof setTimeout> | undefined;
let enabled: boolean | undefined;
let checkedAt = 0;

const base = () => import.meta.env.VITE_APP_BASE_API ?? '';
const headers = () => ({
  'Content-Type': 'application/json;charset=utf-8',
  Authorization: 'Bearer ' + getToken(),
  clientid: import.meta.env.VITE_APP_CLIENT_ID
});

async function isEnabled(): Promise<boolean> {
  if (enabled !== undefined && Date.now() - checkedAt < CONFIG_TTL_MS) return enabled;
  try {
    const r = await fetch(`${base()}/api/ui/usage/config`, { headers: headers() });
    const body = await r.json();
    enabled = body?.code === 200 && body.data?.enabled === true;
  } catch {
    enabled = false;
  }
  checkedAt = Date.now();
  return enabled;
}

const send = (batch: TrackEvent[], keepalive: boolean) =>
  fetch(`${base()}/api/ui/usage/events`, { method: 'POST', headers: headers(), body: JSON.stringify(batch), keepalive }).catch(() => undefined);

/** 立即发送队列。离开页面时（keepalive）必须在事件回调内同步发出请求，不能先等待其他异步操作。 */
export async function flushTrack(keepalive = false): Promise<void> {
  if (timer) clearTimeout(timer);
  timer = undefined;
  if (!queue.length || !getToken()) return;
  if (keepalive) {
    // 来不及查询开关：已知关闭则丢弃，未知就发送（关闭时服务端拒收）
    if (enabled === false) queue.length = 0;
    while (queue.length) void send(queue.splice(0, 50), true);
    return;
  }
  if (!(await isEnabled())) {
    queue.length = 0;
    return;
  }
  while (queue.length) await send(queue.splice(0, 50), false);
}

/** 记录一条事件。不合格的名称直接丢弃；只取白名单字段，调用方多传的内容不会被发送。 */
export function track(kind: TrackKind, props: TrackProps): void {
  if (!KINDS.has(kind) || !TRACK_NAME.test(props.task)) return;
  if (enabled === false && Date.now() - checkedAt < CONFIG_TTL_MS) return;
  const e: TrackEvent = { task: props.task, kind };
  if (props.step && TRACK_NAME.test(props.step)) e.step = props.step;
  if (typeof props.durationMs === 'number' && Number.isFinite(props.durationMs) && props.durationMs >= 0) e.durationMs = Math.round(props.durationMs);
  if (queue.length >= MAX_QUEUE) queue.shift();
  queue.push(e);
  listenUnload();
  if (enabled === undefined) void isEnabled();
  if (!timer) timer = setTimeout(() => void flushTrack(), FLUSH_MS);
}

let listening = false;
/** 页面隐藏或卸载时立即发送剩余事件，避免批量间隔内离开页面丢数据。 */
function listenUnload() {
  if (listening || typeof window === 'undefined') return;
  listening = true;
  const now = () => void flushTrack(true);
  window.addEventListener('pagehide', now);
  document.addEventListener('visibilitychange', () => document.visibilityState === 'hidden' && now());
}

/** 测试与切换账号用：清空队列和开关缓存。 */
export function resetTrack(): void {
  queue.length = 0;
  enabled = undefined;
  checkedAt = 0;
  if (timer) clearTimeout(timer);
  timer = undefined;
}

export interface TaskTracker {
  /** 进入某一步（同一步重复进入只记一次）。 */
  step(name: string): void;
  complete(): void;
  error(step?: string): void;
  /** 绑定到表单容器的 focusin / pointerdown：读取最近的 data-track-step 作为当前步骤。 */
  onFocusin(ev: Event): void;
}

/**
 * 关键任务埋点：visible 变为 true 时开始；未完成就关闭（或离开页面）记为在最后一步放弃。
 * 用法：const t = useTaskTracking('nc.register', newVisible)；表单项加 data-track-step="asset"，表单容器上 @focusin 与 @pointerdown 绑定 t.onFocusin。
 */
export function useTaskTracking(task: string, visible: Ref<boolean>): TaskTracker {
  let startedAt = 0;
  let lastStep: string | undefined;
  let active = false;

  const elapsed = () => Math.round(performance.now() - startedAt);
  const abandon = (keepalive: boolean) => {
    if (!active) return;
    active = false;
    track('abandon', { task, step: lastStep, durationMs: elapsed() });
    if (keepalive) void flushTrack(true);
  };
  const onPageHide = () => abandon(true);

  watch(
    visible,
    (v) => {
      if (v && !active) {
        active = true;
        startedAt = performance.now();
        lastStep = undefined;
        track('start', { task });
        window.addEventListener('pagehide', onPageHide);
      } else if (!v) {
        abandon(false);
        window.removeEventListener('pagehide', onPageHide);
      }
    },
    { immediate: true }
  );
  onBeforeUnmount(() => {
    abandon(false);
    window.removeEventListener('pagehide', onPageHide);
  });

  const tracker: TaskTracker = {
    step(name) {
      if (!active || name === lastStep) return;
      lastStep = name;
      track('step', { task, step: name, durationMs: elapsed() });
    },
    complete() {
      if (!active) return;
      active = false;
      track('complete', { task, durationMs: elapsed() });
    },
    error(step) {
      track('error', { task, step: step ?? lastStep });
    },
    onFocusin(ev) {
      const el = (ev.target as Element | null)?.closest?.('[data-track-step]');
      const name = el?.getAttribute('data-track-step');
      if (name) tracker.step(name);
    }
  };
  return tracker;
}
