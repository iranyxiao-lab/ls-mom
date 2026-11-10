import type { FormSchema } from '@/types/lsView';

/** 服务端统一字段错误（VALIDATION_FAILED 的 details.errors）。 */
export interface ServerFieldError {
  /** 原始键：字段键或 子表[行].列 */
  key?: string;
  /** 字段键或 子表.列 */
  field: string;
  table?: string;
  rowIndex?: number;
  code?: string;
  message: string;
  fix?: string;
}

export interface MappedErrors {
  /** 键（与前端校验相同：字段键或 子表[行].列）→ 文字 */
  errors: Record<string, string>;
  /** 键 → 修复提示 */
  fixes: Record<string, string>;
}

const keyOf = (e: ServerFieldError) =>
  e.key ?? (e.table != null && e.rowIndex != null ? `${e.table}[${e.rowIndex}].${e.field.slice(e.table.length + 1)}` : e.field);

/**
 * 从请求错误（或 details、键值对）中取出字段错误；不是字段校验错误时返回 null。
 * 子表单元格的文字统一带"第 n 行："前缀，便于在单元格和汇总里直接显示。
 */
export function mapServerErrors(input: unknown): MappedErrors | null {
  const any = input as any;
  const details = any?.details ?? (any?.errors || any?.fields ? any : null);
  const list: ServerFieldError[] | undefined = Array.isArray(details?.errors) ? details.errors : undefined;
  const out: MappedErrors = { errors: {}, fixes: {} };
  if (list?.length) {
    for (const e of list) {
      const k = keyOf(e);
      out.errors[k] = e.rowIndex != null && !/^第 \d+ 行：/.test(e.message) ? `第 ${e.rowIndex + 1} 行：${e.message}` : e.message;
      if (e.fix) out.fixes[k] = e.fix;
    }
    return out;
  }
  const fields = details?.fields ?? (input && typeof input === 'object' && !any.details ? input : null);
  if (fields && typeof fields === 'object' && !Array.isArray(fields) && Object.keys(fields).length) {
    for (const [k, v] of Object.entries(fields)) if (typeof v === 'string') out.errors[k] = v;
    return Object.keys(out.errors).length ? out : null;
  }
  return null;
}

export interface SummaryItem {
  key: string;
  /** 字段名（子表为 子表名 · 列名） */
  label: string;
  message: string;
  fix?: string;
}

/** 顶部错误汇总：按表单布局里字段出现的顺序排列（先主表字段，再子表），每项可点击定位。 */
export function summaryItems(schema: FormSchema, errors: Record<string, string>, fixes: Record<string, string> = {}): SummaryItem[] {
  const fieldLabel = new Map(schema.fields.map((f) => [f.key, f.label ?? f.key]));
  const order = new Map<string, number>();
  const walk = (nodes: FormSchema['layout']) => {
    for (const n of nodes) {
      if ((n.type === 'field' || n.type === 'subTable') && n.key && !order.has(n.key)) order.set(n.key, order.size);
      if (n.children) walk(n.children);
    }
  };
  walk(schema.layout);
  const items: (SummaryItem & { rank: number })[] = [];
  for (const [key, message] of Object.entries(errors)) {
    if (key === '_form') continue;
    const cell = /^([A-Za-z][A-Za-z0-9]*)\[(\d+)]\.([A-Za-z][A-Za-z0-9]*)$/.exec(key);
    let label = fieldLabel.get(key) ?? key;
    let rank = order.get(key) ?? 1e6;
    if (cell) {
      const t = schema.subTables?.find((x) => x.key === cell[1]);
      const col = t?.fields.find((f) => f.key === cell[3]);
      label = `${t?.label ?? cell[1]} · ${col?.label ?? cell[3]}`;
      rank = (order.get(cell[1]) ?? 1e6) + (Number(cell[2]) + 1) / 1e4;
    } else {
      const t = schema.subTables?.find((x) => x.key === key);
      if (t) label = t.label ?? key;
    }
    items.push({ key, label, message, fix: fixes[key], rank });
  }
  return items.sort((a, b) => a.rank - b.rank).map(({ rank, ...x }) => x);
}
