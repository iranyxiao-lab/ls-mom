/**
 * modernize-ui-ux 3.3：菜单固定分组（工作 / 台账 / 分析 / 配置），最多三级，Pack 入口自动归组。
 */
import { describe, expect, it } from 'vitest';
import type { RouteRecordRaw } from 'vue-router';
import { groupMenus, menuDepth, type PackMenuEntry } from '@/layout/menuGroups';

const r = (path: string, title: string, children?: RouteRecordRaw[], extra: Record<string, unknown> = {}) =>
  ({ path, meta: { title, icon: 'x' }, children, ...extra }) as unknown as RouteRecordRaw;

// 与后端动态路由同形：顶层目录 → 页面（系统管理下还有三级目录）
const routes: RouteRecordRaw[] = [
  { path: '', redirect: '/index', children: [r('/index', '首页')] } as unknown as RouteRecordRaw,
  r('/system', '系统管理', [r('user', '用户管理'), r('log', '日志管理', [r('operlog', '操作日志'), r('logininfor', '登录日志')])]),
  r('/monitor', '系统监控', [r('online', '在线用户'), r('ui-usage', '体验分析')]),
  r('/demo', '测试菜单', [r('demo', '测试单表')]),
  r('https://gitee.com/x', 'PLUS官网'),
  r('/production', '轻生产', [r('requests', '生产需求板'), r('board', '工作板'), r('dashboard', '经营看板')]),
  r('/asset', '设备', [r('center', '设备台账'), r('defects', '缺陷与维修')]),
  r('/dcs', 'DCS', [r('alarms', 'DCS 报警'), r('points', '测点')]),
  r('/toolloan-config', '工器具借还配置', [r('x', '按钮', undefined, { hidden: true })]),
  r('/toolloan-config2', '工器具借还配置'),
  r('/toolloan-config3', '工器具借还配置', [r('y', '工器具借还配置', undefined, { redirect: 'noRedirect' })])
];
const entries: PackMenuEntry[] = [
  { pack: 'ls.production', packName: '轻生产', name: '生产需求板', path: '/production/requests', group: 'work' },
  { pack: 'ls.production', packName: '轻生产', name: '经营看板', path: '/production/dashboard', group: 'analysis' },
  { pack: 'ls.asset', packName: '设备', name: '设备台账', path: '/asset/center', group: 'ledger' },
  { pack: 'ls.asset', packName: '设备', name: '点检计划', path: '/lsmom/ledger/ls.asset.InspectionPlan', group: 'ledger' },
  { pack: 'acme.toolloan', packName: '工器具借还', name: '工器具借还', path: '/lsmom/ledger/acme.toolloan.LoanOrder', group: 'ledger' }
];

const titles = (rs: RouteRecordRaw[]): any[] =>
  rs.map((x) => (x.children?.length ? { [String(x.meta?.title)]: titles(x.children) } : String(x.meta?.title)));

describe('菜单固定分组', () => {
  it('按工作 / 台账 / 分析 / 配置归组，模块为第二级、页面为第三级', () => {
    const menu = groupMenus(routes, entries, ['*:*:*']);
    expect(titles(menu)).toEqual([
      { undefined: ['首页'] },
      { 工作: ['协同工作中心', { 轻生产: ['生产需求板', '工作板'] }, { 设备: ['缺陷与维修'] }, { DCS: ['DCS 报警'] }] },
      { 台账: [{ 设备: ['设备台账', '点检计划'] }, { 工器具借还: ['工器具借还'] }] },
      { 分析: [{ 系统监控: ['体验分析'] }, { 轻生产: ['经营看板'] }] },
      {
        配置: [
          '协同应用中心',
          '表单设计器',
          '流程配置',
          { 系统管理: ['用户管理', '操作日志', '登录日志'] },
          { 系统监控: ['在线用户'] },
          { DCS: ['测点'] }
        ]
      }
    ]);
    expect(menuDepth(menu)).toBeLessThanOrEqual(3);
  });

  it('页面路径改为完整路径，点击直达原页面', () => {
    const menu = groupMenus(routes, entries, ['*:*:*']);
    const work = menu.find((x) => x.meta?.title === '工作')!;
    const prod = work.children!.find((x) => x.meta?.title === '轻生产')!;
    expect(prod.children!.map((x) => x.path)).toEqual(['/production/requests', '/production/board']);
    const config = menu.find((x) => x.meta?.title === '配置')!;
    expect(config.children!.find((x) => x.meta?.title === '系统管理')!.children!.map((x) => x.path)).toEqual([
      '/system/user',
      '/system/log/operlog',
      '/system/log/logininfor'
    ]);
  });

  it('不显示上游演示目录、外链和没有页面的目录；无权限的 Pack 台账与平台入口不出现', () => {
    const menu = groupMenus(routes, entries, ['crud:ls.asset.InspectionPlan:list']);
    const all = JSON.stringify(titles(menu));
    for (const hidden of ['测试菜单', '测试单表', 'PLUS官网', '工器具借还配置', '工器具借还', '协同应用中心', '表单设计器'])
      expect(all).not.toContain(hidden);
    expect(all).toContain('点检计划');
    expect(all).toContain('协同工作中心');
  });
});
