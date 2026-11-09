import type { FilterOp, ListSchema, QuerySpec, SchemaField } from '@/api/lsmom/types';

/** 字段在筛选区使用的控件与操作符。 */
export function filterOf(f: SchemaField): { widget: 'text' | 'select' | 'number' | 'dateRange' | 'boolean'; op: FilterOp } {
  switch (f.type) {
    case 'ENUM':
      return { widget: 'select', op: 'eq' };
    case 'BOOLEAN':
      return { widget: 'boolean', op: 'eq' };
    case 'INTEGER':
    case 'DECIMAL':
    case 'REF':
      return { widget: 'number', op: 'eq' };
    case 'DATE':
    case 'DATETIME':
      return { widget: 'dateRange', op: 'between' };
    default:
      return { widget: 'text', op: f.dictType ? 'eq' : 'like' };
  }
}

/** 由筛选表单生成 QuerySpec：只包含白名单（queryable）字段，空值忽略。 */
export function buildQuerySpec(
  schema: ListSchema,
  model: Record<string, unknown>,
  sort: { field: string; dir: 'asc' | 'desc' } | null,
  page: number,
  size: number
): QuerySpec {
  const filters: QuerySpec['filters'] = [];
  for (const f of schema.fields) {
    if (!f.queryable) continue;
    const v = model[f.code];
    if (v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0)) continue;
    const { op } = filterOf(f);
    if (op === 'between') {
      if (Array.isArray(v) && v.length === 2) filters.push({ field: f.code, op, value: v });
      continue;
    }
    filters.push({ field: f.code, op, value: v });
  }
  const refs = schema.fields.filter((f) => f.type === 'REF').map((f) => f.code);
  return {
    filters,
    sort: sort && schema.fields.some((f) => f.code === sort.field && f.sortable) ? [sort] : [],
    page,
    size,
    expand: refs
  };
}

/** 列：优先使用已发布 LIST 视图的 columns，否则取全部非 JSON/FILE 字段。 */
export function columnsOf(schema: ListSchema): SchemaField[] {
  let wanted: string[] | null = null;
  if (schema.view) {
    try {
      const v = JSON.parse(schema.view);
      if (Array.isArray(v.columns)) wanted = v.columns;
    } catch {
      wanted = null;
    }
  }
  const usable = schema.fields.filter((f) => f.type !== 'JSON' && f.type !== 'FILE');
  if (!wanted) return usable;
  return wanted.map((c) => usable.find((f) => f.code === c)).filter((f): f is SchemaField => !!f);
}

/** 单元格显示值：字典/枚举标签、引用标签、布尔中文。 */
export function cellText(f: SchemaField, row: Record<string, any>): string {
  const v = row[f.code];
  if (v === null || v === undefined) return '';
  if (row[f.code + 'Label'] != null) return String(row[f.code + 'Label']);
  if (f.type === 'ENUM' && f.optionLabels?.[String(v)]) return f.optionLabels[String(v)];
  if (f.type === 'REF') {
    const ref = row[f.code + 'Ref'];
    if (ref?.restricted) return '（无权查看）';
    return ref?.label != null ? String(ref.label) : String(v);
  }
  if (f.type === 'BOOLEAN') return v ? '是' : '否';
  return String(v);
}

/** 行可执行的迁移动作（有权限的 TRANSITION）。 */
export function rowOperations(schema: ListSchema) {
  return schema.operations.filter((o) => o.kind === 'TRANSITION' && o.permitted);
}
