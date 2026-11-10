/**
 * Agent 管理与审批页（spec ai/agent-gateway）：签发 Key 只显示一次、轮换与吊销、Key 状态、OBO 双主体审计、真人审批。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import AgentAdmin from '@/views/lsmom/agent/admin/AgentAdmin.vue';
import AgentApprovals from '@/views/lsmom/agent/approvals/AgentApprovals.vue';

const NOW = new Date('2026-11-10T10:00:00');

function makeAgentApi() {
  const keys = [
    { id: 1, agentId: 5, prefix: 'aaaaaaaaaaaa', status: 'ACTIVE', expiresAt: '2027-02-01T00:00:00', createdAt: '2026-11-01T08:00:00' },
    {
      id: 2,
      agentId: 5,
      prefix: 'bbbbbbbbbbbb',
      status: 'REVOKED',
      expiresAt: '2027-02-01T00:00:00',
      createdAt: '2026-10-01T08:00:00',
      revokeReason: '泄露'
    },
    { id: 3, agentId: 5, prefix: 'cccccccccccc', status: 'ACTIVE', expiresAt: '2026-11-09T00:00:00', createdAt: '2026-09-01T08:00:00' },
    { id: 4, agentId: 5, prefix: 'dddddddddddd', status: 'ACTIVE', expiresAt: '2026-11-11T00:00:00', rotatedTo: 1, createdAt: '2026-08-01T08:00:00' }
  ];
  const agent = {
    id: 5,
    code: 'shift-assistant',
    name: '交接班助手',
    partyId: 90,
    permissions: ['lsmom:issue:raise', 'lsmom:work:execute'],
    rateLimitPerMinute: 60,
    status: 'ACTIVE',
    createdAt: '2026-11-01T08:00:00',
    keys,
    events: [{ occurred_at: '2026-11-10T09:00:00', prefix: 'bbbbbbbbbbbb', event: 'REVOKED_USE', detail: '泄露' }]
  };
  return {
    list: vi.fn(async () => [agent]),
    get: vi.fn(async () => agent),
    create: vi.fn(async (b: any) => ({ ...agent, id: 6, ...b, keys: [] })),
    updatePermissions: vi.fn(async () => agent),
    setEnabled: vi.fn(async () => agent),
    issueKey: vi.fn(async () => ({ key: 'lsk_eeeeeeeeeeee_' + 'X'.repeat(40), info: { ...keys[0], id: 9, prefix: 'eeeeeeeeeeee' } })),
    rotateKey: vi.fn(async () => ({ key: 'lsk_ffffffffffff_' + 'Y'.repeat(40), info: { ...keys[0], id: 10, prefix: 'ffffffffffff' } })),
    revokeKey: vi.fn(async () => keys[0]),
    audit: vi.fn(async () => [
      {
        id: 1,
        occurred_at: '2026-11-10T09:30:00',
        op_code: 'ls.core.Issue.raise',
        actor_type: 'AGENT',
        actor_party: 90,
        agent_name: '交接班助手',
        on_behalf_of: 12,
        on_behalf_of_name: '王班长',
        target_concept: 'ls.core.Issue',
        target_id: '77',
        result: 'SUCCESS',
        batch_id: 'B1',
        trace_id: 't1'
      },
      {
        id: 2,
        occurred_at: '2026-11-10T09:31:00',
        op_code: 'ls.core.Work.find',
        actor_type: 'AGENT',
        actor_party: 90,
        agent_name: '交接班助手',
        on_behalf_of: 12,
        on_behalf_of_name: '王班长',
        result: 'FAILED',
        error_code: 'ACCESS_DENIED',
        error_message: '无权查看该组织的工作'
      }
    ])
  };
}

let app: App | undefined;
let host: HTMLElement | undefined;
afterEach(() => {
  app?.unmount();
  host?.remove();
  document.querySelectorAll('.el-overlay, .el-drawer__wrapper, .el-message-box__wrapper').forEach((e) => e.remove());
});

async function flush(ms = 80) {
  await nextTick();
  await new Promise((r) => setTimeout(r, ms));
  await nextTick();
}

const q = (id: string) => document.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
/** el-input 把 data-testid 透传到内部 input / textarea。 */
const field = (id: string) => {
  const e = q(id)!;
  return (['INPUT', 'TEXTAREA'].includes(e.tagName) ? e : e.querySelector('input, textarea')) as HTMLInputElement;
};

