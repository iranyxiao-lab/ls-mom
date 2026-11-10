/**
 * 19.3 / 19.4 第一步（在"上一版本"环境）：经 ChangeSet（升级演练 → 批准 → 发布）安装标准 Pack 与客户扩展 Pack，
 * 登记带客户字段的记录、发起一条停在部门审核的借还审批（在途流程）、上传一份附件；把要核对的内容写到 LSMOM_E2E_STATE。
 */
import { execFileSync, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { open, check, BASE, API, CLIENT_ID } from './lib.mjs';
import { root, buildStd, buildCust, b64 } from './upgrade-lib.mjs';

const PROJECT = process.env.LSMOM_E2E_PROJECT ?? 'lsmom-verify';
const STATE = process.env.LSMOM_E2E_STATE;
if (!STATE) throw new Error('需要 LSMOM_E2E_STATE');
const psql = (sql) =>
  execFileSync('docker', ['compose', '-p', PROJECT, 'exec', '-T', 'postgres', 'psql', '-U', 'lsmom', '-d', 'lsmom', '-Atc', sql], {
    cwd: path.join(root, 'deploy'),
    encoding: 'utf8'
  }).trim();
const ok = async (p, what) => {
  const r = await p;
  if (r.code !== 200) throw new Error(`${what}失败：${r.msg}`);
  return r.data;
};

const sfx = Date.now().toString(36);
const S = 'acme.us' + sfx;
const C = 'acme.uc' + sfx;
const admin = await open();
const team = await open({ user: 'demo_team', browser: admin.browser });
const leader = await open({ user: 'demo_leader', browser: admin.browser });
try {
  // 1. 标准 Pack 与客户 Pack 经 ChangeSet 发布（每个都跑升级演练）
  for (const [code, zip] of [[S, buildStd(S, '1.0.0')], [C, buildCust(C, S, '1.0.0')]]) {
    const cs = await ok(admin.api('POST', '/api/changesets', { archive: b64(zip) }), '提交 ChangeSet ' + code);
    const d = spawnSync(path.join(root, 'deploy/scripts/upgrade-drill.sh'), ['--changeset', String(cs.id), '--backup-from', PROJECT], {
      env: { ...process.env, LSMOM_E2E_BASE: BASE }, encoding: 'utf8', timeout: 20 * 60 * 1000
    });
    if (d.status !== 0) console.log(d.stdout + d.stderr);
    check(d.status === 0, `升级演练通过：${code}（ChangeSet #${cs.id}）`);
    await ok(admin.api('POST', `/api/changesets/${cs.id}/approve`, { approve: true, note: '升级验收准备' }), '批准');
    const rel = await ok(admin.api('POST', `/api/changesets/${cs.id}/release`), '发布');
    check(rel.status === 'INSTALLED', `发布 ${code} 1.0.0`);
  }

  // 2. 带客户字段的记录
  const visit = await ok(admin.api('POST', `/api/crud/${encodeURIComponent(S + '.Visit')}`, {
    visitorName: '升级前访客', company: '老客户', hostName: '周主任', visitDate: '2026-11-10', plateNo: '粤B88888', escort: '吴班长',
    orgId: Number(psql(`select id from core_org_unit where code = 'D2000'`))
  }), '登记访客');
  const saved = await ok(admin.api('GET', `/api/crud/${encodeURIComponent(S + '.Visit')}/${visit.id}`), '读访客');
  check(saved.plateNo === '粤B88888' && saved.escort === '吴班长', '客户字段写入');

  // 3. 在途流程：借还单停在部门审核
  const perms = psql(`select m.perms from sys_menu m join sys_role_menu rm on rm.menu_id = m.menu_id
      where rm.role_id = 2102 and m.perms like 'crud:acme.tl%.LoanOrder:add' order by m.menu_id desc limit 1`);
  if (!perms) throw new Error('未找到借还台账配置包授权：请先运行 tests/e2e/toolloan-config.e2e.mjs');
  const ORDER = perms.split(':')[1];
  const ns = ORDER.replace(/\.LoanOrder$/, '');
  const me = await ok(team.api('GET', '/api/flow/me'), '读当前人员');
  const loanNo = 'TL-UP-' + sfx;
  const loan = await ok(team.api('POST', `/api/crud/${encodeURIComponent(ORDER)}`, {
    loanNo, borrower: me.partyId, loanDate: '2026-11-10', purpose: 'OUTSOURCE', outsourceUnit: '升级验收', loanLine: [{ tool: '万用表', qty: 1 }]
  }), '新建借还单');
  await ok(team.api('POST', `/api/op/${ns}.LoanOrder.submit`, { targetId: String(loan.id), version: loan.version, input: {} }), '提交审核');
  let task;
  for (let i = 0; i < 30 && !task; i++) {
    task = ((await leader.api('GET', '/api/flow/todo')).data ?? []).find((x) => x.bizId === String(loan.id) && x.nodeKey === 'deptAudit' && x.status === 'TODO');
    if (!task) await new Promise((r) => setTimeout(r, 300));
  }
  check(task, '车间主任待办中出现部门审核');
  const status = (await ok(team.api('GET', `/api/crud/${encodeURIComponent(ORDER)}/${loan.id}`), '读借还单')).status;
  check(status === 'IN_REVIEW', '借还单停在部门审核（在途流程）');

  // 4. 附件
  const content = '升级前上传的附件 ' + sfx;
  const up = await team.page.evaluate(
    async ([api, clientId, text]) => {
      const fd = new FormData();
      fd.append('file', new Blob([text], { type: 'text/plain' }), 'before-upgrade.txt');
      const r = await fetch(api + '/resource/oss/upload', { method: 'POST', headers: { clientid: clientId, Authorization: 'Bearer ' + localStorage.getItem('Admin-Token') }, body: fd });
      return r.json();
    },
    [API, CLIENT_ID, content]
  );
  check(up.code === 200, '上传附件');
  const att = await ok(team.api('POST', '/api/op/ls.core.DocumentLink.attach', {
    input: { ossId: String(up.data.ossId), objectType: ORDER, objectId: String(loan.id), classification: 'INTERNAL', orgUnitId: Number(psql(`select id from core_org_unit where code = 'D2011'`)) }
  }), '关联附件');

  const state = {
    S, C, visitId: visit.id, ORDER, ns, loanId: loan.id, loanNo, linkId: att.data.id,
    sha: crypto.createHash('sha256').update(content).digest('hex'),
    flowDefs: psql(`select count(*) from flowable.act_re_procdef`),
    migrations: Number(psql(`select count(*) from flyway_schema_history where success`))
  };
  fs.writeFileSync(STATE, JSON.stringify(state, null, 2));
  console.log('准备完成：' + JSON.stringify(state));
} finally {
  await admin.browser.close();
}
