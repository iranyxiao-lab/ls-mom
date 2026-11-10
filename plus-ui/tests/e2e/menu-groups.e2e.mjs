/**
 * modernize-ui-ux 3.3 端到端（无头 Chromium，部署环境）：菜单固定分组与层级。
 *   管理员侧栏：顶层只有首页与「工作 / 台账 / 分析 / 配置」四个分组，层级不超过三级，上游演示目录与外链不出现；
 *   安装示例客户 Pack（经 ChangeSet 发布）后，其入口自动出现在「台账 → <Pack 名称>」下，点开即台账页；
 *   质检员只看到有权限的页面，看不到新 Pack 与配置类入口。
 * 运行：node tests/e2e/menu-groups.e2e.mjs（LSMOM_E2E_BASE 指定地址；只用于测试环境）
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE, check, open } from './lib.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const suffix = Date.now().toString(36);
const ns = 'acme.mnu' + suffix;
const packName = '访客登记' + suffix.slice(-4);

/** 复制示例 Pack，换成唯一命名空间与名称。 */
function build() {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'lsmom-menu-e2e-'));
  const tbl = 'c_' + ns.replace(/\./g, '_') + '_visit';
  const dir = path.join(work, ns);
  fs.cpSync(path.join(root, 'packs/examples/acme-visitor'), dir, { recursive: true });
  for (const f of fs.readdirSync(dir, { recursive: true })) {
    const p = path.join(dir, f);
    if (!fs.statSync(p).isFile()) continue;
    let s = fs
      .readFileSync(p, 'utf8')
      .replaceAll('acme.visitor', ns)
      .replaceAll('c_acme_visitor_visit', tbl)
      .replaceAll('idx_acme_visitor_visit_host', `idx_${tbl}_host`);
    if (f === 'pack.yaml') s = s.replace(/^name: .*$/m, `name: ${packName}`);
    fs.writeFileSync(p, s);
  }
  const zip = path.join(work, `${ns}.zip`);
  execFileSync('python3', [path.join(root, 'scripts/pack-build.py'), dir, zip]);
  return zip;
}

/** 读侧栏菜单树（标题与层级），不依赖展开状态。 */
const menuTree = (page) =>
  page.evaluate(() => {
    const walk = (ul) =>
      // 菜单项外面包着一层 role="none" 的 div（SidebarItem）
      [...ul.querySelectorAll(':scope > li, :scope > div > li')]
        .filter((li) => li.matches('li.el-menu-item, li.el-sub-menu'))
        .map((li) => {
          const title = (li.matches('.el-sub-menu') ? li.querySelector(':scope > .el-sub-menu__title') : li)?.textContent?.trim() ?? '';
          const sub = li.querySelector(':scope > ul.el-menu');
          return sub ? { title, children: walk(sub) } : { title };
        });
    return walk(document.querySelector('.sidebar-container ul.el-menu'));
  });
const depth = (nodes) => nodes.reduce((d, n) => Math.max(d, 1 + depth(n.children ?? [])), 0);
const flat = (nodes, trail = []) => nodes.flatMap((n) => [[...trail, n.title].join(' → '), ...flat(n.children ?? [], [...trail, n.title])]);

/** 展开全部子菜单（Element Plus 只在展开后渲染下级），再读菜单树。 */
async function fullTree(page) {
  for (let round = 0; round < 3; round++) {
    const closed = page.locator('.sidebar-container li.el-sub-menu:not(.is-opened) > .el-sub-menu__title');
    const n = await closed.count();
    if (!n) break;
    for (let i = 0; i < n; i++) await closed.first().click();
  }
  return menuTree(page);
}

