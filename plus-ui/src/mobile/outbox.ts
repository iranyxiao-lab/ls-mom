import { reactive, toRaw } from 'vue';
import { createStore, del, entries, set } from 'idb-keyval';

/**
 * 离线提交队列 outbox-ui（IndexedDB）：断网时报工等提交排队，显示"待同步"；恢复网络后按顺序重放。
 * 每条带客户端生成的幂等键与对象版本，服务端幂等，重放不会重复生成记录；冲突或校验失败的条目标为"同步失败"，由用户查看并丢弃。
 */
export interface OutboxPhoto {
  name: string;
  blob: Blob;
}
export interface OutboxItem {
  id: string;
  /** 排队顺序 */
  seq: number;
  title: string;
  operation: string;
  targetId?: string;
  version?: number;
  input: Record<string, unknown>;
  idempotencyKey: string;
  /** 提交成功后把照片作为证据附件关联到结果对象（取 data[objectIdFrom]） */
  photos?: OutboxPhoto[];
  attachTo?: { objectType: string; objectIdFrom: string; fallbackId?: string };
  status: 'PENDING' | 'SYNCING' | 'FAILED';
  error?: string;
  createdAt: string;
}

export interface OutboxSender {
  execute: (item: OutboxItem) => Promise<{ data?: Record<string, any> }>;
  upload: (file: File) => Promise<{ ossId: string | number }>;
  attach: (ossId: string | number, objectType: string, objectId: string | number) => Promise<unknown>;
  /** 把失败原因转成用户能懂的话（如"任务已取消"） */
  explain?: (item: OutboxItem, err: any) => Promise<string> | string;
}

const store = () => createStore('outbox-ui', 'items');
export const outbox = reactive({ items: [] as OutboxItem[], online: typeof navigator === 'undefined' ? true : navigator.onLine, syncing: false });

let seq = Date.now();
const uuid = () => (globalThis.crypto?.randomUUID ? globalThis.crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`);

export async function loadOutbox() {
  try {
    const all = (await entries<string, OutboxItem>(store())).map(([, v]) => v);
    outbox.items = all.sort((a, b) => a.seq - b.seq);
  } catch {
    outbox.items = [];
  }
  return outbox.items;
}

async function save(item: OutboxItem) {
  // 存纯对象（响应式代理不能结构化克隆）；照片 Blob 由 IndexedDB 原样保存
  const raw = toRaw(item);
  const plain: OutboxItem = {
    ...(JSON.parse(JSON.stringify({ ...raw, photos: undefined })) as OutboxItem),
    photos: raw.photos?.map((p) => ({ name: p.name, blob: toRaw(p).blob }))
  };
  await set(item.id, plain, store()).catch(() => undefined);
}

/** 排队一条提交（断网或网络错误时调用） */
export async function enqueue(p: Omit<OutboxItem, 'id' | 'seq' | 'status' | 'createdAt' | 'idempotencyKey'> & { idempotencyKey?: string }) {
  const item: OutboxItem = {
    ...p,
    id: uuid(),
    seq: seq++,
    idempotencyKey: p.idempotencyKey ?? uuid(),
    status: 'PENDING',
    createdAt: new Date().toISOString()
  };
  await save(item);
  outbox.items.push(item);
  return item;
}

export async function discard(id: string) {
  await del(id, store()).catch(() => undefined);
  outbox.items = outbox.items.filter((i) => i.id !== id);
}

/** 是否网络问题（排队重试），而不是业务拒绝（标失败） */
export function isNetworkError(e: any) {
  if (typeof navigator !== 'undefined' && !navigator.onLine) return true;
  const m = String(e?.message ?? e ?? '');
  return !e?.code && /Network Error|timeout|Failed to fetch|ERR_INTERNET|ERR_NETWORK|断网|网络/i.test(m);
}

/** 按顺序重放待同步条目；遇到网络问题停下，等下次联网 */
export async function flush(sender: OutboxSender) {
  if (outbox.syncing) return;
  outbox.syncing = true;
  try {
    for (const item of [...outbox.items].sort((a, b) => a.seq - b.seq)) {
      if (item.status === 'FAILED') continue;
      item.status = 'SYNCING';
      try {
        const r = await sender.execute(item);
        if (item.photos?.length && item.attachTo) {
          const objectId = r?.data?.[item.attachTo.objectIdFrom] ?? item.attachTo.fallbackId;
          for (const ph of item.photos) {
            const up = await sender.upload(new File([ph.blob], ph.name, { type: ph.blob.type || 'image/jpeg' }));
            if (objectId != null) await sender.attach(up.ossId, item.attachTo.objectType, objectId);
          }
        }
        await discard(item.id);
      } catch (e: any) {
        if (isNetworkError(e)) {
          item.status = 'PENDING';
          break;
        }
        item.status = 'FAILED';
        item.error = sender.explain ? await sender.explain(item, e) : (e?.message ?? String(e));
        await save(item);
      }
    }
  } finally {
    outbox.syncing = false;
  }
}

/** 监听联网：恢复网络后自动同步 */
export function watchOnline(sender: OutboxSender) {
  const on = () => {
    outbox.online = true;
    void flush(sender);
  };
  const off = () => (outbox.online = false);
  window.addEventListener('online', on);
  window.addEventListener('offline', off);
  return () => {
    window.removeEventListener('online', on);
    window.removeEventListener('offline', off);
  };
}
