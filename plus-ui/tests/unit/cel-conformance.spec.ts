/**
 * 6.2：与后端 CelConformanceTest 同跑 packs/_shared/cel-conformance 用例，保证前后端求值一致。
 */
import { describe, expect, it } from 'vitest';
import { CelRuleError, compileRule, fromCel, testRule } from '@/utils/cel';

type Case = {
  name: string;
  expr: string;
  model?: unknown;
  context?: unknown;
  expect: { value?: unknown; error?: boolean; invalid?: string; function?: string };
};

const files = import.meta.glob('../../../packs/_shared/cel-conformance/*.json', { eager: true, import: 'default' }) as Record<
  string,
  { cases: Case[] }
>;

function expand(expr: string) {
  if (!expr.startsWith('REPEAT:')) return expr;
  const [part, n, ...rest] = expr.slice(7).split(':');
  return part.repeat(Number(n)) + rest.join(':');
}

const cases = Object.entries(files).flatMap(([file, doc]) => doc.cases.map((c) => ({ file: file.split('/').pop(), ...c })));

describe('CEL 前后端一致性', () => {
  it('用例集已加载', () => {
    expect(cases.length).toBeGreaterThanOrEqual(40);
  });

  it.each(cases)('$file · $name', (c) => {
    let rule;
    try {
      rule = compileRule(expand(c.expr));
    } catch (e) {
      expect(e).toBeInstanceOf(CelRuleError);
      expect(c.expect.invalid, `不应拒绝：${(e as Error).message}`).toBeDefined();
      expect((e as CelRuleError).code).toBe(c.expect.invalid);
      if (c.expect.function) expect((e as CelRuleError).fn).toBe(c.expect.function);
      return;
    }
    expect(c.expect.invalid, '应被发布校验拒绝').toBeUndefined();
    let value: unknown;
    try {
      value = rule.run(c.model ?? {}, c.context ?? {});
    } catch (e) {
      expect(c.expect.error, `不应求值失败：${(e as Error).message}`).toBe(true);
      return;
    }
    expect(c.expect.error, `应求值失败，实际得到 ${String(value)}`).toBeFalsy();
    expect(value).toEqual(c.expect.value);
  });

  it('布尔条件求值', () => {
    expect(testRule(compileRule('has($model.plannedEnd)'), {})).toBe(false);
    expect(() => testRule(compileRule('$model.a + 1'), { a: 1 })).toThrow(CelRuleError);
    expect(fromCel(10n)).toBe(10);
  });
});
