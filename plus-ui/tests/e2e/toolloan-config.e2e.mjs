/**
 * 14.3 端到端（无头 Chromium，部署环境，演示账号）：只用配置交付"工器具借还"台账（CRUD-01 不写 Java、FORM-07 旧单按旧版渲染）。
 *   实施配置（管理员）：按钮权限授予角色 → 上传 packs/examples/acme-toolloan（YAML/JSON）为 ChangeSet → 升级演练 → 批准 → 发布；
 *   吴班长（班组长）在通用台账页新增借还单：主子表、借用人/设备引用、附件；外协缺外协单位被前端与后端拦下 → 补齐后提交审核；
 *   周主任（车间主任）退回 → 吴班长在待办中修改预计归还后再提交 → 周主任审核（必填审核意见）→ 王工（设备员）确认归档；
 *   列表按用途筛选、导出并下载；导入预检报告错误行，修正后确认导入；
 *   修改布局发布表单 v2：旧单按 v1 只读显示并提示，新建单使用 v2。
 * 运行：node tests/e2e/toolloan-config.e2e.mjs（LSMOM_E2E_BASE 指定环境，LSMOM_E2E_PROJECT 指定 Compose 项目；只用于测试环境）
 */
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE, check, open } from './lib.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const PROJECT = process.env.LSMOM_E2E_PROJECT ?? 'lsmom-verify';
// LSMOM_E2E_TL_SUFFIX：复用已发布的同名配置包（排查时跳过演练）
const reuse = process.env.LSMOM_E2E_TL_SUFFIX;
const suffix = reuse ?? Date.now().toString(36);
const ns = 'acme.tl' + suffix;
const run = Date.now().toString(36);
const ORDER = ns + '.LoanOrder';
const FORM = ns + '.loanForm';
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'lsmom-toolloan-e2e-'));
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

/** 示例配置包换成本次唯一的命名空间后打包。 */
function buildPack() {
  const dir = path.join(work, ns);
  fs.cpSync(path.join(root, 'packs/examples/acme-toolloan'), dir, { recursive: true });
  const names = fs.readdirSync(dir, { recursive: true }).map(String);
  check(
    names.every((f) => fs.statSync(path.join(dir, f)).isDirectory() || /\.(ya?ml|json|md)$/.test(f)),
    'CRUD-01：配置包只有 YAML/JSON（' + names.filter((f) => /\./.test(f)).length + ' 个文件，无 Java）'
  );
  for (const f of names) {
    const p = path.join(dir, f);
    if (fs.statSync(p).isFile()) fs.writeFileSync(p, fs.readFileSync(p, 'utf8').replaceAll('acme.toolloan', ns));
  }
  const zip = path.join(work, ns + '.zip');
  execFileSync('python3', [path.join(root, 'scripts/pack-build.py'), dir, zip]);
  return zip;
}

function drill(id) {
  const r = spawnSync(path.join(root, 'deploy/scripts/upgrade-drill.sh'), ['--changeset', String(id), '--backup-from', PROJECT], {
    env: { ...process.env, LSMOM_E2E_BASE: BASE },
    encoding: 'utf8',
    timeout: 20 * 60 * 1000
  });
  return { code: r.status, out: (r.stdout ?? '') + (r.stderr ?? '') };
}

const ok = async (p, what) => {
  const r = await p;
  if (r.code !== 200) throw new Error(`${what}失败：${r.msg}`);
  return r.data;
};

