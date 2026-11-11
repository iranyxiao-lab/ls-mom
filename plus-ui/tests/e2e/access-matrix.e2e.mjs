/**
 * 19.2 越权测试矩阵（部署环境，无头）：菜单、Operation、组织、字段、附件、流程任务、Agent 七类越权全部被拒，且每次拒绝都有审计。
 * 审计位置：接口与菜单权限 → core_access_audit（PERMISSION_DENIED）；Operation / 组织 / 字段 / 流程任务 / Agent → op_audit（FAILED，
 * 请求层面的安全拒绝另写 core_access_audit）；附件 → core_access_audit（DOCUMENT_DOWNLOAD）。
 * 前置：已运行过 toolloan-config.e2e.mjs（发布借还台账配置包并授权）。运行：node tests/e2e/access-matrix.e2e.mjs（只用于测试环境）
 */
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { open, check, BASE, API, CLIENT_ID } from './lib.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const PROJECT = process.env.LSMOM_E2E_PROJECT ?? 'lsmom-verify';
const psql = (sql) =>
  execFileSync('docker', ['compose', '-p', PROJECT, 'exec', '-T', 'postgres', 'psql', '-U', 'lsmom', '-d', 'lsmom', '-Atc', sql], {
    cwd: path.join(root, 'deploy'),
    encoding: 'utf8'
  }).trim();
const since = psql(`select to_char(now() - interval '2 second', 'YYYY-MM-DD HH24:MI:SS.US')`);
const audits = (where) => Number(psql(`select count(*) from core_access_audit where occurred_at >= '${since}' and result = 'DENIED' and ${where}`));
const opAudits = (where) => Number(psql(`select count(*) from op_audit where occurred_at >= '${since}' and result = 'FAILED' and ${where}`));
const partyOf = (u) => psql(`select p.id from core_party p join sys_user u on u.user_id = p.user_id where u.user_name = '${u}'`);

const suffix = Date.now().toString(36);
const browser = await chromium.launch({ headless: true });
const operator = await open({ user: 'demo_operator', browser });
const team = await open({ user: 'demo_team', browser });
const leader = await open({ user: 'demo_leader', browser });
const admin = await open({ user: 'demo_implementer', browser });
const sysadmin = await open({ browser });
const P = { operator: partyOf('demo_operator'), team: partyOf('demo_team'), leader: partyOf('demo_leader') };
const rows = [];
const row = (kind, what, ok) => {
  rows.push({ kind, what, ok });
  check(ok, `${kind}｜${what}`);
};

