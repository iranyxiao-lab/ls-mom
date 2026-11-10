/**
 * 18.1～18.3 部署环境实测（无头）：运行期 Agent 网关与 MCP 的完整闭环。
 * 实施顾问在「Agent 管理」新建 Agent、签发 Key（只显示一次）→ 班组长身份（OBO）经 MCP（Streamable HTTP）握手、列工具、调用：
 * 草稿（AI-06 双主体＋批次审计）、越权查询（AI-05）、模拟关闭前检查（缺证据、数据库无变化）、永不暴露清单（AI-09）、
 * 审批绑定（车间主任在「Agent 审批」页批准；AI-07 输入变化被拒、AI-08 不能重复执行）→ 限流 → 界面吊销后 Key 失效。
 * 运行：node tests/e2e/agent-gateway.e2e.mjs（只用于测试环境）
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

const suffix = Date.now().toString(36);
const browser = await chromium.launch({ headless: true });
const admin = await open({ user: 'demo_implementer', browser, width: 1600, height: 1000 });
const team = await open({ user: 'demo_team', browser });
const leader = await open({ user: 'demo_leader', browser, width: 1600, height: 1000 });

/** 以某人的登录令牌＋Agent Key 发 MCP 请求（Streamable HTTP）。 */
const mcpAs =
  (who, key) =>
  async (method, params, id = 1) =>
    who.page.evaluate(
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
        const text = await r.text();
        return { status: r.status, body: text ? JSON.parse(text) : null };
      },
      [API + '/mcp', CLIENT_ID, key, id === null ? { jsonrpc: '2.0', method, params } : { jsonrpc: '2.0', id, method, params }]
    );
const tid = (p, id) => p.locator(`[data-testid="${id}"]`);

