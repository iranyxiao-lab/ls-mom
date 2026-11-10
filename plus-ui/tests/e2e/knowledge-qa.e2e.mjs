/**
 * 18.5 部署环境实测（无头）：带引用的知识问答与指标问数。
 * 班组长在「知识问答」页问"本周一车间甲班异常数" → 数值与按指标口径直接统计一致、给出指标编码与记录引用（AI-10）；
 * 问数据域以外的班组被拒；SOP 有未发布修订稿时只引用已发布版本；其他部门看不到本车间 SOP；
 * 车间主任在页面新建文档 → 发布 v1 → 保存 v2 修订稿（不被引用）→ 发布 v2 后引用 v2；Agent 经 MCP 问答按真人数据域。
 * 运行：node tests/e2e/knowledge-qa.e2e.mjs（只用于测试环境）
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
const team = await open({ user: 'demo_team', browser });
const leader = await open({ user: 'demo_leader', browser, width: 1600, height: 1000 });
const quality = await open({ user: 'demo_quality', browser });
const admin = await open({ user: 'demo_implementer', browser });
const tid = (p, id) => p.locator(`[data-testid="${id}"]`);

async function askUi(who, question) {
  await tid(who.page, 'in-question').fill(question);
  const resp = who.page.waitForResponse((r) => r.url().includes('/api/knowledge/ask'));
  await tid(who.page, 'btn-ask').click();
  return (await resp).json();
}

let agentId;
try {
  // 0. 准备：在一车间甲班登记并提交一条本周异常（与已有数据一起按指标口径核对）
  const teamOrg = psql(`select id from core_org_unit where code = 'D2011'`);
  const raised = await team.api('post', '/api/op/ls.core.Issue.raise', {
    input: { type: 'DEFECT', title: '甲班传送带跑偏 ' + suffix, orgUnitId: Number(teamOrg), severity: 'LOW' }
  });
  check(raised.code === 200, '班组长登记异常' + (raised.code === 200 ? '' : JSON.stringify(raised)));
  const iid = raised.data.targetId;
  const sub = await team.api('post', '/api/op/ls.core.Issue.submit', { targetId: iid, version: raised.data.version, input: {} });
  check(sub.code === 200, '提交异常' + (sub.code === 200 ? '' : JSON.stringify(sub)));

  // 1. AI-10：问数
  await team.page.goto(`${BASE}/agent/knowledge`);
  await tid(team.page, 'knowledge-center').waitFor({ timeout: 30000 });
  const r1 = await askUi(team, '本周一车间甲班异常数');
  check(r1.code === 200 && r1.data.type === 'METRIC', '识别为指标问数' + (r1.code === 200 ? '' : JSON.stringify(r1)));
  const expected = psql(`select count(*) from core_issue where org_unit_id = ${teamOrg} and status not in ('DRAFT', 'CANCELLED')
      and created_at >= date_trunc('week', now()) and created_at < date_trunc('week', now()) + interval '7 day'`);
  check(String(r1.data.value) === expected && Number(expected) >= 1, `数值与按口径直接统计一致（${expected}）`);
  await tid(team.page, 'answer-value').waitFor();
  check((await tid(team.page, 'answer-metric').textContent()).includes('ls.core.issueCount'), '页面显示所用指标编码');
  check(r1.data.refs.some((x) => x.id === String(iid) && x.type === 'ls.core.Issue'), '记录引用包含刚登记的异常');
  await tid(team.page, `answer-ref-${iid}`).waitFor({ state: 'visible', timeout: 5000 });
  check(r1.data.drill.total === Number(expected), '可钻取：钻取总数与数值一致');
  const teamParty = psql(`select p.id from core_party p join sys_user u on u.user_id = p.user_id where u.user_name = 'demo_team'`);
  check(
    Number(psql(`select count(*) from op_audit where op_code = 'ls.core.KnowledgeDocument.ask' and actor_type = 'HUMAN' and actor_party = ${teamParty}`)) >= 1,
    '问答经 Operation 网关并审计'
  );

  const r2 = await askUi(team, '本周二车间异常数');
  check(r2.code !== 200 && /数据范围/.test(r2.msg), '问数据域以外的组织被拒：' + r2.msg);

  // 2. 示例 SOP：有未发布修订稿，只引用已发布 v1
  const r3 = await askUi(team, '检修前怎么挂牌上锁？联锁解除要双人确认吗');
  check(r3.data.type === 'DOCUMENT' && r3.data.citations.length > 0, '文档问答带引用');
  const loto = r3.data.citations.filter((c) => c.documentCode === 'SOP-WS1-LOTO');
  check(loto.length > 0 && loto.every((c) => c.version === 1), '只引用 SOP 已发布的 v1（v2 修订稿未发布）');
  check(!JSON.stringify(r3.data.citations).includes('修订中') && !r3.data.answer.includes('修订中'), '未发布修订稿的内容没有出现在回答与引用中');
  await tid(team.page, 'cite-1').waitFor({ timeout: 5000 });

  // 其他车间的 SOP：实施顾问（全部数据）在二车间建并发布；一车间甲班检索不到，全部数据范围的质检员可以
  const ws2 = psql(`select id from core_org_unit where code = 'D2020'`);
  const other = 'SOP-WS2-' + suffix;
  const word = '堵料疏通' + suffix;
  const od = await admin.api('post', '/api/knowledge/documents', {
    code: other, title: '二车间堵料疏通 ' + suffix, orgUnitId: Number(ws2), classification: 'INTERNAL', content: `${word}前先停机挂牌。`
  });
  check(od.code === 200, '实施顾问建二车间文档');
  check((await admin.api('post', `/api/knowledge/documents/${od.data.id}/publish`)).code === 200, '实施顾问发布二车间文档');
  const r4 = await askUi(team, `${word}怎么做`);
  check(!r4.data.citations.some((c) => c.documentCode === other), '一车间甲班检索不到二车间的文档（检索前权限过滤）');
  check((await team.api('get', `/api/knowledge/documents/${od.data.id}`)).code !== 200, '一车间甲班也打不开二车间文档');
  await quality.page.goto(`${BASE}/agent/knowledge`);
  await tid(quality.page, 'knowledge-center').waitFor({ timeout: 30000 });
  const r5 = await askUi(quality, `${word}怎么做`);
  check(r5.data.citations.some((c) => c.documentCode === other), '全部数据范围的质检员能检索到');

  // 3. 车间主任在页面维护文档：新建 → 发布 v1 → 修订稿 v2 不被引用 → 发布 v2
  const code = 'SOP-E2E-' + suffix;
  const term = '皮带张紧' + suffix;
  await leader.page.goto(`${BASE}/agent/knowledge`);
  await tid(leader.page, 'knowledge-center').waitFor({ timeout: 30000 });
  await leader.page.locator('.el-tabs__item', { hasText: '文档' }).click();
  await tid(leader.page, 'btn-new-doc').click();
  await tid(leader.page, 'in-doc-code').fill(code);
  await tid(leader.page, 'in-doc-title').fill('皮带张紧检查 ' + suffix);
  await tid(leader.page, 'in-doc-org').locator('input').fill(psql(`select id from core_org_unit where code = 'D2010'`));
  await tid(leader.page, 'in-doc-content').fill(`# 检查\n${term}每班检查一次，张紧轮偏移超过 5 毫米须停机调整。`);
  await tid(leader.page, 'btn-create-doc').click();
  await leader.page.locator('.el-message--success', { hasText: '已创建' }).waitFor({ timeout: 10000 });
  const before = await askUi(team, `${term}怎么检查`);
  check(!before.data.citations.some((c) => c.documentCode === code), '未发布的新文档不被引用');
  await tid(leader.page, 'btn-publish-doc').click();
  await leader.page.locator('.el-message--success', { hasText: '已发布 v1' }).waitFor({ timeout: 10000 });
  const v1 = await askUi(team, `${term}怎么检查`);
  check(v1.data.citations.some((c) => c.documentCode === code && c.version === 1), '发布 v1 后被引用');

  await tid(leader.page, 'in-draft').fill(`# 检查\n${term}每班检查两次，偏移超过 3 毫米须停机调整（v2 修订）。`);
  await tid(leader.page, 'in-draft-note').fill('收紧阈值');
  await tid(leader.page, 'btn-save-draft').click();
  await leader.page.locator('.el-message--success', { hasText: '修订稿已保存' }).waitFor({ timeout: 10000 });
  const mid = await askUi(team, `${term}偏移多少毫米要停机`);
  const midCites = mid.data.citations.filter((c) => c.documentCode === code);
  check(midCites.length > 0 && midCites.every((c) => c.version === 1) && !JSON.stringify(mid.data).includes('v2 修订'), '修订稿保存后仍只引用 v1');
  await tid(leader.page, 'btn-publish-doc').click();
  await leader.page.locator('.el-message--success', { hasText: '已发布 v2' }).waitFor({ timeout: 10000 });
  const after = await askUi(team, `${term}偏移多少毫米要停机`);
  const afterCites = after.data.citations.filter((c) => c.documentCode === code);
  check(afterCites.length > 0 && afterCites.every((c) => c.version === 2) && after.data.answer.includes('3 毫米'), '发布 v2 后引用 v2');
  check(
    psql(`select string_agg(version || ':' || status, ',' order by version) from core_knowledge_version v join core_knowledge_document d on d.id = v.document_id where d.code = '${code}'`) ===
      '1:SUPERSEDED,2:PUBLISHED',
    '版本状态：v1 已替代、v2 已发布'
  );

  // 4. Agent 经 MCP 问答（班组长 OBO，按班组长数据域）
  const created = await admin.api('post', '/api/agents', {
    code: 'kb-e2e-' + suffix,
    name: '知识助手 E2E',
    permissions: ['lsmom:knowledge:ask'],
    rateLimitPerMinute: 60
  });
  agentId = created.data.id;
  const key = (await admin.api('post', `/api/agents/${agentId}/keys`, {})).data.key;
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
  await mcp('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'e2e', version: '1' } });
  const tools = (await mcp('tools/list', {})).result.tools.map((t) => t.name);
  check(tools.includes('ls.core.KnowledgeDocument.ask'), 'MCP 工具清单含知识问答');
  const ar = (await mcp('tools/call', { name: 'ls.core.KnowledgeDocument.ask', arguments: { input: { question: '本周一车间甲班异常数' } } })).result;
  check(!ar.isError && String(ar.structuredContent.data.value) === expected, 'Agent 问数结果与真人一致');
  const ad = (await mcp('tools/call', { name: 'ls.core.KnowledgeDocument.ask', arguments: { input: { question: '本周二车间异常数' } } })).result;
  check(ad.isError && ad.structuredContent.errorCode === 'ACCESS_DENIED', 'Agent 按真人数据域：其他车间被拒');

  const errs = [...team.errors, ...leader.errors, ...quality.errors, ...admin.errors].filter((e) => !/数据范围/.test(e));
  check(errs.length === 0, '页面无脚本错误' + (errs.length ? '：' + errs.join('; ') : ''));
  console.log('知识问答闭环通过');
} finally {
  if (agentId) psql(`update agent_identity set status = 'DISABLED' where id = ${agentId}`);
  await browser.close();
}