let agentId;
try {
  // 1. 菜单 / 接口权限：操作工没有「Agent 管理」菜单，直接调接口也被拒
  const routers = JSON.stringify((await operator.api('GET', '/system/menu/getRouters')).data ?? []);
  row('菜单', '操作工的路由中没有 Agent 管理', !routers.includes('lsmom/agent/admin/index'));
  const r1 = await operator.api('GET', '/api/agents');
  row('菜单', `直接调用 /api/agents 返回 ${r1.code}`, r1.code === 403);
  row(
    '菜单',
    '访问审计 PERMISSION_DENIED',
    audits(`actor_party = ${P.operator} and action = 'PERMISSION_DENIED' and object_id like '%/api/agents%'`) >= 1
  );

  // 2. Operation：操作工没有验证权限，验证通过被拒
  const org18 = Number(psql(`select id from core_org_unit where code = 'D2011'`));
  const issue = await team.api('post', '/api/op/ls.core.Issue.raise', { input: { type: 'DEFECT', title: '越权矩阵 ' + suffix, orgUnitId: org18 } });
  const iid = issue.data.targetId;
  const r2 = await operator.api('post', '/api/op/ls.core.Issue.verifyPass', {
    targetId: iid,
    version: issue.data.version,
    input: { conclusion: '越权' }
  });
  row('Operation', `操作工执行「验证通过」返回 ${r2.data?.errorCode}`, r2.code === 403 && r2.data?.errorCode === 'ACCESS_DENIED');
  row(
    'Operation',
    'op_audit 记录失败',
    opAudits(`op_code = 'ls.core.Issue.verifyPass' and actor_party = ${P.operator} and error_code = 'ACCESS_DENIED'`) >= 1
  );
  row('Operation', '访问审计 ACCESS_DENIED', audits(`actor_party = ${P.operator} and action = 'ACCESS_DENIED'`) >= 1);

  // 3. 组织（数据域）：一车间甲班的班组长不能在二车间登记、不能处理二车间的异常、列表看不到
  const org13 = Number(psql(`select id from core_org_unit where code = 'D2020'`));
  const other = await admin.api('post', '/api/op/ls.core.Issue.raise', { input: { type: 'DEFECT', title: '二车间 ' + suffix, orgUnitId: org13 } });
  const r3 = await team.api('post', '/api/op/ls.core.Issue.submit', { targetId: other.data.targetId, version: other.data.version, input: {} });
  row('组织', `班组长提交二车间的异常返回 ${r3.data?.errorCode}`, r3.code !== 200 && ['ACCESS_DENIED', 'NOT_FOUND'].includes(r3.data?.errorCode));
  row(
    '组织',
    'op_audit 记录失败',
    opAudits(`op_code = 'ls.core.Issue.submit' and actor_party = ${P.team} and target_id = '${other.data.targetId}'`) >= 1
  );
  const r3b = await team.api('post', '/api/op/ls.core.Work.find', { input: { orgUnitId: org13 } });
  row('组织', '查询二车间的工作被拒', r3b.code === 403 && r3b.data?.errorCode === 'ACCESS_DENIED');
  const w13 = await sysadmin.api('post', '/api/op/ls.core.Work.create', {
    input: { workType: 'PRODUCTION', title: '二车间工作 ' + suffix, orgUnitId: org13 }
  });
  check(w13.code === 200, '管理员在二车间建工作' + (w13.code === 200 ? '' : JSON.stringify(w13)));
  const mine = await team.api('post', '/api/op/ls.core.Work.find', { input: { keyword: suffix, limit: 100 } });
  const seen = mine.data?.data?.rows ?? [];
  row('组织', '不指定组织查询时看不到二车间的工作', mine.code === 200 && !seen.some((w) => w.orgUnitId === org13));
  const asAdmin = await sysadmin.api('post', '/api/op/ls.core.Work.find', { input: { keyword: suffix, limit: 100 } });
  check(
    (asAdmin.data?.data?.rows ?? []).some((w) => w.orgUnitId === org13) && JSON.stringify(asAdmin.data).includes('二车间工作 ' + suffix),
    '对照：全部数据域的管理员能查到'
  );

  // 4. 字段：借还单退回后单号只读——在待办中改单号被拒；脱离待办直接带字段调用迁移也被拒
  const menu = psql(`select m.perms from sys_menu m join sys_role_menu rm on rm.menu_id = m.menu_id
      where rm.role_id = 2102 and m.perms like 'crud:acme.tl%.LoanOrder:add' order by m.menu_id desc limit 1`);
  if (!menu) throw new Error('未找到借还台账配置包授权：请先运行 tests/e2e/toolloan-config.e2e.mjs');
  const ORDER = menu.split(':')[1];
  const ns = ORDER.replace(/\.LoanOrder$/, '');
  const loanNo = `TL-AM-${suffix}`;
  const created = await team.api('POST', `/api/crud/${encodeURIComponent(ORDER)}`, {
    loanNo,
    borrower: Number(P.team),
    loanDate: '2026-11-10',
    purpose: 'OUTSOURCE',
    outsourceUnit: '矩阵测试',
    loanLine: [{ tool: '扳手', qty: 1 }]
  });
  check(created.code === 200, '班组长新建借还单' + (created.code === 200 ? '' : '：' + created.msg));
  const rec = created.data;
  const sub = await team.api('post', `/api/op/${ns}.LoanOrder.submit`, { targetId: String(rec.id), version: rec.version, input: {} });
  check(sub.code === 200, '提交审核' + (sub.code === 200 ? '' : '：' + sub.msg));
  const todo = async (who, node) => {
    for (let i = 0; i < 30; i++) {
      const t = ((await who.api('GET', '/api/flow/todo')).data ?? []).find(
        (x) => x.bizType === ORDER && x.bizId === String(rec.id) && x.nodeKey === node && x.status === 'TODO'
      );
      if (t) return t;
      await new Promise((r) => setTimeout(r, 300));
    }
    throw new Error('没有待办：' + node);
  };
  const audit = await todo(leader, 'deptAudit');

  // 5. 流程任务：发起人（非办理人）不能办理部门审核
  const r5 = await team.api('POST', `/api/flow/tasks/${audit.taskId}/complete`, { action: 'APPROVE', comment: '自己批' });
  row(
    '流程任务',
    `非办理人办理待办返回 ${r5.data?.errorCode ?? r5.code}`,
    r5.code !== 200 && ['TASK_NOT_ASSIGNED', 'ACCESS_DENIED'].includes(r5.data?.errorCode)
  );
  row(
    '流程任务',
    'op_audit 记录失败',
    opAudits(`actor_party = ${P.team} and error_code in ('TASK_NOT_ASSIGNED', 'ACCESS_DENIED') and target_id = '${rec.id}'`) >= 1
  );
  row(
    '流程任务',
    '访问审计',
    audits(`actor_party = ${P.team} and action in ('TASK_NOT_ASSIGNED', 'ACCESS_DENIED') and object_id like '%/api/flow/tasks/%'`) >= 1
  );

  const ret = await leader.api('POST', `/api/flow/tasks/${audit.taskId}/return`, { reason: '补充预计归还' });
  check(ret.code === 200, '车间主任退回' + (ret.code === 200 ? '' : '：' + ret.msg));
  const back = await todo(team, 'initiator');
  const r4 = await team.api('POST', `/api/flow/tasks/${back.taskId}/complete`, {
    action: 'RESUBMIT',
    comment: '改单号',
    input: { loanNo: loanNo + '-X' }
  });
  row('字段', `待办中修改只读字段返回 ${r4.data?.errorCode ?? r4.msg}`, r4.code !== 200 && r4.data?.errorCode === 'FIELD_READONLY');
  const cur = (await team.api('GET', `/api/crud/${encodeURIComponent(ORDER)}/${rec.id}`)).data;
  const r4b = await team.api('post', `/api/op/${ns}.LoanOrder.resubmit`, {
    targetId: String(rec.id),
    version: cur.version,
    input: { loanNo: loanNo + '-Y' }
  });
  row(
    '字段',
    `脱离待办直接改字段返回 ${r4b.data?.errorCode ?? r4b.msg}`,
    // 审批中的单据不能脱离待办调用动作（流程守卫），或字段只读、无权限：都必须被拒
    r4b.code !== 200 &&
      (['FIELD_READONLY', 'ACCESS_DENIED'].includes(r4b.data?.errorCode) || (r4b.data?.errorCode === 'GUARD_FAILED' && String(r4b.msg).includes('审批中')))
  );
  row('字段', 'op_audit 记录失败', opAudits(`actor_party = ${P.team} and error_code = 'FIELD_READONLY'`) >= 1);
  row('字段', '访问审计 FIELD_READONLY', audits(`actor_party = ${P.team} and action = 'FIELD_READONLY'`) >= 1);
  check((await team.api('GET', `/api/crud/${encodeURIComponent(ORDER)}/${rec.id}`)).data.loanNo === loanNo, '单号未被修改');

  // 6. 附件：机密附件对内部密级的人不可下载
  const up = await leader.page.evaluate(
    async ([api, clientId]) => {
      const fd = new FormData();
      fd.append('file', new Blob(['confidential drawing'], { type: 'text/plain' }), 'matrix-secret.txt');
      const r = await fetch(api + '/resource/oss/upload', {
        method: 'POST',
        headers: { clientid: clientId, Authorization: 'Bearer ' + localStorage.getItem('Admin-Token') },
        body: fd
      });
      return r.json();
    },
    [API, CLIENT_ID]
  );
  check(up.code === 200, '上传附件' + (up.code === 200 ? '' : '：' + up.msg));
  const att = await leader.api('post', '/api/op/ls.core.DocumentLink.attach', {
    input: { ossId: String(up.data.ossId), objectType: 'ISSUE', objectId: String(iid), classification: 'CONFIDENTIAL', orgUnitId: org18 }
  });
  check(att.code === 200, '关联机密附件' + (att.code === 200 ? '' : '：' + att.msg));
  const linkId = att.data.data.id;
  const dl = await team.page.evaluate(
    async ([url, clientId]) => {
      const r = await fetch(url, { headers: { clientid: clientId, Authorization: 'Bearer ' + localStorage.getItem('Admin-Token') } });
      const ct = r.headers.get('content-type') ?? '';
      return { status: r.status, ct, body: ct.includes('json') ? await r.json() : await r.text() };
    },
    [`${API}/api/doc/${linkId}/download`, CLIENT_ID]
  );
  row('附件', '内部密级的班组长下载机密附件被拒', dl.ct.includes('json') && dl.body.code !== 200);
  row('附件', '访问审计 DOCUMENT_DOWNLOAD 拒绝', audits(`actor_party = ${P.team} and action = 'DOCUMENT_DOWNLOAD' and object_id = '${linkId}'`) >= 1);

  // 7. Agent：即使真人与 Agent 都有确认权限，确认完成也不暴露；越权查询被拒
  const ag = await admin.api('post', '/api/agents', {
    code: 'matrix-' + suffix,
    name: '越权矩阵 Agent',
    permissions: ['lsmom:work:execute', 'lsmom:work:confirm'],
    rateLimitPerMinute: 60
  });
  agentId = ag.data.id;
  const key = (await admin.api('post', `/api/agents/${agentId}/keys`, {})).data.key;
  const agentParty = psql(`select party_id from agent_identity where id = ${agentId}`);
  const mcp = async (method, params) =>
    team.page.evaluate(
      async ([url, clientId, key, body]) => {
        const r = await fetch(url, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json, text/event-stream',
            'MCP-Protocol-Version': '2025-11-25',
            clientid: clientId,
            Authorization: 'Bearer ' + localStorage.getItem('Admin-Token'),
            'X-Agent-Key': key
          },
          body: JSON.stringify(body)
        });
        return r.json();
      },
      [API + '/mcp', CLIENT_ID, key, { jsonrpc: '2.0', id: 1, method, params }]
    );
  await mcp('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'matrix', version: '1' } });
  const tools = (await mcp('tools/list', {})).result.tools.map((t) => t.name);
  row('Agent', '工具清单不含确认完成', !tools.includes('ls.core.Work.confirm'));
  const work = psql(
    `select id || ':' || version from core_work_item where org_unit_id = ${org18} and status = 'PENDING_CONFIRM' order by id desc limit 1`
  );
  const [wid, wver] = work ? work.split(':') : [psql(`select id from core_work_item where org_unit_id = ${org18} order by id desc limit 1`), '0'];
  const c = (await mcp('tools/call', { name: 'ls.core.Work.confirm', arguments: { input: {}, targetId: wid, version: Number(wver) } })).result ?? {};
  row('Agent', `直接调用确认完成被拒（${c.structuredContent?.errorCode ?? 'error'}）`, c.isError === true || c === undefined);
  row(
    'Agent',
    'op_audit 记录 Agent 被拒',
    opAudits(`actor_party = ${agentParty} and on_behalf_of = ${P.team} and error_code = 'AGENT_TOOL_DENIED'`) >= 1
  );
  const q = (await mcp('tools/call', { name: 'ls.core.Work.find', arguments: { input: { orgUnitId: org13 } } })).result;
  row('Agent', '越出真人数据域的查询被拒', q.isError && q.structuredContent.errorCode === 'ACCESS_DENIED');
  row(
    'Agent',
    'op_audit 记录越权查询',
    opAudits(`actor_party = ${agentParty} and op_code = 'ls.core.Work.find' and error_code = 'ACCESS_DENIED'`) >= 1
  );

  const kinds = [...new Set(rows.map((r) => r.kind))];
  check(kinds.length === 7 && rows.every((r) => r.ok), `越权矩阵：${kinds.join('、')} 共 ${rows.length} 项全部被拒并有审计`);
} finally {
  if (agentId) psql(`update agent_identity set status = 'DISABLED' where id = ${agentId}`);
  await browser.close();
}
