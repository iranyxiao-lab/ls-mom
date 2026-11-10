/**
 * modernize-ui-ux 3.5：命令面板的本地匹配（拼音、首字母）、动作按权限过滤、最近访问与收藏。
 */
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { loadPinyin, pinyinScore, rankBy } from '@/utils/pinyinMatch';
import { availableCommands, registerCommand } from '@/layout/commands';
import { RECENT_MAX, useNavHistoryStore } from '@/store/modules/navHistory';
import { objectPath } from '@/api/lsmom/uiSearchModel';

describe('拼音匹配', () => {
  beforeAll(loadPinyin);
  const menus = ['用户管理', '设备台账', '质量记录', '生产需求板', '设备点检'];
  it('"sbtz" 命中设备台账，且排在首位', () => {
    expect(rankBy(menus, (m) => m, 'sbtz')[0]).toBe('设备台账');
  });
  it('全拼、混合缩写、原文都能命中；不相关的词不命中', () => {
    expect(rankBy(menus, (m) => m, 'shebeitaizhang')).toEqual(['设备台账']);
    expect(rankBy(menus, (m) => m, 'shebtz')).toEqual(['设备台账']);
    expect(rankBy(menus, (m) => m, '台账')).toEqual(['设备台账']);
    expect(rankBy(menus, (m) => m, 'sb')).toEqual(['设备台账', '设备点检']);
    expect(pinyinScore('质量记录', 'xyz')).toBeNull();
  });
  it('原文开头命中优先于拼音命中', () => {
    expect((pinyinScore('SB 设备', 'sb') as number) < (pinyinScore('设备', 'sb') as number)).toBe(true);
  });
});

describe('动作', () => {
  it('按权限与依赖页面过滤', () => {
    const off = [
      registerCommand({ id: 't.a', title: '甲', run: () => 0 }),
      registerCommand({ id: 't.b', title: '乙', perm: 'x:y', run: () => 0 }),
      registerCommand({ id: 't.c', title: '丙', requiresPath: '/quality/nc', run: () => 0 })
    ];
    const ids = (perms: string[], paths: string[]) =>
      availableCommands(perms, new Set(paths))
        .map((c) => c.id)
        .filter((i) => i.startsWith('t.'));
    expect(ids([], [])).toEqual(['t.a']);
    expect(ids(['x:y'], ['/quality/nc'])).toEqual(['t.a', 't.b', 't.c']);
    expect(ids(['*:*:*'], [])).toEqual(['t.a', 't.b']);
    off.forEach((f) => f());
    expect(ids(['*:*:*'], ['/quality/nc'])).toEqual([]);
  });
});

describe('最近访问与收藏', () => {
  beforeEach(() => {
    localStorage.removeItem('ls-nav-history');
    setActivePinia(createPinia());
  });
  it('最近访问去重、最新在前，最多保留 30 条，并持久化', () => {
    const h = useNavHistoryStore();
    for (let i = 0; i < 40; i++) h.visit({ kind: 'page', path: `/p/${i}`, title: `页面${i}` });
    h.visit({ kind: 'page', path: '/p/35', title: '页面35' });
    expect(h.recent.length).toBe(RECENT_MAX);
    expect(RECENT_MAX).toBeGreaterThanOrEqual(20);
    expect(h.recent[0].path).toBe('/p/35');
    expect(h.recent.filter((r) => r.path === '/p/35').length).toBe(1);
    setActivePinia(createPinia());
    expect(useNavHistoryStore().recent[0].path).toBe('/p/35');
  });
  it('收藏切换', () => {
    const h = useNavHistoryStore();
    const e = { kind: 'object' as const, path: '/lsmom/object/work/1', title: 'WI1 巡检' };
    h.toggleFavorite(e);
    expect(h.isFavorite(e.path)).toBe(true);
    h.toggleFavorite(e);
    expect(h.isFavorite(e.path)).toBe(false);
  });
});

describe('对象地址', () => {
  it('工作、人员走对象详情页，台账记录打开台账详情', () => {
    expect(objectPath('ls.core.Work', '7')).toBe('/lsmom/object/work/7');
    expect(objectPath('ls.core.Party', 3)).toBe('/lsmom/object/party/3');
    expect(objectPath('ls.asset.Asset', '9')).toBe('/lsmom/ledger/ls.asset.Asset?id=9');
  });
});
