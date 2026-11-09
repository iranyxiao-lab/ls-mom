/** 8.3：字段策略合并与后端共用 packs/_shared/field-policy 用例集。 */
import { describe, expect, it } from 'vitest';
import suite from '../../../packs/_shared/field-policy/basic.json';
import { resolveFieldPolicy } from '@/utils/lsform/fieldPolicy';

const fmt = (a: { visible: boolean; editable: boolean; required: boolean }) =>
  `${a.visible ? 'v' : '-'} ${a.editable ? 'e' : '-'} ${a.required ? 'r' : '-'}`;

describe('resolveFieldPolicy 共享用例', () => {
  it('用例数量与后端一致（≥8）', () => expect(suite.cases.length).toBeGreaterThanOrEqual(8));
  for (const c of suite.cases) {
    it(c.name, () => {
      const r = resolveFieldPolicy(suite.form as any, c.policy as any);
      for (const [k, v] of Object.entries(c.expect)) {
        expect(r[k], k).toBeDefined();
        expect(fmt(r[k]), `${c.name} / ${k}`).toBe(v);
      }
    });
  }
});