const admin = await open({ width: 1440, height: 2400 });
const qa = await open({ user: 'demo_quality', width: 1440, height: 2400, browser: admin.browser });
try {
  // —— 安装示例客户 Pack（与正式发布同一条 ChangeSet 路径） ——
  const zip = build();
  const cs = (await admin.api('POST', '/api/changesets', { archive: fs.readFileSync(zip).toString('base64') })).data;
  for (const stage of ['SANDBOX', 'REGRESSION', 'PRIVILEGE', 'UPGRADE']) {
    await admin.api('POST', `/api/changesets/${cs.id}/stages`, {
      stage,
      status: 'PASSED',
      artifactSha: cs.artifactSha,
      detail: { note: '示例 Pack 已演练' }
    });
  }
  await admin.api('POST', `/api/changesets/${cs.id}/approve`, { approve: true, note: '菜单分组测试' });
  check((await admin.api('POST', `/api/changesets/${cs.id}/release`)).code === 200, `发布示例 Pack ${ns}（${packName}）`);

  await admin.page.goto(BASE + '/index');
  await admin.page.waitForLoadState('networkidle');
  // 菜单可能较多：展开前先等侧栏渲染
  await admin.page.locator('.sidebar-container ul.el-menu').first().waitFor();
  const tree = await fullTree(admin.page);
  const paths = flat(tree).filter((p) => !p.startsWith('收藏'));
  check(
    // 有收藏时「收藏」分组排在首页之后（偏好中心 3.6）
    JSON.stringify(tree.map((n) => n.title).filter((t) => t !== '收藏')) === JSON.stringify(['首页', '工作', '台账', '分析', '配置']),
    '顶层：首页＋（收藏）＋工作 / 台账 / 分析 / 配置'
  );
  check(depth(tree) <= 3, `菜单层级不超过三级（实际 ${depth(tree)} 级）`);
  for (const hidden of ['测试菜单', 'PLUS官网']) check(!paths.some((p) => p.includes(hidden)), `不显示上游${hidden}`);
  check(paths.includes(`台账 → ${packName} → 访客台账`), `新 Pack 入口自动归入「台账 → ${packName} → 访客台账」`);
  for (const p of [
    '台账 → 设备 → 设备台账',
    '台账 → 质量 → 质量记录',
    '分析 → 轻生产 → 经营看板',
    '工作 → 轻生产 → 生产需求板',
    '配置 → 系统管理 → 用户管理',
    '工作 → 协同工作中心',
    '配置 → 协同应用中心'
  ])
    check(paths.includes(p), `菜单中有「${p}」`);

  // 侧栏同一时间只展开一个分组：依次展开「台账」→ Pack 目录，再点页面
  const side = admin.page.locator('.sidebar-container');
  const openSub = async (title) => {
    const li = side.locator('li.el-sub-menu', { has: admin.page.locator(':scope > .el-sub-menu__title', { hasText: title }) }).first();
    if (!(await li.evaluate((e) => e.classList.contains('is-opened')))) await li.locator(':scope > .el-sub-menu__title').click();
    return li;
  };
  await openSub('台账');
  const packDir = await openSub(packName);
  await packDir.locator('li.el-menu-item', { hasText: '访客台账' }).click();
  await admin.page.waitForURL((u) => u.pathname === `/lsmom/ledger/${ns}.Visit`, { timeout: 15000 });
  check(true, '点击新 Pack 菜单打开其台账页');
  check(admin.errors.length === 0, '管理员页面无前端异常');

  // —— 质检员：只看到有权限的页面 ——
  await qa.page.goto(BASE + '/index');
  await qa.page.locator('.sidebar-container ul.el-menu').first().waitFor();
  const qaPaths = flat(await fullTree(qa.page));
  check(qaPaths.includes('工作 → 质量 → 不符合项') && qaPaths.includes('台账 → 质量 → 质量记录'), '质检员看到质量工作与质量台账');
  check(!qaPaths.some((p) => p.includes(packName)), '质检员没有新 Pack 台账权限，看不到其菜单');
  check(!qaPaths.some((p) => /协同应用中心|表单设计器|系统管理/.test(p)), '质检员看不到配置类入口');
  check(depth(await menuTree(qa.page)) <= 3, '质检员菜单同样不超过三级');
  check(qa.errors.length === 0, '质检员页面无前端异常');
} finally {
  await admin.browser.close();
}
