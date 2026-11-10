/**
 * 中文文本的模糊匹配：原文包含 > 拼音连续命中（全拼、首字母、混合） > 拼音不连续命中。
 * 返回分值（越小越靠前），不匹配返回 null。命令面板的菜单与动作在本地用它过滤。
 * 拼音字典较大，首次打开命令面板时才加载（loadPinyin）；加载完成前只按原文匹配。
 */
type MatchFn = (text: string, pinyin: string, options?: { continuous?: boolean }) => number[] | null;
let matchFn: MatchFn | null = null;
let loading: Promise<void> | null = null;

export function loadPinyin(): Promise<void> {
  loading ??= import('pinyin-pro').then((m) => {
    matchFn = m.match as MatchFn;
  });
  return loading;
}
export const pinyinReady = () => matchFn !== null;

export function pinyinScore(text: string, query: string): number | null {
  const q = query.trim().toLowerCase().replace(/\s+/g, '');
  if (!q) return 0;
  const t = text.toLowerCase();
  const at = t.replace(/\s+/g, '').indexOf(q);
  if (at >= 0) return at === 0 ? 0 : 1 + at / 100;
  // 只含 ASCII 的输入才按拼音匹配（中文输入已在上面按原文匹配）
  if (!matchFn || !/^[a-z0-9]+$/.test(q)) return null;
  const hit = matchFn(text, q, { continuous: true }) ?? null;
  if (hit) return 2 + hit[0] / 100;
  // 不连续命中（跳字）只对较长的输入放开，短输入跳字匹配噪声太大
  if (q.length < 4) return null;
  const loose = matchFn(text, q) ?? null;
  return loose ? 3 + loose[0] / 100 : null;
}

/** 按分值排序并过滤（同分保持原顺序）。 */
export function rankBy<T>(items: T[], text: (x: T) => string, query: string): T[] {
  return items
    .map((x, i) => ({ x, i, s: pinyinScore(text(x), query) }))
    .filter((r) => r.s !== null)
    .sort((a, b) => (a.s as number) - (b.s as number) || a.i - b.i)
    .map((r) => r.x);
}
