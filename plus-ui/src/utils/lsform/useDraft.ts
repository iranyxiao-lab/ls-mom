import { onBeforeUnmount, ref, watch } from 'vue';
import { contentHash, hasContent, newer, type DraftRecord } from './draftModel';
import { localDrafts } from './draftStore';

/** 服务端草稿接口（ui_form_draft）。 */
export interface DraftApi {
  draftGet: (key: string) => Promise<{ content: Record<string, unknown>; hash: string; updatedAt: string } | null>;
  draftSave: (key: string, body: { content: Record<string, unknown>; hash: string }) => Promise<unknown>;
  draftDelete: (key: string) => Promise<unknown>;
}

export interface UseDraftOptions {
  key: () => string | undefined;
  user: () => string | number | undefined;
  model: () => Record<string, unknown>;
  /** 用户动过表单后才保存（只有预填值不算草稿） */
  dirty: () => boolean;
  api?: Partial<DraftApi>;
  delay?: number;
}

/**
 * 表单草稿自动保存：内容变化后防抖 3 秒写本机（IndexedDB）与服务端；内容摘要相同则不写。
 * 打开表单时取本机与服务端较新的一份，与当前内容不同就作为待恢复草稿（found）。服务端写失败（断网、会话过期）只留本机，下次变化时再试。
 */
export function useDraft(o: UseDraftOptions) {
  const found = ref<DraftRecord | null>(null);
  const savedAt = ref<number | null>(null);
  const delay = o.delay ?? 3000;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let lastHash = '';
  let serverHash = '';
  let loadSeq = 0;

  const localKey = () => {
    const k = o.key();
    const u = o.user();
    return k && u != null && u !== '' ? `${u}:${k}` : undefined;
  };

  async function load() {
    const seq = ++loadSeq;
    found.value = null;
    savedAt.value = null;
    lastHash = serverHash = '';
    const k = o.key();
    const lk = localKey();
    if (!k || !lk) return;
    const [local, server] = await Promise.all([
      localDrafts.get(lk).catch(() => null),
      o.api?.draftGet ? o.api.draftGet(k).catch(() => null) : Promise.resolve(null)
    ]);
    if (seq !== loadSeq) return;
    const fromServer: DraftRecord | null = server
      ? { content: server.content, hash: server.hash, savedAt: new Date(server.updatedAt).getTime() }
      : null;
    if (server) serverHash = server.hash;
    const best = newer(local, fromServer);
    if (best && hasContent(best.content) && best.hash !== contentHash(o.model())) found.value = best;
  }

  async function save() {
    timer = undefined;
    const k = o.key();
    const lk = localKey();
    if (!k || !lk || !o.dirty()) return;
    const content = JSON.parse(JSON.stringify(o.model())) as Record<string, unknown>;
    const hash = contentHash(content);
    if (hash === lastHash && hash === serverHash) return;
    const rec: DraftRecord = { content, hash, savedAt: Date.now() };
    if (hash !== lastHash) {
      await localDrafts.put(lk, rec).catch(() => undefined);
      lastHash = hash;
      savedAt.value = rec.savedAt;
    }
    if (o.api?.draftSave && hash !== serverHash) {
      try {
        await o.api.draftSave(k, { content, hash });
        serverHash = hash;
      } catch {
        /* 断网或会话过期：已存本机，下次变化或 flush 时再写服务端 */
      }
    }
  }

  function schedule() {
    if (!o.dirty()) return;
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => void save(), delay);
  }

  /** 立即保存（离开页面前） */
  async function flush() {
    if (timer) clearTimeout(timer);
    await save();
  }

  /** 提交成功或丢弃：删除本机与服务端草稿。 */
  async function clear() {
    if (timer) clearTimeout(timer);
    timer = undefined;
    found.value = null;
    savedAt.value = null;
    lastHash = serverHash = '';
    const k = o.key();
    const lk = localKey();
    if (lk) await localDrafts.remove(lk).catch(() => undefined);
    if (k && o.api?.draftDelete) await o.api.draftDelete(k).catch(() => undefined);
  }

  /** 取出待恢复的内容（调用方写回表单），并把它记为已保存。 */
  function take(): Record<string, unknown> | null {
    const d = found.value;
    found.value = null;
    if (!d) return null;
    lastHash = d.hash;
    savedAt.value = d.savedAt;
    return d.content;
  }

  watch(o.key, () => void load(), { immediate: true });
  watch(o.model, schedule, { deep: true });

  const onHide = () => {
    if (document.visibilityState === 'hidden') void flush();
  };
  document.addEventListener('visibilitychange', onHide);
  window.addEventListener('pagehide', onHide);
  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', onHide);
    window.removeEventListener('pagehide', onHide);
    if (timer) void flush();
  });

  return { found, savedAt, take, clear, flush, reload: load };
}
