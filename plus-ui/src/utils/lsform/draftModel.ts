/** 表单草稿的纯函数：稳定序列化、内容摘要、选取较新的草稿。 */

export interface DraftRecord {
  content: Record<string, unknown>;
  hash: string;
  /** 毫秒时间戳 */
  savedAt: number;
}

/** 键排序后的 JSON（相同内容得到相同字符串）；去掉以 _ 开头的内部键。 */
export function stableJson(v: unknown): string {
  if (Array.isArray(v)) return '[' + v.map(stableJson).join(',') + ']';
  if (v && typeof v === 'object') {
    const keys = Object.keys(v as object)
      .filter((k) => !k.startsWith('_') && (v as any)[k] !== undefined)
      .sort();
    return '{' + keys.map((k) => JSON.stringify(k) + ':' + stableJson((v as any)[k])).join(',') + '}';
  }
  return JSON.stringify(v ?? null);
}

/** FNV-1a 64 位（两段 32 位）摘要，十六进制。只用于判断内容是否变化。 */
export function contentHash(v: unknown): string {
  const s = stableJson(v);
  let h1 = 0x811c9dc5;
  let h2 = 0x01000193;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    h1 = Math.imul(h1 ^ c, 0x01000193) >>> 0;
    h2 = Math.imul(h2 ^ c, 0x811c9dc5) >>> 0;
  }
  return h1.toString(16).padStart(8, '0') + h2.toString(16).padStart(8, '0');
}

/** 内容里是否有用户填写的值（全空的草稿不提示恢复）。 */
export function hasContent(v: Record<string, unknown> | null | undefined): boolean {
  if (!v) return false;
  return Object.entries(v).some(([k, x]) => !k.startsWith('_') && x !== undefined && x !== null && x !== '' && !(Array.isArray(x) && !x.length));
}

/** 本地与服务端草稿取较新的一份。 */
export function newer(a?: DraftRecord | null, b?: DraftRecord | null): DraftRecord | null {
  if (!a) return b ?? null;
  if (!b) return a;
  return b.savedAt > a.savedAt ? b : a;
}

/** 草稿保存时间的显示文字：今天显示时分，其他日期显示月日时分。 */
export function draftTime(ms: number, now = Date.now()): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  const hm = `${p(d.getHours())}:${p(d.getMinutes())}`;
  return new Date(now).toDateString() === d.toDateString() ? `今天 ${hm}` : `${d.getMonth() + 1}月${d.getDate()}日 ${hm}`;
}
