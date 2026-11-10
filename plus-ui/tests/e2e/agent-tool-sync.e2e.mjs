/**
 * 18.3 部署环境实测（无头）：MCP 工具清单与 Operation 注册一致。
 * 客户 Pack 1.0.0 把「签到」迁移声明为 DRAFT 暴露 → 经 ChangeSet 发布 → MCP 工具清单出现；
 * 1.0.1 改为 DENY → 发布 → 工具从清单消失，调用被拒。
 * 运行：node tests/e2e/agent-tool-sync.e2e.mjs（只用于测试环境）
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { open, check, API, CLIENT_ID } from './lib.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'lsmom-toolsync-'));
const ns = 'acme.ts' + Date.now().toString(36);
const op = ns + '.Visit.checkIn';

function build(version, exposure) {
  const dir = path.join(work, version);
  fs.cpSync(path.join(root, 'packs/examples/acme-visitor'), dir, { recursive: true });
  for (const f of fs.readdirSync(dir, { recursive: true })) {
    const p = path.join(dir, f);
    if (!fs.statSync(p).isFile()) continue;
    let s = fs
      .readFileSync(p, 'utf8')
      .replaceAll('acme.visitor', ns)
      .replaceAll('c_acme_visitor_visit', 'c_' + ns.replace(/\./g, '_') + '_visit')
      .replaceAll('idx_acme_visitor_visit_host', 'idx_' + ns.replace(/\./g, '_') + '_visit_host');
    if (f === 'pack.yaml') s = s.replace(/^version: .*$/m, `version: ${version}`);
    if (f.endsWith('visit-lifecycle.yaml')) s = s.replace('    name: 签到\n', `    name: 签到\n    exposure: ${exposure}\n`);
    fs.writeFileSync(p, s);
  }
  const zip = path.join(work, `${version}.zip`);
  execFileSync('python3', [path.join(root, 'scripts/pack-build.py'), dir, zip]);
  return zip;
}

const { browser, page, api, errors } = await open({ user: 'admin' });
async function release(zip) {
  const cs = (await api('POST', '/api/changesets', { archive: fs.readFileSync(zip).toString('base64') })).data;
  for (const stage of ['SANDBOX', 'REGRESSION', 'PRIVILEGE', 'UPGRADE']) {
    await api('POST', `/api/changesets/${cs.id}/stages`, { stage, status: 'PASSED', artifactSha: cs.artifactSha, detail: { note: '工具同步演练' } });
  }
  await api('POST', `/api/changesets/${cs.id}/approve`, { approve: true, note: '同意' });
  const r = await api('POST', `/api/changesets/${cs.id}/release`);
  check(r.code === 200, `发布 ${ns} ${r.data?.version ?? r.msg}`);
}
const mcp = (key, method, params) =>
  page.evaluate(
    async ([url, clientId, key, body]) => {
      const r = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json, text/event-stream',
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

try {
  const agent = (
    await api('POST', '/api/agents', { code: 'sync-' + Date.now().toString(36), name: '工具同步演练', permissions: ['acme:visitor:checkin'] })
  ).data;
  const key = (await api('POST', `/api/agents/${agent.id}/keys`, { expiresInDays: 1 })).data.key;
  check(!!key, '新建 Agent 并签发 Key');

  await release(build('1.0.0', 'DRAFT'));
  const t1 = (await mcp(key, 'tools/list', {})).result.tools.map((t) => t.name);
  check(t1.includes(op), `暴露等级 DRAFT：工具清单含 ${op}`);

  await release(build('1.0.1', 'DENY'));
  const t2 = (await mcp(key, 'tools/list', {})).result.tools.map((t) => t.name);
  check(!t2.includes(op), '改为 DENY 并发布后：工具从清单消失');
  const call = (await mcp(key, 'tools/call', { name: op, arguments: { input: {}, targetId: '1', version: 0 } })).result;
  check(call.isError && call.structuredContent.errorCode === 'AGENT_TOOL_DENIED', '直接调用被拒（AGENT_TOOL_DENIED）');
  await api('POST', `/api/agents/${agent.id}/disable`);
  check(errors.length === 0, '页面无脚本错误');
  console.log('MCP 工具同步通过');
} finally {
  await browser.close();
}
