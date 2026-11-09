import type { FormField, FormSchema, NodePolicy, SubTable } from '@/types/lsView';

/** 字段访问控制：可见、可编辑、必填。 */
export interface Access {
  visible: boolean;
  editable: boolean;
  required: boolean;
}

type PolicyForm = Pick<FormSchema, 'fields'> & { subTables?: Pick<SubTable, 'key' | 'fields'>[] };

/**
 * 节点字段策略合并（与后端 FieldPolicy.resolve 同一算法，共享用例 packs/_shared/field-policy）。
 * 优先级：显式 editable ＞ readonly/hidden（显式或通配 *）＞ 表单默认；计算字段永远只读；required 使字段可见；
 * 子表列键为「子表.列」，受子表整体策略约束（显式列 editable 例外）。
 */
export function resolveFieldPolicy(form: PolicyForm, policy?: NodePolicy | null): Record<string, Access> {
  const ro = new Set(policy?.readonly ?? []);
  const ed = new Set(policy?.editable ?? []);
  const hi = new Set(policy?.hidden ?? []);
  const rq = new Set(policy?.required ?? []);
  const out: Record<string, Access> = {};
  for (const f of form.fields) out[f.key] = access(f, f.key, undefined, ro, ed, hi, rq);
  for (const s of form.subTables ?? []) {
    const table = access(s as unknown as FormField, s.key, undefined, ro, ed, hi, rq);
    out[s.key] = table;
    for (const f of s.fields) {
      const key = `${s.key}.${f.key}`;
      const col = access(f, key, s.key, ro, ed, hi, rq);
      const visible = col.visible && table.visible;
      const editable = visible && col.editable && (table.editable || ed.has(key));
      out[key] = { visible, editable, required: col.required && visible };
    }
  }
  return out;
}

function access(
  f: Partial<FormField>,
  key: string,
  parent: string | undefined,
  ro: Set<string>,
  ed: Set<string>,
  hi: Set<string>,
  rq: Set<string>
): Access {
  const computed = f.widget === 'computed' || f.computed != null;
  let visible = !f.hidden;
  let editable = !f.readonly && !computed;
  let required = !!f.required;
  const hit = (s: Set<string>) => s.has('*') || s.has(key) || (parent != null && s.has(parent));
  const explicit = (s: Set<string>) => s.has(key) || (parent != null && s.has(parent));
  if (hit(ro)) editable = false;
  if (hit(hi)) visible = false;
  if (explicit(ed) && !computed) {
    editable = true;
    visible = true;
  }
  if (hit(rq)) {
    required = true;
    visible = true;
  }
  if (!visible) {
    editable = false;
    required = false;
  }
  return { visible, editable, required };
}
