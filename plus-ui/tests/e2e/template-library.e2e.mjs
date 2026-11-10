/**
 * modernize-ui-ux 8.1 端到端（无头 Chromium，部署环境）：从模板新建台账应用。
 *   管理员在协同应用中心"从模板新建" → 选"借还登记" → 填名称、编码、负责人与角色 → 预览生成的 Pack 文件 →
 *   授权并生成草稿：ChangeSet 直接通过发布校验（VALIDATE）→ 升级演练 → 批准 → 发布；
 *   吴班长新增借还单并提交 → 周主任（数据范围覆盖班组）部门审核 → 设备员确认归档 → 单据"已通过"。
 * 运行：node tests/e2e/template-library.e2e.mjs（只用于测试环境）
 */
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE, check, open } from './lib.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const PROJECT = process.env.LSMOM_E2E_PROJECT ?? 'lsmom-verify';
const run = Date.now().toString(36);
const ns = 'cust.loan' + run;
const ORDER = ns + '.LoanOrder';

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
const sessions = [admin];
try {
  const { page } = admin;
  // —— 1. 向导：选模板 → 基本信息 → 预览 → 生成草稿 ——
  await page.goto(BASE + '/lsmom/apps');
  await page.locator('#tab-changes').click();
  await page.getByTestId('btn-from-template').click();
  const wz = page.getByTestId('tpl-wizard');
  await wz.getByTestId('tpl-list').waitFor();
  const cards = await wz.locator('[data-testid^="tpl-"].tw-card').allTextContents();
  check(
    ['借还登记', '点检表', '整改单', '简单审批'].every((n) => cards.some((c) => c.includes(n))),
    '模板库：借还登记、点检表、整改单、简单审批'
  );
  await wz.getByTestId('tpl-loan').click();
  await wz.getByTestId('tpl-info').waitFor();
  await wz.getByTestId('tpl-title').fill(`借还登记 ${run}`);
  await wz.getByTestId('tpl-ns').fill(ns);
  const pickRole = async (testid, label) => {
    await wz.getByTestId(testid).click();
    await page.locator('.el-select-dropdown:visible .el-select-dropdown__item', { hasText: label }).first().click();
  };
  // 登记角色默认带出班组长；节点角色沿用模板（部门审核：车间主任，确认归档：设备员）
  const starters = await wz.getByTestId('tpl-starters').textContent();
  check(/班组长/.test(starters ?? ''), '登记角色默认班组长：' + starters?.trim());
  await pickRole('tpl-node-confirm', '设备员');
  await wz.getByTestId('tpl-next').click();
  await wz.getByTestId('tpl-preview').waitFor();
  const fileNames = await wz.locator('[data-testid^="tpl-file-"]:not([data-testid="tpl-file-editor"])').allTextContents();
  check(
    [
      'pack.yaml',
      'concepts/loan-order.yaml',
      'operations/loan-order-lifecycle.yaml',
      'views/loan-order-form.json',
      'views/loan-order-list.json',
      'flows/loan-order-approval.json'
    ].every((f) => fileNames.some((n) => n.trim().startsWith(f))),
    '预览生成的文件：清单、Concept、状态机、表单、列表、审批流（' + fileNames.length + ' 个）'
  );
  const manifest = await wz.getByTestId('tpl-file-editor').inputValue();
  check(manifest.includes('ls.asset') && manifest.includes('owner:') && manifest.includes(ns), 'pack.yaml 含依赖 ls.asset、负责人与编码');
  // 在预览里直接改文件：表单标题
  await wz.getByTestId('tpl-file-views/loan-order-form.json').click();
  const editor = wz.getByTestId('tpl-file-editor');
  const form = await editor.inputValue();
  await editor.fill(form.replace('"title" : "借还单"', '"title" : "借还单（模板）"'));
  const grants = await wz.getByTestId('tpl-grants').textContent();
  check(grants?.includes(ns.replace('.', ':') + ':flow') && grants.includes('车间主任'), '授权清单：审批办理权限授予节点角色');
  await wz.getByTestId('tpl-create').click();
  await page.locator('.el-message--success', { hasText: '已生成草稿' }).first().waitFor({ timeout: 60000 });
  await wz.waitFor({ state: 'hidden' });

  const cs = (await ok(admin.api('GET', `/api/changesets?pack=${encodeURIComponent(ns)}`), '读 ChangeSet'))[0];
  check(cs && cs.status === 'DRAFT' && cs.source === 'TEMPLATE', `草稿 ChangeSet #${cs?.id}（来源模板）`);
  check(
    cs.stages.some((s) => s.stage === 'VALIDATE' && s.status === 'PASSED'),
    '借还模板生成的草稿直接通过发布校验'
  );
  await page.getByTestId(`stage-${cs.id}-VALIDATE`).waitFor();

  // —— 2. 演练 → 批准 → 发布 ——
  const d = drill(cs.id);
  if (d.code !== 0) console.log(d.out.slice(-4000));
  check(d.code === 0 && d.out.includes('门禁通过'), `升级演练通过（沙箱、回归、越权、升级）`);
  await ok(admin.api('POST', `/api/changesets/${cs.id}/approve`, { approve: true, note: '模板生成台账评审通过' }), '批准');
  const rel = await ok(admin.api('POST', `/api/changesets/${cs.id}/release`), '发布');
  check(rel.status === 'INSTALLED', `发布 ${ns}：${rel.status}`);
  const views = await ok(admin.api('GET', '/api/form/views?type=FORM'), '读表单列表');
  const fv = views.find((v) => v.code === ns + '.loanOrderForm');
  check(fv && (fv.displayName ?? fv.title) === '借还单（模板）', '预览中修改的表单标题随草稿发布：' + (fv?.displayName ?? fv?.title))

  // —— 3. 业务闭环：登记 → 部门审核 → 确认归档 ——
  const team = await open({ user: 'demo_team', browser: admin.browser });
  sessions.push(team);
  const me = await ok(team.api('GET', '/api/flow/me'), '读当前人员');
  const created = await ok(
    team.api('POST', `/api/crud/${encodeURIComponent(ORDER)}`, {
      loanNo: `TPL-${run}`,
      borrower: Number(me.partyId),
      loanDate: '2026-11-11',
      purpose: 'MAINTENANCE',
      loanLine: [{ item: '扭力扳手', qty: 2 }]
    }),
    '班组长新增借还单'
  );
  await ok(team.api('POST', `/api/op/${ORDER}.submit`, { targetId: String(created.id), version: created.version, input: {} }), '提交审核');
  check(true, `班组长新增并提交借还单 #${created.id}`);

  const waitTask = async (s, what) => {
    for (let n = 0; n < 40; n++) {
      const t = (await s.api('GET', '/api/flow/todo')).data?.find(
        (x) => x.bizType === ORDER && String(x.bizId) === String(created.id) && x.status === 'TODO'
      );
      if (t) return t;
      await new Promise((r) => setTimeout(r, 300));
    }
    throw new Error(what + '没有收到待办');
  };
  const leader = await open({ user: 'demo_leader', browser: admin.browser });
  sessions.push(leader);
  const t1 = await waitTask(leader, '车间主任');
  await ok(
    leader.api('POST', `/api/flow/tasks/${t1.taskId}/complete`, { action: 'APPROVE', comment: '同意', input: { auditNote: '同意借用' } }),
    '部门审核'
  );
  check(true, '车间主任（数据范围覆盖班组）收到并办理部门审核');
  const keeper = await open({ user: 'demo_asset', browser: admin.browser });
  sessions.push(keeper);
  const t2 = await waitTask(keeper, '设备员');
  await ok(keeper.api('POST', `/api/flow/tasks/${t2.taskId}/complete`, { action: 'APPROVE', comment: '已归档' }), '确认归档');
  let status = '';
  for (let n = 0; n < 30 && status !== 'APPROVED'; n++) {
    status = (await ok(team.api('GET', `/api/crud/${encodeURIComponent(ORDER)}/${created.id}`), '读单据')).status;
    if (status !== 'APPROVED') await new Promise((r) => setTimeout(r, 300));
  }
  check(status === 'APPROVED', '设备员确认归档后单据为"已通过"');
  const errs = sessions.flatMap((s) => s.errors);
  check(errs.length === 0, '页面无脚本错误' + (errs.length ? '：' + errs.join(' | ') : ''));
} finally {
  await admin.browser.close();
}
