/**
 * 旅程 J-F-AI：LLM 交接班摘要与 Agent 草稿（切片 F）。
 * 角色：实施顾问（陈顾问）建摘要助手并签发 Key；甲班班长（吴班长）交班；Agent 以车间主任（周主任）的身份取交接班上下文并草拟摘要；
 * 车间主任在「摘要确认」页核对引用后发布。
 * 负向：Agent 引用上下文之外的对象、要点无引用、Agent 直接发布（禁止能力，代审批永不暴露）、Agent 调用未授权的写操作（分派工作）。
 * 一致性：摘要状态、来源 Agent、代理人、真人发布审计、发布不改原交接班。
 */
import { ok, until } from '../support/ops.ts';
import { API_PREFIX, CLIENT_ID } from '../support/env.ts';
import { expect, test, type Session } from '../support/walk.ts';

const HO = 'ls.production.ShiftHandover';

/** 以会话的登录身份＋Agent Key 调 MCP（Agent 代理该用户） */
const mcp = (s: Session, key: string) => async (method: string, params: unknown, id = 1) =>
  s.page.evaluate(
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
    [API_PREFIX + '/mcp', CLIENT_ID, key, { jsonrpc: '2.0', id, method, params }] as const
  );

test('[AC-J-F-AI-001] 旅程：LLM 交接班摘要与 Agent 草稿，真人确认发布', async ({ walk }) => {
  const impl = await walk.as('acc_implementer');
  const team = await walk.as('acc_team');
  const leader = await walk.as('acc_leader');
  const admin = await walk.as('acc_admin');
  const run = Date.now().toString(36);

  const ho = await walk.step('甲班班长：生成交接班并确认交班', async () => {
    const t = team.page;
    await t.goto('/production/handover');
    await t.getByTestId('btn-new-handover').click();
    await t.getByTestId('ho-to').click();
    await t.locator('.el-select-dropdown__item:visible', { hasText: '一车间乙班' }).first().click();
    await t.getByTestId('btn-create-handover').click();
    await t.getByTestId('ho-summary').waitFor({ timeout: 15_000 });
    const rows = ok<any>(await team.api('POST', `/api/crud/${HO}/query`, { filters: [{ field: 'status', op: 'eq', value: 'DRAFT' }], sort: [{ field: 'id', dir: 'desc' }], page: 1, size: 1 }), '交接班').rows;
    await t.getByTestId('btn-publish').click();
    await expect(t.locator('.el-message', { hasText: '已确认交班' }).first()).toBeVisible({ timeout: 15_000 });
    return rows[0];
  }, team);

  const key = await walk.step('实施顾问：新建摘要助手（草拟、确认权限），签发 Key', async () => {
    const a = ok<any>(await impl.api('POST', '/api/agents', { code: `acc-summary-${run}`, name: `交接班摘要助手（验收 ${run}）`, permissions: ['lsmom:summary:draft', 'lsmom:summary:confirm'], rateLimitPerMinute: 120 }), '新建 Agent');
    const k = ok<any>(await impl.api('POST', `/api/agents/${a.id}/keys`, {}), '签发 Key').key as string;
    expect(k).toMatch(/^lsk_/);
    await impl.page.goto('/agent/admin');
    return k;
  }, impl);

  const call = mcp(leader, key);
  const tool = async (name: string, args: unknown) => (await call('tools/call', { name, arguments: args })).body.result;

  const sid = await walk.step('Agent（代理车间主任）：取交接班上下文，草拟带引用的摘要；负向：编造引用、无引用被拒', async () => {
    await leader.page.goto('/index');
    await call('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'acc', version: '1' } });
    const tools = ((await call('tools/list', {})).body.result.tools as any[]).map((t) => t.name);
    expect(tools).toContain('ls.core.Summary.draft');
    expect(tools, '确认发布不在工具清单中').not.toContain('ls.core.Summary.publish');
    const ctx = await tool('ls.core.Summary.context', { input: { kind: 'SHIFT_HANDOVER', subjectId: String(ho.id) } });
    expect(ctx.isError, JSON.stringify(ctx.structuredContent)).toBeFalsy();
    const facts = ctx.structuredContent.data.facts as any[];
    const self = facts.find((f) => f.ref.type === HO);
    expect(self, '上下文含交接班本身').toBeTruthy();
    const fake = await tool('ls.core.Summary.draft', { input: { kind: 'SHIFT_HANDOVER', subjectId: String(ho.id), content: '编造', points: [{ text: '全部完成', refs: [{ type: 'ls.core.Work', id: '999999999' }] }] } });
    expect(fake.structuredContent?.errorCode).toBe('SUMMARY_CITATION_INVALID');
    const noRef = await tool('ls.core.Summary.draft', { input: { kind: 'SHIFT_HANDOVER', subjectId: String(ho.id), content: '无引用', points: [{ text: '一切正常', refs: [] }] } });
    expect(noRef.structuredContent?.errorCode).toBe('SUMMARY_CITATION_INVALID');
    const d = await tool('ls.core.Summary.draft', {
      input: { kind: 'SHIFT_HANDOVER', subjectId: String(ho.id), model: 'acc-model', content: `甲班交班摘要（验收 ${run}）`, points: [{ text: self.text, refs: [self.ref] }] }
    });
    expect(d.isError, JSON.stringify(d.structuredContent)).toBeFalsy();
    expect(d.structuredContent.status).toBe('DRAFT');
    return d.structuredContent.targetId as string;
  }, leader);

  await walk.step('负向：Agent 直接发布、调用未授权的写操作（分派工作）都被拒，摘要仍待确认', async () => {
    const pub = await tool('ls.core.Summary.publish', { input: {}, targetId: sid, version: 0 });
    expect(pub.isError, 'Agent 发布被拒').toBe(true);
    expect(JSON.stringify(pub)).toMatch(/AGENT_TOOL_DENIED|UNKNOWN_TOOL|NOT_A_TOOL|不存在|禁止/);
    const assign = await tool('ls.core.Work.assign', { input: { targets: [] }, targetId: '1', version: 0 });
    expect(assign.isError, 'Agent 分派工作被拒').toBe(true);
    const list = ok<any>(await leader.api('GET', '/api/summaries?status=DRAFT'), '待确认摘要');
    expect(JSON.stringify(list)).toContain(`甲班交班摘要（验收 ${run}）`);
  }, leader);

  await walk.step('车间主任：在「摘要确认」页核对引用并发布（真人确认）', async () => {
    const p = leader.page;
    await p.goto('/agent/summaries');
    await p.getByTestId(`summary-${sid}`).waitFor({ timeout: 30_000 });
    await expect(p.getByTestId(`source-${sid}`)).toContainText('acc-model');
    await p.getByTestId(`btn-publish-${sid}`).click();
    await p.locator('.el-message--success', { hasText: '已发布' }).waitFor({ timeout: 10_000 });
  }, leader);

  await walk.step('一致性：摘要已发布、来源 Agent、真人发布审计、原交接班未被修改', async () => {
    const audit = ok<any>(await admin.api('POST', '/api/q/ls.audit.objectOperations', { concept: 'ls.core.Summary', id: String(sid) }), '摘要审计').rows as any[];
    const pub = audit.find((a) => a.opCode === 'ls.core.Summary.publish' && a.result === 'SUCCESS');
    expect(pub?.actorType, '发布由真人完成').toBe('HUMAN');
    const draft = audit.find((a) => a.opCode === 'ls.core.Summary.draft' && a.result === 'SUCCESS');
    expect(draft?.actorType, '草拟由 Agent 完成').toBe('AGENT');
    expect(draft?.onBehalfOf, '草拟代理车间主任').toBeTruthy();
    const after = await until(async () => {
      const l = ok<any>(await leader.api('GET', '/api/summaries?status=PUBLISHED'), '已发布摘要');
      return JSON.stringify(l).includes(`甲班交班摘要（验收 ${run}）`) ? l : null;
    }, '摘要已发布');
    expect(after).toBeTruthy();
    const h = ok<any>(await team.api('GET', `/api/crud/${HO}/${ho.id}`), '交接班');
    expect(h.status, '发布摘要不改交接班状态').toBe('PUBLISHED');
  }, admin);
});
