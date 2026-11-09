import { compileRule, testRule } from '@/utils/cel';
import type { FormField, FormSchema } from '@/types/lsView';
import type { Access } from './fieldPolicy';

/** 规则与计算字段的上下文（与后端一致：orgId、deptId、userId、partyId）。 */
export type FormContext = Record<string, unknown>;

export interface FormCheck {
  /** 字段键（子表行为 子表[行].列）→ 错误 */
  errors: Record<string, string>;
  /** L0 提示 */
  warnings: string[];
}

const empty = (v: unknown) => v === null || v === undefined || (typeof v === 'string' && v.trim() === '') || (Array.isArray(v) && v.length === 0);

const labelOf = (f: FormField) => f.label ?? f.key;

function optionValues(options: FormField['options'], out: string[] = []): string[] {
  for (const o of options ?? []) {
    out.push(String(o.value));
    if (o.children) optionValues(o.children, out);
  }
  return out;
}

/** 单字段校验（必填、长度、范围、正则、静态选项）；字典与引用过滤由服务端按冻结版本复核。 */
export function checkField(f: FormField, a: Access | undefined, v: unknown): string | undefined {
  const label = labelOf(f);
  if (empty(v)) return a?.required ? `${label}必填` : undefined;
  if (typeof v === 'string') {
    const len = [...v].length;
    if (f.minLength != null && len < f.minLength) return `${label}至少 ${f.minLength} 个字符`;
    if (f.maxLength != null && len > f.maxLength) return `${label}最多 ${f.maxLength} 个字符`;
    if (f.pattern) {
      try {
        if (!new RegExp(`^(?:${f.pattern.replace(/^\^/, '').replace(/\$$/, '')})$`).test(v)) return f.patternMessage ?? `${label}格式不正确`;
      } catch {
        return `${label}格式规则无效`;
      }
    }
  }
  if ((f.min != null || f.max != null) && (typeof v === 'number' || typeof v === 'string')) {
    const n = Number(v);
    if (Number.isNaN(n)) return `${label}必须是数字`;
    if (f.min != null && n < f.min) return `${label}不能小于 ${f.min}`;
    if (f.max != null && n > f.max) return `${label}不能大于 ${f.max}`;
  }
  if (f.options?.length && f.widget !== 'cascader') {
    const allowed = optionValues(f.options);
    const values = Array.isArray(v) ? v : [v];
    if (values.some((x) => !allowed.includes(String(x)))) return `${label}的取值不在可选范围内`;
  }
  return undefined;
}

/** when 求值出错（如引用的可选字段未填写）视为条件不成立，与后端一致。 */
function applies(when: string | undefined, model: unknown, context: FormContext): boolean {
  if (!when) return true;
  try {
    return testRule(compileRule(when), model, context);
  } catch {
    return false;
  }
}

/** 计算字段：按表达式重算（结果仅供展示，服务端会重算并覆盖）。 */
export function computeFields(form: FormSchema, model: Record<string, any>, context: FormContext = {}): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const f of form.fields) {
    if (!f.computed) continue;
    try {
      out[f.key] = compileRule(f.computed.expression).run(model, context);
    } catch {
      out[f.key] = null;
    }
    model[f.key] = out[f.key];
  }
  return out;
}

/** 整单校验：字段、子表行数与行字段、L1/L2 规则（错误）、L0 规则（提示）。 */
export function validateForm(form: FormSchema, model: Record<string, any>, access: Record<string, Access>, context: FormContext = {}): FormCheck {
  const errors: Record<string, string> = {};
  const warnings: string[] = [];
  for (const f of form.fields) {
    if (f.computed) continue;
    const a = access[f.key];
    if (a && !a.visible && !a.required) continue;
    const e = checkField(f, a, model[f.key]);
    if (e) errors[f.key] = e;
  }
  for (const st of form.subTables ?? []) {
    const rows: any[] = Array.isArray(model[st.key]) ? model[st.key] : [];
    const name = st.label ?? st.key;
    if (access[st.key]?.visible === false) continue;
    if (st.minRows != null && rows.length < st.minRows) errors[st.key] = `${name}至少 ${st.minRows} 行`;
    if (st.maxRows != null && rows.length > st.maxRows) errors[st.key] = `${name}最多 ${st.maxRows} 行`;
    rows.forEach((row, i) => {
      for (const f of st.fields) {
        const e = checkField(f, access[`${st.key}.${f.key}`], row?.[f.key]);
        if (e) errors[`${st.key}[${i}].${f.key}`] = `第 ${i + 1} 行：${e}`;
      }
    });
  }
  // 与服务端一致：字段错误清零后才评估规则
  for (const r of Object.keys(errors).length ? [] : (form.rules ?? [])) {
    if (!applies(r.when, model, context)) continue;
    let ok = false;
    try {
      ok = testRule(compileRule(r.assert), model, context);
    } catch {
      ok = false;
    }
    if (ok) continue;
    if (r.level === 'L0') warnings.push(r.message);
    else errors[r.field ?? '_form'] ??= r.message;
  }
  return { errors, warnings };
}
