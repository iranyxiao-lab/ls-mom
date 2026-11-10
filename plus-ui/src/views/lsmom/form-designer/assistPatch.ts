import type { FormSchema } from '@/types/lsView';
import type { AssistOp } from '@/api/lsmom/formAssist';

/** 一条补丁的差异说明（设计器高亮显示：新增为绿色，修改显示改前 → 改后）。 */
export interface OpDiff {
  kind: 'add' | 'change';
  target: string;
  lines: { op: '+' | '-'; text: string }[];
}

export function describeOp(schema: FormSchema, o: AssistOp): OpDiff {
  if (o.op === 'addRule') {
    const r = o.rule;
    return {
      kind: 'add',
      target: `规则 ${r.code}`,
      lines: [
        { op: '+', text: `提示：${r.message}` },
        ...(r.when ? [{ op: '+' as const, text: `条件：${r.when}` }] : []),
        { op: '+', text: `断言：${r.assert}` },
        ...(r.field ? [{ op: '+' as const, text: `标注字段：${r.field}` }] : [])
      ]
    };
  }
  if (o.op === 'addField') {
    return {
      kind: 'add',
      target: `字段 ${o.field.key}`,
      lines: [{ op: '+', text: `${o.field.label ?? o.field.key}（${o.field.widget ?? 'input'}${o.field.required ? '，必填' : ''}）` }]
    };
  }
  const f = schema.fields.find((x) => x.key === o.key) as unknown as Record<string, unknown> | undefined;
  const lines: OpDiff['lines'] = [];
  for (const [k, v] of Object.entries(o.set)) {
    lines.push({ op: '-', text: `${k}: ${JSON.stringify(f?.[k] ?? null)}` });
    lines.push({ op: '+', text: `${k}: ${JSON.stringify(v)}` });
  }
  return { kind: 'change', target: `字段 ${o.key}`, lines };
}

/** 把选中的合法补丁应用到草稿副本（不改入参）。 */
export function applyAssistOps(schema: FormSchema, ops: AssistOp[]): FormSchema {
  const s: FormSchema = JSON.parse(JSON.stringify(schema));
  for (const o of ops) {
    if (!o.valid) continue;
    if (o.op === 'addRule') {
      const { code, level, when, assert, field, message } = o.rule;
      (s.rules ??= []).push({ code, level: (level ?? 'L1') as any, ...(when ? { when } : {}), assert, ...(field ? { field } : {}), message } as any);
    } else if (o.op === 'updateField') {
      const f = s.fields.find((x) => x.key === o.key);
      if (f) Object.assign(f, o.set);
    } else if (o.op === 'addField') {
      if (s.fields.some((x) => x.key === o.field.key)) continue;
      s.fields.push({
        key: o.field.key,
        label: o.field.label ?? o.field.key,
        widget: (o.field.widget ?? 'input') as any,
        ...(o.field.required ? { required: true } : {})
      } as any);
      s.layout.push({ type: 'field', key: o.field.key } as any);
    }
  }
  return s;
}