async function mount(component: any, props: Record<string, unknown>) {
  host = document.createElement('div');
  document.body.appendChild(host);
  const r = ref<any>();
  app = createApp({ setup: () => () => h(component, { ref: r, ...props }) });
  app.use(ElementPlus);
  app.mount(host);
  await flush();
  return r;
}

describe('Agent 管理', () => {
  it('Key 状态：有效、已吊销、已过期、宽限中；审计显示 Agent 代真人与拒绝原因', async () => {
    const api = makeAgentApi();
    const r = await mount(AgentAdmin, { api, now: () => NOW });
    expect(q('table-agents')!.textContent).toContain('交接班助手');
    expect(q('table-agents')!.textContent).toContain('lsmom:issue:raise');
    await r.value.open(5);
    await flush(200);
    const keys = q('table-keys')!.textContent!;
    expect(keys).toContain('有效');
    expect(keys).toContain('已吊销');
    expect(keys).toContain('已过期');
    expect(keys).toContain('宽限中');
    expect(q('btn-revoke-aaaaaaaaaaaa')).not.toBeNull();
    expect(q('btn-revoke-bbbbbbbbbbbb')).toBeNull();
    expect(q('btn-revoke-cccccccccccc')).toBeNull();
    const audit = q('table-audit')!.textContent!;
    expect(audit).toContain('交接班助手（代 王班长）');
    expect(audit).toContain('ACCESS_DENIED：无权查看该组织的工作');
    expect(audit).toContain('B1');
  });

  it('签发 Key 只在弹窗中显示一次，关闭后清除', async () => {
    const api = makeAgentApi();
    const r = await mount(AgentAdmin, { api, now: () => NOW });
    await r.value.open(5);
    await flush(200);
    q('btn-issue')!.click();
    await flush(200);
    expect(api.issueKey).toHaveBeenCalledWith(5, 90);
    const input = field('issued-key');
    expect(input.value).toBe('lsk_eeeeeeeeeeee_' + 'X'.repeat(40));
    q('btn-key-done')!.click();
    await flush(300);
    expect(document.body.innerHTML).not.toContain('X'.repeat(40));

    q('btn-rotate-aaaaaaaaaaaa')!.click();
    await flush(200);
    expect(api.rotateKey).toHaveBeenCalledWith(1, 24);
    expect(field('issued-key').value).toContain('ffffffffffff');
  });

  it('新建 Agent：权限按行拆分', async () => {
    const api = makeAgentApi();
    const r = await mount(AgentAdmin, { api, now: () => NOW });
    q('btn-new-agent')!.click();
    await flush(200);
    const set = (id: string, v: string) => {
      const el = field(id);
      el.value = v;
      el.dispatchEvent(new Event('input'));
    };
    set('in-code', 'qc-helper');
    set('in-name', '质检助手');
    set('in-perms', 'lsmom:issue:raise\nlsmom:work:execute, lsmom:issue:handle');
    await flush();
    q('btn-create')!.click();
    await flush(300);
    expect(api.create).toHaveBeenCalledWith({
      code: 'qc-helper',
      name: '质检助手',
      permissions: ['lsmom:issue:raise', 'lsmom:work:execute', 'lsmom:issue:handle'],
      rateLimitPerMinute: 60,
      description: undefined
    });
    expect(r.value).toBeTruthy();
  });
});

describe('Agent 审批', () => {
  it('列出待审批申请，批准时带有效期', async () => {
    const api = {
      pending: vi.fn(async () => [
        {
          id: 'ap-1',
          agentId: 5,
          onBehalfOf: 12,
          operation: 'ls.core.Issue.createRectification',
          targetId: '77',
          targetVersion: 2,
          input: { titles: ['打磨去毛刺'] },
          inputDigest: 'x',
          reason: '交接班发现毛刺',
          status: 'PENDING',
          createdAt: '2026-11-10T09:00:00'
        }
      ]),
      decide: vi.fn(async () => ({ expiresAt: '2026-11-10T10:30:00' }))
    };
    await mount(AgentApprovals, { api });
    const t = q('table-approvals')!.textContent!;
    expect(t).toContain('ls.core.Issue.createRectification');
    expect(t).toContain('#77 v2');
    expect(t).toContain('打磨去毛刺');
    q('btn-approve-ap-1')!.click();
    await flush(200);
    expect(api.decide).toHaveBeenCalledWith('ap-1', true, 30);
  });
});
