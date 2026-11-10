import type { FormField, Option } from '@/types/lsView';

/**
 * 从 Excel / WPS 粘贴到子表：解析制表符分隔的文本（含引号包裹的换行），按表头或列顺序对应到列，逐格转换取值并给出问题与候选值。
 */

/** 解析剪贴板文本：行以换行分隔、列以制表符分隔；"..." 包裹的单元格可含换行与制表符，"" 表示一个引号。 */
export function parseTsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  let i = 0;
  const s = text.replace(/\r\n?/g, '\n');
  while (i < s.length) {
    const c = s[i];
    if (quoted) {
      if (c === '"' && s[i + 1] === '"') {
        cell += '"';
        i += 2;
        continue;
      }
      if (c === '"') {
        quoted = false;
        i++;
        continue;
      }
      cell += c;
      i++;
      continue;
    }
    if (c === '"' && cell === '') {
      quoted = true;
    } else if (c === '\t') {
      row.push(cell);
      cell = '';
    } else if (c === '\n') {
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
    } else {
      cell += c;
    }
    i++;
  }
  if (cell !== '' || row.length) {
    row.push(cell);
    rows.push(row);
  }
  // 去掉末尾的空行
  while (rows.length && rows[rows.length - 1].every((x) => x.trim() === '')) rows.pop();
  return rows.map((r) => r.map((x) => x.trim()));
}

const norm = (s: string) => s.replace(/[\s*：:（）()]/g, '').toLowerCase();

/** 第一行是表头时返回 列序号 → 字段键（至少一半的列能按名称或键对上），否则返回 null。 */
export function headerMapping(first: string[], fields: FormField[]): (string | null)[] | null {
  const byName = new Map<string, string>();
  for (const f of fields) {
    byName.set(norm(f.label ?? f.key), f.key);
    byName.set(norm(f.key), f.key);
  }
  const mapped = first.map((h) => byName.get(norm(h)) ?? null);
  const hits = mapped.filter(Boolean).length;
  return hits > 0 && hits >= Math.ceil(first.filter((h) => h !== '').length / 2) ? mapped : null;
}

function distance(a: string, b: string): number {
  const m = a.length;
  const n = b.length;
  const d = Array.from({ length: m + 1 }, (_, i) => [i, ...Array(n).fill(0)]);
  for (let j = 1; j <= n; j++) d[0][j] = j;
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
  }
  return d[m][n];
}

/** 与输入最接近的选项（最多 3 个）：包含关系优先，其次编辑距离。 */
export function candidates(raw: string, options: Option[], max = 3): Option[] {
  const r = norm(raw);
  return options
    .map((o) => {
      const l = norm(o.label);
      const v = norm(String(o.value));
      const contains = l.includes(r) || r.includes(l) || v.includes(r) || r.includes(v);
      return { o, score: (contains ? 0 : 100) + Math.min(distance(r, l), distance(r, v)) };
    })
    .sort((a, b) => a.score - b.score)
    .slice(0, max)
    .map((x) => x.o);
}

function flat(options: Option[] = [], out: Option[] = []): Option[] {
  for (const o of options) {
    out.push(o);
    if (o.children) flat(o.children, out);
  }
  return out;
}

const TRUE = new Set(['是', 'true', '1', 'y', 'yes', '√', '已']);
const FALSE = new Set(['否', 'false', '0', 'n', 'no', '×', '未', '']);

/** Excel 日期序号（1900 日期系统）转 YYYY-MM-DD。 */
function excelSerial(n: number): string {
  const d = new Date(Date.UTC(1899, 11, 30) + n * 86400000);
  return d.toISOString().slice(0, 10);
}

function normalizeDate(raw: string, withTime: boolean): string | null {
  const s = raw.trim();
  if (/^\d{5}(\.\d+)?$/.test(s)) {
    const n = Number(s);
    const day = excelSerial(Math.floor(n));
    if (!withTime) return day;
    const secs = Math.round((n % 1) * 86400);
    const p = (x: number) => String(x).padStart(2, '0');
    return `${day}T${p(Math.floor(secs / 3600))}:${p(Math.floor((secs % 3600) / 60))}:${p(secs % 60)}`;
  }
  const m = /^(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?(?:[ T]+(\d{1,2}):(\d{2})(?::(\d{2}))?)?$/.exec(s);
  if (!m) return null;
  const p = (x: string | undefined) => String(x ?? '0').padStart(2, '0');
  const day = `${m[1]}-${p(m[2])}-${p(m[3])}`;
  return withTime ? `${day}T${p(m[4])}:${p(m[5])}:${p(m[6])}` : day;
}

export interface CellResult {
  value: unknown;
  error?: string;
  candidates?: Option[];
}

/** 把粘贴的文字转换成该列的取值；对不上时保留原文并给出问题（选择类附候选值）。引用列由调用方按名称查找。 */
export function convertCell(f: FormField, raw: string, options: Option[] = []): CellResult {
  const label = f.label ?? f.key;
  if (raw === '') return { value: undefined };
  switch (f.widget) {
    case 'number':
    case 'money':
    case 'rate': {
      const n = Number(raw.replace(/[,，\s]/g, ''));
      return Number.isFinite(n) ? { value: n } : { value: raw, error: `${label}「${raw}」不是数字` };
    }
    case 'switch': {
      const k = raw.toLowerCase();
      if (TRUE.has(k)) return { value: true };
      if (FALSE.has(k)) return { value: false };
      return { value: raw, error: `${label}只能填 是 / 否` };
    }
    case 'date':
    case 'datetime': {
      const d = normalizeDate(raw, f.widget === 'datetime');
      return d ? { value: d } : { value: raw, error: `${label}「${raw}」不是有效日期` };
    }
    case 'select':
    case 'radio':
    case 'dict':
    case 'checkbox':
    case 'cascader': {
      const all = flat(options);
      const pick = (x: string) => all.find((o) => norm(o.label) === norm(x) || norm(String(o.value)) === norm(x));
      if (f.multiple || f.widget === 'checkbox') {
        const parts = raw
          .split(/[,，、;；]/)
          .map((x) => x.trim())
          .filter(Boolean);
        const hits = parts.map(pick);
        const bad = parts.filter((_, i) => !hits[i]);
        if (!bad.length) return { value: hits.map((o) => o!.value) };
        return { value: raw, error: `${label}「${bad.join('、')}」不在可选范围内`, candidates: candidates(bad[0], all) };
      }
      const hit = pick(raw);
      if (hit) return { value: hit.value };
      return { value: raw, error: `${label}「${raw}」不在可选范围内`, candidates: candidates(raw, all) };
    }
    default:
      return { value: raw };
  }
}
