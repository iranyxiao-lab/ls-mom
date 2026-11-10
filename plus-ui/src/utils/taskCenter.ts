import { reactive } from 'vue';
import { ElNotification } from 'element-plus';
import type { BgTask } from '@/api/lsmom/tasks';

/** 按需加载接口：表格等组件引用任务中心时不连带加载请求与路由模块 */
const api = () => import('@/api/lsmom/tasks').then((m) => m.taskApi);

/**
 * 任务中心：导入、导出、批量操作、Pack 安装等耗时任务的进度与完成通知。
 * 服务端任务有进行中的就轮询；任务完成时，若发起页面还在跟进（followTask）就由页面自己展示结果，
 * 否则（已离开页面或关了对话框）弹出完成通知，点通知打开任务中心。
 */
export const taskCenter = reactive({
  tasks: [] as BgTask[],
  open: false,
  /** 打开任务中心时高亮的任务 */
  focus: null as number | null
});

const POLL_MS = 1500;
let timer: ReturnType<typeof setTimeout> | null = null;
let loading: Promise<void> | null = null;
let localSeq = -1;
const local = new Map<number, BgTask>();
const followers = new Map<number, (t: BgTask) => void>();
const announced = new Set<number>();
/** 只通知最近一天内完成的任务，避免首次登录时把历史任务全弹出来 */
const RECENT_MS = 24 * 3600 * 1000;

export const running = (t: BgTask) => t.status === 'RUNNING';

/** 结果摘要（通知与任务中心共用）。 */
export function taskSummary(t: BgTask): string {
  if (t.status === 'FAILED') return t.error || '任务失败';
  if (t.status === 'RUNNING') return t.total ? `${t.done} / ${t.total}` : '处理中…';
  const r = t.result ?? {};
  switch (t.kind) {
    case 'IMPORT_CHECK': {
      const rep = r.report ?? {};
      return `共 ${rep.total ?? 0} 行：可导入 ${rep.valid ?? 0} 行，错误 ${rep.invalid ?? 0} 行`;
    }
    case 'IMPORT':
      return `已导入 ${r.imported ?? 0} 条`;
    case 'EXPORT':
      return `共 ${r.rowCount ?? 0} 行，可下载`;
    case 'PACK_INSTALL':
      return `已发布 ${r.code ?? ''} ${r.version ?? ''}`.trim();
    case 'BATCH':
      return `成功 ${r.ok ?? 0} 条，失败 ${r.failed ?? 0} 条`;
    default:
      return '已完成';
  }
}

function merged(server: BgTask[]) {
  const all = [...local.values(), ...server];
  return all.sort((a, b) => Number(running(b)) - Number(running(a)) || (b.createdAt > a.createdAt ? 1 : b.createdAt < a.createdAt ? -1 : 0));
}

function announce(t: BgTask) {
  if (announced.has(t.id)) return;
  announced.add(t.id);
  if (!t.local)
    void api()
      .then((a) => a.notified(t.id))
      .catch(() => undefined);
  const ok = t.status === 'DONE' && !(t.kind === 'BATCH' && (t.result?.failed ?? 0) > 0);
  const n = ElNotification({
    type: t.status === 'FAILED' ? 'error' : ok ? 'success' : 'warning',
    title: `${t.title}${t.status === 'FAILED' ? ' 失败' : ' 已完成'}`,
    message: `${taskSummary(t)}（点此查看）`,
    duration: 8000,
    customClass: 'ls-task-notice',
    onClick: () => {
      n.close();
      openTaskCenter(t.id);
    }
  });
}

/** 发起页面已经展示了结果：标记已通知，不再弹出。 */
function consume(t: BgTask) {
  announced.add(t.id);
  if (!t.local)
    void api()
      .then((a) => a.notified(t.id))
      .catch(() => undefined);
}

