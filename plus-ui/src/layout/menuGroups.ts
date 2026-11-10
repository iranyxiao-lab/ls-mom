/**
 * 导航菜单固定分组：工作 / 台账 / 分析 / 配置，最多三级（分组 → 模块 → 页面）。
 * - 菜单来自 RuoYi 动态路由（权限已在后端过滤）和已启用 Pack 的入口（/api/packs/menu，按入口声明的 group 归组）；
 * - 上游系统目录（系统管理、租户、监控、工具）归入「配置」，更深的层级拍平到第三级；
 * - 上游演示目录与外链不显示。
 * 纯函数，不依赖组件实例，便于单测。
 */
import type { RouteRecordRaw } from 'vue-router';

export type MenuGroup = 'work' | 'ledger' | 'analysis' | 'config';

export const MENU_GROUPS: { key: MenuGroup; title: string; icon: string }[] = [
  { key: 'work', title: '工作', icon: 'job' },
  { key: 'ledger', title: '台账', icon: 'list' },
  { key: 'analysis', title: '分析', icon: 'chart' },
  { key: 'config', title: '配置', icon: 'system' }
];

export interface PackMenuEntry {
  pack: string;
  packName: string;
  name: string;
  path: string;
  group: MenuGroup;
}

/** 平台自带（非 Pack）页面的分组；未列出的按所在目录推断。 */
const PLATFORM_GROUP: Record<string, MenuGroup> = {
  '/dcs/alarms': 'work',
  '/dcs/points': 'config',
  '/agent/admin': 'config',
  '/agent/approvals': 'work',
  '/agent/summaries': 'work',
  '/agent/knowledge': 'work',
  '/monitor/ui-usage': 'analysis'
};
/** 上游系统目录：整体归入配置。 */
const UPSTREAM_DIRS = new Set(['/system', '/tenant', '/monitor', '/tool']);
/** 不显示的上游演示目录。 */
const HIDDEN_DIRS = new Set(['/demo']);

/** 平台入口（静态路由，不在后端菜单里）：有权限才显示。 */
export const PLATFORM_LINKS: { title: string; path: string; icon: string; group: MenuGroup; perm?: string }[] = [
  { title: '协同工作中心', path: '/lsmom/workbench', icon: 'job', group: 'work' },
  { title: '协同应用中心', path: '/lsmom/apps', icon: 'component', group: 'config', perm: 'lsmom:pack:install' },
  { title: '表单设计器', path: '/lsmom/form-designer', icon: 'form', group: 'config', perm: 'lsmom:form:design' },
  { title: '流程配置', path: '/lsmom/flow-designer', icon: 'tree', group: 'config', perm: 'lsmom:flow:design' }
];

const isExternal = (p: string) => /^(https?:|mailto:|tel:)/.test(p);
const join = (base: string, p: string) => (p.startsWith('/') ? p : `${base.replace(/\/$/, '')}/${p}`).replace(/\/+/g, '/');
const can = (perms: string[], perm?: string) => !perm || perms.includes('*:*:*') || perms.includes(perm);

interface Leaf {
  route: RouteRecordRaw;
  fullPath: string;
}

/** 目录下全部可见页面（任意深度拍平），路径改为完整路径。 */
function leaves(r: RouteRecordRaw, base: string): Leaf[] {
  const full = join(base, r.path);
  // 目录（哪怕下面只有隐藏的按钮权限）不算页面
  if (Array.isArray(r.children)) return r.children.filter((c) => !(c as any).hidden).flatMap((c) => leaves(c, full));
  // 后端把没有页面的目录（只含按钮权限）也作为路由下发：redirect 为 noRedirect 或 alwaysShow，不算页面
  if ((r as any).redirect === 'noRedirect' || (r as any).alwaysShow) return [];
  return isExternal(r.path) ? [] : [{ route: r, fullPath: full }];
}

const node = (path: string, title: string, icon: string | undefined, children: RouteRecordRaw[]): RouteRecordRaw =>
  ({ path, name: undefined, alwaysShow: true, meta: { title, icon }, children }) as unknown as RouteRecordRaw;

