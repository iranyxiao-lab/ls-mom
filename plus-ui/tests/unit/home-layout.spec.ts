/**
 * modernize-ui-ux 3.9：首页布局合并（角色或个人布局 × 已注册且可用的卡片）、调整顺序、写回。
 */
import { describe, expect, it } from 'vitest';
import { defineComponent } from 'vue';
import { mergeLayout, move, toLayout } from '@/home/layout';
import { registerHomeCard, usableCards } from '@/home/registry';

describe('首页布局', () => {
  it('按布局顺序显示；隐藏的单列；未注册或无权的不显示；新注册的列为可添加', () => {
    const m = mergeLayout([{ code: 'a' }, { code: 'x' }, { code: 'b', hidden: true }, { code: 'c' }, { code: 'a' }], ['a', 'b', 'c', 'd']);
    expect(m).toEqual({ shown: ['a', 'c'], hidden: ['b'], available: ['d'] });
    expect(toLayout(m)).toEqual([{ code: 'a' }, { code: 'c' }, { code: 'b', hidden: true }]);
  });
  it('上移下移，越界不变', () => {
    expect(move(['a', 'b', 'c'], 'c', -1)).toEqual(['a', 'c', 'b']);
    expect(move(['a', 'b', 'c'], 'a', -1)).toEqual(['a', 'b', 'c']);
    expect(move(['a', 'b', 'c'], 'c', 1)).toEqual(['a', 'b', 'c']);
  });
  it('卡片按权限与依赖页面过滤', () => {
    const C = defineComponent({ render: () => null });
    const off = [
      registerHomeCard({ code: 't.home.a', title: 'A', component: C }),
      registerHomeCard({ code: 't.home.b', title: 'B', component: C, perm: 'x:y' }),
      registerHomeCard({ code: 't.home.c', title: 'C', component: C, requiresAnyPath: ['/quality/nc', '/asset/defects'] })
    ];
    const mine = (perms: string[], paths: string[]) => usableCards(perms, new Set(paths)).filter((c) => c.startsWith('t.home.'));
    expect(mine([], [])).toEqual(['t.home.a']);
    expect(mine(['x:y'], ['/asset/defects'])).toEqual(['t.home.a', 't.home.b', 't.home.c']);
    off.forEach((f) => f());
  });
});