function settle(t: BgTask) {
  if (running(t)) return;
  const f = followers.get(t.id);
  if (f) {
    followers.delete(t.id);
    consume(t);
    f(t);
    return;
  }
  if (t.notified || announced.has(t.id)) return;
  const finished = t.finishedAt ? new Date(t.finishedAt).getTime() : Date.now();
  if (Date.now() - finished > RECENT_MS) return;
  announce(t);
}

function schedule() {
  if (timer) clearTimeout(timer);
  timer = null;
  const serverRunning = taskCenter.tasks.some((t) => !t.local && running(t)) || [...followers.keys()].some((id) => id > 0);
  if (serverRunning) timer = setTimeout(() => void refreshTasks(), POLL_MS);
}

/** 拉取本人最近的任务；有进行中的任务时继续轮询。 */
let again = false;
export function refreshTasks(): Promise<void> {
  // 进行中的请求可能早于刚开始的任务：结束后再拉一次
  if (loading) {
    again = true;
    return loading;
  }
  loading = (async () => {
    try {
      const server = await (await api()).mine(20);
      taskCenter.tasks = merged(server ?? []);
      for (const t of taskCenter.tasks) {
        const f = followers.get(t.id);
        if (f && running(t)) f(t);
        settle(t);
      }
    } catch {
      /* 任务中心不可用时不影响页面 */
    } finally {
      loading = null;
      if (again) {
        again = false;
        void refreshTasks();
      } else schedule();
    }
  })();
  return loading;
}

/** 新任务已在服务端开始：立即刷新并开始轮询。 */
export function trackTask(_id: number) {
  return refreshTasks();
}

/**
 * 页面跟进某个任务：每次进度变化回调；完成时回调并由页面展示结果（不再弹通知）。
 * 页面离开或对话框关闭时调用 stop，之后完成会弹通知。
 */
export function followTask(id: number, onUpdate: (t: BgTask) => void): { stop: () => void } {
  followers.set(id, onUpdate);
  const t = taskCenter.tasks.find((x) => x.id === id);
  if (t) {
    onUpdate(t);
    settle(t);
  }
  if (id > 0) void refreshTasks();
  return {
    stop: () => {
      if (followers.get(id) === onUpdate) followers.delete(id);
      const cur = taskCenter.tasks.find((x) => x.id === id);
      if (cur) settle(cur);
    }
  };
}

/** 本页执行的任务（如批量操作）：登记到任务中心，离开页面后完成也会通知。 */
export function startLocalTask(kind: string, title: string, total: number) {
  const t = reactive<BgTask>({
    id: localSeq--,
    kind,
    title,
    status: 'RUNNING',
    total,
    done: 0,
    failed: 0,
    createdAt: new Date().toISOString(),
    notified: false,
    local: true
  }) as BgTask;
  local.set(t.id, t);
  taskCenter.tasks = merged(taskCenter.tasks.filter((x) => !x.local));
  return {
    id: t.id,
    progress(done: number, failed = 0) {
      t.done = done;
      t.failed = failed;
    },
    /** 完成：发起页面已离开时弹通知 */
    finish(result: Record<string, any>, error?: string) {
      t.status = error ? 'FAILED' : 'DONE';
      t.result = result;
      t.error = error ?? null;
      t.finishedAt = new Date().toISOString();
      settle(t);
    },
    /** 完成且发起页面已展示结果：不弹通知 */
    consume(result: Record<string, any>) {
      announced.add(t.id);
      this.finish(result);
    }
  };
}

export function openTaskCenter(focus?: number) {
  taskCenter.focus = focus ?? null;
  taskCenter.open = true;
  void refreshTasks();
}

/** 测试用：清空状态 */
export function resetTaskCenter() {
  if (timer) clearTimeout(timer);
  timer = null;
  loading = null;
  again = false;
  local.clear();
  followers.clear();
  announced.clear();
  taskCenter.tasks = [];
  taskCenter.open = false;
  taskCenter.focus = null;
}
