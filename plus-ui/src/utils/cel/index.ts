/**
 * 前端受限 CEL 求值（@bufbuild/cel，Apache-2.0）。
 *
 * 与后端 CelRuleEngine 使用同一白名单、同一 `$model`/`$context` 预处理和同一数值转换规则
 * （整数 → int，小数 → double），一致性用例见 packs/_shared/cel-conformance。
 * 前端结论只用于即时提示；L2 及以上规则一律以后端求值为准。类型检查只在后端发布时进行，
 * 已发布规则在前端不会出现类型错误。
 */
import { celEnv, isCelError, parse, plan } from '@bufbuild/cel';

export const ALLOWED_FUNCTIONS = ['size', 'matches', 'startsWith', 'endsWith', 'contains', 'int', 'double', 'string', 'bool'] as const;

export const LIMITS = { maxLength: 2000, maxNodes: 200, maxDepth: 24 };

export type CelIssueCode = 'RULE_INVALID' | 'RULE_ILLEGAL_FUNCTION' | 'RULE_TOO_COMPLEX';

export class CelRuleError extends Error {
  constructor(
    public readonly code: CelIssueCode | 'RULE_EVAL_FAILED',
    message: string,
    public readonly fn?: string
  ) {
    super(message);
  }
}

const IDENT = /^[A-Za-z_][A-Za-z0-9_]*$/;
const VARIABLES = new Set(['model', 'context']);

/** `$model` / `$context` → CEL 标识符；字符串字面量内不替换（与后端 preprocess 一致）。 */
export function preprocess(source: string): string {
  let out = '';
  let quote = '';
  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    if (quote) {
      out += c;
      if (c === '\\' && i + 1 < source.length) out += source[++i];
      else if (c === quote) quote = '';
      continue;
    }
    if (c === '"' || c === "'") {
      quote = c;
      out += c;
      continue;
    }
    if (c === '$') {
      const m = /^\$(model|context)\b/.exec(source.slice(i));
      if (!m) throw new CelRuleError('RULE_INVALID', '只能引用 $model 与 $context');
      out += m[1];
      i += m[0].length - 1;
      continue;
    }
    out += c;
  }
  return out;
}

type AnyExpr = { exprKind: { case?: string; value?: any } };

function walk(e: AnyExpr | undefined, depth: number, visit: (e: AnyExpr, depth: number) => void) {
  if (!e) return;
  visit(e, depth);
  const v = e.exprKind.value;
  switch (e.exprKind.case) {
    case 'callExpr':
      walk(v.target, depth + 1, visit);
      v.args.forEach((a: AnyExpr) => walk(a, depth + 1, visit));
      break;
    case 'selectExpr':
      walk(v.operand, depth + 1, visit);
      break;
    case 'listExpr':
      v.elements.forEach((a: AnyExpr) => walk(a, depth + 1, visit));
      break;
    case 'structExpr':
      v.entries.forEach((en: any) => {
        if (en.keyKind?.case === 'mapKey') walk(en.keyKind.value, depth + 1, visit);
        walk(en.value, depth + 1, visit);
      });
      break;
    default:
      break;
  }
}

export interface CompiledRule {
  source: string;
  run: (model: unknown, context?: unknown) => unknown;
}

