/**
 * 19.4 / 19.3 第二步（在恢复出的全新环境，LSMOM_E2E_BASE / LSMOM_E2E_PROJECT 指向它）：
 *   MODE=restore（同版本联合恢复）：业务记录、客户字段、附件、Pack 版本、流程定义一致，在途流程可继续办理到归档。
 *   MODE=upgrade（新版本恢复上一版本备份）：核心迁移已执行；标准 Pack 1.0→1.1、客户 Pack 1.0→1.1 升级；
 *     客户字段与数据保留，客户与标准 Pack 验收测试通过、无漂移；在途流程继续办理到归档。
 */
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { open, check, API, CLIENT_ID } from './lib.mjs';
import { root, buildStd, buildCust, b64 } from './upgrade-lib.mjs';

const MODE = process.env.MODE ?? 'restore';
const PROJECT = process.env.LSMOM_E2E_PROJECT;
const st = JSON.parse(fs.readFileSync(process.env.LSMOM_E2E_STATE, 'utf8'));
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
const tag = MODE === 'upgrade' ? '［升级］' : '［恢复］';

const admin = await open();
const team = await open({ user: 'demo_team', browser: admin.browser });
const leader = await open({ user: 'demo_leader', browser: admin.browser });
const keeper = await open({ user: 'demo_asset', browser: admin.browser });
try {
  check(true, `${tag}恢复环境可登录（管理员、班组长、车间主任、设备员）`);
  const packs = await ok(admin.api('GET', '/api/packs'), '读 Pack');
  const ver = (c) => packs.find((p) => p.code === c)?.version;
  check(ver(st.S) === '1.0.0' && ver(st.C) === '1.0.0', `${tag}Pack 版本与备份一致：${st.S}@${ver(st.S)}、${st.C}@${ver(st.C)}`);
  check(psql(`select count(*) from flowable.act_re_procdef`) === st.flowDefs, `${tag}流程定义 ${st.flowDefs} 个`);

  if (MODE === 'upgrade') {
    const now = Number(psql(`select count(*) from flyway_schema_history where success`));
    check(now > st.migrations, `${tag}核心升级：新版本执行了 ${now - st.migrations} 个迁移`);
    check(psql(`select count(*) from pg_indexes where indexname = 'idx_core_access_audit_actor'`) === '1', `${tag}新版本迁移的结构已生效`);
    const s11 = await ok(admin.api('POST', '/api/packs/install', { archive: b64(buildStd(st.S, '1.1.0')) }), '升级标准 Pack');
    check(s11.status === 'UPGRADED' && s11.previousVersion === '1.0.0', `${tag}标准 Pack 1.0.0 → 1.1.0`);
    const c11 = await ok(admin.api('POST', '/api/packs/install', { archive: b64(buildCust(st.C, st.S, '1.1.0')) }), '升级客户 Pack');
    check(c11.status === 'UPGRADED', `${tag}客户 Pack 1.0.0 → 1.1.0`);
    const fields = (await ok(admin.api('GET', `/api/crud/${encodeURIComponent(st.S + '.Visit')}/schema`), '读台账元数据')).fields.map((f) => f.code);
    check(['badgeNo', 'plateNo', 'escort', 'parkingLot'].every((f) => fields.includes(f)), `${tag}字段：标准新增胸卡号，客户字段与新增停车位均在`);
  }

  const v = await ok(admin.api('GET', `/api/crud/${encodeURIComponent(st.S + '.Visit')}/${st.visitId}`), '读访客记录');
  check(v.visitorName === '升级前访客' && v.plateNo === '粤B88888' && v.escort === '吴班长', `${tag}客户字段与数据保留`);
  for (const code of [st.C, st.S]) {
    const rep = await ok(admin.api('POST', `/api/packs/${code}/test`), '运行 Pack 测试');
    check(rep.passed, `${tag}${code === st.C ? '客户' : '标准'} Pack 验收测试通过`);
  }
  if (MODE === 'upgrade') {
    for (const code of [st.S, st.C]) check((await ok(admin.api('GET', `/api/packs/${code}/drift`), '漂移')).drifted === false, `${tag}${code} 无漂移`);
  }

  // 附件
  const dl = await team.page.evaluate(
    async ([url, clientId]) => {
      const r = await fetch(url, { headers: { clientid: clientId, Authorization: 'Bearer ' + localStorage.getItem('Admin-Token') } });
      const buf = await r.arrayBuffer();
      return { ct: r.headers.get('content-type') ?? '', bytes: Array.from(new Uint8Array(buf)) };
    },
    [`${API}/api/doc/${st.linkId}/download`, CLIENT_ID]
  );
  const sha = crypto.createHash('sha256').update(Buffer.from(dl.bytes)).digest('hex');
  check(!dl.ct.includes('json') && sha === st.sha, `${tag}附件可下载且内容一致`);

  // 在途流程继续办理：部门审核通过 → 设备员确认归档
  const todo = async (who, node) => {
    for (let i = 0; i < 40; i++) {
      const t = ((await who.api('GET', '/api/flow/todo')).data ?? []).find((x) => x.bizType === st.ORDER && x.bizId === String(st.loanId) && x.nodeKey === node && x.status === 'TODO');
      if (t) return t;
      await new Promise((r) => setTimeout(r, 300));
    }
    throw new Error('没有待办：' + node);
  };
  const a = await todo(leader, 'deptAudit');
  check(true, `${tag}恢复后车间主任的待办仍在`);
  await ok(leader.api('POST', `/api/flow/tasks/${a.taskId}/complete`, { action: 'APPROVE', comment: '恢复后审核', input: { auditNote: '恢复后继续办理' } }), '部门审核');
  const c = await todo(keeper, 'confirm');
  await ok(keeper.api('POST', `/api/flow/tasks/${c.taskId}/complete`, { action: 'APPROVE', comment: '已核对' }), '确认归档');
  let loan;
  for (let i = 0; i < 40; i++) {
    loan = await ok(team.api('GET', `/api/crud/${encodeURIComponent(st.ORDER)}/${st.loanId}`), '读借还单');
    if (loan.status === 'ARCHIVED') break;
    await new Promise((r) => setTimeout(r, 500));
  }
  check(loan.status === 'ARCHIVED' && loan.auditNote === '恢复后继续办理',
    `${tag}在途流程继续办理到归档（状态 ${loan.status}，审核意见 ${loan.auditNote ?? '—'}）`);
  const rec = await ok(admin.api('GET', '/api/flow/reconcile'), '流程对账');
  check(!rec.diffs.some((d) => JSON.stringify(d).includes(String(st.loanId))), `${tag}流程与业务状态对账无差异（检查 ${rec.checkedInstances} 个实例）`);
  console.log(`${tag}验收通过`);
} finally {
  await admin.browser.close();
}
