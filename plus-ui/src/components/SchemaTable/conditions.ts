/**
 * 列表筛选条件：快速筛选芯片、条件标签文字、保存视图与筛选表单之间的转换、$context 变量解析。纯函数。
 */
import type { FilterOp, ListSchema, SchemaField } from '@/api/lsmom/types';
import type { ViewContext, ViewFilter } from '@/api/lsmom/savedViews';
import { filterOf } from './querySpec';

export const KEYWORD = '_keyword';

export interface ListState {
  code: string;
  label: string;
  initial: boolean;
  terminal: boolean;
}

export interface QuickChip {
  key: string;
  label: string;
  filters: ViewFilter[];
}

const SYSTEM_LABELS: Record<string, string> = {
  status: '状态',
  orgId: '所属组织',
  createdBy: '创建人',
  createdAt: '创建时间',
  updatedAt: '更新时间'
};
const TOKENS: Record<string, string> = { '$context.orgId': '本组织', '$context.partyId': '我' };
const OPS: Partial<Record<FilterOp, string>> = {
  eq: '等于',
  ne: '不等于',
  gt: '大于',
  ge: '不小于',
  lt: '小于',
  le: '不大于',
  like: '包含',
  startsWith: '开头是',
  in: '属于',
  notIn: '不属于',
  between: '介于',
  isNull: '为空',
  notNull: '不为空'
};

export function statesOf(schema: ListSchema): ListState[] {
  return ((schema as any).states ?? []) as ListState[];
}

/** 快速筛选：本组织、我创建的、未完成（不在终态）、草稿；没有状态机时不提供状态相关芯片。 */
export function quickChips(schema: ListSchema): QuickChip[] {
  const states = statesOf(schema);
  const chips: QuickChip[] = [
    { key: 'myOrg', label: '本组织', filters: [{ field: 'orgId', op: 'eq', value: '$context.orgId' }] },
    { key: 'mine', label: '我创建的', filters: [{ field: 'createdBy', op: 'eq', value: '$context.partyId' }] }
  ];
  const terminal = states.filter((s) => s.terminal).map((s) => s.code);
  if (terminal.length) chips.push({ key: 'open', label: '未完成', filters: [{ field: 'status', op: 'notIn', value: terminal }] });
  const initial = states.find((s) => s.initial);
  if (initial) chips.push({ key: 'draft', label: initial.label, filters: [{ field: 'status', op: 'eq', value: initial.code }] });
  return chips;
}

export const sameFilter = (a: ViewFilter, b: ViewFilter) =>
  a.field === b.field && a.op === b.op && JSON.stringify(a.value) === JSON.stringify(b.value);

/** 把 $context.* 换成当前用户的值；缺值时条件改为"不可能命中"（不退化为不过滤）。 */
export function resolveTokens(filters: ViewFilter[], ctx: ViewContext | null): ViewFilter[] {
  return filters.map((f) => {
    if (typeof f.value !== 'string' || !f.value.startsWith('$context.')) return f;
    const v = ctx?.[f.value.slice('$context.'.length) as keyof ViewContext];
    return v == null ? { field: 'id', op: 'eq', value: -1 } : { ...f, value: v };
  });
}

export function fieldLabel(schema: ListSchema, code: string) {
  if (code === KEYWORD) return '关键字';
  return schema.fields.find((f) => f.code === code)?.label ?? SYSTEM_LABELS[code] ?? code;
}

function valueLabel(schema: ListSchema, f: ViewFilter): string {
  const one = (v: unknown): string => {
    if (typeof v === 'string' && TOKENS[v]) return TOKENS[v];
    if (f.field === 'status') return statesOf(schema).find((s) => s.code === v)?.label ?? String(v);
    const fd = schema.fields.find((x) => x.code === f.field);
    if (fd?.optionLabels && typeof v === 'string') return fd.optionLabels[v] ?? v;
    if (typeof v === 'boolean') return v ? '是' : '否';
    return String(v);
  };
  if (Array.isArray(f.value)) return f.op === 'between' ? f.value.map(one).join(' 至 ') : f.value.map(one).join('、');
  return f.value === undefined ? '' : one(f.value);
}

/** 条件标签文字，如"状态 不属于 已关闭、已取消"、"所属组织 等于 本组织"。 */
export function condLabel(schema: ListSchema, f: ViewFilter, chips: QuickChip[] = []) {
  const chip = chips.find((c) => c.filters.length === 1 && sameFilter(c.filters[0], f));
  if (chip) return chip.label;
  if (f.field === KEYWORD) return `关键字：${String(f.value ?? '')}`;
  return `${fieldLabel(schema, f.field)} ${OPS[f.op] ?? f.op} ${valueLabel(schema, f)}`.trim();
}

/** 筛选表单能表示的条件（白名单字段、与控件一致的操作符、没有变量） */
function formRepresentable(field: SchemaField | undefined, f: ViewFilter) {
  if (!field?.queryable) return false;
  if (typeof f.value === 'string' && f.value.startsWith('$')) return false;
  return filterOf(field).op === f.op;
}

const SYSTEM = new Set(['id', 'status', 'orgId', 'createdBy', 'createdAt', 'updatedBy', 'updatedAt', KEYWORD]);

/** 保存视图的条件拆成：筛选表单取值、额外条件（以标签显示）、已弃用字段（忽略并提示）。 */
export function splitFilters(schema: ListSchema, filters: ViewFilter[]) {
  const model: Record<string, unknown> = {};
  const extra: ViewFilter[] = [];
  const dropped: string[] = [];
  for (const f of filters) {
    const field = schema.fields.find((x) => x.code === f.field);
    if (!field && !SYSTEM.has(f.field)) {
      dropped.push(f.field);
      continue;
    }
    if (formRepresentable(field, f) && model[f.field] === undefined) model[f.field] = f.value;
    else extra.push(f);
  }
  return { model, extra, dropped };
}

/** 视图里引用的、当前已不存在的字段（列、排序、分组） */
export function droppedFields(schema: ListSchema, codes: (string | null | undefined)[]) {
  const known = new Set([...schema.fields.map((f) => f.code), ...SYSTEM]);
  return [...new Set(codes.filter((c): c is string => !!c && !known.has(c)))];
}
