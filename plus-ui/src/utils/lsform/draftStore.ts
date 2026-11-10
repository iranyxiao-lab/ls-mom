import type { DraftRecord } from './draftModel';

/**
 * 本机草稿（IndexedDB）。键为 用户:草稿键，不同用户互不可见；IndexedDB 不可用（隐私模式等）时退回内存，不影响录入。
 */
const DB = 'ls-form-drafts';
const STORE = 'drafts';
const memory = new Map<string, DraftRecord>();
let dbPromise: Promise<IDBDatabase | null> | null = null;

function open(): Promise<IDBDatabase | null> {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve) => {
    try {
      const req = indexedDB.open(DB, 1);
      req.onupgradeneeded = () => req.result.createObjectStore(STORE);
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => resolve(null);
      req.onblocked = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
  return dbPromise;
}

async function tx<T>(mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T | undefined> {
  const db = await open();
  if (!db) return undefined;
  return new Promise((resolve) => {
    try {
      const r = fn(db.transaction(STORE, mode).objectStore(STORE));
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => resolve(undefined);
    } catch {
      resolve(undefined);
    }
  });
}

export const localDrafts = {
  async get(key: string): Promise<DraftRecord | null> {
    const db = await open();
    if (!db) return memory.get(key) ?? null;
    return ((await tx('readonly', (s) => s.get(key))) as DraftRecord | undefined) ?? null;
  },
  async put(key: string, rec: DraftRecord): Promise<void> {
    const db = await open();
    if (!db) {
      memory.set(key, rec);
      return;
    }
    // 存纯数据（去掉响应式代理）
    await tx('readwrite', (s) => s.put(JSON.parse(JSON.stringify(rec)), key));
  },
  async remove(key: string): Promise<void> {
    memory.delete(key);
    await tx('readwrite', (s) => s.delete(key));
  },
  /** 退出登录时清掉该用户在本机的草稿（服务端仍保留，重新登录后可恢复）。 */
  async clearUser(userPrefix: string): Promise<void> {
    for (const k of [...memory.keys()]) if (k.startsWith(userPrefix + ':')) memory.delete(k);
    const keys = ((await tx('readonly', (s) => s.getAllKeys())) as IDBValidKey[] | undefined) ?? [];
    for (const k of keys) if (String(k).startsWith(userPrefix + ':')) await tx('readwrite', (s) => s.delete(k));
  }
};