/** 静态校验并编译；不合法时抛 CelRuleError（code 与后端一致）。 */
export function compileRule(source: string): CompiledRule {
  if (!source || !source.trim()) throw new CelRuleError('RULE_INVALID', '表达式为空');
  if ([...source].length > LIMITS.maxLength) throw new CelRuleError('RULE_TOO_COMPLEX', `表达式长度超过上限 ${LIMITS.maxLength}`);
  const cel = preprocess(source);
  let parsed: ReturnType<typeof parse>;
  try {
    parsed = parse(cel);
  } catch (e) {
    throw new CelRuleError('RULE_INVALID', '表达式语法错误：' + (e as Error).message);
  }
  let nodes = 0;
  let maxDepth = 0;
  const illegal: string[] = [];
  let unknownIdent: string | undefined;
  walk(parsed.expr as AnyExpr, 1, (e, depth) => {
    nodes++;
    maxDepth = Math.max(maxDepth, depth);
    const k = e.exprKind.case;
    if (k === 'callExpr') {
      const fn: string = e.exprKind.value.function;
      if (IDENT.test(fn) && !(ALLOWED_FUNCTIONS as readonly string[]).includes(fn)) illegal.push(fn);
    } else if (k === 'comprehensionExpr') {
      illegal.push(macroName(cel) ?? '<comprehension>');
    } else if (k === 'structExpr' && e.exprKind.value.messageName) {
      illegal.push('<message>');
    } else if (k === 'identExpr' && !VARIABLES.has(e.exprKind.value.name)) {
      unknownIdent ??= e.exprKind.value.name;
    }
  });
  if (nodes > LIMITS.maxNodes) throw new CelRuleError('RULE_TOO_COMPLEX', `表达式节点数 ${nodes} 超过上限 ${LIMITS.maxNodes}`);
  if (maxDepth > LIMITS.maxDepth * 4) throw new CelRuleError('RULE_TOO_COMPLEX', '表达式嵌套过深');
  if (illegal.length) throw new CelRuleError('RULE_ILLEGAL_FUNCTION', `函数「${illegal[0]}」不在白名单中`, illegal[0]);
  if (unknownIdent) throw new CelRuleError('RULE_INVALID', `未声明的引用「${unknownIdent}」`);
  const program = plan(celEnv(), parsed);
  return {
    source,
    run(model: unknown, context?: unknown) {
      const r = program({ model: toCel(model ?? {}), context: toCel(context ?? {}) } as any);
      if (isCelError(r)) throw new CelRuleError('RULE_EVAL_FAILED', '规则求值失败：' + r.message);
      return fromCel(r);
    }
  };
}

/** 推导式宏（all/exists/exists_one/map/filter）展开后不再保留函数名，从源码中找出以便提示。 */
function macroName(cel: string): string | undefined {
  const m = /\.\s*(all|exists_one|exists|map|filter)\s*\(/.exec(cel);
  return m?.[1];
}

/** 布尔条件求值；结果非布尔视为求值失败。 */
export function testRule(rule: CompiledRule, model: unknown, context?: unknown): boolean {
  const v = rule.run(model, context);
  if (typeof v !== 'boolean') throw new CelRuleError('RULE_EVAL_FAILED', '规则结果不是布尔值');
  return v;
}

/**
 * JSON → CEL 输入：整数 → bigint（int），其余数字 → double，对象 → Map。
 * 对象中值为 null/undefined 的字段视为"未填写"并移除（与后端一致）。
 */
export function toCel(v: unknown): unknown {
  if (v === null || v === undefined) return null;
  if (typeof v === 'number') return Number.isInteger(v) && Math.abs(v) < 9.007199254740992e15 ? BigInt(v) : v;
  if (Array.isArray(v)) return v.map(toCel);
  if (typeof v === 'object' && !(v instanceof Map)) {
    return new Map(
      Object.entries(v as Record<string, unknown>)
        .filter(([, x]) => x !== null && x !== undefined)
        .map(([k, x]) => [k, toCel(x)])
    );
  }
  return v;
}

/** CEL 结果 → JSON 值（int → number），便于比较与展示。 */
export function fromCel(v: any): unknown {
  if (typeof v === 'bigint') return Number(v);
  if (v === null || typeof v !== 'object') return v;
  if (typeof v[Symbol.iterator] === 'function' && typeof v.get !== 'function') return [...v].map(fromCel);
  if (typeof v.get === 'function' && typeof v.entries === 'function') {
    const out: Record<string, unknown> = {};
    for (const [k, x] of v.entries()) out[String(fromCel(k))] = fromCel(x);
    return out;
  }
  if (typeof v.get === 'function' && typeof v.size === 'number' && typeof v[Symbol.iterator] === 'function') return [...v].map(fromCel);
  return v;
}
