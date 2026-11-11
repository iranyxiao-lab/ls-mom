import { describe, expect, it } from 'vitest';
import { History } from '@/utils/lsform/history';

describe('设计器撤销历史', () => {
  it('载入后的规范化回读替换当前步骤，撤销回到上一步而不是回读结果', () => {
    const h = new History<{ v: number; n?: boolean }>();
    h.push({ v: 1 });
    h.push({ v: 2 });
    h.normalize({ v: 2 }, { v: 2, n: true });
    expect(h.push({ v: 2, n: true }), '回读与当前步骤相同，不再记一步').toBe(false);
    expect(h.undo()).toEqual({ v: 1 });
  });

  it('当前步骤不是载入的快照时不替换', () => {
    const h = new History<{ v: number }>();
    h.push({ v: 1 });
    h.normalize({ v: 9 }, { v: 10 });
    expect(h.size).toBe(1);
    expect(h.push({ v: 1 })).toBe(false);
  });
});
