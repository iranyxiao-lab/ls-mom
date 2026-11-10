/**
 * 页面清单与路由表比对（acceptance-walkthrough 6.2）：
 *   路由来源 = 前端静态路由（plus-ui/src/router/index.ts）＋ 菜单表 sys_menu（运行中的验收环境，动态路由）；
 *   与 acceptance/pages.yaml 比对：有路由但未登记 → "未走查页面"（退出码 1）；登记了但已没有路由 → "已失效登记"。
 * 运行：node tools/pages.ts [--menus <sys_menu 导出.json>] [--init]
 *   默认经 docker compose（项目 LSMOM_ACC_PROJECT，默认 lsmom-acc）只读查询菜单表；--init 按当前路由生成 pages.yaml 草稿（已有登记保留）。
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import YAML from 'yaml';
import { ACC, ROOT } from './lib.ts';
import type { Page } from './manual.ts';

export interface Route {
  path: string;
  name: string;
  source: 'static' | 'menu';
  area: string;
}
export interface Menu {
  menu_id: number;
  parent_id: number;
  menu_name: string;
  path: string;
  component: string | null;
  menu_type: string;
  visible: string;
  status: string;
  is_frame: string;
}

const SKIP_STATIC = [/^\/redirect/, /^\/:pathMatch/, /^\/social-callback/];

/** 解析静态路由：按花括号层级跟踪 path，拼出完整路径；只收有 component 的叶子路由 */
export function staticRoutes(src: string): Route[] {
  const out: Route[] = [];
  const stack: { path?: string; title?: string; component?: boolean; children?: boolean }[] = [];
  const re = /\{|\}|path:\s*'([^']*)'|title:\s*'([^']*)'|component:|children:/g;
  const full = () => {
    let p = '';
    for (const f of stack) {
      if (f.path == null) continue;
      p = f.path.startsWith('/') ? f.path : `${p.replace(/\/$/, '')}/${f.path}`;
    }
    return p.replace(/\/$/, '') || '/';
  };
  let m: RegExpExecArray | null;
  const body = src.slice(src.indexOf('export const constantRoutes'));
  while ((m = re.exec(body))) {
    const t = m[0];
    if (t === '{') stack.push({});
    else if (t === '}') {
      const f = stack.pop();
      if (f?.path != null && f.component && !f.children) {
        stack.push(f);
        const p = full();
        stack.pop();
        if (!SKIP_STATIC.some((r) => r.test(p))) out.push({ path: p, name: f.title ?? p, source: 'static', area: p.split('/')[1] || 'home' });
      }
    } else if (m[1] !== undefined && stack.length) stack[stack.length - 1].path ??= m[1];
    else if (m[2] !== undefined && stack.length) {
      // title 写在 meta 对象里：归到最近的带 path 的路由
      const f = [...stack].reverse().find((x) => x.path != null);
      if (f) f.title ??= m[2];
    }
    else if (t.startsWith('component') && stack.length) stack[stack.length - 1].component = true;
    else if (t.startsWith('children') && stack.length) stack[stack.length - 1].children = true;
  }
  return [...new Map(out.map((r) => [r.path, r])).values()];
}

/** 菜单表 → 可见、启用、非外链的页面（类型 C），路径按父目录拼接 */
export function menuRoutes(menus: Menu[]): Route[] {
  const byId = new Map(menus.map((m) => [Number(m.menu_id), m]));
  const out: Route[] = [];
  for (const m of menus) {
    if (m.menu_type !== 'C' || String(m.status) !== '0' || String(m.visible) !== '0' || String(m.is_frame) === '0' || /^https?:/.test(m.path)) continue;
    const parts: string[] = [m.path];
    let p = byId.get(Number(m.parent_id));
    let hidden = false;
    while (p) {
      if (String(p.status) !== '0' || String(p.visible) !== '0') hidden = true;
      parts.unshift(p.path);
      p = byId.get(Number(p.parent_id));
    }
    if (hidden) continue;
    out.push({ path: '/' + parts.join('/').replace(/^\/+/, ''), name: m.menu_name, source: 'menu', area: parts[0] });
  }
  return out;
}

export interface PagesFile {
  pages: Page[];
  /** 不走查的路由（上游演示、外链等）及理由 */
  ignore?: { path: string; reason: string }[];
}

export function compare(routes: Route[], file: PagesFile): { unlisted: Route[]; stale: Page[]; ignored: number } {
  const listed = new Set(file.pages.map((p) => p.path));
  const ignore = (file.ignore ?? []).map((x) => x.path);
  const isIgnored = (p: string) => ignore.some((x) => (x.endsWith('/*') ? p.startsWith(x.slice(0, -1)) : p === x));
  const unlisted = routes.filter((r) => !listed.has(r.path) && !isIgnored(r.path));
  const known = new Set(routes.map((r) => r.path));
  const stale = file.pages.filter((p) => !known.has(p.path));
  return { unlisted, stale, ignored: routes.filter((r) => isIgnored(r.path)).length };
}

export function loadMenus(file?: string): Menu[] {
  if (file) return JSON.parse(fs.readFileSync(file, 'utf8'));
  const project = process.env.LSMOM_ACC_PROJECT ?? 'lsmom-acc';
  const sql = "select coalesce(json_agg(m), '[]') from (select menu_id, parent_id, menu_name, path, component, menu_type, visible, status, is_frame from sys_menu where menu_type in ('M','C')) m";
  const out = execFileSync('docker', ['compose', '-p', project, 'exec', '-T', 'postgres', 'sh', '-c', 'psql -q -At -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], {
    cwd: path.join(ROOT, 'deploy'),
    input: sql + ';\n',
    encoding: 'utf8'
  });
  return JSON.parse(out.trim());
}

export function allRoutes(menus: Menu[]): Route[] {
  const src = fs.readFileSync(path.join(ROOT, 'plus-ui/src/router/index.ts'), 'utf8');
  return [...new Map([...staticRoutes(src), ...menuRoutes(menus)].map((r) => [r.path, r])).values()].sort((a, b) => a.path.localeCompare(b.path));
}

const FILE = path.join(ACC, 'pages.yaml');

if (import.meta.main) {
  const args = process.argv.slice(2);
  const menusArg = args.includes('--menus') ? args[args.indexOf('--menus') + 1] : undefined;
  const routes = allRoutes(loadMenus(menusArg));
  const file: PagesFile = fs.existsSync(FILE) ? YAML.parse(fs.readFileSync(FILE, 'utf8')) : { pages: [], ignore: [] };
  if (args.includes('--init')) {
    const r = compare(routes, file);
    let n = file.pages.length;
    for (const x of r.unlisted) file.pages.push({ id: `P-${String(++n).padStart(3, '0')}`, path: x.path, name: x.name, area: x.area });
    fs.writeFileSync(FILE, YAML.stringify(file, { lineWidth: 0 }));
    console.log(`已追加 ${r.unlisted.length} 个页面到 pages.yaml（请补全 roles 与名称）`);
    process.exit(0);
  }
  const r = compare(routes, file);
  console.log(`路由 ${routes.length} 个（静态 ${routes.filter((x) => x.source === 'static').length}、菜单 ${routes.filter((x) => x.source === 'menu').length}），已登记 ${file.pages.length}，不走查 ${r.ignored}`);
  if (r.stale.length) console.log('已失效登记（没有对应路由）：\n  ' + r.stale.map((p) => `${p.id} ${p.path}`).join('\n  '));
  if (r.unlisted.length) {
    console.error('未走查页面（有路由但未登记到 pages.yaml）：\n  ' + r.unlisted.map((x) => `${x.path}（${x.name}，来自${x.source === 'menu' ? '菜单' : '静态路由'}）`).join('\n  '));
    process.exit(1);
  }
  console.log('页面清单与路由表一致');
}