/**
 * 把动态路由与 Pack 入口整理成固定分组菜单。
 * @param routes 侧栏路由（后端已按权限过滤）
 * @param entries 已启用 Pack 的入口
 * @param perms 当前用户权限编码
 */
export function groupMenus(routes: RouteRecordRaw[], entries: PackMenuEntry[], perms: string[]): RouteRecordRaw[] {
  const entryGroup = new Map(entries.map((e) => [e.path, e.group]));
  const top: RouteRecordRaw[] = [];
  // 分组 → 模块目录（按标题合并）→ 页面
  const dirs = new Map<MenuGroup, Map<string, { title: string; icon?: string; items: RouteRecordRaw[] }>>();
  const direct = new Map<MenuGroup, RouteRecordRaw[]>();
  const seen = new Set<string>();

  const add = (g: MenuGroup, dirKey: string, dirTitle: string, dirIcon: string | undefined, leaf: RouteRecordRaw) => {
    if (!dirs.has(g)) dirs.set(g, new Map());
    const m = dirs.get(g)!;
    if (!m.has(dirKey)) m.set(dirKey, { title: dirTitle, icon: dirIcon, items: [] });
    m.get(dirKey)!.items.push(leaf);
  };
  const asLeaf = (l: Leaf): RouteRecordRaw => ({ ...l.route, path: l.fullPath, children: undefined }) as RouteRecordRaw;

  for (const r of routes) {
    if ((r as any).hidden || HIDDEN_DIRS.has(r.path) || isExternal(r.path)) continue;
    // 顶层菜单页面在后端会包一层布局路由，所以顶层没有子路由的一定是空目录（例如只放按钮权限的目录）
    if (!r.children?.length) continue;
    const kids = (r.children ?? []).filter((c) => !(c as any).hidden);
    // 首页等单页面入口（目录只是外壳）：保留在顶层
    if (!r.meta?.title && kids.length === 1 && !kids[0].children?.length) {
      top.push(r);
      leaves(r, '').forEach((l) => seen.add(l.fullPath));
      continue;
    }
    const base = join('', r.path);
    for (const l of leaves(r, '')) {
      if (seen.has(l.fullPath)) continue;
      seen.add(l.fullPath);
      const g: MenuGroup =
        entryGroup.get(l.fullPath) ??
        PLATFORM_GROUP[l.fullPath] ??
        (UPSTREAM_DIRS.has(base) ? 'config' : l.fullPath.startsWith('/lsmom/ledger/') ? 'ledger' : 'work');
      add(g, String(r.meta?.title ?? r.path), String(r.meta?.title ?? r.path), r.meta?.icon as string | undefined, asLeaf(l));
    }
  }

  // 只以入口形式存在的 Pack 页面（如配置交付的台账）：有该台账的查看权限才显示
  for (const e of entries) {
    if (seen.has(e.path)) continue;
    const concept = /^\/lsmom\/ledger\/([^/?#]+)/.exec(e.path)?.[1];
    if (!concept || !can(perms, `crud:${concept}:list`)) continue;
    seen.add(e.path);
    add(e.group, e.packName, e.packName, 'component', { path: e.path, meta: { title: e.name, icon: 'list' } } as RouteRecordRaw);
  }

  for (const p of PLATFORM_LINKS) {
    if (seen.has(p.path) || !can(perms, p.perm)) continue;
    if (!direct.has(p.group)) direct.set(p.group, []);
    direct.get(p.group)!.push({ path: p.path, meta: { title: p.title, icon: p.icon } } as RouteRecordRaw);
  }

  const out = [...top];
  for (const g of MENU_GROUPS) {
    const children = [
      ...(direct.get(g.key) ?? []),
      ...[...(dirs.get(g.key)?.entries() ?? [])].map(([, d], i) => node(`/menu-group/${g.key}/${i}`, d.title, d.icon, d.items))
    ];
    if (children.length) out.push(node(`/menu-group/${g.key}`, g.title, g.icon, children));
  }
  return out;
}

/** 菜单层级（用于检查不超过三级）。 */
export function menuDepth(routes: RouteRecordRaw[]): number {
  return routes.reduce((d, r) => Math.max(d, 1 + menuDepth((r.children ?? []).filter((c) => !(c as any).hidden))), 0);
}