const admin = await open();
try {
  // —— 1. 实施配置：按钮权限与角色授权（系统管理 → 菜单 / 角色） ——
  const menus = (await ok(admin.api('GET', '/system/menu/list'), '读菜单')).filter((m) => m.menuType !== 'F' || m.perms);
  const ensureMenu = async (menu) => {
    const found = menus.find((m) => (menu.perms ? m.perms === menu.perms : m.menuName === menu.menuName && m.parentId === menu.parentId));
    if (found) return found.menuId;
    await ok(
      admin.api('POST', '/system/menu', { orderNum: 9, isFrame: '1', isCache: '0', visible: '0', status: '0', icon: '#', path: '', ...menu }),
      '新增菜单 ' + menu.menuName
    );
    const all = await ok(admin.api('GET', '/system/menu/list?menuName=' + encodeURIComponent(menu.menuName)), '读菜单');
    const m = all.find((x) => x.menuName === menu.menuName && x.parentId === menu.parentId);
    menus.push(m);
    return m.menuId;
  };
  const dirId = await ensureMenu({ parentId: 0, menuName: '工器具借还配置', menuType: 'M', path: 'toolloan-config' });
  const button = (name, perms) => ensureMenu({ parentId: dirId, menuName: name, menuType: 'F', perms });
  const submitBtn = await button('借还单提交', 'acme:toolloan:submit');
  const flowBtn = await button('借还单审批', 'acme:toolloan:flow');
  const crudBtn = {};
  for (const a of ['list', 'query', 'add', 'edit', 'export', 'import']) crudBtn[a] = await button(`借还单 ${suffix} ${a}`, `crud:${ORDER}:${a}`);
  const grant = async (roleKey, ids) => {
    const list = await admin.api('GET', '/system/role/list?pageNum=1&pageSize=100&roleKey=' + roleKey);
    const roleId = list.rows.find((r) => r.roleKey === roleKey).roleId;
    const full = await ok(admin.api('GET', '/system/role/' + roleId), '读角色详情');
    const tree = await ok(admin.api('GET', '/system/menu/roleMenuTreeselect/' + roleId), '读角色菜单');
    // checkedKeys 在父子联动模式下不含半选的上级目录：按菜单树补齐全部上级，避免回写后丢失「设备」「质量」等目录
    const want = new Set([...tree.checkedKeys, dirId, ...ids].map(String)); // 雪花 ID 超出 Number 精度，按字符串比较
    const withParents = new Set(want);
    const walk = (nodes, trail) => {
      for (const n of nodes ?? []) {
        if (want.has(String(n.id))) trail.forEach((t) => withParents.add(t));
        walk(n.children, [...trail, String(n.id)]);
      }
    };
    walk(tree.menus, []);
    await ok(
      admin.api('PUT', '/system/role', { ...full, menuIds: [...withParents], menuCheckStrictly: full.menuCheckStrictly }),
      '授权角色 ' + roleKey
    );
  };
  await grant('team_leader', [submitBtn, ...Object.values(crudBtn)]);
  await grant('workshop_leader', [flowBtn, crudBtn.list, crudBtn.query]);
  await grant('asset_keeper', [flowBtn, crudBtn.list, crudBtn.query]);
  check(true, '实施配置：提交、审批、台账按钮权限授予班组长、车间主任、设备员');

  // —— 2. 配置包经 ChangeSet：演练 → 批准 → 发布 ——
  if (!reuse) {
    const zip = buildPack();
    const cs = await ok(admin.api('POST', '/api/changesets', { archive: fs.readFileSync(zip).toString('base64') }), '提交 ChangeSet');
    const d = drill(cs.id);
    if (d.code !== 0) console.log(d.out);
    check(d.code === 0 && d.out.includes('门禁通过'), `升级演练通过（ChangeSet #${cs.id}）`);
    await ok(admin.api('POST', `/api/changesets/${cs.id}/approve`, { approve: true, note: '借还台账配置评审通过' }), '批准');
    const rel = await ok(admin.api('POST', `/api/changesets/${cs.id}/release`), '发布');
    check(['INSTALLED', 'UPGRADED'].includes(rel.status), `配置包发布：${ns} ${rel.status}`);
  }
  const orgs = await ok(admin.api('GET', '/api/asset/orgs'), '读组织');
  const teamA = orgs.find((o) => o.name === '一车间甲班');
  const asset = await ok(
    admin.api('POST', '/api/crud/ls.asset.Asset', { assetCode: `TL-${run}`, name: `焊机 ${run}`, assetType: 'STATION', orgId: teamA.id }),
    '准备设备'
  );

  // —— 3. 班组长新增借还单（演示账号在授权后登录） ——
  const op = await open({ user: 'demo_team', browser: admin.browser });
  const leader = await open({ user: 'demo_leader', browser: admin.browser });
  const keeper = await open({ user: 'demo_asset', browser: admin.browser });
  const p = op.page;
  const pick = async (page, testId, text, typed) => {
    const sel = page.getByTestId(testId);
    await sel.click();
    if (typed) await sel.locator('input').first().fill(typed);
    await page.locator('.el-select-dropdown__item:visible', { hasText: text }).first().click();
    await page.keyboard.press('Escape').catch(() => undefined);
  };
  await p.goto(`${BASE}/lsmom/ledger/${ORDER}`);
  await p.getByTestId('create').click();
  const ed = p.getByTestId('ledger-editor');
  await ed.getByTestId('input-loanNo').fill(`TL-${run}-1`);
  await pick(p, 'input-borrower', '郑师傅', '郑');
  await ed.getByTestId('field-loanDate').locator('input').fill('2026-11-10');
  await ed.getByTestId('field-loanDate').locator('input').press('Enter');
  // Enter 跳到下一项（预计归还）时其日期面板会展开，先收起
  await p.keyboard.press('Escape');
  await pick(p, 'input-purpose', '外协');
  await pick(p, 'input-asset', `焊机 ${run}`, `焊机 ${run}`);
  await ed.getByTestId('add-row-loanLine').click();
  const line = ed.getByTestId('subtable-loanLine');
  await line.getByTestId('input-tool').first().fill('扭力扳手');
  await line.getByTestId('input-qty').first().locator('input').fill('2');
  await ed
    .getByTestId('field-attachments')
    .locator('input[type="file"]')
    .setInputFiles({ name: `借用照片-${run}.png`, mimeType: 'image/png', buffer: png });
  await ed.getByTestId('field-attachments').getByText(`借用照片-${run}.png`).waitFor({ timeout: 15000 });
  await ed.getByTestId('btn-form-submit').click();
  await ed.locator('.el-form-item__error, .ls-field__error, .schema-form__alert', { hasText: '外协单位' }).first().waitFor({ timeout: 5000 });
  check(true, '条件必填：用途为外协时前端标出外协单位必填');
  const bypass = await op.api('POST', `/api/crud/${encodeURIComponent(ORDER)}`, {
    loanNo: `TL-${run}-X`,
    borrower: (await op.api('GET', '/api/flow/me')).data.partyId,
    loanDate: '2026-11-10',
    purpose: 'OUTSOURCE',
    loanLine: [{ tool: '扳手', qty: 1 }]
  });
  check(bypass.code !== 200 && String(bypass.msg).includes('外协单位'), `条件必填：直接调接口也被后端拒绝（${bypass.msg}）`);
  await ed.getByTestId('input-outsourceUnit').fill('华东检测站');
  await ed.getByTestId('btn-form-submit').click();
  await p.locator('.el-message--success', { hasText: '已提交审核' }).waitFor({ timeout: 15000 });
  const find = async (s, loanNo) =>
    (
      await ok(
        s.api('POST', `/api/crud/${encodeURIComponent(ORDER)}/query`, {
          filters: [{ field: 'loanNo', op: 'eq', value: loanNo }],
          page: 1,
          size: 1,
          expand: ['asset', 'borrower']
        }),
        '查询借还单'
      )
    ).rows[0];
  let rec = await find(op, `TL-${run}-1`);
  const full = await ok(op.api('GET', `/api/crud/${encodeURIComponent(ORDER)}/${rec.id}`), '读借还单');
  check(
    rec.status === 'IN_REVIEW' &&
      full.loanLine?.length === 1 &&
      full.attachments?.length === 1 &&
      String(rec.borrowerRef?.label).startsWith('郑师傅') &&
      rec.asset === asset.id,
    '主子表、借用人与设备引用、附件一并保存，提交后进入审核'
  );
  const base = (await ok(op.api('GET', `/api/form/views/${encodeURIComponent(FORM)}`), '读表单')).version;
  check(String(full.schemaVersion) === String(base), `记录冻结表单 v${base}（新发布配置包为 v1）`);
  if (!reuse) check(base === 1, '首次发布的表单为 v1');

  // —— 4. 审批：退回 → 修改后再提交 → 审核 → 确认归档 ——
  const openTask = async (s, node) => {
    let t;
    for (let i = 0; i < 30 && !t; i++) {
      t = (await ok(s.api('GET', '/api/flow/todo'), '读待办')).find((x) => x.bizId === String(rec.id) && x.nodeKey === node && x.status === 'TODO');
      if (!t) await new Promise((r) => setTimeout(r, 300));
    }
    if (!t) throw new Error('没有待办：' + node);
    await s.page.goto(`${BASE}/lsmom/workbench`);
    await s.page
      .locator('[data-testid="table-todo"] .el-table__body tr')
      .filter({ hasText: `TL-${run}-1` })
      .first()
      .click();
    await s.page.getByTestId('snapshot').waitFor({ timeout: 15000 });
    return t;
  };
  const l = leader.page;
  await openTask(leader, 'deptAudit');
  await l.getByTestId('task-comment').fill('请补充预计归还日期');
  await l.getByTestId('btn-return').click();
  await l.locator('.el-message--success', { hasText: '已退回' }).waitFor({ timeout: 15000 });
  check((await find(op, `TL-${run}-1`)).status === 'RETURNED', '部门审核退回发起人');

  await openTask(op, 'initiator');
  const tf = p.getByTestId('task-form');
  check((await tf.getByTestId('text-loanNo').count()) === 1, '退回修改：单号只读');
  await tf.getByTestId('field-returnDue').locator('input').fill('2026-11-20');
  await tf.getByTestId('field-returnDue').locator('input').press('Enter');
  await p.getByTestId('task-comment').fill('已补充预计归还日期');
  await p.getByTestId('btn-resubmit').click();
  await p.locator('.el-message--success', { hasText: '已再提交' }).waitFor({ timeout: 15000 });
  rec = await find(op, `TL-${run}-1`);
  check(rec.status === 'IN_REVIEW' && rec.returnDue === '2026-11-20', '发起人修改预计归还后再提交，回到审核');

  await openTask(leader, 'deptAudit');
  await l.getByTestId('btn-approve').click();
  await l.locator('.el-notification, .el-message--error, .el-message--warning').first().waitFor({ timeout: 10000 });
  check(true, '审核节点未填审核意见被拦');
  await l.locator('.el-notification, .el-message').evaluateAll((els) => els.forEach((e) => e.remove()));
  await l.getByTestId('task-form').getByTestId('input-auditNote').fill('同意外协借用，按期归还');
  await l.getByTestId('btn-approve').click();
  await l.locator('.el-message--success', { hasText: '已通过' }).waitFor({ timeout: 15000 });

  await openTask(keeper, 'confirm');
  await keeper.page.getByTestId('task-comment').fill('已核对实物');
  await keeper.page.getByTestId('btn-approve').click();
  await keeper.page.locator('.el-message--success', { hasText: '已通过' }).waitFor({ timeout: 15000 });
  rec = await find(op, `TL-${run}-1`);
  check(rec.status === 'ARCHIVED' && rec.auditNote === '同意外协借用，按期归还', '设备员确认归档，审核意见写入');
  const tl = (await ok(op.api('GET', `/api/timeline/${encodeURIComponent(ORDER)}/${rec.id}`), '读时间轴')).map((e) => e.type);
  check(
    ['ls.flow.task.returned', 'ls.flow.task.resubmitted'].every((t) => tl.includes(t)),
    '时间轴记录退回与再提交'
  );
  const edit = await op.api('PUT', `/api/crud/${encodeURIComponent(ORDER)}/${rec.id}?version=${rec.version}`, { remark: '改' });
  check(edit.code !== 200, `已归档记录不能通用编辑（${edit.msg}）`);

  // —— 5. 列表筛选、导出下载、导入预检 ——
  await p.goto(`${BASE}/lsmom/ledger/${ORDER}`);
  await pick(p, 'filter-purpose', '外协');
  await p.getByTestId('search').click();
  await p.waitForTimeout(800);
  const rowsShown = await p.locator('[data-testid="table"] .el-table__body tr').count();
  check(
    rowsShown >= 1 && (await p.locator('[data-testid="table"] .el-table__body tr').first().textContent()).includes(`TL-${run}-1`),
    '列表按用途筛选'
  );
  await p.getByTestId('export').click();
  await p.getByTestId('export-download').waitFor({ timeout: 30000 });
  const [dl] = await Promise.all([p.waitForEvent('download'), p.getByTestId('export-download').click()]);
  const exported = path.join(work, 'export.xlsx');
  await dl.saveAs(exported);
  check(fs.statSync(exported).size > 0 && fs.readFileSync(exported).subarray(0, 2).toString() === 'PK', '导出完成并下载 xlsx');

  const me = (await op.api('GET', '/api/flow/me')).data.partyId;
  const xlsx = (name, rows) => {
    const f = path.join(work, name);
    execFileSync('python3', [path.join(here, 'make-xlsx.py'), f, JSON.stringify(rows)]);
    return f;
  };
  const head = ['借用单号*', '借用人*', '借出日期*', '用途*', '外协单位'];
  await p.getByTestId('import').click();
  const [tpl] = await Promise.all([p.waitForEvent('download'), p.getByTestId('import-template').click()]);
  check(/导入模板\.xlsx$/.test(tpl.suggestedFilename()), '下载导入模板');
  await p
    .getByTestId('import-file')
    .setInputFiles(
      xlsx('bad.xlsx', [
        head,
        [`TL-${run}-I1`, String(me), '2026-11-11', 'MAINTENANCE', ''],
        [`TL-${run}-I2`, String(me), '2026-11-11', 'OUTSOURCE', '']
      ])
    );
  await p.getByTestId('import-summary').filter({ hasText: '错误 1 行' }).waitFor({ timeout: 15000 });
  check(
    (await p.getByTestId('import-errors').textContent()).includes('外协单位') && (await p.getByTestId('import-confirm').isDisabled()),
    '导入预检：外协行缺外协单位报错，不能确认'
  );
  await p
    .getByTestId('import-file')
    .setInputFiles(
      xlsx('good.xlsx', [
        head,
        [`TL-${run}-I1`, String(me), '2026-11-11', 'MAINTENANCE', ''],
        [`TL-${run}-I2`, String(me), '2026-11-11', 'OUTSOURCE', '华东检测站']
      ])
    );
  await p.getByTestId('import-summary').filter({ hasText: '可导入 2 行' }).waitFor({ timeout: 15000 });
  await p.getByTestId('import-confirm').click();
  await p.locator('.el-message--success', { hasText: '已导入 2 条' }).waitFor({ timeout: 15000 });
  check((await find(op, `TL-${run}-I2`))?.outsourceUnit === '华东检测站', '修正后确认导入 2 条');

  // —— 6. FORM-07：改布局发布 v2，旧单按 v1 渲染 ——
  const v2 = await ok(admin.api('POST', `/api/form/views/${encodeURIComponent(FORM)}/derive`), '派生表单');
  const schema = v2.schema;
  schema.layout.unshift({ type: 'text', text: `借用前请核对工器具编号 ${run}` });
  await ok(admin.api('POST', '/api/form/drafts', { schema, note: '调整布局' }), '保存草稿');
  const pub = await ok(admin.api('POST', `/api/form/views/${encodeURIComponent(FORM)}/${v2.version}/publish`), '发布表单');
  check(pub.status === 'APPLIED' && v2.version === base + 1, `改布局后发布生成表单 v${v2.version}`);
  await p.goto(`${BASE}/lsmom/ledger/${ORDER}`);
  await p
    .locator('[data-testid="table"] .el-table__body tr')
    .filter({ hasText: `TL-${run}-1` })
    .first()
    .click();
  const det = p.getByTestId('ledger-detail');
  await det.getByTestId('frozen-hint').waitFor({ timeout: 15000 });
  check(
    (await det.getByTestId('frozen-hint').textContent()).includes(`v${base}`) &&
      (await det.getByTestId('frozen-hint').textContent()).includes(`v${v2.version}`) &&
      (await det.getByTestId('detail-form-version').textContent()).includes(`v${base}`) &&
      !(await det.textContent()).includes(`借用前请核对工器具编号 ${run}`),
    `FORM-07：旧单按 v${base} 布局只读显示，并提示最新为 v${v2.version}`
  );
  await p.keyboard.press('Escape');
  await p.getByTestId('create').click();
  await ed.getByText(`借用前请核对工器具编号 ${run}`).waitFor({ timeout: 15000 });
  check(true, `新建单使用表单 v${v2.version} 布局`);
  for (const s of [op, leader, keeper]) check(s.errors.length === 0, '页面无脚本错误：' + s.errors.join('；'));
} finally {
  await admin.browser.close();
  fs.rmSync(work, { recursive: true, force: true });
}