try {
  // 1. 实施顾问新建 Agent 并签发 Key
  await admin.page.goto(`${BASE}/agent/admin`);
  await tid(admin.page, 'agent-admin').waitFor({ timeout: 30000 });
  await tid(admin.page, 'btn-new-agent').click();
  await tid(admin.page, 'in-code').fill('shift-e2e-' + suffix);
  await tid(admin.page, 'in-name').fill('交接班助手 E2E');
  await tid(admin.page, 'in-perms').fill('lsmom:work:execute\nlsmom:issue:raise\nlsmom:work:confirm\nlsmom:work:edit');
  await tid(admin.page, 'btn-create').click();
  await admin.page.locator('.el-message--success', { hasText: '已创建' }).waitFor({ timeout: 10000 });
  await tid(admin.page, 'agent-drawer').waitFor({ timeout: 10000 });
  await tid(admin.page, 'btn-issue').click();
  const key = await tid(admin.page, 'issued-key').inputValue();
  check(/^lsk_[a-z0-9]{12}_[A-Za-z0-9]{40}$/.test(key), '签发 Key（只在弹窗显示一次）');
  const prefix = key.split('_')[1];
  check(psql(`select count(*) from agent_api_key where prefix = '${prefix}' and key_hash <> '${key}'`) === '1', '库中只存摘要');
  await tid(admin.page, 'btn-key-done').click();
  await admin.page.waitForTimeout(500);
  check(!(await admin.page.content()).includes(key), '关闭弹窗后页面不再包含明文 Key');
  const agentId = psql(`select agent_id from agent_api_key where prefix = '${prefix}'`);
  const agentParty = psql(`select party_id from agent_identity where id = ${agentId}`);
  const teamParty = psql(`select p.id from core_party p join sys_user u on u.user_id = p.user_id where u.user_name = 'demo_team'`);

  // 2. MCP 握手与工具清单（班组长 OBO）
  const mcp = mcpAs(team, key);
  const init = await mcp('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'e2e', version: '1' } });
  check(
    init.status === 200 && init.body?.result?.protocolVersion === '2025-11-25',
    'MCP initialize（Streamable HTTP，JSON 响应）' + (init.status === 200 && init.body?.result ? '' : JSON.stringify(init))
  );
  check((await mcp('notifications/initialized', {}, null)).status === 202, '通知返回 202');
  const tools = (await mcp('tools/list', {})).body.result.tools.map((t) => t.name);
  check(
    tools.includes('ls.core.Work.find') && tools.includes('ls.core.Issue.raise') && tools.includes('ls.core.Issue.cancel'),
    '工具清单含查询、草稿、需审批能力'
  );
  const forbidden = ['ls.core.Work.confirm', 'ls.core.Issue.verifyPass', 'ls.core.Issue.reject', 'ls.core.Work.rejectConfirm'];
  check(
    forbidden.every((t) => !tools.includes(t)),
    'AI-09：确认完成、验证通过、驳回等永不暴露（即使真人与 Agent 都有确认权限）'
  );
  check(!tools.some((t) => /changeset|release|delete|remove|dcs/i.test(t)), '无 ChangeSet 发布、删除、DCS 写入类工具');
  check(!tools.includes('ls.core.Work.create'), '有效权限取交集：Agent 没有 work:create，工具不出现');

  const call = async (name, args) => (await mcp('tools/call', { name, arguments: args })).body.result;
  // 3. AI-05：越权查询其他组织
  const root = psql(`select id from core_org_unit where parent_id is null order by id limit 1`);
  const denied = await call('ls.core.Work.find', { input: { orgUnitId: Number(root) } });
  check(denied.isError && denied.structuredContent.errorCode === 'ACCESS_DENIED', 'AI-05：查询数据域以外组织的工作被拒');
  check(
    psql(
      `select count(*) from op_audit where op_code = 'ls.core.Work.find' and actor_party = ${agentParty} and on_behalf_of = ${teamParty} and result = 'FAILED'`
    ) === '1',
    '越权查询有审计（Agent 主体＋代理人）'
  );
  const mine = await call('ls.core.Work.find', { input: { limit: 5 } });
  check(!mine.isError, '数据域内查询成功（' + mine.structuredContent.data.total + ' 条）');

  // 4. AI-06：两条整改草稿
  const org = Number(
    psql(`select o.id from core_org_unit o join core_party_org po on po.org_unit_id = o.id where po.party_id = ${teamParty} order by po.id limit 1`)
  );
  const batch = 'B' + suffix;
  const drafts = [];
  for (const title of ['更换 3 号泵密封（交接班）', '补充润滑脂（交接班）']) {
    const r = await call('ls.core.Issue.raise', { input: { type: 'SHIFT', title, orgUnitId: org }, batchId: batch });
    check(!r.isError && r.structuredContent.status === 'DRAFT' && r.structuredContent.source === 'AGENT', `草稿：${title}`);
    drafts.push(r.structuredContent.targetId);
  }
  const rows = psql(`select a.actor_type || '/' || a.actor_party || '/' || a.on_behalf_of || '/' || i.status || '/' || (a.trace_id is not null)
    from op_audit a join core_issue i on i.id = cast(a.target_id as bigint) where a.batch_id = '${batch}' order by a.id`).split('\n');
  check(
    rows.length === 2 && rows.every((r) => r === `AGENT/${agentParty}/${teamParty}/DRAFT/true`),
    'AI-06：两条为 DRAFT，审计含 Agent 与真人主体、trace_id、batch_id'
  );

  // 5. 模拟关闭前检查：高严重度无证据 → 缺证据，数据库无变化
  const high = await call('ls.core.Issue.raise', { input: { type: 'SAFETY', title: '高处坠物', severity: 'HIGH', orgUnitId: org } });
  const hid = high.structuredContent.targetId;
  const before = psql(`select status || '/' || version from core_issue where id = ${hid}`);
  const events = psql(`select count(*) from core_domain_event where subject_id = '${hid}'`);
  const sim = await call('ls.core.Issue.submit', { input: {}, targetId: hid, version: high.structuredContent.version });
  check(
    sim.isError && sim.structuredContent.errorCode === 'GUARD_FAILED' && /证据/.test(sim.structuredContent.message),
    '模拟提交：返回缺少现场证据'
  );
  check(
    psql(`select status || '/' || version from core_issue where id = ${hid}`) === before &&
      psql(`select count(*) from core_domain_event where subject_id = '${hid}'`) === events,
    '模拟后数据库与事件无变化'
  );

  // 6. 审批绑定：取消异常须真人批准（车间主任在页面批准）
  const iss = psql(`select version from core_issue where id = ${drafts[0]}`);
  const reqA = await call('ls.agent.requestApproval', {
    operation: 'ls.core.Issue.cancel',
    input: { reason: '重复登记' },
    targetId: drafts[0],
    version: Number(iss),
    reason: '与上一班重复'
  });
  check(!reqA.isError && reqA.structuredContent.status === 'PENDING', 'Agent 申请审批');
  const noAppr = await call('ls.core.Issue.cancel', { input: { reason: '重复登记' }, targetId: drafts[0], version: Number(iss) });
  check(noAppr.isError && noAppr.structuredContent.errorCode === 'AGENT_APPROVAL_REQUIRED', '未批准不能执行');
  await leader.page.goto(`${BASE}/agent/approvals`);
  await tid(leader.page, 'agent-approvals').waitFor({ timeout: 30000 });
  await leader.page.waitForTimeout(600);
  await tid(leader.page, `btn-approve-${reqA.structuredContent.id}`).click();
  await leader.page.locator('.el-message--success', { hasText: '已批准' }).waitFor({ timeout: 10000 });
  check(true, '车间主任在「Agent 审批」页批准');
  const ai07 = await call('ls.core.Issue.cancel', {
    input: { reason: '不需要了' },
    targetId: drafts[0],
    version: Number(iss),
    approvalId: reqA.structuredContent.id
  });
  check(ai07.isError && ai07.structuredContent.errorCode === 'AGENT_APPROVAL_MISMATCH', 'AI-07：以不同参数执行被拒，须重新批准');
  const okc = await call('ls.core.Issue.cancel', {
    input: { reason: '重复登记' },
    targetId: drafts[0],
    version: Number(iss),
    approvalId: reqA.structuredContent.id
  });
  check(!okc.isError && okc.structuredContent.status === 'CANCELLED', '按批准内容执行成功');
  const ai08 = await call('ls.core.Issue.cancel', {
    input: { reason: '重复登记' },
    targetId: drafts[0],
    version: Number(iss) + 1,
    approvalId: reqA.structuredContent.id
  });
  check(ai08.isError && ai08.structuredContent.errorCode === 'AGENT_APPROVAL_USED', 'AI-08：同一审批单不能再次使用');
  check(psql(`select count(*) from op_audit where approval_id = '${reqA.structuredContent.id}' and result = 'SUCCESS'`) === '1', '只有一次成功执行');

  // 7. 管理页审计：OBO 双主体与批次
  await admin.page.reload();
  await tid(admin.page, 'agent-admin').waitFor({ timeout: 30000 });
  await admin.page.locator('[data-testid="table-agents"] .el-table__body tr', { hasText: 'shift-e2e-' + suffix }).click();
  await tid(admin.page, 'agent-drawer').waitFor({ timeout: 10000 });
  await admin.page.locator('.el-drawer .el-tabs__item', { hasText: '调用审计' }).click();
  await admin.page.waitForTimeout(500);
  const auditText = await tid(admin.page, 'table-audit').textContent();
  check(
    auditText.includes('交接班助手 E2E（代 ') && auditText.includes(batch) && auditText.includes('AGENT_APPROVAL_USED'),
    '管理页审计显示 Agent 代真人、批次与拒绝原因'
  );

  // 8. 限流：上限改为 2 次/分钟
  psql(`update agent_identity set rate_limit_per_minute = 2 where id = ${agentId}`);
  let limited = null;
  for (let i = 0; i < 4 && !limited; i++) {
    const r = await mcp('ping', {});
    if (r.status === 429) limited = r;
  }
  check(limited && limited.body.error.data.errorCode === 'AGENT_RATE_LIMITED', '超过每分钟上限返回 429');
  psql(`update agent_identity set rate_limit_per_minute = 60 where id = ${agentId}`);

  // 9. 界面吊销后 Key 立即失效
  await admin.page.locator('.el-drawer .el-tabs__item', { hasText: 'API Key' }).click();
  await tid(admin.page, `btn-revoke-${prefix}`).click();
  await admin.page.locator('.el-message-box input').fill('演练吊销');
  await admin.page.locator('.el-message-box .el-button--primary').click();
  await admin.page.locator('.el-message--success', { hasText: '已吊销' }).waitFor({ timeout: 10000 });
  const after = await mcp('tools/list', {});
  check(after.status === 401 && after.body.error.data.errorCode === 'AGENT_KEY_REVOKED', '吊销后调用返回 401');
  check(
    psql(`select count(*) from agent_key_event where prefix = '${prefix}' and event in ('REVOKED', 'REVOKED_USE', 'RATE_LIMITED')`) >= '3',
    '签发、限流、吊销与吊销后使用都有记录'
  );
  const errs = [...admin.errors, ...team.errors, ...leader.errors];
  check(errs.length === 0, '页面无脚本错误' + (errs.length ? '：' + errs.join('; ') : ''));
  console.log('Agent 网关闭环通过');
} finally {
  await browser.close();
}
