import type { FormField, FormSchema } from '@/types/lsView';

/** 预填值的来源：source 来源对象、remembered 上次填写、system 当前人员/组织/班次/时间、static 字段默认值。 */
export type FillOrigin = 'source' | 'remembered' | 'system' | 'static';

export interface SmartContext {
  partyId?: number | null;
  orgId?: number | null;
  shift?: string | null;
  /** 服务端当前时间 YYYY-MM-DDTHH:mm:ss */
  now?: string;
  /** 发起表单的来源对象（如从设备报缺陷时的设备） */
  source?: Record<string, unknown> | null;
  /** 本人上次填写的值 */
  remembered?: Record<string, unknown>;
}

export const ORIGIN_LABEL: Record<FillOrigin, string> = {
  source: '自动带入',
  remembered: '上次填写',
  system: '自动带入',
  static: '默认值'
};

const empty = (v: unknown) => v === undefined || v === null || v === '' || (Array.isArray(v) && !v.length);

function systemValue(f: FormField, ctx: SmartContext): unknown {
  switch (f.defaultFrom) {
    case '$user':
      return ctx.partyId ?? undefined;
    case '$org':
      return ctx.orgId ?? undefined;
    case '$shift':
      return ctx.shift ?? undefined;
    case '$now': {
      const now = ctx.now ?? localNow();
      return f.widget === 'date' ? now.slice(0, 10) : now;
    }
    default:
      return undefined;
  }
}

function localNow(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/**
 * 计算新单据的预填值。优先级：来源对象 > 上次填写 > 当前人员/组织/班次/时间 > 字段默认值。
 * 只填 model 里还没有值的字段；返回填入的值和每个字段的来源（用于区分显示）。
 */
export function smartDefaults(schema: FormSchema, model: Record<string, unknown>, ctx: SmartContext) {
  const values: Record<string, unknown> = {};
  const origin: Record<string, FillOrigin> = {};
  for (const f of schema.fields) {
    if (!empty(model[f.key]) || f.computed || f.widget === 'computed') continue;
    const candidates: [FillOrigin, unknown][] = [];
    if (f.defaultFrom?.startsWith('$source.') && ctx.source) candidates.push(['source', ctx.source[f.defaultFrom.slice(8)]]);
    if (f.remember) candidates.push(['remembered', ctx.remembered?.[f.key]]);
    if (f.defaultFrom && !f.defaultFrom.startsWith('$source.')) candidates.push(['system', systemValue(f, ctx)]);
    if (f.default !== undefined) candidates.push(['static', f.default]);
    const hit = candidates.find(([, v]) => !empty(v));
    if (hit) {
      values[f.key] = hit[1];
      origin[f.key] = hit[0];
    }
  }
  return { values, origin };
}

/** 表单里是否用到了自动带入或记忆值（不用时不必请求上下文）。 */
export const usesSmartDefaults = (schema: FormSchema) => schema.fields.some((f) => f.defaultFrom || f.remember);

/** 提交成功后要记住的值：标了 remember 且有值的字段。 */
export function rememberValues(schema: FormSchema, model: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const f of schema.fields) if (f.remember && !empty(model[f.key])) out[f.key] = model[f.key];
  return out;
}
