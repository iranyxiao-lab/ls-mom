/**
 * 列设置（顺序、显隐、宽度、固定）：纯函数，供 SchemaTable 与保存视图共用。
 * 状态只记字段编码；字段被删除或不再开放时自动忽略，新字段按默认顺序追加在末尾。
 */
import type { SchemaField } from '@/api/lsmom/types';

export interface ColumnPref {
  code: string;
  visible: boolean;
  width?: number;
  fixed?: 'left' | 'right';
}

export function defaultColumns(fields: SchemaField[]): ColumnPref[] {
  return fields.map((f) => ({ code: f.code, visible: true }));
}

/** 把保存的列设置套到当前可用字段上：返回结果与被忽略（字段已弃用）的编码。 */
export function mergeColumns(fields: SchemaField[], saved: ColumnPref[] | undefined | null): { columns: ColumnPref[]; dropped: string[] } {
  if (!saved?.length) return { columns: defaultColumns(fields), dropped: [] };
  const known = new Set(fields.map((f) => f.code));
  const dropped = saved.filter((c) => !known.has(c.code)).map((c) => c.code);
  const kept = saved.filter((c) => known.has(c.code)).map((c) => ({ ...c }));
  const seen = new Set(kept.map((c) => c.code));
  for (const f of fields) if (!seen.has(f.code)) kept.push({ code: f.code, visible: true });
  return { columns: kept, dropped };
}

export function moveColumn(cols: ColumnPref[], code: string, delta: -1 | 1): ColumnPref[] {
  const i = cols.findIndex((c) => c.code === code);
  const j = i + delta;
  if (i < 0 || j < 0 || j >= cols.length) return cols;
  const next = [...cols];
  [next[i], next[j]] = [next[j], next[i]];
  return next;
}

/** 按拖拽后的可见列顺序重排，隐藏列保持在原来的相对位置。 */
export function reorderVisible(cols: ColumnPref[], visibleOrder: string[]): ColumnPref[] {
  const queue = visibleOrder.filter((c) => cols.some((x) => x.code === c && x.visible));
  let k = 0;
  return cols.map((c) => (c.visible && k < queue.length ? { ...cols.find((x) => x.code === queue[k++])! } : c));
}

export function patchColumn(cols: ColumnPref[], code: string, patch: Partial<ColumnPref>): ColumnPref[] {
  return cols.map((c) => {
    if (c.code !== code) return c;
    const next: ColumnPref = { ...c, ...patch };
    if (next.fixed === undefined) delete next.fixed;
    if (next.width === undefined) delete next.width;
    return next;
  });
}

export function sameColumns(a: ColumnPref[], b: ColumnPref[]) {
  return JSON.stringify(a) === JSON.stringify(b);
}
