/**
 * 18.4 部署环境实测（无头）：Agent 草拟带引用的摘要 → 真人在「摘要确认」页发布。
 * 车间主任身份（OBO）经 MCP 取异常与 DCS 报警的上下文、草拟摘要（每个要点引用上下文记录；编造的引用被拒）；
 * Agent 无法发布；车间主任在界面核对引用后确认发布；被摘要的原记录不变。
 * 运行：node tests/e2e/agent-summary.e2e.mjs（只用于测试环境）
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
const admin = await open({ user: 'demo_implementer', browser });
const leader = await open({ user: 'demo_leader', browser, width: 1600, height: 1000 });
const tid = (p, id) => p.locator(`[data-testid="${id}"]`);

const mcp = (key) => async (method, params, id = 1) =>
  leader.page.evaluate(
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
    [API + '/mcp', CLIENT_ID, key, { jsonrpc: '2.0', id, method, params }]
  );

let agentId;
try {
  // 1. 实施顾问建一个只有草拟能力的摘要助手（即使授予确认权限，发布也永不暴露）
  const created = await admin.api('post', '/api/agents', {
    code: 'summary-e2e-' + suffix,
    name: '摘要助手 E2E',
    permissions: ['lsmom:summary:draft', 'lsmom:summary:confirm', 'lsmom:issue:raise'],
    rateLimitPerMinute: 120
  });
  check(created.code === 200, '新建摘要助手' + (created.code === 200 ? '' : JSON.stringify(created)));
  agentId = created.data.id;
  const issued = await admin.api('post', `/api/agents/${agentId}/keys`, {});
  const key = issued.data.key;
  check(/^lsk_/.test(key ?? ''), '签发 Key');

  const call = mcp(key);
  await call('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'e2e', version: '1' } });
  const tools = (await call('tools/list', {})).body.result.tools.map((t) => t.name);
  check(tools.includes('ls.core.Summary.context') && tools.includes('ls.core.Summary.draft'), '工具清单含摘要上下文与草拟');
  check(!tools.includes('ls.core.Summary.publish'), '确认发布不在工具清单中（代审批，永不暴露）');
  const tool = async (name, args) => (await call('tools/call', { name, arguments: args })).body.result;

  // 2. 异常摘要：Agent 登记异常草稿 → 取上下文 → 草拟
  const leaderParty = psql(`select p.id from core_party p join sys_user u on u.user_id = p.user_id where u.user_name = 'demo_leader'`);
  const org = Number(
    psql(`select o.id from core_org_unit o join core_party_org po on po.org_unit_id = o.id where po.party_id = ${leaderParty} order by po.id limit 1`)
  );
  const raised = await tool('ls.core.Issue.raise', { input: { type: 'DEFECT', title: '2 号线传送带跑偏（摘要）' + suffix, orgUnitId: org } });
  check(!raised.isError, '登记异常草稿' + (raised.isError ? JSON.stringify(raised.structuredContent) : ''));
  const issueId = raised.structuredContent.targetId;
  const issueBefore = psql(`select status || '/' || version || '/' || title from core_issue where id = ${issueId}`);

  const ctx = await tool('ls.core.Summary.context', { input: { kind: 'ISSUE', subjectId: issueId } });
  check(!ctx.isError, '取异常上下文' + (ctx.isError ? JSON.stringify(ctx.structuredContent) : ''));
  const facts = ctx.structuredContent.data.facts;
  const ev = facts.find((f) => f.ref.type === 'ls.core.Event');
  check(facts.some((f) => f.ref.type === 'ls.core.Issue' && f.ref.id === issueId) && ev, '上下文含异常本身与登记事件，均带引用');

  const fake = await tool('ls.core.Summary.draft', {
    input: { kind: 'ISSUE', subjectId: issueId, content: '编造', points: [{ text: '已整改完毕', refs: [{ type: 'ls.core.Work', id: '999999999' }] }] }
  });
  check(fake.isError && fake.structuredContent.errorCode === 'SUMMARY_CITATION_INVALID', '引用不在上下文中的要点被拒');
  const noRef = await tool('ls.core.Summary.draft', {
    input: { kind: 'ISSUE', subjectId: issueId, content: '无引用', points: [{ text: '情况正常', refs: [] }] }
  });
  check(noRef.isError && noRef.structuredContent.errorCode === 'SUMMARY_CITATION_INVALID', '没有引用的要点被拒');

  const drafted = await tool('ls.core.Summary.draft', {
    input: {
      kind: 'ISSUE',
      subjectId: issueId,
      model: 'e2e-model',
      content: '2 号线传送带跑偏已登记为缺陷草稿，待班组长确认提交。',
      points: [
        { text: '已登记缺陷：传送带跑偏', refs: [{ type: 'ls.core.Issue', id: issueId }] },
        { text: '登记事件已记录', refs: [{ type: 'ls.core.Event', id: ev.ref.id }] }
      ]
    }
  });
  check(!drafted.isError && drafted.structuredContent.status === 'DRAFT', 'Agent 草拟摘要：DRAFT');
  const sid = drafted.structuredContent.targetId;
  check(
    psql(`select status || '/' || source || '/' || on_behalf_of || '/' || jsonb_array_length(citations) from core_summary where id = ${sid}`) ===
      `DRAFT/AGENT/${leaderParty}/2`,
    '库中：待确认、来源 Agent、代理车间主任、2 条引用'
  );

  // 3. Agent 不能发布（即使直接调用）
  const pub = await tool('ls.core.Summary.publish', { input: {}, targetId: sid, version: 0 });
  check(pub.isError && /AGENT_TOOL_DENIED|UNKNOWN_TOOL|NOT_A_TOOL/.test(pub.structuredContent?.errorCode ?? JSON.stringify(pub)), 'Agent 调用发布被拒');
  check(psql(`select status from core_summary where id = ${sid}`) === 'DRAFT', '摘要仍为待确认');

  // 4. DCS 报警摘要：上下文含报警、原始事件与快照
  const alarm = psql(`select id from dcs_alarm where org_unit_id = ${org} or org_unit_id in (select id from core_org_unit where parent_id = ${org}) order by last_occurred_at desc limit 1`);
  let dcsSid;
  if (alarm) {
    const dctx = await tool('ls.core.Summary.context', { input: { kind: 'DCS_ALARM', subjectId: alarm } });
    check(!dctx.isError, '取 DCS 报警上下文' + (dctx.isError ? JSON.stringify(dctx.structuredContent) : ''));
    const types = new Set(dctx.structuredContent.data.facts.map((f) => f.ref.type));
    check(types.has('ls.dcs.Alarm') && types.has('ls.dcs.AlarmEvent'), 'DCS 上下文含报警与原始事件' + (types.has('ls.dcs.Snapshot') ? '、快照' : ''));
    const evRef = dctx.structuredContent.data.facts.find((f) => f.ref.type === 'ls.dcs.AlarmEvent').ref;
    const d2 = await tool('ls.core.Summary.draft', {
      input: {
        kind: 'DCS_ALARM',
        subjectId: alarm,
        content: '报警复盘草稿',
        points: [{ text: '报警发生及原始事件', refs: [{ type: 'ls.dcs.Alarm', id: alarm }, evRef] }]
      }
    });
    check(!d2.isError && d2.structuredContent.status === 'DRAFT', 'DCS 报警摘要草稿');
    dcsSid = d2.structuredContent.targetId;
  } else {
    throw new Error('车间主任数据域内没有 DCS 报警（需以 --profile dcs 启动模拟器）');
  }

  // 5. 车间主任在「摘要确认」页核对引用并发布
  await leader.page.goto(`${BASE}/agent/summaries`);
  await tid(leader.page, `summary-${sid}`).waitFor({ timeout: 30000 });
  check((await tid(leader.page, `source-${sid}`).textContent()).includes('e2e-model'), '页面标明 Agent 草拟与模型');
  await tid(leader.page, `ref-${sid}-ls.core.Issue-${issueId}`).waitFor({ state: 'visible', timeout: 10000 });
  check((await tid(leader.page, `ref-${sid}-ls.core.Issue-${issueId}`).textContent()).includes('异常'), '页面显示要点引用的异常');
  await tid(leader.page, `btn-publish-${sid}`).click();
  await leader.page.locator('.el-message--success', { hasText: '已发布' }).waitFor({ timeout: 10000 });
  check(
    psql(`select status || '/' || published_by || '/' || (published_at is not null) from core_summary where id = ${sid}`) ===
      `PUBLISHED/${leaderParty}/true`,
    '真人确认发布：发布人为车间主任'
  );
  check(psql(`select status || '/' || version || '/' || title from core_issue where id = ${issueId}`) === issueBefore, '发布摘要不修改原异常');
  check(
    psql(`select count(*) from op_audit where op_code = 'ls.core.Summary.publish' and target_id = '${sid}' and actor_type = 'HUMAN' and result = 'SUCCESS'`) === '1',
    '发布有真人审计'
  );

  // 6. 作废 DCS 摘要须填原因
  await tid(leader.page, `summary-${dcsSid}`).waitFor({ timeout: 10000 });
  await tid(leader.page, `btn-discard-${dcsSid}`).click();
  await leader.page.locator('.el-message--warning', { hasText: '作废原因' }).waitFor({ timeout: 5000 });
  await tid(leader.page, `reason-${dcsSid}`).fill('需补充快照趋势');
  await tid(leader.page, `btn-discard-${dcsSid}`).click();
  await leader.page.locator('.el-message--success', { hasText: '已作废' }).waitFor({ timeout: 10000 });
  check(psql(`select status || '/' || discard_reason from core_summary where id = ${dcsSid}`) === 'DISCARDED/需补充快照趋势', '作废并记录原因');

  const errs = [...admin.errors, ...leader.errors];
  check(errs.length === 0, '页面无脚本错误' + (errs.length ? '：' + errs.join('; ') : ''));
  console.log('Agent 摘要闭环通过');
} finally {
  if (agentId) psql(`update agent_identity set status = 'DISABLED' where id = ${agentId}`);
  await browser.close();
}
